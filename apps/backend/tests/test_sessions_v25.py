from __future__ import annotations

import asyncio
import base64
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from typing import Any
from uuid import UUID, uuid4

import pytest

from app.security.crypto import AesGcmKeyring
from app.v25.auth_service import AuthorizationError
from app.v25.models import UserRecord
from app.v25.session_agents import APPROVED_INTERVENTIONS, BedrockSessionAgent, validate_agent_output, validate_generated_text
from app.v25.session_service import (
    LegacySessionReadOnly,
    MessageInProgress,
    SessionNotFound,
    SessionService,
    corrected_declined_topic,
    is_immediate_safety_risk,
    select_intervention_type,
)


class Agent:
    model_name = "fake-sonnet"; model_version = "1"
    def __init__(self) -> None:
        self.dialogue_result = {"assistantText": "지금 가장 필요한 도움을 알려주실 수 있나요?", "questionTopicId": "desired_help", "interventionType": None}
        self.fail_dialogue = False; self.fail_summary = False; self.report_calls = []
    async def dialogue(self, context):
        if self.fail_dialogue: raise RuntimeError("AWS secret-value")
        return dict(self.dialogue_result)
    async def summarize_state(self, evidence):
        if self.fail_summary: raise RuntimeError("provider secret-value")
        return f"현재 상태는 {evidence['state']}로 기록되었어요."
    async def report(self, **values):
        self.report_calls.append(values)
        return {"summary": "근거 기반 기록", "partial": values["partial"]}


class Repo:
    def __init__(self, patient_id: UUID) -> None:
        self.patient_id = patient_id; self.sessions = {}; self.messages = []; self.interventions = []
        self.assessments = []; self.inferences = []; self.reports_rows = []; self.audits = []
        self.settings = SimpleNamespace(chat_timeout_seconds=3600, interventions_enabled=True)
        self.consent = SimpleNamespace(report_generation=True, ai_analysis=True); self.lock_acquired = True
        self.model_calls = []; self.stale_sessions = []; self.prediction = None
    async def system_settings(self): return self.settings
    async def open_session(self, **v):
        active = next((row for row in self.sessions.values() if row["status"] in {"created", "in_progress"}), None)
        if active: return active
        row = {"id": v["session_id"], "patient_id": v["patient_id"], "session_type": v["session_type"],
               "trigger_alert_id": v["trigger_alert_id"], "status": "in_progress", "interaction_phase": "safety_check",
               "dialogue_state_encrypted": v["dialogue_state_encrypted"], "dialogue_state_key_version": v["dialogue_state_key_version"],
               "created_at": v["now"], "updated_at": v["now"], "started_at": v["now"], "ended_at": None,
               "completion_reason": None, "_timed_out_session_id": None}
        self.sessions[row["id"]] = row; return row
    async def owned_session(self, patient_id, session_id):
        row = self.sessions.get(session_id); return row if row and row["patient_id"] == patient_id else None
    @asynccontextmanager
    async def message_turn(self, session_id): yield self.lock_acquired
    async def update_dialogue_session(self, **v):
        self.sessions[v["session"]].update(interaction_phase=v["phase"], dialogue_state_encrypted=v["dialogue_state"],
                                           dialogue_state_key_version=v["dialogue_key"], updated_at=datetime.now(timezone.utc))
    async def append_message(self, **v):
        row = {"id": v["message_id"], "role": v["role"], "content_encrypted": v["content_encrypted"],
               "sequence_no": len(self.messages)+1, "created_at": datetime.now(timezone.utc)}
        self.messages.append(row); self.sessions[v["session_id"]]["updated_at"] = datetime.now(timezone.utc); return v["message_id"]
    async def session_messages(self, session_id): return list(self.messages)
    async def session_interventions(self, session_id): return list(self.interventions)
    async def add_intervention(self, **v):
        self.interventions.append({"id": v["id"], "session_id": v["session"], "intervention_type": v["type"],
                                   "selection_basis_encrypted": v["basis"], "content_encrypted": v["content"],
                                   "status": v["status"], "presentation_order": v["presentation_order"],
                                   "evidence_refs": v["evidence_refs"], "created_at": datetime.now(timezone.utc),
                                   "model_version_id": v["model"]}); return v["id"]
    async def add_assessment(self, **v): self.assessments.append({**v, "completed_at": datetime.now(timezone.utc)}); return v["id"]
    async def session_assessments(self, session_id): return self.assessments
    async def ensure_agent_model_version(self, **v): self.model_calls.append(v); return uuid4()
    async def ensure_rule_model_version(self, **v): self.model_calls.append({"kind": "rule", **v}); return uuid4()
    async def inference_evidence(self, patient_id, session_id, *, since=None):
        return {"prediction": self.prediction, "assessments": [{"id": row["id"], "raw_score": row["score"],
                 "scale_min": row["minimum"], "scale_max": row["maximum"], "completed_at": row["completed_at"]} for row in self.assessments],
                "alerts": [], "interventions": [{"id": row["id"], "intervention_type": row["intervention_type"],
                 "status": row["status"], "presentation_order": row["presentation_order"], "created_at": row["created_at"]} for row in self.interventions],
                "messages": [{"id": row["id"], "role": row["role"], "created_at": row["created_at"]} for row in self.messages]}
    async def add_state_inference(self, **v):
        row = {**v, "inference_scope": v["scope"], "created_at": datetime.now(timezone.utc)}
        self.inferences.append(row); return row
    async def state_inferences(self, patient_id, session_id=None): return [row for row in self.inferences if session_id is None or row["session_id"] == session_id]
    async def finish_session(self, session_id, *, status, reason):
        self.sessions[session_id].update(status=status, interaction_phase="completed" if status == "completed" else "abandoned",
                                         completion_reason=reason, ended_at=datetime.now(timezone.utc))
    async def abandon_inactive_sessions(self, *, cutoff, now):
        rows, self.stale_sessions = self.stale_sessions, []
        for item in rows: self.sessions[item["session_id"]].update(status="abandoned", interaction_phase="abandoned", ended_at=now)
        return rows
    async def current_consent(self, patient_id): return self.consent
    async def create_report_job(self, **v):
        if self.reports_rows and self.reports_rows[-1]["status"] in {"generating", "ready"}: return self.reports_rows[-1]
        now = datetime.now(timezone.utc)
        row = {"id": v["report_id"], "session_id": v["session_id"], "version": len(self.reports_rows)+1,
               "status": "generating", "evidence_refs": {"partial": "true" in v["evidence_json"]},
               "content_encrypted": None, "created_at": now, "generated_at": None, "updated_at": now}
        self.reports_rows.append(row); return row
    async def finish_report_job(self, **v):
        row = next(row for row in self.reports_rows if row["id"] == v["id"])
        row.update(status=v["status"], content_encrypted=v["content"], generated_at=datetime.now(timezone.utc), updated_at=datetime.now(timezone.utc))
    async def session_reports(self, patient_id, session_id): return [row for row in reversed(self.reports_rows) if row["session_id"] == session_id]
    async def pending_report_jobs(self): return []
    async def audit(self, **v): self.audits.append(v)


