from __future__ import annotations

import asyncio
import json
import re
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from typing import Any
from uuid import UUID, uuid4

from app.core.security.crypto import AesGcmKeyring, aad_for
from app.models.records import UserRecord
from app.prompts.intervention import DIALOGUE_PROMPT_VERSION
from app.prompts.report import REPORT_PROMPT_VERSION
from app.prompts.state_summary import STATE_PROMPT_VERSION, STATE_RULE_VERSION
from app.repositories.postgres import RepositoryConflictError, V25Repository
from app.services.dashboard import class_from_schema
from app.agents.intervention import (
    APPROVED_INTERVENTIONS,
    InterventionAgent,
    initial_dialogue_state,
    validate_agent_output,
)


class SessionError(ValueError):
    code = "session_error"


class SessionNotFound(SessionError): code = "session_not_found"
class SessionStateError(SessionError): code = "session_not_active"
class LegacySessionReadOnly(SessionStateError): code = "legacy_session_read_only"
class MessageInProgress(SessionStateError): code = "message_in_progress"
class ClientMessageConflict(SessionStateError): code = "client_message_conflict"


class SessionAgentError(SessionError):
    code = "dialogue_provider_error"

    def __init__(
        self,
        *,
        code: str,
        client_message_id: UUID | None,
        user_message_id: UUID,
        attempts_remaining: int,
    ) -> None:
        super().__init__(code)
        self.code = code
        self.client_message_id = client_message_id
        self.user_message_id = user_message_id
        self.attempts_remaining = attempts_remaining
        self.retryable = client_message_id is not None and attempts_remaining > 0


_SELF_HARM = re.compile(r"(자살|자해|죽고\s*싶|목숨을\s*끊|suicid|self[- ]?harm)", re.I)
_DRIVING = re.compile(r"((술|음주|마셨|취했).{0,24}(운전|차를\s*몰)|(운전|차를\s*몰).{0,24}(술|음주|마셨|취했))", re.I)
_DRIVING_NEGATED = re.compile(r"(운전.{0,10}(안\s*하|하지\s*않)|대리\s*운전|택시|차를\s*두고)", re.I)
_MIXING = re.compile(r"((술|음주).{0,30}(수면제|진정제|마약성\s*진통제).{0,20}(같이|함께|복용|먹었)|(수면제|진정제|마약성\s*진통제).{0,30}(술|음주))", re.I)
_SEVERE = re.compile(r"(호흡\s*곤란|숨이\s*(안|잘\s*안)\s*쉬|심한\s*가슴\s*통증|의식이\s*흐|의식을\s*잃|실신|경련|피를\s*토|멈추지\s*않는\s*구토|심한\s*금단|환각|금단\s*발작)", re.I)
_NONCURRENT = re.compile(r"(과거|예전|작년|지금은\s*괜찮|현재는\s*괜찮)", re.I)
_ACCEPT = re.compile(r"^(네|예|응|그래|좋아|동의|부탁)", re.I)
_DECLINE = re.compile(r"^(아니|아뇨|싫|괜찮|원하지)", re.I)
_REFUSAL = re.compile(r"(권유|거절|마시라고|한잔\s*하자)", re.I)
_ACCESS = re.compile(r"(술이\s*(있|보이)|냉장고.{0,8}술|바로\s*살\s*수|술집)", re.I)
_BREATH = re.compile(r"(숨이\s*가쁘|호흡이\s*빨|과호흡)", re.I)
_TENSION = re.compile(r"(불안|긴장|초조|심장이\s*빨|떨려)", re.I)
_REPETITIVE = re.compile(r"(계속\s*생각|머릿속.{0,8}술|갈망이\s*반복)", re.I)
_HABIT = re.compile(r"(습관|매번|늘\s*이럴|반복되는\s*상황)", re.I)
_SUPPORT = re.compile(r"(연락할\s*사람|친구|가족|지지자).{0,20}(있|가능|연락)", re.I)
_DEHYDRATION = re.compile(r"(갈증|목이\s*마르|물을\s*못\s*마)", re.I)
_REFUSE_TOPIC = re.compile(r"(말하고\s*싶지\s*않|대답하고\s*싶지\s*않|그건\s*넘어가)", re.I)
_CORRECTION = re.compile(r"(아까.{0,20}(아니|정정)|정정할게|다시\s*말할게|이제는\s*말할게|생각이\s*바뀌)", re.I)
_EXPLICIT_SAFE = re.compile(r"((네|예|응).{0,12}(안전|괜찮)|(지금|현재).{0,8}(안전|괜찮)|위험.{0,8}(없|않)|다칠\s*일.{0,8}없)", re.I)
_EXPLICIT_UNSAFE = re.compile(r"(안전하지\s*않|위험해|위험한\s*상황|다칠\s*것\s*같|잘\s*모르겠|확신이\s*없)", re.I)
_BARE_AFFIRMATIVE = re.compile(r"^\s*(?:네|예|응|그래요?)\s*[.!]?\s*$", re.I)

_CORRECTION_TOPIC_PATTERNS = {
    "safety": re.compile(r"(안전|위험|다칠)"),
    "current_environment": re.compile(r"(현재|지금).{0,8}(환경|장소|어디|곳)"),
    "alcohol_access": re.compile(r"(술|알코올).{0,10}(있|접근|구할|주변)"),
    "trigger": re.compile(r"(촉발|계기|무슨\s*일|시작되기\s*전)"),
    "emotion_body": re.compile(r"(감정|기분|마음|몸|신체|느낌)"),
    "past_coping": re.compile(r"(대처|도움이\s*됐|해왔던\s*방법|전에\s*했던\s*방법)"),
    "support": re.compile(r"(지지|연락할\s*사람|친구|가족|도와줄\s*사람)"),
    "desired_help": re.compile(r"(원하는\s*도움|어떤\s*도움|도움을\s*원|원하는지)"),
}

