from __future__ import annotations

import asyncio
from difflib import SequenceMatcher
import json
import os
import re
import unicodedata
from typing import Any
from urllib import error as urllib_error
from urllib import request as urllib_request
from urllib.parse import quote

import boto3


DEFAULT_MODEL_ID = "openai.gpt-5.5"
CRAVING_SLOT_KEYS = (
    "trigger",
    "duration",
    "intensity",
    "recent_alcohol_use",
    "physiological_context",
    "coping_attempt",
    "safety_concern",
    "support_context",
    "intervention_summary",
)
REPETITION_SIMILARITY_THRESHOLD = 0.86
RECENT_ASSISTANT_TURN_LIMIT = 3
QUESTION_FREE_ACKNOWLEDGEMENT = (
    "말해 주셔서 감사합니다. 지금 말씀해 주신 내용을 바탕으로 함께 이어가겠습니다."
)

_LEADING_ACKNOWLEDGEMENT_RE = re.compile(
    r"^(?:(?:네|좋아요|그렇군요|그랬군요|알겠습니다|이해했습니다|"
    r"힘드셨겠어요|말씀해\s*주셔서\s*감사합니다|말해\s*주셔서\s*감사합니다)"
    r"[\s,，.!。！？?\-]*)+",
    re.IGNORECASE,
)
_QUESTION_SENTENCE_RE = re.compile(r"[^?？.!。！\n]*[?？]")
_SENTENCE_RE = re.compile(r"[^?？.!。！\n]+[?？.!。！]?|[^\n]+")
_SAFETY_EXEMPT_RE = re.compile(
    r"(?:자살|자해|죽고\s*싶|죽을\s*(?:것|거)|죽이|목숨|"
    r"해치(?:고|려|는)|해칠|해를\s*(?:가하|입히)|안전|위험|"
    r"다른\s*사람(?:을|에게).*해|타인(?:을|에게).*해|응급|긴급|위기|119|112|"
    r"suicid|self[\s-]*harm|harm\s+(?:yourself|others?)|"
    r"immediate\s+danger|emergency|crisis|safety\s+(?:check|probe|question))",
    re.IGNORECASE,
)


CHAT_SYSTEM_PROMPT = """
You are NeuroTruth's F1-style craving intervention dialogue agent.
Respond in Korean by default. Use the supplied conversation history, currentSlots,
and missingSlots as the authoritative state for this turn. A topic is complete when
the user answered it positively or negatively, said they did not know, or declined
to answer. Never ask a completed topic again, including by paraphrase. If a question
is useful, ask at most one concise question about one genuinely missing slot. A
question is not mandatory. Do not invent or force empathy for neutral factual
answers. Reflect briefly only when it is natural, and offer a small coping step only
as supportive coaching.
Do not diagnose, prescribe treatment, claim certainty, or give medical directives.
If there is immediate safety risk, encourage contacting local emergency or trusted
support resources without giving clinical instructions.
""".strip()

SLOTS_SYSTEM_PROMPT = """
You are NeuroTruth's craving slot extraction agent. Extract only the JSON object
requested by the user. Allowed keys are:
trigger, duration, intensity, recent_alcohol_use, physiological_context,
coping_attempt, safety_concern, support_context, intervention_summary.
Use user-role content as factual evidence. Assistant-role content may only identify
the topic of the immediately preceding single-topic question; it is never evidence
for a slot value. When the user's direct answer to that question is an explicit
negative, unknown, or refusal, store a non-empty flat string for only that topic and
include a short verbatim user quote, for example `없음 (환자: "전혀 안 마셨어요")`,
`모름 (환자: "잘 모르겠어요")`, or `답변 거부 (환자: "말하고 싶지 않아요")`.
Do not copy that state to unrelated slots. Preserve non-empty currentSlots exactly;
return null for an unchanged, unsupported, or merely weaker inferred value. A new
non-empty value may replace a current slot only when user-role evidence explicitly
corrects or updates that same slot. Otherwise use null for unknown values. Do not
include diagnosis, treatment plans, or any other keys. Return JSON only.
""".strip()