class Auth:
    async def require_consent(self, patient_id, feature): return None


def fixture() -> tuple[SessionService, Repo, Agent, UserRecord]:
    patient = UserRecord(uuid4(), "p@example.com", "", "patient", "active", False, datetime.now(timezone.utc))
    repo, agent = Repo(patient.id), Agent()
    ring = AesGcmKeyring.from_config("v1:" + base64.b64encode(b"z" * 32).decode(), "v1")
    return SessionService(repo, ring, Auth(), agent), repo, agent, patient


async def opened():
    svc, repo, agent, patient = fixture()
    created = await svc.open(patient, "manual_checkin", None)
    session_id = UUID(created["sessionId"])
    return svc, repo, agent, patient, session_id, created


def test_open_is_slot_free_and_starts_safety_with_encrypted_ledger() -> None:
    async def scenario():
        svc, repo, _, _, session_id, created = await opened()
        assert created["interactionPhase"] == "safety_check" and created["legacy"] is False
        assert created["inactivityTimeoutSeconds"] == 3600
        assert "slots" not in created and "missingSlots" not in created and "handoffReady" not in created
        assert repo.sessions[session_id]["dialogue_state_encrypted"] and repo.messages[0]["role"] == "assistant"
        assert repo.inferences[0]["state_class"] == "unknown"
        await svc.shutdown()
    asyncio.run(scenario())


