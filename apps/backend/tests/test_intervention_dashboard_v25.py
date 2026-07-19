from __future__ import annotations

import asyncio
import base64
import json
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest

from app.core.security.crypto import AesGcmKeyring, aad_for
from app.services.dashboard import DashboardRangeError, DashboardService, PpgPreviewNotFound, class_from_schema


class Repo:
    def __init__(self, rows, sensor=None): self.rows, self.sensor, self.audits = rows, sensor, []
    async def dashboard_rows(self, patient_id, since): return self.rows
    async def prediction_sensor(self, patient_id, prediction_id): return self.sensor
    async def audit(self, **values): self.audits.append(values)


class Storage:
    def __init__(self, payload): self.payload = payload
    def read(self, **values): return json.dumps(self.payload).encode()


def ring(): return AesGcmKeyring.from_config("v1:" + base64.b64encode(b"k" * 32).decode(), "v1")


def empty_rows():
    return {key: [] for key in ("predictions", "assessments", "alerts", "sessions", "interventions", "inferences", "reports")}


def test_class_mapping_uses_output_schema_and_quality_failure_is_unknown() -> None:
    schema = {"classes": [{"index": 0, "code": "low"}, {"index": 1, "code": "mid"}, {"index": 2, "code": "high"}]}
    assert class_from_schema("class_2", 2, schema) == "high"
    assert class_from_schema("class_0", 0, {"classes": [0, 1, 2]}) == "unknown"
    assert class_from_schema("high", 2, schema, quality=False) == "unknown"


def test_patient_and_admin_dashboard_have_distinct_sensitive_and_ppg_shapes() -> None:
    async def scenario():
        patient, prediction, inference = uuid4(), uuid4(), uuid4(); now = datetime.now(timezone.utc)
        rows = empty_rows()
        rows["predictions"] = [{"id": prediction, "sensor_recording_id": uuid4(), "predicted_class_index": 2,
            "predicted_class_code": "class_2", "predicted_class_probability": .8, "predicted_at": now,
            "quality_gate_passed": True, "output_schema": {"classes": [{"index": 2, "code": "high"}]}}]
        encrypted = ring().encrypt("근거 기반 요약".encode(), aad=aad_for(table="state_inferences", column="summary_encrypted",
            patient_id=str(patient), record_id=str(inference))).pack()
        rows["inferences"] = [{"id": inference, "session_id": None, "trigger_prediction_id": prediction,
            "inference_scope": "realtime", "state_class": "high", "confidence": .8,
            "summary_status": "ready", "summary_encrypted": encrypted, "created_at": now}]
        rows["assessments"] = [{"id": uuid4(), "session_id": uuid4(), "completed_at": now,
            "instrument_code": "AUQ", "raw_score": 12, "scale_min": 0, "scale_max": 56}]
        service = DashboardService(
            Repo(rows), ring(), Storage({}), state_summary_ai_enabled=True,
        )
        patient_view = await service.dashboard(patient, "24h")
        admin_view = await service.dashboard(patient, "24h", admin=True)
        assert patient_view["predictions"][0]["ppgPreviewAvailable"] is True
        assert patient_view["latestState"]["summaryStatus"] == "ready"
        assert patient_view["latestState"]["summary"] == "근거 기반 요약"
        assert any(event["type"] == "auq" for event in patient_view["events"])
        assert "ppgPreviewAvailable" not in admin_view["predictions"][0]
        assert "summary" not in admin_view["latestState"]
        assert admin_view["latestState"]["summaryStatus"] == "ready"
        assert patient_view["from"].endswith("+00:00") and patient_view["to"].endswith("+00:00")
        with pytest.raises(DashboardRangeError): await service.dashboard(patient, "1y")
    asyncio.run(scenario())


def test_disabled_state_summary_is_masked_without_decryption_for_patient_and_admin() -> None:
    class DecryptSpy:
        def __init__(self) -> None:
            self.decrypt_calls = 0

        def decrypt(self, *_args, **_kwargs):
            self.decrypt_calls += 1
            raise AssertionError("disabled summaries must not be decrypted")

    async def scenario() -> None:
        patient, inference = uuid4(), uuid4()
        rows = empty_rows()
        rows["inferences"] = [{
            "id": inference,
            "inference_scope": "realtime",
            "state_class": "high",
            "confidence": .8,
            "summary_status": "ready",
            "summary_encrypted": b"stored-summary",
            "created_at": datetime.now(timezone.utc),
        }]
        keyring = DecryptSpy()
        service = DashboardService(Repo(rows), keyring, Storage({}))

        patient_view = await service.dashboard(patient, "24h")
        admin_view = await service.dashboard(patient, "24h", admin=True)

        assert patient_view["latestState"]["summaryStatus"] == "unavailable"
        assert patient_view["latestState"]["summary"] is None
        assert admin_view["latestState"]["summaryStatus"] == "unavailable"
        assert "summary" not in admin_view["latestState"]
        assert keyring.decrypt_calls == 0

    asyncio.run(scenario())


def test_ppg_preview_is_owned_window_finite_chronological_and_capped_at_512() -> None:
    async def scenario():
        patient, prediction, recording = uuid4(), uuid4(), uuid4(); start = datetime.now(timezone.utc); end = start + timedelta(seconds=10)
        samples = [{"sensor": "PPG_GREEN", "timestampMs": int((start + timedelta(seconds=i/60)).timestamp()*1000), "value": i/10} for i in range(601)]
        repo = Repo(empty_rows(), {"prediction_id": prediction, "recording_id": recording, "storage_uri": "safe.ntg",
            "window_started_at": start, "window_ended_at": end, "sample_rates": {"ppg": 60}})
        result = await DashboardService(repo, ring(), Storage({"samples": samples})).ppg_preview(patient, prediction)
        assert len(result["samples"]) == 512 and result["samples"][0]["at"] <= result["samples"][-1]["at"]
        assert 51 <= result["samplingHz"] <= 52
    asyncio.run(scenario())


def test_unowned_ppg_preview_returns_same_not_found_and_audits_without_owner_leak() -> None:
    async def scenario():
        repo = Repo(empty_rows(), None); patient, prediction = uuid4(), uuid4()
        with pytest.raises(PpgPreviewNotFound):
            await DashboardService(repo, ring(), Storage({})).ppg_preview(patient, prediction)
        assert repo.audits[0]["metadata"] == {"code": "ppg_preview_not_found"}
    asyncio.run(scenario())
