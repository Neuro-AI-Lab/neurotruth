from __future__ import annotations

import asyncio
import base64
import json
import tempfile
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from inspect import getsource
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.security.crypto import AesGcmKeyring
from app.services.auth import AuthorizationError
from app.models.records import SensorResultRecord
from app.repositories.postgres import RepositoryConflictError, SqlAlchemyV25Repository
from app.api.v1.routes.sensor import SensorWindow, get_sensor_service, prediction_stream, router
from app.api.v1.dependencies import get_runtime, patient_user
from app.services.prediction import PatientPredictionHub
from app.services.sensor import (
    SensorModelUnavailable,
    SensorPayloadConflict,
    SensorPayloadInvalid,
    SensorService,
)
from app.storage.sensor import EncryptedSensorStorage, canonical_sensor_json


class FakePredictor:
    ready = True
    model_name = "Conv1DNet"
    model_version = "fixture-v1"
    artifact_uri = None

    def __init__(self) -> None:
        self.calls = 0
        self.payloads: list[dict[str, Any]] = []
        self.entered: asyncio.Event | None = None
        self.release: asyncio.Event | None = None

    async def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        self.calls += 1
        self.payloads.append(payload)
        if self.entered is not None:
            self.entered.set()
        if self.release is not None:
            await self.release.wait()
        return {
            "predictionSchema": "binary-craving-v1", "class": 1, "classCode": "high",
            "confidence": 0.91, "cravingProbability": 0.91,
            "classProbabilities": {"low": 0.09, "high": 0.91},
            "timestampMs": payload["windowEndMs"], "sequence": payload["sequence"],
            "_lat": {"comm_ms": 1.0, "queue_ms": 2.0, "feature_ms": 3.0,
                     "model_ms": 4.0, "server_ms": 5.0},
            "_readyPerf": time.perf_counter(),
        }


class FakeLatency:
    def __init__(self) -> None:
        self.started: list[str] = []
        self.records: list[dict[str, Any]] = []
        self.ended = 0

    def start_session(self, client: str = "") -> None:
        self.started.append(client)

    def record(self, **values: Any) -> None:
        self.records.append(values)

    def end_session(self) -> None:
        self.ended += 1


class FakeRepository:
    def __init__(self) -> None:
        self.results: dict[tuple[UUID, UUID], SensorResultRecord] = {}
        self.recording_values: dict[str, Any] | None = None
        self.prediction_values: dict[str, Any] | None = None
        self.audits: list[dict[str, Any]] = []
        self.window_results: dict[tuple[UUID, datetime, datetime, UUID], SensorResultRecord] = {}
        self.fail_recording = False
        self.fail_prediction = False
        self.recording_calls = 0
        self.prediction_calls = 0

    async def find_sensor_result(self, patient_id: UUID, client_window_id: UUID) -> SensorResultRecord | None:
        return self.results.get((patient_id, client_window_id))

    async def ensure_craving_model_version(self, **values: Any) -> UUID:
        assert values["model_name"] == "Conv1DNet"
        return UUID("00000000-0000-0000-0000-000000000001")

    async def find_sensor_prediction(self, **values: Any) -> SensorResultRecord | None:
        return self.window_results.get((
            values["patient_id"], values["started_at"], values["ended_at"],
            values["model_version_id"],
        ))

    async def persist_sensor_recording(self, **values: Any) -> SensorResultRecord:
        self.recording_calls += 1
        if self.fail_recording:
            raise RuntimeError("database unavailable")
        self.recording_values = values
        result = SensorResultRecord(
            recording_id=values["recording_id"], prediction_id=None, alert_id=None,
            client_window_id=values["client_window_id"], checksum_sha256=values["checksum"],
            prediction={},
        )
        self.results[(values["patient_id"], values["client_window_id"])] = result
        return result

    async def persist_sensor_prediction(self, **values: Any) -> SensorResultRecord:
        self.prediction_calls += 1
        if self.fail_prediction:
            raise RuntimeError("prediction database unavailable")
        self.prediction_values = values
        key = next(key for key, result in self.results.items() if result.recording_id == values["recording_id"])
        current = self.results[key]
        if current.prediction_id is not None:
            return current
        result = SensorResultRecord(
            recording_id=current.recording_id, prediction_id=values["prediction_id"],
            alert_id=values["alert_id"], client_window_id=current.client_window_id,
            checksum_sha256=current.checksum_sha256, prediction=values["prediction"],
        )
        self.results[key] = result
        self.window_results[(
            values["patient_id"], values["started_at"], values["ended_at"],
            values["model_version_id"],
        )] = result
        return result

    async def audit(self, **values: Any) -> None:
        self.audits.append(values)