@pytest.mark.parametrize(("message", "expected"), [
    ("친구가 한잔하자고 권유해서 거절해야 해요", "refusal_practice"),
    ("냉장고에 술이 있어요", "leave_location"), ("숨이 가쁘고 빨라요", "breathing"),
    ("너무 불안하고 긴장돼요", "grounding"), ("술 생각이 계속 생각나요", "urge_surfing"),
    ("매번 반복되는 상황이에요", "attention_shift"), ("연락할 친구가 있어요", "social_support"),
    ("목이 마르고 갈증이 나요", "hydration"), ("그냥 힘들어요", "self_monitoring"),
])
def test_first_intervention_priority_and_persistence(message: str, expected: str) -> None:
    async def scenario():
        svc, repo, _, patient, session_id, _ = await opened()
        cleared = await svc.message(patient, session_id, "네, 지금은 안전해요")
        assert cleared["phase"] == "intervention_dialogue" and not repo.interventions
        result = await svc.message(patient, session_id, message)
        assert result["phase"] == "intervention_dialogue"
        assert result["inactivityTimeoutSeconds"] == 3600
        assert result["activeInterventions"][0]["type"] == expected
        assert repo.interventions[0]["presentation_order"] == 1 and repo.interventions[0]["evidence_refs"]
        assert not hasattr(repo, "slots")
        await svc.shutdown()
    asyncio.run(scenario())


@pytest.mark.parametrize("message", ["죽고 싶어요", "술을 마시고 운전하려고 해요", "술과 수면제를 같이 먹었어요", "심한 금단으로 경련이 있어요"])
def test_urgent_safety_is_deterministic_audited_and_does_not_end_dialogue(message: str) -> None:
    async def scenario():
        svc, repo, _, patient, session_id, _ = await opened()
        result = await svc.message(patient, session_id, message)
        assert "119" in result["assistantText"] and result["safety"]["status"] == "urgent"
        if "죽고" in message: assert "109" in result["assistantText"]
        assert "보장하지" in result["assistantText"] and repo.sessions[session_id]["status"] == "in_progress"
        assert any(row["action"] == "safety.detected" for row in repo.audits)
        await svc.shutdown()
    asyncio.run(scenario())


def test_provider_failure_uses_safe_fallback_and_summary_failure_keeps_inference() -> None:
    async def scenario():
        svc, repo, agent, patient, session_id, _ = await opened()
        await svc.message(patient, session_id, "네, 지금은 안전해요")
        await svc.message(patient, session_id, "그냥 힘들어요")
        agent.fail_dialogue = True; agent.fail_summary = True
        result = await svc.message(patient, session_id, "조금 더 말할게요")
        assert "안전하고 부담이 적은" in result["assistantText"]
        assert result["stateSnapshot"]["summaryStatus"] == "unavailable"
        assert result["stateSnapshot"]["state"] == "unknown"
        assert "secret-value" not in repr(repo.audits)
        assert {row["metadata"]["code"] for row in repo.audits if "code" in row.get("metadata", {})} >= {"dialogue_provider_error", "state_summary_provider_error"}
        await svc.shutdown()
    asyncio.run(scenario())


def test_optional_assessment_creates_no_placeholder_when_skipped_and_inference_keeps_model_class() -> None:
    async def scenario():
        svc, repo, _, patient, session_id, _ = await opened()
        repo.prediction = {"id": uuid4(), "predicted_class_index": 2, "predicted_class_code": "class_2",
                           "predicted_class_probability": .91, "quality_gate_passed": True,
                           "output_schema": {"classes": [{"index": 0, "code": "low"}, {"index": 1, "code": "mid"}, {"index": 2, "code": "high"}]},
                           "predicted_at": datetime.now(timezone.utc)}
        await svc.message(patient, session_id, "그냥 힘들어요")
        assert not repo.assessments and repo.inferences[-1]["state_class"] == "high"
        created = await svc.assessment(patient, session_id, {"instrumentCode": "AUQ", "version": "1", "phase": "pre_intervention",
                         "attemptNo": 1, "answers": {"q1": 7}, "rawScore": 7, "scaleMin": 0, "scaleMax": 56})
        assert created["assessmentId"] and len(repo.assessments) == 1 and repo.inferences[-1]["state_class"] == "high"
        await svc.shutdown()
    asyncio.run(scenario())