HANDOFF_SYSTEM_PROMPT = """
You are NeuroTruth's clinician handoff drafting agent. Write concise Korean
Markdown for review by a clinician or researcher. Include evidence from the
conversation, limitations, and missing slots. Avoid diagnosis, certainty claims,
and treatment directives.
""".strip()


def missing_slot_keys(slots: dict[str, Any]) -> list[str]:
    return [
        key
        for key in CRAVING_SLOT_KEYS
        if slots.get(key) in (None, "", [], {})
    ]


def filter_craving_slots(slots: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(slots, dict):
        return {}
    return {key: slots.get(key) for key in CRAVING_SLOT_KEYS if key in slots}


def compact_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str)


def normalize_history(history: list[dict[str, Any]] | None) -> list[dict[str, str]]:
    normalized: list[dict[str, str]] = []
    if not history:
        return normalized

    for turn in history:
        if not isinstance(turn, dict):
            continue
        role = str(turn.get("role", "")).lower()
        if role not in {"user", "assistant"}:
            continue
        content = turn.get("content", turn.get("message", turn.get("text", "")))
        if content is None:
            continue
        text = str(content).strip()
        if text:
            normalized.append({"role": role, "content": text})
    return normalized


def question_sentences(text: str) -> list[str]:
    """Return explicit question-mark-bearing sentences from a response."""

    if not isinstance(text, str):
        return []
    return [match.group(0).strip() for match in _QUESTION_SENTENCE_RE.finditer(text)]


def normalize_question(text: str) -> str:
    """Normalize a question for conservative wording-similarity comparison."""

    normalized = unicodedata.normalize("NFKC", str(text)).casefold().strip()
    normalized = _LEADING_ACKNOWLEDGEMENT_RE.sub("", normalized)
    return re.sub(r"[\W_]+", "", normalized, flags=re.UNICODE)


def recent_assistant_questions(
    history: list[dict[str, Any]] | None,
) -> list[str]:
    """Collect questions from only the three most recent assistant turns."""

    assistant_turns = [
        turn
        for turn in normalize_history(history)
        if turn["role"] == "assistant"
    ][-RECENT_ASSISTANT_TURN_LIMIT:]
    return [
        question
        for turn in assistant_turns
        for question in question_sentences(turn["content"])
    ]


def is_safety_exempt_response(text: str) -> bool:
    """Keep safety, probe, crisis, and emergency questions out of suppression."""

    return bool(_SAFETY_EXEMPT_RE.search(str(text)))


def questions_are_similar(left: str, right: str) -> bool:
    normalized_left = normalize_question(left)
    normalized_right = normalize_question(right)
    if not normalized_left or not normalized_right:
        return False
    if normalized_left == normalized_right:
        return True
    return (
        SequenceMatcher(None, normalized_left, normalized_right).ratio()
        >= REPETITION_SIMILARITY_THRESHOLD
    )


def repeats_recent_question(
    response: str,
    history: list[dict[str, Any]] | None,
) -> bool:
    """Return whether a non-safety response repeats a recent assistant question."""

    candidate_questions = question_sentences(response)
    if not candidate_questions or is_safety_exempt_response(response):
        return False
    previous_questions = recent_assistant_questions(history)
    return any(
        questions_are_similar(candidate, previous)
        for candidate in candidate_questions
        for previous in previous_questions
    )


def build_chat_repair_messages(
    messages: list[dict[str, str]],
    draft_response: str,
) -> list[dict[str, str]]:
    """Build one stateless repair request without exposing comparison metadata."""

    repaired_messages = [dict(message) for message in messages]
    repaired_messages.append({"role": "assistant", "content": draft_response})
    repaired_messages.append(
        {
            "role": "user",
            "content": (
                "방금 초안의 질문이 최근 assistant 질문과 의미상 반복됩니다. "
                "완료된 주제는 다시 묻지 마세요. currentSlots와 missingSlots를 "
                "확인해 꼭 필요하면 아직 묻지 않은 한 가지 주제만 질문하고, "
                "질문이 필요하지 않으면 질문 없이 짧게 응답하세요. 수정된 "
                "assistant 응답만 반환하세요."
            ),
        }
    )
    return repaired_messages