def payload(window_id: UUID | None = None, *, value: float = 1.2) -> dict[str, Any]:
    return {
        "clientWindowId": str(window_id or uuid4()), "sessionStartedAtMs": 1_700_000_000_000,
        "sequence": 1, "sentAtMs": 1_700_000_020_100,
        "windowStartMs": 1_700_000_000_000, "windowEndMs": 1_700_000_020_000,
        "windowMs": 20_000,
        "samples": [{"sensor": "PPG", "timestampMs": 1_700_000_000_000, "value": value}],
        "sync": {
            "mode": "fixed_grid_ppg_25hz_eda_1hz_continuous",
            "fillMode": "linear_interpolation_nearest_edge_hold",
            "ppgHz": 25,
            "ppgSamplesPerChannel": 500,
            "edaHz": 1,
            "edaSamples": 20,
        },
    }


def make_service(
    directory: str, *, hub: PatientPredictionHub | None = None
) -> tuple[SensorService, FakeRepository, FakePredictor]:
    ring = AesGcmKeyring.from_config(
        "v1:" + base64.b64encode(b"q" * 32).decode(), "v1"
    )
    repo, predictor = FakeRepository(), FakePredictor()
    service = SensorService(
        repo, EncryptedSensorStorage(Path(directory), ring), predictor,
        lambda _patient, _event: {"alertRequired": True, "alertAction": "intervention", "triggerReason": "high"},
        hub or PatientPredictionHub(), FakeLatency(),
    )
    return service, repo, predictor


def test_ingestion_persists_encrypted_file_and_is_idempotent() -> None:
    async def scenario() -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            service, repo, predictor = make_service(directory)
            patient_id, consent_id, window_id = uuid4(), uuid4(), uuid4()
            body = payload(window_id)
            first = await service.ingest(patient_id=patient_id, consent_snapshot_id=consent_id,
                                         ai_analysis_allowed=True, notification_allowed=True, payload=body)
            second = await service.ingest(patient_id=patient_id, consent_snapshot_id=consent_id,
                                          ai_analysis_allowed=True, notification_allowed=True, payload=body)
            assert first == second
            assert predictor.calls == 1
            assert first["class"] == 1 and first["recordingId"] and first["predictionId"]
            assert first["alertId"] is None
            assert first["cravingProbability"] == 0.91
            assert first["source"] == "watch_sensor"
            assert "_lat" not in first and "_readyPerf" not in first
            assert "_lat" not in repo.prediction_values["prediction"]
            assert repo.prediction_values["prediction"]["source"] == "watch_sensor"
            assert repo.prediction_values["notification_allowed"] is True
            stored_path = Path(directory) / repo.recording_values["storage_uri"]
            assert stored_path.is_file()
            assert canonical_sensor_json(body) not in stored_path.read_bytes()
            restored = json.loads(service.storage.read(
                patient_id=patient_id, recording_id=UUID(first["recordingId"]),
                relative_path=repo.recording_values["storage_uri"],
            ))
            assert restored["sync"] == body["sync"]
            assert predictor.payloads[0]["sync"] == body["sync"]
            assert repo.recording_values["consent_snapshot_id"] == consent_id
            assert repo.prediction_values["model_version_id"] == UUID("00000000-0000-0000-0000-000000000001")

    asyncio.run(scenario())