def test_manual_finish_is_completed_without_slots_and_report_body_is_never_returned() -> None:
    async def scenario():
        svc, repo, agent, patient, session_id, _ = await opened()
        result = await svc.finish(patient, session_id)
        assert result["status"] == "completed" and result["interactionPhase"] == "completed"
        await asyncio.gather(*tuple(svc._tasks))
        reports = await svc.reports(patient, session_id)
        assert reports[0]["status"] == "ready" and "content" not in reports[0]
        assert agent.report_calls and "slots" not in agent.report_calls[0]
        assert not any(call["component"] == "memory_agent" for call in repo.model_calls)
        again = await svc.finish(patient, session_id)
        assert again["status"] == "completed"
        await svc.shutdown()
    asyncio.run(scenario())


def test_timeout_abandons_and_creates_final_inferences_and_partial_report() -> None:
    async def scenario():
        svc, repo, _, patient, session_id, _ = await opened()
        repo.stale_sessions = [{"session_id": session_id, "patient_id": patient.id}]
        assert await svc.sweep_timeouts(now=datetime.now(timezone.utc)) == 1
        await asyncio.gather(*tuple(svc._tasks))
        assert repo.sessions[session_id]["status"] == "abandoned"
        assert {row["scope"] for row in repo.inferences} == {"realtime", "longitudinal"}
        assert repo.reports_rows[-1]["status"] == "ready"
        await svc.shutdown()
    asyncio.run(scenario())


def test_legacy_session_is_readable_but_every_mutation_is_rejected() -> None:
    async def scenario():
        svc, repo, _, patient = fixture(); now = datetime.now(timezone.utc); sid = uuid4()
        repo.sessions[sid] = {"id": sid, "patient_id": patient.id, "session_type": "manual_checkin", "status": "completed",
                              "interaction_phase": None, "created_at": now, "updated_at": now, "started_at": now,
                              "ended_at": now, "completion_reason": "normal"}
        read = await svc.get(patient, sid)
        assert read["legacy"] is True and read["interactionPhase"] is None
        for operation in (lambda: svc.message(patient, sid, "수정"), lambda: svc.finish(patient, sid),
                          lambda: svc.assessment(patient, sid, {"instrumentCode":"AUQ"})):
            with pytest.raises(LegacySessionReadOnly): await operation()
        await svc.shutdown()
    asyncio.run(scenario())


def test_concurrent_message_conflict_and_cross_owner_hidden() -> None:
    async def scenario():
        svc, repo, _, patient, session_id, _ = await opened(); repo.lock_acquired = False
        with pytest.raises(MessageInProgress): await svc.message(patient, session_id, "동시 메시지")
        other = UserRecord(uuid4(), "o@example.com", "", "patient", "active", False, datetime.now(timezone.utc))
        with pytest.raises(SessionNotFound): await svc.get(other, session_id)
        await svc.shutdown()
    asyncio.run(scenario())


def test_agent_output_validation_blocks_repeats_multiple_questions_and_treatment_claims() -> None:
    state = {"askedTopicIds": ["trigger"], "declinedTopicIds": ["support"]}
    assert validate_agent_output({"assistantText": "무슨 일이 있었나요?", "questionTopicId": "trigger"}, state) is None
    assert validate_agent_output({"assistantText": "어디인가요? 괜찮나요?", "questionTopicId": "safety"}, state) is None
    assert validate_agent_output({"assistantText": "이 중재로 갈망이 바로 감소합니다.", "questionTopicId": None}, state) is None
    assert validate_agent_output({"assistantText": "지금은 안전한가요", "questionTopicId": None}, state) is None
    assert validate_agent_output({"assistantText": "안전한가요 그리고 술이 있나요", "questionTopicId": "safety"}, state) is None
    assert validate_agent_output({"assistantText": "지금 느낌을 살펴볼까요?", "questionTopicId": "emotion_body", "interventionType": "other"}, state) is None
    assert validate_agent_output({"assistantText": "지금 느낌을 살펴볼까요?", "questionTopicId": "emotion_body", "interventionType": None}, state)


@pytest.mark.parametrize(("text", "topic"), [
    ("무슨 일이 있었죠", "trigger"),
    ("지금 어디에 계세요", "current_environment"),
])
def test_punctuationless_korean_questions_require_topic_classification(text: str, topic: str) -> None:
    state = {"askedTopicIds": [], "declinedTopicIds": []}
    assert validate_agent_output({"assistantText": text, "questionTopicId": None}, state) is None
    assert validate_agent_output({"assistantText": text, "questionTopicId": topic}, state)