def question_free_fallback(
    response: str,
    history: list[dict[str, Any]] | None,
) -> str:
    """Remove repeated interrogatives, then use a fixed acknowledgement if empty."""

    previous_questions = recent_assistant_questions(history)
    kept: list[str] = []
    for match in _SENTENCE_RE.finditer(str(response)):
        sentence = match.group(0).strip()
        if not sentence:
            continue
        questions = question_sentences(sentence)
        if questions and any(
            questions_are_similar(question, previous)
            for question in questions
            for previous in previous_questions
        ):
            continue
        kept.append(sentence)
    fallback = " ".join(kept).strip()
    return fallback or QUESTION_FREE_ACKNOWLEDGEMENT


def parse_json_object(text: str) -> dict[str, Any] | None:
    try:
        parsed = json.loads(text)
        return parsed if isinstance(parsed, dict) else None
    except json.JSONDecodeError:
        pass

    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end <= start:
        return None

    try:
        parsed = json.loads(text[start : end + 1])
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


class BedrockClaudeAdapter:
    def __init__(self, model_id: str | None = None) -> None:
        self.model_id = model_id or os.getenv("BEDROCK_MODEL_ID", DEFAULT_MODEL_ID)
        self.region_name = os.getenv("AWS_REGION") or os.getenv("AWS_DEFAULT_REGION") or None
        self._session = boto3.Session(region_name=self.region_name)
        self._client = None

    @property
    def client(self):
        if self._client is None:
            self._client = self._session.client("bedrock-runtime")
        return self._client

    async def complete(
        self,
        *,
        system: str,
        messages: list[dict[str, str]],
        max_tokens: int = 700,
        temperature: float = 0.4,
    ) -> str:
        return await asyncio.to_thread(
            self._complete_sync,
            system=system,
            messages=messages,
            max_tokens=max_tokens,
            temperature=temperature,
        )

    def _complete_sync(
        self,
        *,
        system: str,
        messages: list[dict[str, str]],
        max_tokens: int,
        temperature: float,
    ) -> str:
        if self.model_id.startswith("openai."):
            response = self._complete_with_mantle(
                system=system,
                messages=messages,
                max_tokens=max_tokens,
            )
            return self._extract_mantle_response_text(response)

        payload = {
            "system": [{"text": system}],
            "messages": [
                {
                    "role": message["role"],
                    "content": [{"text": message["content"]}],
                }
                for message in messages
            ],
            "inferenceConfig": {
                "maxTokens": max_tokens,
                "temperature": temperature,
            },
        }
        if os.getenv("AWS_BEARER_TOKEN_BEDROCK"):
            response = self._complete_with_bearer_token(payload)
        else:
            response = self.client.converse(modelId=self.model_id, **payload)
        return self._extract_response_text(response)

    def _complete_with_mantle(
        self,
        *,
        system: str,
        messages: list[dict[str, str]],
        max_tokens: int,
    ) -> dict[str, Any]:
        token = os.getenv("AWS_BEARER_TOKEN_BEDROCK", "").strip()
        if not token:
            raise RuntimeError(
                "AWS_BEARER_TOKEN_BEDROCK is required for Bedrock OpenAI models"
            )

        region_name = self.region_name or "us-east-1"
        url = f"https://bedrock-mantle.{region_name}.api.aws/openai/v1/responses"
        payload = {
            "model": self.model_id,
            "instructions": system,
            "input": [
                {"role": message["role"], "content": message["content"]}
                for message in messages
            ],
            "max_output_tokens": max_tokens,
        }
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(
            "utf-8"
        )
        timeout_seconds = float(os.getenv("BEDROCK_TIMEOUT_SECONDS", "60"))
        request = urllib_request.Request(
            url,
            data=body,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib_request.urlopen(request, timeout=timeout_seconds) as response:
                response_body = response.read()
        except urllib_error.HTTPError as exc:
            raise RuntimeError(
                f"Bedrock Mantle request failed with HTTP {exc.code}"
            ) from exc
        except urllib_error.URLError as exc:
            raise RuntimeError("Bedrock Mantle request failed") from exc

        try:
            parsed = json.loads(response_body.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise RuntimeError("Bedrock Mantle returned malformed JSON") from exc
        if not isinstance(parsed, dict):
            raise RuntimeError("Bedrock Mantle returned malformed JSON")
        return parsed

    def _complete_with_bearer_token(self, payload: dict[str, Any]) -> dict[str, Any]:
        token = os.getenv("AWS_BEARER_TOKEN_BEDROCK", "").strip()
        region_name = self.region_name or "us-east-1"
        model_id = quote(self.model_id, safe="")
        url = f"https://bedrock-runtime.{region_name}.amazonaws.com/model/{model_id}/converse"
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(
            "utf-8"
        )
        timeout_seconds = float(os.getenv("BEDROCK_TIMEOUT_SECONDS", "60"))
        request = urllib_request.Request(
            url,
            data=body,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib_request.urlopen(request, timeout=timeout_seconds) as response:
                response_body = response.read().decode("utf-8")
        except urllib_error.HTTPError as exc:
            error_body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(
                f"Bedrock bearer request failed with HTTP {exc.code}: {error_body[:300]}"
            ) from exc
        return json.loads(response_body)

    @staticmethod
    def _extract_response_text(response: dict[str, Any]) -> str:
        output = response.get("output") or {}
        message = output.get("message") or {}
        parts = message.get("content") or []
        text_parts = [
            part.get("text", "")
            for part in parts
            if isinstance(part, dict) and isinstance(part.get("text"), str)
        ]
        return "".join(text_parts).strip()

    @staticmethod
    def _extract_mantle_response_text(response: dict[str, Any]) -> str:
        output = response.get("output")
        if not isinstance(output, list):
            raise RuntimeError("Bedrock Mantle returned malformed output")

        text_parts: list[str] = []
        for item in output:
            if not isinstance(item, dict):
                continue
            content = item.get("content")
            if not isinstance(content, list):
                continue
            for part in content:
                if not isinstance(part, dict):
                    continue
                text = part.get("text")
                if isinstance(text, str):
                    text_parts.append(text)

        text = "".join(text_parts).strip()
        if not text:
            raise RuntimeError("Bedrock Mantle returned empty text output")
        return text


def build_chat_messages(
    *,
    message: str,
    conversation_history: list[dict[str, Any]] | None,
    slots: dict[str, Any] | None,
    alert_context: dict[str, Any] | None,
) -> list[dict[str, str]]:
    messages = normalize_history(conversation_history)
    current_slots = filter_craving_slots(slots)
    context = {
        "currentSlots": current_slots,
        "missingSlots": missing_slot_keys(current_slots),
        "alertContext": alert_context or {},
    }
    context_text = (
        "NeuroTruth context for this turn. Use it only to personalize support; "
        f"do not reveal raw JSON unless useful: {compact_json(context)}"
    )
    messages.append({"role": "user", "content": context_text})
    messages.append({"role": "user", "content": message})
    return messages


def build_slot_messages(
    *,
    conversation_history: list[dict[str, Any]] | None,
    current_slots: dict[str, Any] | None,
) -> list[dict[str, str]]:
    payload = {
        "conversationHistory": normalize_history(conversation_history),
        "currentSlots": filter_craving_slots(current_slots),
        "requiredOutput": {key: None for key in CRAVING_SLOT_KEYS},
    }
    return [
        {
            "role": "user",
            "content": (
                "Extract craving slots from this data and return JSON only: "
                f"{compact_json(payload)}"
            ),
        }
    ]


def build_handoff_messages(
    *,
    slots: dict[str, Any] | None,
    conversation_history: list[dict[str, Any]] | None,
    alert_events: list[dict[str, Any]] | None,
    prediction_summary: dict[str, Any] | None,
) -> list[dict[str, str]]:
    filtered_slots = filter_craving_slots(slots)
    payload = {
        "slots": filtered_slots,
        "missingSlots": missing_slot_keys(filtered_slots),
        "conversationHistory": normalize_history(conversation_history),
        "alertEvents": alert_events or [],
        "predictionSummary": prediction_summary or {},
    }
    return [
        {
            "role": "user",
            "content": (
                "Generate a Markdown handoff with sections for evidence, "
                "limitations, missing slots, and suggested support discussion "
                f"topics. Data: {compact_json(payload)}"
            ),
        }
    ]