def test_conflicting_id_is_audited_without_second_prediction() -> None:
    async def scenario() -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            service, repo, predictor = make_service(directory)
            patient_id, window_id = uuid4(), uuid4()
            await service.ingest(patient_id=patient_id, consent_snapshot_id=uuid4(),
                                 ai_analysis_allowed=True, notification_allowed=True, payload=payload(window_id))
            with pytest.raises(SensorPayloadConflict):
                await service.ingest(patient_id=patient_id, consent_snapshot_id=uuid4(),
                                     ai_analysis_allowed=True, notification_allowed=True,
                                     payload=payload(window_id, value=9.9))
            assert predictor.calls == 1
            assert repo.audits[-1]["action"] == "sensor.idempotency_conflict"

    asyncio.run(scenario())


def test_duplicate_time_window_with_new_client_id_reuses_prediction() -> None:
    async def scenario() -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            service, repo, predictor = make_service(directory)
            patient_id, consent_id = uuid4(), uuid4()
            first = await service.ingest(
                patient_id=patient_id,
                consent_snapshot_id=consent_id,
                ai_analysis_allowed=True,
                notification_allowed=True,
                payload=payload(uuid4()),
            )
            second = await service.ingest(
                patient_id=patient_id,
                consent_snapshot_id=consent_id,
                ai_analysis_allowed=True,
                notification_allowed=True,
                payload=payload(uuid4()),
            )

            assert second["predictionId"] == first["predictionId"]
            assert predictor.calls == 1
            assert repo.prediction_calls == 1
            assert repo.recording_calls == 2

    asyncio.run(scenario())


def test_concurrent_identical_retry_predicts_persists_and_publishes_once() -> None:
    async def scenario() -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            service, repo, predictor = make_service(directory)
            patient_id, consent_id, window_id = uuid4(), uuid4(), uuid4()
            body = payload(window_id)
            queue = await service.hub.subscribe(patient_id)
            alert_calls = 0

            def decide_alert(_patient_id: UUID, _prediction: dict[str, Any]) -> dict[str, Any]:
                nonlocal alert_calls
                alert_calls += 1
                return {
                    "alertRequired": True,
                    "alertAction": "intervention",
                    "triggerReason": "high",
                }

            service.alert_decider = decide_alert
            predictor.entered, predictor.release = asyncio.Event(), asyncio.Event()
            first = asyncio.create_task(service.ingest(
                patient_id=patient_id,
                consent_snapshot_id=consent_id,
                ai_analysis_allowed=True,
                notification_allowed=True,
                payload=body,
            ))
            await asyncio.wait_for(predictor.entered.wait(), timeout=0.1)
            second = asyncio.create_task(service.ingest(
                patient_id=patient_id,
                consent_snapshot_id=consent_id,
                ai_analysis_allowed=True,
                notification_allowed=True,
                payload=body,
            ))
            await asyncio.sleep(0)
            assert predictor.calls == 1
            predictor.release.set()
            first_result, second_result = await asyncio.gather(first, second)

            assert first_result == second_result
            assert predictor.calls == repo.recording_calls == repo.prediction_calls == 1
            assert alert_calls == 0
            published = await asyncio.wait_for(queue.get(), timeout=0.1)
            assert published["predictionId"] == first_result["predictionId"]
            with pytest.raises(asyncio.TimeoutError):
                await asyncio.wait_for(queue.get(), timeout=0.01)
            assert len(list(Path(directory).rglob("*.ntg"))) == 1
            assert not service._ingest_locks
            await service.hub.unsubscribe(patient_id, queue)

    asyncio.run(scenario())


def test_model_unavailable_retains_raw_and_retry_completes_without_duplicate_file() -> None:
    async def scenario() -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            service, repo, predictor = make_service(directory)
            patient_id, consent_id, window_id = uuid4(), uuid4(), uuid4()
            body = payload(window_id)
            predictor.ready = False
            with pytest.raises(SensorModelUnavailable):
                await service.ingest(patient_id=patient_id, consent_snapshot_id=consent_id,
                                     ai_analysis_allowed=True, notification_allowed=True, payload=body)
            assert len(list(Path(directory).rglob("*.ntg"))) == 1
            raw = repo.results[(patient_id, window_id)]
            assert raw.prediction_id is None

            predictor.ready = True
            completed = await service.ingest(patient_id=patient_id, consent_snapshot_id=consent_id,
                                             ai_analysis_allowed=True, notification_allowed=True, payload=body)
            assert completed["predictionId"] and predictor.calls == 1
            assert len(list(Path(directory).rglob("*.ntg"))) == 1

    asyncio.run(scenario())