@pytest.mark.parametrize("text", [
    "알코올 사용 장애로 진단됩니다.", "수면제를 끊으세요.", "이 방법은 효과가 있습니다.",
    "대화 때문에 갈망이 개선됩니다.", "치료로 호전되었습니다.",
    "당신은 알코올 중독이에요.", "환자는 알코올 의존증으로 보여요.",
    "이 방법으로 상태가 좋아집니다.", "이런 훈련을 통해 증상이 완화됩니다.",
])
def test_generated_text_rejects_diagnosis_medication_effect_and_causality_variants(text: str) -> None:
    assert not validate_generated_text(text)


def test_report_agent_rejects_treatment_claim_in_provider_json() -> None:
    class Adapter:
        model_id = "fake"
        async def complete(self, **kwargs): return '{"summary":"이 대화로 갈망이 바로 감소합니다."}'
    async def scenario():
        with pytest.raises(ValueError, match="report_output_rejected"):
            await BedrockSessionAgent(Adapter()).report(evidence={}, history=[], partial=False)
    asyncio.run(scenario())


def test_safety_detection_negation_and_allowlist_are_stable() -> None:
    assert is_immediate_safety_risk("술을 마시고 운전하려고 해요")
    assert not is_immediate_safety_risk("술은 마셨지만 운전은 하지 않고 택시를 탈 거예요")
    assert set(APPROVED_INTERVENTIONS) == {"breathing", "urge_surfing", "attention_shift", "leave_location", "refusal_practice", "social_support", "grounding", "hydration", "self_monitoring"}


def test_ambiguous_or_explicitly_unsafe_initial_answer_stays_in_safety_check() -> None:
    async def scenario():
        for answer in ("아니요, 안전하지 않아요", "잘 모르겠어요"):
            svc, repo, _, patient, session_id, _ = await opened()
            result = await svc.message(patient, session_id, answer)
            assert result["phase"] == "safety_check" and result["safety"]["status"] == "concern"
            assert "119" in result["assistantText"] and not repo.interventions
            await svc.shutdown()
    asyncio.run(scenario())


def test_initial_bare_affirmative_resolves_safety_from_prompt_context() -> None:
    async def scenario():
        svc, repo, _, patient, session_id, _ = await opened()
        result = await svc.message(patient, session_id, "네")
        assert result["phase"] == "intervention_dialogue"
        assert result["safety"]["status"] == "clear"
        assert not result["activeInterventions"] and not repo.interventions
        await svc.shutdown()
    asyncio.run(scenario())


def test_urgent_ambiguous_followup_retains_safety_until_involvement_choice() -> None:
    async def scenario():
        svc, repo, _, patient, session_id, _ = await opened()
        urgent = await svc.message(patient, session_id, "지금 죽고 싶어요")
        assert urgent["safety"]["status"] == "urgent"
        ambiguous = await svc.message(patient, session_id, "잘 모르겠어요")
        assert ambiguous["phase"] == "safety_check"
        assert ambiguous["safety"]["status"] == "urgent"
        assert not repo.interventions
        resolved = await svc.message(patient, session_id, "아니요, 관리자 기록은 원하지 않아요")
        assert resolved["phase"] == "intervention_dialogue"
        assert resolved["safety"]["status"] == "clear"
        assert any(row["action"] == "safety.involvement_declined" for row in repo.audits)
        await svc.shutdown()
    asyncio.run(scenario())


def test_interventions_disabled_suppresses_first_and_later_llm_intervention_content() -> None:
    async def scenario():
        svc, repo, agent, patient, session_id, _ = await opened(); repo.settings.interventions_enabled = False
        await svc.message(patient, session_id, "네, 지금은 안전해요")
        first = await svc.message(patient, session_id, "냉장고에 술이 있어요")
        agent.dialogue_result = {"assistantText": "술에서 벗어나는 방법을 해보세요.", "questionTopicId": None, "interventionType": "leave_location"}
        later = await svc.message(patient, session_id, "조금 더 말할게요")
        assert not repo.interventions and first["activeInterventions"] == [] and later["activeInterventions"] == []
        assert first["assistantText"] == later["assistantText"] and "술에서 벗어나는" not in later["assistantText"]
        await svc.shutdown()
    asyncio.run(scenario())


