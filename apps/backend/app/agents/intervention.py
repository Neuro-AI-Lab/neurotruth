from __future__ import annotations

import json
import re
import unicodedata
from difflib import SequenceMatcher
from typing import Any

from app.agents.bedrock import BedrockClaudeAdapter, parse_json_object
from app.prompts.intervention import DIALOGUE_PROMPT_VERSION, DIALOGUE_SYSTEM_PROMPT


QUESTION_BANK_VERSION = "niaaa-samhsa-who-ko-v1"
QUESTION_BANK_SOURCES = (
    "https://www.niaaa.nih.gov/health-professionals-communities/core-resource-on-alcohol/conduct-brief-intervention-build-motivation-and-plan-change",
    "https://library.samhsa.gov/product/tip-35-enhancing-motivation-change-substance-use-disorder-treatment/pep19-02-01-003",
    "https://www.who.int/teams/mental-health-and-substance-use/treatment-care/mental-health-gap-action-programme/evidence-centre/alcohol-use-disorders",
)
QUESTION_TOPICS = {
    "safety": "지금 바로 다치거나 위험해질 상황은 없는지 알려주실 수 있나요?",
    "current_environment": "지금 계신 곳에서 잠시 안전하게 머물 수 있나요?",
    "alcohol_access": "지금 주변에 술이 있거나 쉽게 구할 수 있나요?",
    "trigger": "이번 갈망이 시작되기 전에 무슨 일이 있었나요?",
    "emotion_body": "지금 마음이나 몸에서 가장 두드러지는 느낌은 무엇인가요?",
    "past_coping": "비슷한 때 조금이라도 도움이 됐던 방법이 있었나요?",
    "support": "지금 편하게 연락할 수 있는 사람이 있나요?",
    "desired_help": "지금 이 대화에서 어떤 도움을 가장 원하시나요?",
}
APPROVED_INTERVENTIONS = frozenset({
    "breathing", "urge_surfing", "attention_shift", "leave_location",
    "refusal_practice", "social_support", "grounding", "hydration", "self_monitoring",
})

_PROHIBITED = re.compile(
    r"(진단(?:은|이|입니다|한다|됩니다|할\s*수\s*있)|(?:알코올|정신|불안|우울)\s*(?:중독|의존증|장애|질환)(?:이?에요|예요|입니다|으로\s*(?:보입니다|보여요)|이라고\s*(?:봅니다|판단합니다))|"
    r"(?:당신|사용자|환자|귀하)(?:은|는|이|가)?\s*(?:알코올|술)\s*(?:중독|의존증)(?:이?에요|예요|입니다|으로\s*(?:보입니다|보여요))|"
    r"처방|(?:약|약물|수면제|진정제)(?:을|를)?\s*(?:복용|먹|끊|중단|늘리|줄이)(?:으)?(?:세요|십시오|해야)|"
    r"치료(?:가|는|로)?\s*(?:성공|효과|됐다|되었|됩니다|개선|호전)|"
    r"갈망(?:이|은)?\s*(?:즉시|바로|확실히)?\s*(?:감소|줄었|낮아졌|개선|호전|사라)|"
    r"효과가\s*있|확실히|반드시|보장(?:합니다|된다)|"
    r"(?:이|그|이런)?\s*(?:방법|중재|상담|대화|훈련|기법)(?:으?로|을\s*통해).{0,24}(?:상태|증상|갈망|기분)(?:은|는|이|가)?\s*(?:좋아|나아|개선|호전|완화|감소|줄어)|"
    r"(?:중재|상담|대화|방법)(?:로|가|때문에)\s*갈망(?:이|을)?\s*(?:감소|줄|개선|호전|사라))",
    re.I,
)

_QUESTION_ENDING = re.compile(r"(나요|까요|가요|습니까|인가요|할래요|어때요|어떤가요|알려주시겠어요)(?=[?.,!\s]|$)", re.I)
_QUESTION_CUE_ENDING = re.compile(
    r"(?:무슨|무엇|뭐|어디|누구|언제|왜|어떻게|어떤|얼마|괜찮|안전|있(?:는지|었는지)?)"
    r"[^?!.\n]{0,80}(?:죠|세요)(?=[?.,!\s]|$)",
    re.I,
)


def question_count(text: str) -> int:
    return max(
        text.count("?"),
        len(_QUESTION_ENDING.findall(text)) + len(_QUESTION_CUE_ENDING.findall(text)),
    )