def test_raw_recording_survives_prediction_database_failure() -> None:
    async def scenario() -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            service, repo, _ = make_service(directory)
            patient_id, window_id = uuid4(), uuid4()
            repo.fail_prediction = True
            with pytest.raises(RuntimeError):
                await service.ingest(patient_id=patient_id, consent_snapshot_id=uuid4(),
                                     ai_analysis_allowed=True, notification_allowed=True,
                                     payload=payload(window_id))
            assert len(list(Path(directory).rglob("*.ntg"))) == 1
            assert repo.results[(patient_id, window_id)].prediction_id is None
            repo.fail_prediction = False
            completed = await service.ingest(patient_id=patient_id, consent_snapshot_id=uuid4(),
                                             ai_analysis_allowed=True, notification_allowed=True,
                                             payload=payload(window_id))
            assert completed["predictionId"]
            assert len(list(Path(directory).rglob("*.ntg"))) == 1

    asyncio.run(scenario())


def test_raw_recording_database_failure_removes_unlinked_encrypted_file() -> None:
    async def scenario() -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            service, repo, _ = make_service(directory)
            repo.fail_recording = True
            with pytest.raises(RuntimeError):
                await service.ingest(patient_id=uuid4(), consent_snapshot_id=uuid4(),
                                     ai_analysis_allowed=False, notification_allowed=False, payload=payload())
            assert not list(Path(directory).rglob("*.ntg"))
            assert not repo.results

    asyncio.run(scenario())


def test_overflow_timestamp_is_rejected_before_storage_or_prediction(monkeypatch: Any) -> None:
    async def scenario() -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            service, repo, predictor = make_service(directory)
            store_calls = 0
            original_store = service.storage.store

            def tracked_store(**values: Any) -> Any:
                nonlocal store_calls
                store_calls += 1
                return original_store(**values)

            monkeypatch.setattr(service.storage, "store", tracked_store)
            huge_start = 10**400
            body = {
                **payload(),
                "windowStartMs": huge_start,
                "windowEndMs": huge_start + 20_000,
            }
            with pytest.raises(SensorPayloadInvalid, match="Invalid sensor timestamp"):
                await service.ingest(
                    patient_id=uuid4(),
                    consent_snapshot_id=uuid4(),
                    ai_analysis_allowed=True,
                    notification_allowed=True,
                    payload=body,
                )

            assert store_calls == repo.recording_calls == predictor.calls == 0
            assert not list(Path(directory).rglob("*.ntg"))

    asyncio.run(scenario())


def test_ai_consent_off_stores_raw_without_prediction_or_sse_then_later_completes() -> None:
    async def scenario() -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            service, repo, predictor = make_service(directory)
            patient_id, consent_id, window_id = uuid4(), uuid4(), uuid4()
            body = payload(window_id)
            queue = await service.hub.subscribe(patient_id)
            stored = await service.ingest(patient_id=patient_id, consent_snapshot_id=consent_id,
                                          ai_analysis_allowed=False, notification_allowed=True, payload=body)
            assert stored["recordingId"] and stored["predictionId"] is None and stored["alertId"] is None
            assert predictor.calls == 0 and repo.results[(patient_id, window_id)].prediction_id is None
            with pytest.raises(asyncio.TimeoutError):
                await asyncio.wait_for(queue.get(), timeout=0.01)

            completed = await service.ingest(patient_id=patient_id, consent_snapshot_id=consent_id,
                                             ai_analysis_allowed=True, notification_allowed=True, payload=body)
            assert completed["predictionId"] and predictor.calls == 1
            assert len(list(Path(directory).rglob("*.ntg"))) == 1
            await service.hub.unsubscribe(patient_id, queue)

    asyncio.run(scenario())