def test_finish_creates_distinct_terminal_snapshot_and_event_key_tracks_prediction_alert() -> None:
    async def scenario():
        svc, repo, _, patient, session_id, _ = await opened()
        prediction_id, alert_id = uuid4(), uuid4()
        repo.prediction = {"id": prediction_id, "predicted_class_index": 1, "predicted_class_code": "mid",
            "predicted_class_probability": .5, "quality_gate_passed": True, "output_schema": {},
            "predicted_at": datetime.now(timezone.utc)}
        original = repo.inference_evidence
        async def with_alert(*args, **kwargs):
            evidence = await original(*args, **kwargs)
            evidence["alerts"] = [{"id": alert_id, "status": "notified", "triggered_at": datetime.now(timezone.utc)}]
            return evidence
        repo.inference_evidence = with_alert
        await svc.message(patient, session_id, "네, 지금은 안전해요")
        before = repo.inferences[-1]["evidence_refs"]["eventKey"]
        finished = await svc.finish(patient, session_id)
        after = repo.inferences[-2]["evidence_refs"]["eventKey"]
        assert before != after and "terminal:completed" in after
        assert str(prediction_id) in after and str(alert_id) in after
        assert finished["inactivityTimeoutSeconds"] == 3600
        await svc.shutdown()
    asyncio.run(scenario())


def test_ai_consent_withdrawal_blocks_all_new_patient_mutations_and_llm_paths() -> None:
    async def scenario():
        svc, repo, _, patient, session_id, _ = await opened()
        class Deny:
            async def require_consent(self, patient_id, feature): raise AuthorizationError("Consent required")
        svc.auth_service = Deny(); repo.consent.ai_analysis = False
        body = {"instrumentCode": "AUQ", "version": "1", "phase": "pre_intervention", "attemptNo": 1,
                "answers": {}, "rawScore": 0, "scaleMin": 0, "scaleMax": 56}
        for operation in (lambda: svc.message(patient, session_id, "새 메시지"),
                          lambda: svc.assessment(patient, session_id, body),
                          lambda: svc.finish(patient, session_id),
                          lambda: svc.request_report(patient, session_id)):
            with pytest.raises(AuthorizationError): await operation()
        assert not repo.assessments and repo.sessions[session_id]["status"] == "in_progress"
        await svc.shutdown()
    asyncio.run(scenario())


def test_stale_legacy_session_is_never_timed_out_or_mutated() -> None:
    async def scenario():
        svc, repo, _, patient = fixture(); sid = uuid4(); old = datetime.now(timezone.utc) - timedelta(days=3)
        repo.sessions[sid] = {"id": sid, "patient_id": patient.id, "session_type": "manual_checkin", "status": "in_progress",
            "interaction_phase": None, "created_at": old, "updated_at": old, "started_at": old, "ended_at": None,
            "completion_reason": None}
        result = await svc.get(patient, sid)
        assert result["legacy"] is True and repo.sessions[sid]["status"] == "in_progress"
        with pytest.raises(LegacySessionReadOnly): await svc.message(patient, sid, "수정")
        assert any(row["action"] == "legacy.mutation_rejected" for row in repo.audits)
        await svc.shutdown()
    asyncio.run(scenario())


def test_explicit_correction_releases_named_declined_topic_only() -> None:
    async def scenario():
        svc, repo, agent, patient, session_id, _ = await opened()
        await svc.message(patient, session_id, "네, 지금은 안전해요")
        await svc.message(patient, session_id, "그냥 힘들어요")
        await svc.message(patient, session_id, "계속 이야기할게요")
        await svc.message(patient, session_id, "어떤 도움을 원하는지는 말하고 싶지 않아요")
        state = svc._dialogue_state(patient.id, repo.sessions[session_id])
        assert "desired_help" in state["declinedTopicIds"]
        agent.dialogue_result = {"assistantText": "편한 만큼 말씀해 주세요.", "questionTopicId": None, "interventionType": None}
        await svc.message(patient, session_id, "아까 어떤 도움을 원하는지에 대한 말은 정정할게요. 이제는 말할게요")
        state = svc._dialogue_state(patient.id, repo.sessions[session_id])
        assert "desired_help" not in state["declinedTopicIds"]
        await svc.shutdown()
    asyncio.run(scenario())


def test_correction_never_blindly_pops_a_declined_topic() -> None:
    declined = ["support", "desired_help"]
    assert corrected_declined_topic("아까 말은 정정할게요", declined) is None
    assert corrected_declined_topic("아까 연락할 사람에 대한 말은 정정할게요", declined) == "support"
    assert corrected_declined_topic("아까 어떤 도움을 원하는지에 대한 말은 정정할게요", declined) == "desired_help"