def validate_generated_text(text: str) -> bool:
    return bool(text.strip()) and not _PROHIBITED.search(text)


def initial_dialogue_state() -> dict[str, Any]:
    return {
        "version": 2,
        "askedQuestions": [],
        "refusedQuestions": [],
        "latestQuestion": None,
    }


def _question_text(text: str) -> str | None:
    if question_count(text) != 1:
        return None
    candidates = [
        item.strip()
        for item in re.split(r"(?<=[?.!])\s+|\n+", text)
        if item.strip() and question_count(item.strip())
    ]
    return candidates[-1] if candidates else text.strip()


def _normalized_question(text: str) -> str:
    normalized = unicodedata.normalize("NFKC", text)
    return re.sub(r"[^0-9A-Za-z가-힣]", "", normalized).lower()


def _repeats_question(question: str, state: dict[str, Any]) -> bool:
    normalized = _normalized_question(question)
    if not normalized:
        return False
    ledger = [
        *(state.get("askedQuestions") or ()),
        *(state.get("refusedQuestions") or ()),
    ]
    latest = state.get("latestQuestion")
    if latest:
        ledger.append(latest)
    for prior in ledger[-50:]:
        prior_normalized = _normalized_question(str(prior))
        if not prior_normalized:
            continue
        if normalized == prior_normalized:
            return True
        if min(len(normalized), len(prior_normalized)) >= 8 and (
            normalized in prior_normalized or prior_normalized in normalized
        ):
            return True
        if SequenceMatcher(None, normalized, prior_normalized).ratio() >= 0.82:
            return True
    return False


def validate_agent_output(payload: Any, state: dict[str, Any]) -> dict[str, Any] | None:
    if not isinstance(payload, dict):
        return None
    text = str(payload.get("assistantText") or "").strip()
    questions = question_count(text)
    if not text or len(text) > 2000 or questions > 1 or not validate_generated_text(text):
        return None
    question = _question_text(text)
    if question and _repeats_question(question, state):
        return None
    return {"assistantText": text, "questionText": question}


class InterventionAgent:
    def __init__(self, adapter: BedrockClaudeAdapter | None = None) -> None:
        self.adapter = adapter or BedrockClaudeAdapter()
        self.model_name = self.adapter.model_id
        self.model_version = self.adapter.model_id
        self.prompt_version = DIALOGUE_PROMPT_VERSION

    async def dialogue(self, context: dict[str, Any]) -> dict[str, Any]:
        state = context["dialogueState"]
        messages = [{
            "role": "user",
            "content": json.dumps({
                **context,
                "questionBank": {
                    "version": QUESTION_BANK_VERSION,
                    "sources": QUESTION_BANK_SOURCES,
                    "note": (
                        "These are original Korean paraphrases and optional guidance only. "
                        "Do not follow a fixed order or try to cover every topic."
                    ),
                },
                "conversationFramework": {
                    "version": DIALOGUE_PROMPT_VERSION,
                    "optionalDomains": [
                        "situation_or_trigger",
                        "thought_or_alcohol_expectancy",
                        "emotion_or_body_sensation",
                        "urge_or_behavior",
                        "short_and_later_consequences",
                        "coping_or_support",
                        "values_or_user_owned_next_step",
                    ],
                    "decisionRule": (
                        "Use at most one domain only when it helps the user's stated need. "
                        "These domains are not a checklist and need not be covered."
                    ),
                },
                "requiredOutput": {
                    "assistantText": "one short, TTS-friendly Korean response with zero or one question",
                },
            }, ensure_ascii=False, default=str),
        }]
        system = DIALOGUE_SYSTEM_PROMPT
        draft = parse_json_object(await self.adapter.complete(
            system=system, messages=messages, max_tokens=700, temperature=0.3,
        ))
        accepted = validate_agent_output(draft, state)
        if accepted is not None:
            return accepted
        repair = parse_json_object(await self.adapter.complete(
            system=system,
            messages=messages + [{"role": "assistant", "content": json.dumps(draft or {}, ensure_ascii=False)}, {
                "role": "user",
                "content": "Repair the response once. Obey every constraint and return JSON only.",
            }],
            max_tokens=700,
            temperature=0.0,
        ))
        accepted = validate_agent_output(repair, state)
        if accepted is None:
            raise ValueError("dialogue_output_rejected")
        return accepted