def test_notification_consent_off_persists_and_publishes_prediction_without_alert() -> None:
    async def scenario() -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            service, repo, predictor = make_service(directory)
            patient_id = uuid4()
            queue = await service.hub.subscribe(patient_id)
            result = await service.ingest(patient_id=patient_id, consent_snapshot_id=uuid4(),
                                          ai_analysis_allowed=True, notification_allowed=False,
                                          payload=payload())
            assert result["predictionId"] and result["alertId"] is None
            assert result["alertRequired"] is False and result["alertAction"] == "none"
            assert repo.prediction_values["alert_id"] is None and predictor.calls == 1
            published = await asyncio.wait_for(queue.get(), timeout=0.1)
            assert published["predictionId"] == result["predictionId"]
            assert "_lat" in published and "_lat" not in result
            await service.hub.unsubscribe(patient_id, queue)

    asyncio.run(scenario())


def test_repository_alert_decision_is_durable_atomic_and_watch_only() -> None:
    source = getsource(SqlAlchemyV25Repository.persist_sensor_prediction)
    assert "pg_advisory_xact_lock" in source
    assert "craving-alert:" in source
    assert "p.output_metadata->>'source'='watch_sensor'" in source
    assert "ORDER BY p.predicted_at DESC,p.id DESC" in source
    assert "'watch-danger-v1'" in source
    assert "notification_allowed" in source


def test_prediction_hub_never_crosses_patient_queues() -> None:
    async def scenario() -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as directory:
            hub = PatientPredictionHub()
            service, _, _ = make_service(directory, hub=hub)
            assert service.hub is hub
            patient_a, patient_b = uuid4(), uuid4()
            queue_a = await service.hub.subscribe(patient_a)
            queue_b = await service.hub.subscribe(patient_b)
            await service.hub.publish(patient_a, {"class": 1})
            assert await asyncio.wait_for(queue_a.get(), timeout=0.1) == {"class": 1}
            with pytest.raises(asyncio.TimeoutError):
                await asyncio.wait_for(queue_b.get(), timeout=0.01)
            await service.hub.unsubscribe(patient_a, queue_a)
            await service.hub.unsubscribe(patient_b, queue_b)

    asyncio.run(scenario())


def test_authenticated_sse_records_latency_without_exposing_timing_keys() -> None:
    async def scenario() -> None:
        patient_id = uuid4()
        hub, latency = PatientPredictionHub(), FakeLatency()
        service = type("StreamingService", (), {"hub": hub, "latency": latency})()

        class ConnectedRequest:
            checks = 0

            async def is_disconnected(self) -> bool:
                self.checks += 1
                return self.checks > 1

        response = await prediction_stream(
            ConnectedRequest(), type("Patient", (), {"id": patient_id})(), service
        )
        iterator = response.body_iterator
        assert "connected" in await anext(iterator)
        await hub.publish(patient_id, {
            "sequence": 9, "class": 1,
            "_lat": {"comm_ms": 1.0, "queue_ms": 2.0, "feature_ms": 3.0,
                     "model_ms": 4.0, "server_ms": 5.0},
            "_readyPerf": time.perf_counter(),
        })
        event = await anext(iterator)
        public = json.loads(next(line[6:] for line in event.splitlines() if line.startswith("data: ")))
        assert public == {"sequence": 9, "class": 1}
        with pytest.raises(StopAsyncIteration):
            await anext(iterator)
        assert latency.started == [str(patient_id)] and latency.ended == 1
        assert latency.records[0]["model_ms"] == 4.0

    asyncio.run(scenario())


def test_main_has_only_authenticated_sensor_contract() -> None:
    from app import main

    paths = {getattr(route, "path", None) for route in main.app.routes}
    assert "/api/sensor-windows" in paths
    assert "/api/predictions/stream" in paths
    assert "/sensor-window" not in paths
    assert "/prediction-stream" not in paths


