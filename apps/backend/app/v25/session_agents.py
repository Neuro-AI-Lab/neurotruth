from __future__ import annotations

import json
import re
from typing import Any

from app.ai.bedrock_agents import BedrockClaudeAdapter, parse_json_object


QUESTION_BANK_VERSION = "niaaa-samhsa-who-ko-v1"
DIALOGUE_PROMPT_VERSION = "intervention-dialogue-v1"
STATE_PROMPT_VERSION = "state-summary-v1"
STATE_RULE_VERSION = "state-rule-v1"

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
        "version": 1,
        "questionBankVersion": QUESTION_BANK_VERSION,
        "askedTopicIds": ["safety"],
        "declinedTopicIds": [],
        "safety": {
            "status": "awaiting_response",
            "riskCodes": [],
            "adminInvolvement": "not_offered",
        },
        "firstInterventionSelected": False,
    }


def validate_agent_output(payload: Any, state: dict[str, Any]) -> dict[str, Any] | None:
    if not isinstance(payload, dict):
        return None
    text = str(payload.get("assistantText") or "").strip()
    questions = question_count(text)
    if not text or len(text) > 2000 or questions > 1 or not validate_generated_text(text):
        return None
    topic = payload.get("questionTopicId")
    if topic is not None:
        if topic not in QUESTION_TOPICS or questions != 1:
            return None
        if topic in set(state.get("askedTopicIds") or ()) | set(state.get("declinedTopicIds") or ()):
            return None
        if topic == "safety" and (state.get("safety") or {}).get("status") == "clear":
            return None
    elif questions:
        return None
    intervention = payload.get("interventionType")
    if intervention is not None and intervention not in APPROVED_INTERVENTIONS:
        return None
    return {"assistantText": text, "questionTopicId": topic, "interventionType": intervention}


class BedrockSessionAgent:
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
                "allowedQuestionTopics": QUESTION_TOPICS,
                "allowedInterventions": sorted(APPROVED_INTERVENTIONS),
                "questionBank": {
                    "version": QUESTION_BANK_VERSION,
                    "sources": QUESTION_BANK_SOURCES,
                    "note": "These are original Korean paraphrases and optional guidance, not a questionnaire.",
                },
                "requiredOutput": {
                    "assistantText": "one concise Korean response with at most one question",
                    "questionTopicId": "allowed topic or null",
                    "interventionType": "allowed type or null",
                },
            }, ensure_ascii=False),
        }]
        system = (
            "You are the NeuroTruth research-use supportive dialogue agent. Return one JSON object only. "
            "Be nonjudgmental. Never diagnose, prescribe, promise contact, claim certainty, immediate craving reduction, "
            "treatment success, or causal treatment effect. Respect asked/refused topic IDs. "
            "Never ask about safety again when dialogueState.safety.status is clear."
        )
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

    async def summarize_state(self, evidence: dict[str, Any]) -> str:
        text = await self.adapter.complete(
            system=(
                "Summarize only the supplied evidence in neutral Korean. Do not change the state class, combine evidence "
                "into a new score, diagnose, prescribe, claim certainty, treatment success, immediate reduction, or causality."
            ),
            messages=[{"role": "user", "content": json.dumps(evidence, ensure_ascii=False)}],
            max_tokens=450,
            temperature=0.1,
        )
        text = text.strip()
        if not validate_generated_text(text):
            raise ValueError("state_summary_rejected")
        return text

    async def report(self, *, evidence: dict[str, Any], history: list[dict[str, str]], partial: bool) -> dict[str, Any]:
        text = await self.adapter.complete(
            system=(
                "Return concise Korean research-support report JSON only. Use supplied evidence and conversation. "
                "Do not diagnose, prescribe, show slot completion, claim treatment effect, immediate reduction, or causality."
            ),
            messages=[{"role": "user", "content": json.dumps({
                "partial": partial, "evidence": evidence, "history": history,
            }, ensure_ascii=False)}],
            max_tokens=1200,
            temperature=0.1,
        )
        parsed = parse_json_object(text)
        result = parsed or {"summary": text.strip(), "partial": partial}
        generated = json.dumps(result, ensure_ascii=False)
        if not validate_generated_text(generated):
            raise ValueError("report_output_rejected")
        return result