_INTERVENTION_TEXT = {
    "refusal_practice": "지금은 권유를 짧고 분명하게 거절하는 문장을 함께 정해볼 수 있어요.",
    "leave_location": "가능하다면 술에서 거리를 둘 수 있는 안전한 장소로 잠시 이동해 보세요.",
    "breathing": "편한 자세에서 숨을 천천히 내쉬는 데 잠시 집중해 보세요.",
    "grounding": "주변에서 보이는 것과 몸이 닿는 감각을 차분히 하나씩 살펴보세요.",
    "urge_surfing": "갈망을 없애려 하기보다 올라왔다 내려가는 감각으로 잠시 관찰해 보세요.",
    "attention_shift": "지금 반복되는 흐름을 끊을 수 있는 짧고 안전한 활동으로 주의를 옮겨 보세요.",
    "social_support": "부담이 적은 사람에게 지금 잠깐 대화가 필요하다고 연락해 볼 수 있어요.",
    "hydration": "가능하다면 물을 천천히 조금 마시며 몸 상태를 살펴보세요.",
    "self_monitoring": "지금 갈망이 언제 강해지고 약해지는지 판단 없이 잠시 관찰해 보세요.",
}

_INITIAL_PROMPT = "지금 상황이나 원하는 도움을 편하게 말씀해 주세요."
_FALLBACK = "말씀해 주신 내용을 기록했어요. 지금 할 수 있는 안전하고 부담이 적은 방법부터 함께 살펴볼게요."


def safety_result(content: str) -> tuple[str, list[str], list[dict[str, str]]]:
    risks = []
    if _SELF_HARM.search(content): risks.append("self_harm")
    if _DRIVING.search(content) and not _DRIVING_NEGATED.search(content): risks.append("impaired_driving")
    if _MIXING.search(content): risks.append("alcohol_medication_combination")
    if _SEVERE.search(content) and not _NONCURRENT.search(content): risks.append("severe_acute_or_withdrawal")
    if not risks:
        return "clear", [], []
    resources = [{"label": "응급 도움", "contact": "119"}]
    if "self_harm" in risks:
        resources.append({"label": "자살예방 상담", "contact": "109"})
    return "urgent", risks, resources


def is_immediate_safety_risk(content: str) -> bool:
    return safety_result(content)[0] == "urgent"


def select_intervention_type(context: Any) -> str:
    text = context if isinstance(context, str) else json.dumps(context, ensure_ascii=False)
    if _REFUSAL.search(text): return "refusal_practice"
    if _ACCESS.search(text): return "leave_location"
    if _BREATH.search(text): return "breathing"
    if _TENSION.search(text): return "grounding"
    if _REPETITIVE.search(text): return "urge_surfing"
    if _HABIT.search(text): return "attention_shift"
    if _SUPPORT.search(text): return "social_support"
    if _DEHYDRATION.search(text): return "hydration"
    return "self_monitoring"


def corrected_declined_topic(content: str, declined_topic_ids: list[str]) -> str | None:
    """Return only a declined topic that the correction explicitly names."""
    if not _CORRECTION.search(content):
        return None
    matches = [
        topic for topic in declined_topic_ids
        if (pattern := _CORRECTION_TOPIC_PATTERNS.get(topic)) and pattern.search(content)
    ]
    return matches[0] if len(matches) == 1 else None