def test_sensor_route_accepts_exact_mobile_payload_and_rejects_legacy_session_id() -> None:
    class RouteSensorService:
        def __init__(self) -> None:
            self.calls: list[dict[str, Any]] = []

        async def ingest(self, **values: Any) -> dict[str, Any]:
            SensorService._timestamp(values["payload"]["windowStartMs"])
            self.calls.append(values)
            return {"recordingId": str(uuid4()), "class": 0}

    class RouteAuthService:
        biosignal = True
        async def require_consent(self, patient_id: UUID, feature: str) -> None:
            assert feature == "biosignal"
            if not self.biosignal:
                raise AuthorizationError("Consent required for biosignal")

    class RouteRepository:
        async def current_consent(self, patient_id: UUID) -> Any:
            return type("Consent", (), {"id": uuid4(), "ai_analysis": True, "notification": False})()

    user = type("Patient", (), {"id": uuid4()})()
    runtime = type("Runtime", (), {"service": RouteAuthService(), "repository": RouteRepository()})()
    sensor_service = RouteSensorService()
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_runtime] = lambda: runtime
    app.dependency_overrides[patient_user] = lambda: user
    app.dependency_overrides[get_sensor_service] = lambda: sensor_service
    client = TestClient(app)

    exact = payload()
    assert SensorWindow.model_validate(exact).model_dump(mode="json") == exact
    without_duration = {key: value for key, value in exact.items() if key != "windowMs"}
    assert SensorWindow.model_validate(without_duration).windowMs == 20_000
    accepted = client.post("/api/sensor-windows", json=exact)
    assert accepted.status_code == 200
    assert sensor_service.calls[0]["payload"] == exact
    assert sensor_service.calls[0]["ai_analysis_allowed"] is True
    assert sensor_service.calls[0]["notification_allowed"] is False

    legacy = {**exact, "sessionId": "legacy-untrusted-session"}
    rejected = client.post("/api/sensor-windows", json=legacy)
    assert rejected.status_code == 422
    assert rejected.json()["detail"][0]["loc"] == ["body", "sessionId"]
    assert len(sensor_service.calls) == 1

    unknown_sync = {**exact, "sync": {**exact["sync"], "unknownMode": "legacy"}}
    rejected_sync = client.post("/api/sensor-windows", json=unknown_sync)
    assert rejected_sync.status_code == 422
    assert rejected_sync.json()["detail"][0]["loc"] == ["body", "sync", "unknownMode"]
    assert len(sensor_service.calls) == 1

    invalid_windows = [
        {**exact, "windowEndMs": exact["windowStartMs"] + 10_000, "windowMs": 10_000},
        {**exact, "windowEndMs": exact["windowStartMs"] + 1, "windowMs": 1},
        {
            **exact,
            "windowStartMs": exact["windowEndMs"],
            "windowEndMs": exact["windowStartMs"],
        },
        {**exact, "windowMs": 19_500},
    ]
    for invalid in invalid_windows:
        rejected_timing = client.post("/api/sensor-windows", json=invalid)
        assert rejected_timing.status_code == 422
    assert len(sensor_service.calls) == 1

    huge_start = 10**400
    overflow = {
        **exact,
        "clientWindowId": str(uuid4()),
        "windowStartMs": huge_start,
        "windowEndMs": huge_start + 20_000,
    }
    rejected_overflow = client.post("/api/sensor-windows", json=overflow)
    assert rejected_overflow.status_code == 422
    assert rejected_overflow.json() == {"detail": "Invalid sensor timestamp"}
    assert len(sensor_service.calls) == 1

    for non_finite in (float("nan"), float("inf"), float("-inf")):
        invalid_value = {
            **exact,
            "clientWindowId": str(uuid4()),
            "samples": [{**exact["samples"][0], "value": non_finite}],
        }
        rejected_value = client.post(
            "/api/sensor-windows",
            content=json.dumps(invalid_value),
            headers={"Content-Type": "application/json"},
        )
        assert rejected_value.status_code == 422
    assert len(sensor_service.calls) == 1

    runtime.service.biosignal = False
    denied = client.post("/api/sensor-windows", json={**exact, "clientWindowId": str(uuid4())})
    assert denied.status_code == 403
    assert len(sensor_service.calls) == 1
