from __future__ import annotations

import asyncio
import base64
from datetime import datetime, timezone
from unittest.mock import AsyncMock
from uuid import UUID, uuid4

from app.core.security.crypto import AesGcmKeyring
from app.models.records import UserRecord
from app.services.session import SessionService
from tests.test_sessions_v25 import Agent, Repo


class CountingAgent(Agent):
    def __init__(self) -> None:
        super().__init__()
        self.summary_calls = 0

    async def summarize_state(self, evidence):
        self.summary_calls += 1
        return await super().summarize_state(evidence)


class TrackingAuth:
    def __init__(self) -> None:
        self.consent_calls: list[str] = []

    async def require_consent(self, patient_id, feature):
        self.consent_calls.append(feature)


def _fixture(*, state_summary_enabled: bool, report_enabled: bool):
    patient = UserRecord(
        uuid4(), "flags@example.com", "", "patient", "active", False,
        datetime.now(timezone.utc),
    )
    repo = Repo(patient.id)
    auth = TrackingAuth()
    intervention, summary, report = CountingAgent(), CountingAgent(), CountingAgent()
    ring = AesGcmKeyring.from_config(
        "v1:" + base64.b64encode(b"f" * 32).decode(), "v1",
    )
    service = SessionService(
        repo, ring, auth, intervention, summary, report,
        state_summary_ai_enabled=state_summary_enabled,
        report_ai_enabled=report_enabled,
    )
    return service, repo, auth, intervention, summary, report, patient


async def _open(service: SessionService, patient: UserRecord) -> UUID:
    opened = await service.open(patient, "manual_checkin", None)
    return UUID(opened["sessionId"])


def test_disabled_state_and_report_ai_quietly_skip_all_provider_work() -> None:
    async def scenario() -> None:
        service, repo, auth, intervention, summary, report, patient = _fixture(
            state_summary_enabled=False, report_enabled=False,
        )
        session_id = await _open(service, patient)
        await service.message(patient, session_id, "지금 상태를 기록해 주세요.", uuid4())
        assert intervention.dialogue_calls == 1

        repo.pending_report_jobs = AsyncMock(side_effect=AssertionError("pending jobs must not load"))
        await service.resume_pending_reports()
        result = await service.finish(patient, session_id)

        assert result["reportStatus"] == "not_started"
        assert len(repo.inferences) == 2
        assert all(row["summary_status"] == "unavailable" for row in repo.inferences)
        assert all(row["summary_encrypted"] is None for row in repo.inferences)
        assert summary.summary_calls == 0 and report.report_calls == []
        assert not {call["component"] for call in repo.model_calls} & {
            "state_inference_agent", "report_agent",
        }
        assert repo.reports_rows == [] and service._tasks == set()

        manual = await service.request_report(patient, session_id)
        assert manual == {"reportId": None, "version": None, "status": "not_started"}
        assert auth.consent_calls[-1] == "report_generation"
        assert await service.reports(patient, session_id) == []
        await service.shutdown()

    asyncio.run(scenario())


def test_disabled_state_ai_masks_previously_ready_summary_on_dedupe() -> None:
    async def scenario() -> None:
        enabled, repo, _, _, summary, _, patient = _fixture(
            state_summary_enabled=True, report_enabled=False,
        )
        session_id = await _open(enabled, patient)
        ready = await enabled._create_inference(patient.id, session_id, "realtime")
        assert ready["summaryStatus"] == "ready" and ready["summary"]
        calls = summary.summary_calls

        disabled = SessionService(
            repo, enabled.keyring, TrackingAuth(), CountingAgent(), CountingAgent(), CountingAgent(),
            state_summary_ai_enabled=False, report_ai_enabled=False,
        )
        masked = await disabled._create_inference(patient.id, session_id, "realtime")
        assert masked["summaryStatus"] == "unavailable" and masked["summary"] is None
        assert summary.summary_calls == calls
        assert len(repo.inferences) == 1
        await enabled.shutdown()
        await disabled.shutdown()

    asyncio.run(scenario())


def test_enabled_flags_restore_summary_and_report_generation() -> None:
    async def scenario() -> None:
        service, repo, _, _, summary, report, patient = _fixture(
            state_summary_enabled=True, report_enabled=True,
        )
        session_id = await _open(service, patient)
        result = await service.finish(patient, session_id)
        await asyncio.gather(*tuple(service._tasks))

        assert result["stateSnapshot"]["summaryStatus"] == "ready"
        assert summary.summary_calls == 2
        assert report.report_calls and repo.reports_rows[0]["status"] == "ready"
        assert {call["component"] for call in repo.model_calls} >= {
            "state_inference_agent", "report_agent",
        }
        await service.shutdown()

    asyncio.run(scenario())