class SessionService:
    def __init__(
        self,
        repository: V25Repository,
        keyring: AesGcmKeyring,
        auth_service: Any,
        intervention_agent: InterventionAgent | Any,
        state_summary_agent: Any | None = None,
        report_agent: Any | None = None,
        *,
        state_summary_ai_enabled: bool = True,
        report_ai_enabled: bool = True,
    ) -> None:
        self.repository = repository
        self.keyring = keyring
        self.auth_service = auth_service
        self.intervention_agent = intervention_agent
        # Optional fallbacks preserve injected test/provider implementations while
        # runtime composition assigns one explicit owner per AI responsibility.
        self.state_summary_agent = state_summary_agent or intervention_agent
        self.report_agent = report_agent or intervention_agent
        self.state_summary_ai_enabled = state_summary_ai_enabled
        self.report_ai_enabled = report_ai_enabled
        self._tasks: set[asyncio.Task[Any]] = set()
        self._report_tasks: set[UUID] = set()

    async def shutdown(self) -> None:
        for task in tuple(self._tasks): task.cancel()
        if self._tasks: await asyncio.gather(*tuple(self._tasks), return_exceptions=True)
        self._tasks.clear(); self._report_tasks.clear()

    async def resume_pending_reports(self) -> None:
        if not self.report_ai_enabled:
            return
        for row in await self.repository.pending_report_jobs():
            self._spawn_report(row["patient_id"], row["session_id"], row["id"], bool((row.get("evidence_refs") or {}).get("partial")))

    async def open(self, patient: UserRecord, session_type: str, trigger_alert_id: UUID | None) -> dict[str, Any]:
        await self.auth_service.require_consent(patient.id, "ai_analysis")
        settings = await self.repository.system_settings()
        session_id = uuid4()
        state = initial_dialogue_state()
        packed = self._encrypt_json("sessions", "dialogue_state_encrypted", patient.id, session_id, state)
        row = await self.repository.open_session(
            session_id=session_id, patient_id=patient.id, session_type=session_type,
            trigger_alert_id=trigger_alert_id,
            timeout_seconds=settings.chat_timeout_seconds if settings else 3600,
            now=datetime.now(timezone.utc), dialogue_state_encrypted=packed,
            dialogue_state_key_version=self.keyring.current_key_id,
        )
        if row.get("_timed_out_session_id"):
            await self._finalize_terminal(patient.id, row["_timed_out_session_id"], partial=True)
        if row.get("interaction_phase") is None:
            return await self._public_session(patient.id, row)
        if row["id"] == session_id:
            await self._message(
                patient.id, session_id, "assistant", _INITIAL_PROMPT, "rule_engine", None,
                generation_metadata={"dialogueStateVersion": 2},
            )
        result = await self._public_session(patient.id, row)
        result["assistantText"] = _INITIAL_PROMPT if row["id"] == session_id else None
        return result

    async def get(self, patient: UserRecord, session_id: UUID) -> dict[str, Any]:
        return await self._public_session(patient.id, await self._owned(patient.id, session_id))

    async def message(
        self,
        patient: UserRecord,
        session_id: UUID,
        content: str,
        client_message_id: UUID | None = None,
        input_modality: str = "text",
    ) -> dict[str, Any]:
        await self.auth_service.require_consent(patient.id, "ai_analysis")
        if input_modality not in {"text", "voice"}:
            raise ValueError("inputModality must be text or voice")
        row = await self._active_new(patient.id, session_id)
        state = self._dialogue_state(patient.id, row)
        if state.get("version") != 2:
            return await self._structured_message(patient, session_id, content)

        async with self.repository.message_turn(session_id) as acquired:
            if not acquired:
                raise MessageInProgress("Another message is being processed")
            row = await self._active_new(patient.id, session_id)
            state = self._dialogue_state(patient.id, row)
            attempt = 1
            existing = (
                await self.repository.message_by_client_id(session_id, client_message_id)
                if client_message_id is not None else None
            )
            if existing is not None:
                metadata = existing.get("generation_metadata") or {}
                existing_modality = str(metadata.get("inputModality") or "text")
                if self._decrypt_message(patient.id, existing) != content or existing_modality != input_modality:
                    raise ClientMessageConflict("clientMessageId was already used with different content")
                user_message_id = existing["id"]
                assistant_row = await self.repository.assistant_reply_for_user(
                    session_id, user_message_id,
                )
                if assistant_row is not None:
                    return await self._free_dialogue_response(
                        patient.id,
                        session_id,
                        user_message_id,
                        assistant_row["id"],
                        self._decrypt_message(patient.id, assistant_row),
                    )
                attempts = int((existing.get("generation_metadata") or {}).get("dialogueAttempts") or 1)
                if attempts >= 2:
                    raise SessionAgentError(
                        code=str(
                            (existing.get("generation_metadata") or {}).get("dialogueLastErrorCode")
                            or "dialogue_provider_error"
                        ),
                        client_message_id=client_message_id,
                        user_message_id=user_message_id,
                        attempts_remaining=0,
                    )
                attempt = attempts + 1
                await self.repository.update_message_generation_metadata(
                    user_message_id,
                    {
                        **(existing.get("generation_metadata") or {}),
                        "clientMessageId": str(client_message_id),
                        "dialogueAttempts": attempt,
                        "inputModality": input_modality,
                    },
                )
            else:
                user_message_id = await self._message(
                    patient.id,
                    session_id,
                    "user",
                    content,
                    None,
                    None,
                    generation_metadata={
                        "clientMessageId": str(client_message_id) if client_message_id else None,
                        "dialogueAttempts": 1,
                        "inputModality": input_modality,
                    },
                    modality=input_modality,
                )

            latest_question = state.get("latestQuestion")
            if latest_question and _REFUSE_TOPIC.search(content):
                refused = state.setdefault("refusedQuestions", [])
                if latest_question not in refused:
                    refused.append(latest_question)
                    del refused[:-50]

            failure_phase = "model_registration"
            try:
                model_id = await self._model(
                    "dialogue_agent", DIALOGUE_PROMPT_VERSION, self.intervention_agent
                )
                failure_phase = "provider_call"
                draft = await self.intervention_agent.dialogue({
                    "history": await self._history(patient.id, session_id, limit=20),
                    "dialogueState": state,
                    "questionLedger": {
                        "askedQuestions": list(state.get("askedQuestions") or ())[-50:],
                        "refusedQuestions": list(state.get("refusedQuestions") or ())[-50:],
                        "latestQuestion": state.get("latestQuestion"),
                    },
                })
                failure_phase = "output_validation"
                accepted = validate_agent_output(draft, state)
                if accepted is None:
                    raise ValueError("dialogue_output_rejected")
                question = accepted.get("questionText")
                if question:
                    asked = state.setdefault("askedQuestions", [])
                    asked.append(question)
                    del asked[:-50]
                    state["latestQuestion"] = question
                else:
                    state["latestQuestion"] = None
                await self._store_state(patient.id, session_id, "free_dialogue", state)
                failure_phase = "assistant_persistence"
                assistant_id = await self._message(
                    patient.id,
                    session_id,
                    "assistant",
                    accepted["assistantText"],
                    "dialogue",
                    model_id,
                    generation_metadata={"replyToUserMessageId": str(user_message_id)},
                )
                return await self._free_dialogue_response(
                    patient.id,
                    session_id,
                    user_message_id,
                    assistant_id,
                    accepted["assistantText"],
                )
            except Exception as exc:
                code = "dialogue_output_rejected" if "rejected" in str(exc) else "dialogue_provider_error"
                await self._store_state(patient.id, session_id, "free_dialogue", state)
                if client_message_id is not None:
                    user_row = await self.repository.message_by_client_id(
                        session_id, client_message_id,
                    )
                    await self.repository.update_message_generation_metadata(
                        user_message_id,
                        {
                            **((user_row or {}).get("generation_metadata") or {}),
                            "clientMessageId": str(client_message_id),
                            "dialogueAttempts": attempt,
                            "dialogueLastErrorCode": code,
                            "inputModality": input_modality,
                        },
                    )
                await self.repository.audit(
                    actor_id=None,
                    actor_role="agent",
                    action="dialogue.failed",
                    resource_type="session",
                    resource_id=session_id,
                    metadata={
                        "code": code,
                        "stage": failure_phase,
                        "exceptionClass": type(exc).__name__,
                        "userMessageId": str(user_message_id),
                        "attempt": attempt,
                    },
                )
                raise SessionAgentError(
                    code=code,
                    client_message_id=client_message_id,
                    user_message_id=user_message_id,
                    attempts_remaining=(2 - attempt) if client_message_id else 0,
                ) from exc

    async def _structured_message(self, patient: UserRecord, session_id: UUID, content: str) -> dict[str, Any]:
        await self.auth_service.require_consent(patient.id, "ai_analysis")
        async with self.repository.message_turn(session_id) as acquired:
            if not acquired: raise MessageInProgress("Another message is being processed")
            row = await self._active_new(patient.id, session_id)
            state = self._dialogue_state(patient.id, row)
            user_message_id = await self._message(patient.id, session_id, "user", content, None, None)
            prior_safety_status = state["safety"].get("status", "awaiting_response")
            prior_risks = list(state["safety"].get("riskCodes") or ())
            status, risks, resources = safety_result(content)
            risk_detected_now = bool(risks)
            safety_resolved = False
            involvement_choice: str | None = None
            if not risks and prior_safety_status == "urgent":
                if state["safety"].get("adminInvolvement") == "offered" and (
                    _ACCEPT.search(content) or _DECLINE.search(content)
                ):
                    involvement_choice = "accepted" if _ACCEPT.search(content) else "declined"
                    status = "clear"
                elif _EXPLICIT_SAFE.search(content):
                    status = "clear"
                    safety_resolved = True
                else:
                    status, risks = "urgent", prior_risks
                    resources = self._support_resources(risks)
            elif not risks and prior_safety_status in {"awaiting_response", "concern"}:
                if _EXPLICIT_SAFE.search(content) or _BARE_AFFIRMATIVE.fullmatch(content):
                    status = "clear"
                    safety_resolved = True
                else:
                    status = "concern"
                    resources = [{"label": "응급 도움", "contact": "119"}]
            state["safety"].update(status=status, riskCodes=risks)
            snapshot = await self._create_inference(
                patient.id, session_id, "realtime", summarize=not risks,
            )
            corrected_topic = corrected_declined_topic(content, state.get("declinedTopicIds") or [])
            if corrected_topic:
                state["declinedTopicIds"].remove(corrected_topic)
            elif _REFUSE_TOPIC.search(content) and state.get("askedTopicIds"):
                topic = state["askedTopicIds"][-1]
                if topic not in state["declinedTopicIds"]: state["declinedTopicIds"].append(topic)
            assistant: str
            model_id: UUID | None = None
            delivered: dict[str, Any] | None = None
            if risk_detected_now:
                state["safety"]["adminInvolvement"] = "offered"
                assistant = self._urgent_text(risks)
                await self.repository.audit(actor_id=patient.id, actor_role="patient", action="safety.detected",
                                            resource_type="session", resource_id=session_id,
                                            metadata={"riskCodes": risks, "messageId": str(user_message_id)})
            elif involvement_choice:
                state["safety"]["adminInvolvement"] = involvement_choice
                await self.repository.audit(
                    actor_id=patient.id, actor_role="patient",
                    action=f"safety.involvement_{involvement_choice}",
                    resource_type="session", resource_id=session_id,
                )
                assistant = _FALLBACK
            elif status == "concern":
                assistant = ("현재 안전하지 않거나 확실하지 않다면 위험한 장소에서 벗어나 주변의 믿을 수 있는 사람에게 도움을 요청해 주세요. "
                             "즉각적인 위험이 있다면 119에 연락해 주세요. 지금 안전한 곳에 있는지 다시 알려주실 수 있나요?")
            elif status == "urgent":
                assistant = self._urgent_text(risks)
            elif safety_resolved:
                assistant = "안전하다고 알려주셔서 고마워요. 지금 갈망 상황에서 가장 두드러지는 점을 편한 만큼 말씀해 주세요."
            elif not state["firstInterventionSelected"]:
                state["safety"]["status"] = "clear"
                kind = select_intervention_type(content)
                state["firstInterventionSelected"] = True
                settings = await self.repository.system_settings()
                if settings and settings.interventions_enabled:
                    rule_model_id = await self.repository.ensure_rule_model_version(
                        component="state_inference_agent", rule_version=STATE_RULE_VERSION,
                    )
                    delivered = await self._persist_intervention(patient.id, session_id, kind, _INTERVENTION_TEXT[kind],
                                                                 {"messageIds": [str(user_message_id)], "ruleVersion": STATE_RULE_VERSION}, rule_model_id)
                    assistant = delivered["content"]
                else:
                    assistant = _FALLBACK
            else:
                history = await self._history(patient.id, session_id)
                active = await self._public_interventions(patient.id, session_id)
                settings = await self.repository.system_settings()
                if not settings or not settings.interventions_enabled:
                    assistant = _FALLBACK
                else:
                    failure_phase = "model_registration"
                    try:
                        model_id = await self._model(
                            "dialogue_agent", DIALOGUE_PROMPT_VERSION, self.intervention_agent
                        )
                        failure_phase = "evidence_preparation"
                        latest_evidence = self._json_safe(
                            await self.repository.inference_evidence(patient.id, session_id)
                        )
                        failure_phase = "provider_call"
                        draft = await self.intervention_agent.dialogue({
                            "history": history, "dialogueState": state, "activeInterventions": active,
                            "latestEvidence": latest_evidence,
                        })
                        failure_phase = "output_validation"
                        draft = validate_agent_output(draft, state)
                        if draft is None:
                            raise ValueError("dialogue_output_rejected")
                        assistant = draft["assistantText"]
                        topic = draft.get("questionTopicId")
                        if topic and topic not in state["askedTopicIds"]: state["askedTopicIds"].append(topic)
                        kind = draft.get("interventionType")
                        if kind and kind in APPROVED_INTERVENTIONS:
                            failure_phase = "intervention_persistence"
                            delivered = await self._persist_intervention(patient.id, session_id, kind, assistant,
                                                                         {"messageIds": [str(user_message_id)]}, model_id)
                    except Exception as exc:
                        code = "dialogue_output_rejected" if "rejected" in str(exc) else "dialogue_provider_error"
                        await self.repository.audit(actor_id=None, actor_role="agent", action="dialogue.failed",
                                                    resource_type="session", resource_id=session_id,
                                                    metadata={
                                                        "code": code,
                                                        "phase": failure_phase,
                                                        "errorType": type(exc).__name__,
                                                    })
                        assistant = _FALLBACK
            phase = "safety_check" if state["safety"]["status"] in {"awaiting_response", "concern", "urgent"} else "intervention_dialogue"
            await self._store_state(patient.id, session_id, phase, state)
            await self._message(patient.id, session_id, "assistant", assistant,
                                "dialogue" if model_id else "rule_engine", model_id)
            if delivered:
                snapshot = await self._create_inference(patient.id, session_id, "realtime")
            return {
                "assistantText": assistant, "phase": phase,
                "safety": {"status": state["safety"]["status"], "riskCodes": state["safety"]["riskCodes"], "supportResources": resources},
                "activeInterventions": await self._public_interventions(patient.id, session_id),
                "stateSnapshot": snapshot, "reportStatus": await self._report_status(patient.id, session_id),
                "inactivityTimeoutSeconds": (await self.repository.system_settings()).chat_timeout_seconds,
            }

    async def assessment(self, patient: UserRecord, session_id: UUID, body: dict[str, Any]) -> dict[str, Any]:
        await self.auth_service.require_consent(patient.id, "ai_analysis")
        await self._active_new(patient.id, session_id)
        assessment_id = uuid4()
        await self.repository.add_assessment(
            id=assessment_id, session=session_id, code=body["instrumentCode"], version=body["version"],
            phase=body["phase"], attempt=body["attemptNo"],
            answers=self._encrypt_json("craving_assessments", "answers_encrypted", patient.id, assessment_id, body["answers"]),
            key=self.keyring.current_key_id, score=body["rawScore"], minimum=body["scaleMin"], maximum=body["scaleMax"],
        )
        return {"assessmentId": str(assessment_id)}

    async def finish(self, patient: UserRecord, session_id: UUID) -> dict[str, Any]:
        await self.auth_service.require_consent(patient.id, "ai_analysis")
        row = await self._owned(patient.id, session_id, mutation=True)
        if row["status"] in {"completed", "abandoned", "report_ready", "closed"}:
            snapshots = await self.repository.state_inferences(patient.id, session_id)
            latest = snapshots[-1] if snapshots else None
            return {"sessionId": str(session_id), "status": row["status"], "interactionPhase": row["interaction_phase"],
                    "stateSnapshot": self._public_inference(patient.id, latest),
                    "reportStatus": await self._report_status(patient.id, session_id),
                    "inactivityTimeoutSeconds": (await self.repository.system_settings()).chat_timeout_seconds}
        await self.repository.finish_session(session_id, status="completed", reason="normal")
        realtime = await self._create_inference(patient.id, session_id, "realtime", event_tag="terminal:completed")
        await self._create_inference(patient.id, session_id, "longitudinal", event_tag="terminal:completed")
        report = await self._queue_report_if_consented(patient.id, session_id, partial=False)
        return {"sessionId": str(session_id), "status": "completed", "interactionPhase": "completed",
                "stateSnapshot": realtime, "reportStatus": report.get("status", "not_started") if report else "not_started",
                "inactivityTimeoutSeconds": (await self.repository.system_settings()).chat_timeout_seconds}

    async def sweep_timeouts(self, *, now: datetime | None = None) -> int:
        swept = now or datetime.now(timezone.utc)
        settings = await self.repository.system_settings()
        rows = await self.repository.abandon_inactive_sessions(
            cutoff=swept - timedelta(seconds=settings.chat_timeout_seconds if settings else 3600), now=swept,
        )
        for row in rows:
            try: await self._finalize_terminal(row["patient_id"], row["session_id"], partial=True)
            except Exception:
                await self.repository.audit(actor_id=None, actor_role="system", action="session.timeout_finalize_failed",
                                            resource_type="session", resource_id=row["session_id"], metadata={"code": "finalize_failed"})
        return len(rows)

    async def request_report(self, patient: UserRecord, session_id: UUID) -> dict[str, Any]:
        await self.auth_service.require_consent(patient.id, "ai_analysis")
        row = await self._owned(patient.id, session_id, mutation=True)
        if row["status"] in {"created", "in_progress"}: raise SessionStateError("Session must be finished")
        await self.auth_service.require_consent(patient.id, "report_generation")
        return await self._queue_report(patient.id, session_id, partial=row["status"] == "abandoned")

    async def reports(self, patient: UserRecord, session_id: UUID) -> list[dict[str, Any]]:
        await self._owned(patient.id, session_id)
        return [{"reportId": str(row["id"]), "version": row["version"], "status": row["status"],
                 "createdAt": row["created_at"].isoformat() if row.get("created_at") else None,
                 "generatedAt": row["generated_at"].isoformat() if row.get("generated_at") else None}
                for row in await self.repository.session_reports(patient.id, session_id)]

    async def _active_new(self, patient_id: UUID, session_id: UUID) -> dict[str, Any]:
        row = await self._owned(patient_id, session_id, mutation=True)
        if row["status"] not in {"created", "in_progress"}: raise SessionStateError("Session is not active")
        return row

    async def _owned(self, patient_id: UUID, session_id: UUID, *, mutation: bool = False) -> dict[str, Any]:
        row = await self.repository.owned_session(patient_id, session_id)
        if row is None: raise SessionNotFound("Session not found")
        if mutation and row.get("interaction_phase") is None:
            await self.repository.audit(actor_id=patient_id, actor_role="patient", action="legacy.mutation_rejected",
                                        resource_type="session", resource_id=session_id,
                                        metadata={"code": "legacy_session_read_only"})
            raise LegacySessionReadOnly("Legacy session is read-only")
        if row.get("interaction_phase") is not None and row["status"] in {"created", "in_progress"}:
            settings = await self.repository.system_settings()
            timeout = settings.chat_timeout_seconds if settings else 3600
            if (datetime.now(timezone.utc) - row["updated_at"]).total_seconds() > timeout:
                await self.repository.finish_session(session_id, status="abandoned", reason="timeout")
                row = {**row, "status": "abandoned", "interaction_phase": "abandoned",
                       "completion_reason": "timeout", "ended_at": datetime.now(timezone.utc)}
                await self._finalize_terminal(patient_id, session_id, partial=True)
        return row

    async def _public_session(self, patient_id: UUID, row: dict[str, Any]) -> dict[str, Any]:
        legacy = row.get("interaction_phase") is None
        result = {
            "sessionId": str(row["id"]), "sessionType": row["session_type"], "status": row["status"],
            "interactionPhase": row.get("interaction_phase"), "legacy": legacy,
            "createdAt": row["created_at"].isoformat(),
            "startedAt": row["started_at"].isoformat() if row.get("started_at") else None,
            "endedAt": row["ended_at"].isoformat() if row.get("ended_at") else None,
            "reportStatus": await self._report_status(patient_id, row["id"]),
            "inactivityTimeoutSeconds": (await self.repository.system_settings()).chat_timeout_seconds,
        }
        if legacy:
            result.update(safety=None, activeInterventions=[], stateSnapshot=None)
            return result
        state = self._dialogue_state(patient_id, row)
        inferences = await self.repository.state_inferences(patient_id, row["id"])
        if state.get("version") == 2:
            result.update(
                safety={"status": "llm_only", "riskCodes": [], "supportResources": []},
                activeInterventions=[],
                stateSnapshot=self._public_inference(patient_id, inferences[-1] if inferences else None),
            )
            return result
        result.update(
            safety={"status": state["safety"]["status"], "riskCodes": state["safety"]["riskCodes"], "supportResources": []},
            activeInterventions=await self._public_interventions(patient_id, row["id"]),
            stateSnapshot=self._public_inference(patient_id, inferences[-1] if inferences else None),
        )
        return result

    async def _store_state(self, patient_id: UUID, session_id: UUID, phase: str, state: dict[str, Any]) -> None:
        await self.repository.update_dialogue_session(
            session=session_id, patient=patient_id, phase=phase,
            dialogue_state=self._encrypt_json("sessions", "dialogue_state_encrypted", patient_id, session_id, state),
            dialogue_key=self.keyring.current_key_id,
        )

    def _dialogue_state(self, patient_id: UUID, row: dict[str, Any]) -> dict[str, Any]:
        packed = row.get("dialogue_state_encrypted")
        if not packed: return initial_dialogue_state()
        state = self._decrypt_json("sessions", "dialogue_state_encrypted", patient_id, row["id"], packed)
        if state.get("version") == 2:
            state.setdefault("askedQuestions", [])
            state.setdefault("refusedQuestions", [])
            state.setdefault("latestQuestion", None)
            return state
        asked_topics = state.setdefault("askedTopicIds", [])
        if (state.get("safety") or {}).get("status") != "awaiting_response" and "safety" not in asked_topics:
            asked_topics.append("safety")
        return state

    async def _history(
        self, patient_id: UUID, session_id: UUID, *, limit: int | None = None,
    ) -> list[dict[str, str]]:
        rows = await self.repository.session_messages(session_id)
        if limit is not None:
            rows = rows[-limit:]
        return [
            {"role": row["role"], "content": self._decrypt_message(patient_id, row)}
            for row in rows
        ]

    def _decrypt_message(self, patient_id: UUID, row: dict[str, Any]) -> str:
        return self.keyring.decrypt(row["content_encrypted"], aad=aad_for(
            table="messages", column="content_encrypted",
            patient_id=str(patient_id), record_id=str(row["id"]),
        )).decode("utf-8")

    async def _message(self, patient_id: UUID, session_id: UUID, role: str, content: str,
                       source_agent: str | None, model_id: UUID | None,
                       generation_metadata: dict[str, Any] | None = None,
                       modality: str = "text") -> UUID:
        message_id = uuid4()
        packed = self.keyring.encrypt(content.encode("utf-8"), aad=aad_for(
            table="messages", column="content_encrypted", patient_id=str(patient_id), record_id=str(message_id),
        )).pack()
        return await self.repository.append_message(
            message_id=message_id, session_id=session_id, role=role, content_encrypted=packed,
            key_version=self.keyring.current_key_id, source_agent=source_agent, model_version_id=model_id,
            generation_metadata=generation_metadata or {}, modality=modality,
        )

    async def _free_dialogue_response(
        self,
        patient_id: UUID,
        session_id: UUID,
        user_message_id: UUID,
        assistant_message_id: UUID,
        assistant_text: str,
    ) -> dict[str, Any]:
        inferences = await self.repository.state_inferences(patient_id, session_id)
        settings = await self.repository.system_settings()
        return {
            "userMessageId": str(user_message_id),
            "assistantMessageId": str(assistant_message_id),
            "assistantText": assistant_text,
            "phase": "free_dialogue",
            "safety": {"status": "llm_only", "riskCodes": [], "supportResources": []},
            "activeInterventions": [],
            "stateSnapshot": self._public_inference(patient_id, inferences[-1] if inferences else None),
            "reportStatus": await self._report_status(patient_id, session_id),
            "inactivityTimeoutSeconds": settings.chat_timeout_seconds if settings else 3600,
        }

    async def _persist_intervention(self, patient_id: UUID, session_id: UUID, kind: str, content: str,
                                    evidence: dict[str, Any], model_id: UUID | None) -> dict[str, Any]:
        rows = await self.repository.session_interventions(session_id)
        order = max((row.get("presentation_order") or 0 for row in rows), default=0) + 1
        intervention_id = uuid4()
        await self.repository.add_intervention(
            id=intervention_id, session=session_id, type=kind,
            basis=self._encrypt_json("interventions", "selection_basis_encrypted", patient_id, intervention_id, evidence),
            content=self._encrypt_json("interventions", "content_encrypted", patient_id, intervention_id, {"text": content}),
            status="delivered", key=self.keyring.current_key_id, model=model_id,
            presentation_order=order, evidence_refs=json.dumps(evidence, separators=(",", ":")),
        )
        return {"id": str(intervention_id), "type": kind, "status": "delivered", "presentationOrder": order,
                "content": content, "createdAt": datetime.now(timezone.utc).isoformat()}

    async def _public_interventions(self, patient_id: UUID, session_id: UUID) -> list[dict[str, Any]]:
        output = []
        for row in await self.repository.session_interventions(session_id):
            content = self._decrypt_json("interventions", "content_encrypted", patient_id, row["id"], row["content_encrypted"])
            output.append({"id": str(row["id"]), "type": row["intervention_type"], "status": row["status"],
                           "presentationOrder": row.get("presentation_order"),
                           "content": content.get("text") if isinstance(content, dict) else str(content),
                           "createdAt": row["created_at"].isoformat() if row.get("created_at") else None})
        return output

    async def _create_inference(self, patient_id: UUID, session_id: UUID, scope: str,
                                event_tag: str | None = None, summarize: bool = True) -> dict[str, Any]:
        since = datetime.now(timezone.utc) - timedelta(days=30) if scope == "longitudinal" else None
        evidence = await self.repository.inference_evidence(patient_id, session_id if scope == "realtime" else None, since=since)
        prediction = evidence.get("prediction")
        state_class = class_from_schema(
            prediction.get("predicted_class_code") if prediction else None,
            prediction.get("predicted_class_index") if prediction else None,
            prediction.get("output_schema") if prediction else None,
            bool(prediction and prediction.get("quality_gate_passed", True)),
        )
        confidence = float(prediction["predicted_class_probability"]) if prediction and prediction.get("predicted_class_probability") is not None else None
        evidence_refs = {
            "predictionId": str(prediction["id"]) if prediction else None,
            "assessmentIds": [str(row["id"]) for row in evidence["assessments"]],
            "alertIds": [str(row["id"]) for row in evidence["alerts"]],
            "interventionIds": [str(row["id"]) for row in evidence["interventions"]],
            "messageIds": [str(row["id"]) for row in evidence.get("messages", [])],
        }
        evidence_refs["eventKey"] = ":".join((
            scope,
            event_tag or "evidence",
            evidence_refs["predictionId"] or "none",
            evidence_refs["alertIds"][-1] if evidence_refs["alertIds"] else "none",
            evidence_refs["messageIds"][-1] if evidence_refs["messageIds"] else "none",
            evidence_refs["assessmentIds"][-1] if evidence_refs["assessmentIds"] else "none",
            evidence_refs["interventionIds"][-1] if evidence_refs["interventionIds"] else "none",
        ))
        for existing in reversed(await self.repository.state_inferences(patient_id, session_id if scope == "realtime" else None)):
            if existing.get("inference_scope") == scope and (existing.get("evidence_refs") or {}).get("eventKey") == evidence_refs["eventKey"]:
                return self._public_inference(patient_id, existing)
        payload = {"state": state_class, "confidence": confidence, "evidence": self._json_safe(evidence)}
        inference_id = uuid4(); summary = None; summary_status = "pending"; model_id = None
        if not summarize or not self.state_summary_ai_enabled or not await self._ai_allowed(patient_id):
            summary_status = "unavailable"
        else:
            try:
                model_id = await self._model(
                    "state_inference_agent", STATE_PROMPT_VERSION, self.state_summary_agent
                )
                summary = await self.state_summary_agent.summarize_state(payload)
                summary_status = "ready"
            except Exception:
                summary_status = "unavailable"
                await self.repository.audit(actor_id=None, actor_role="agent", action="state_summary.failed",
                                            resource_type="state_inference", resource_id=inference_id,
                                            metadata={"code": "state_summary_provider_error"})
        row = await self.repository.add_state_inference(
            id=inference_id, patient_id=patient_id, session_id=session_id,
            trigger_prediction_id=prediction["id"] if prediction else None, scope=scope,
            state_class=state_class, confidence=confidence, evidence_refs=evidence_refs,
            payload_encrypted=self._encrypt_json("state_inferences", "payload_encrypted", patient_id, inference_id, payload),
            summary_encrypted=self._encrypt_text("state_inferences", "summary_encrypted", patient_id, inference_id, summary) if summary else None,
            key_version=self.keyring.current_key_id, rule_version=STATE_RULE_VERSION,
            summary_model_version_id=model_id, summary_status=summary_status,
        )
        return self._public_inference(patient_id, row)

    def _public_inference(self, patient_id: UUID, row: dict[str, Any] | None) -> dict[str, Any] | None:
        if row is None: return None
        summary = self.keyring.decrypt(row["summary_encrypted"], aad=aad_for(
            table="state_inferences", column="summary_encrypted", patient_id=str(patient_id), record_id=str(row["id"]),
        )).decode("utf-8") if self.state_summary_ai_enabled and row.get("summary_encrypted") else None
        return {"inferenceId": str(row["id"]), "scope": row["inference_scope"], "state": row["state_class"],
                "confidence": float(row["confidence"]) if row.get("confidence") is not None else None,
                "summaryStatus": row["summary_status"] if self.state_summary_ai_enabled else "unavailable",
                "summary": summary,
                "createdAt": row["created_at"].isoformat()}

    async def _finalize_terminal(self, patient_id: UUID, session_id: UUID, partial: bool) -> None:
        row = await self.repository.owned_session(patient_id, session_id)
        if row and row.get("interaction_phase") is not None:
            terminal = "terminal:abandoned" if partial else "terminal:completed"
            await self._create_inference(patient_id, session_id, "realtime", event_tag=terminal)
            await self._create_inference(patient_id, session_id, "longitudinal", event_tag=terminal)
            await self._queue_report_if_consented(patient_id, session_id, partial=partial)

    async def _queue_report_if_consented(self, patient_id: UUID, session_id: UUID, partial: bool) -> dict[str, Any] | None:
        if not self.report_ai_enabled:
            return None
        consent = await self.repository.current_consent(patient_id)
        return await self._queue_report(patient_id, session_id, partial) if consent and consent.report_generation and consent.ai_analysis else None

    async def _queue_report(self, patient_id: UUID, session_id: UUID, partial: bool) -> dict[str, Any]:
        if not self.report_ai_enabled:
            return {"reportId": None, "version": None, "status": "not_started"}
        model = await self._model("report_agent", REPORT_PROMPT_VERSION, self.report_agent)
        evidence = await self.repository.inference_evidence(patient_id, session_id)
        row = await self.repository.create_report_job(
            report_id=uuid4(), session_id=session_id, model_version_id=model,
            evidence_json=json.dumps({"partial": partial, "source": "intervention_first"}, separators=(",", ":")),
        )
        if row["status"] == "generating": self._spawn_report(patient_id, session_id, row["id"], partial)
        return {"reportId": str(row["id"]), "version": row["version"], "status": row["status"]}

    def _spawn_report(self, patient_id: UUID, session_id: UUID, report_id: UUID, partial: bool) -> None:
        if report_id in self._report_tasks: return
        self._report_tasks.add(report_id)
        task = asyncio.create_task(self._run_report(patient_id, session_id, report_id, partial))
        self._tasks.add(task)
        task.add_done_callback(lambda finished: (self._tasks.discard(finished), self._report_tasks.discard(report_id)))

    async def _run_report(self, patient_id: UUID, session_id: UUID, report_id: UUID, partial: bool) -> None:
        if not self.report_ai_enabled:
            return
        try:
            consent = await self.repository.current_consent(patient_id)
            if not consent or not consent.ai_analysis or not consent.report_generation:
                raise PermissionError("consent_withdrawn")
            evidence = await self.repository.inference_evidence(patient_id, session_id)
            content = await self.report_agent.report(
                evidence=self._json_safe(evidence),
                history=await self._history(patient_id, session_id),
                partial=partial,
            )
            packed = self._encrypt_json("session_reports", "content_encrypted", patient_id, report_id, content)
            await self.repository.finish_report_job(id=report_id, status="ready", content=packed,
                                                    key=self.keyring.current_key_id, failure=None)
        except asyncio.CancelledError: raise
        except Exception:
            await self.repository.finish_report_job(id=report_id, status="failed", content=None,
                                                    key=None, failure="report_generation_failed")
            await self.repository.audit(actor_id=None, actor_role="agent", action="report.failed",
                                        resource_type="report", resource_id=report_id,
                                        metadata={"code": "report_generation_failed"})

    async def _report_status(self, patient_id: UUID, session_id: UUID) -> str:
        rows = await self.repository.session_reports(patient_id, session_id)
        return rows[0]["status"] if rows else "not_started"

    async def _ai_allowed(self, patient_id: UUID) -> bool:
        consent = await self.repository.current_consent(patient_id)
        return bool(consent and consent.ai_analysis)

    async def _model(self, component: str, prompt_version: str, agent: Any) -> UUID:
        return await self.repository.ensure_agent_model_version(
            component=component, model_name=agent.model_name, model_version=agent.model_version,
            prompt_version=prompt_version,
        )

    def _encrypt_json(self, table: str, column: str, patient_id: UUID, record_id: UUID, value: Any) -> bytes:
        return self.keyring.encrypt(json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str).encode("utf-8"), aad=aad_for(
            table=table, column=column, patient_id=str(patient_id), record_id=str(record_id),
        )).pack()

    def _encrypt_text(self, table: str, column: str, patient_id: UUID, record_id: UUID, value: str) -> bytes:
        return self.keyring.encrypt(value.encode("utf-8"), aad=aad_for(
            table=table, column=column, patient_id=str(patient_id), record_id=str(record_id),
        )).pack()

    def _decrypt_json(self, table: str, column: str, patient_id: UUID, record_id: UUID, packed: bytes) -> Any:
        return json.loads(self.keyring.decrypt(packed, aad=aad_for(
            table=table, column=column, patient_id=str(patient_id), record_id=str(record_id),
        )))

    @staticmethod
    def _json_safe(value: Any) -> Any:
        if isinstance(value, dict): return {key: SessionService._json_safe(item) for key, item in value.items()}
        if isinstance(value, (list, tuple)): return [SessionService._json_safe(item) for item in value]
        if isinstance(value, UUID): return str(value)
        if isinstance(value, (datetime, date)): return value.isoformat()
        if isinstance(value, Decimal): return float(value)
        return value

    @staticmethod
    def _support_resources(risks: list[str]) -> list[dict[str, str]]:
        resources = [{"label": "응급 도움", "contact": "119"}]
        if "self_harm" in risks:
            resources.append({"label": "자살예방 상담", "contact": "109"})
        return resources

    @staticmethod
    def _urgent_text(risks: list[str]) -> str:
        if "self_harm" in risks:
            return ("지금 즉각적인 위험이 있다면 119에 연락하고, 자해나 자살 생각과 관련된 위기라면 자살예방 상담전화 109에도 연락해 주세요. "
                    "원하시면 관리자에게 도움 요청 의사를 기록할 수 있지만 실시간 연결이나 연락을 보장하지는 않습니다. 기록을 원하시나요?")
        return ("지금은 안전이 우선입니다. 즉각적인 의료 위험이 있다면 119에 연락해 주세요. "
                "원하시면 관리자에게 도움 요청 의사를 기록할 수 있지만 실시간 연결이나 연락을 보장하지는 않습니다. 기록을 원하시나요?")
