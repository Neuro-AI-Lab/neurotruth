from __future__ import annotations

import asyncio
import hashlib
from datetime import datetime, timezone
from typing import Any, Callable, Protocol
from uuid import UUID, uuid4
from weakref import WeakValueDictionary

from app.models.records import SensorResultRecord
from app.repositories.postgres import RepositoryConflictError, V25Repository
from app.services.demo_prediction_playback import DemoPredictionPlayback
from app.storage.sensor import EncryptedSensorStorage, canonical_sensor_json


class SensorModelUnavailable(ValueError):
    pass


class SensorPayloadConflict(ValueError):
    pass


class SensorPayloadInvalid(ValueError):
    pass


class Predictor(Protocol):
    ready: bool
    model_name: str
    model_version: str
    artifact_uri: str | None
    inference_task: str
    output_schema: dict[str, Any]
    registration_config: dict[str, Any]

    async def predict(self, payload: dict[str, Any]) -> dict[str, Any]: ...


class SensorService:
    def __init__(
        self,
        repository: V25Repository,
        storage: EncryptedSensorStorage,
        predictor: Predictor,
        alert_decider: Callable[[UUID, dict[str, Any]], dict[str, Any]],
        hub: Any,
        latency: Any,
        demo_playback: DemoPredictionPlayback | None = None,
    ) -> None:
        self.repository = repository
        self.storage = storage
        self.predictor = predictor
        self.alert_decider = alert_decider
        self.hub = hub
        self.latency = latency
        self.demo_playback = demo_playback
        self._ingest_locks: WeakValueDictionary[
            tuple[UUID, datetime, datetime], asyncio.Lock
        ] = WeakValueDictionary()

    async def ingest(
        self,
        *,
        patient_id: UUID,
        consent_snapshot_id: UUID,
        ai_analysis_allowed: bool,
        notification_allowed: bool,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        started_at = self._timestamp(payload.get("windowStartMs"))
        ended_at = self._timestamp(payload.get("windowEndMs"))
        client_window_id = UUID(str(payload["clientWindowId"]))
        canonical = canonical_sensor_json(payload)
        checksum = hashlib.sha256(canonical).hexdigest()
        lock_key = (patient_id, started_at, ended_at)
        lock = self._ingest_locks.get(lock_key)
        if lock is None:
            lock = asyncio.Lock()
            self._ingest_locks[lock_key] = lock
        async with lock:
            return await self._ingest_locked(
                patient_id=patient_id,
                consent_snapshot_id=consent_snapshot_id,
                ai_analysis_allowed=ai_analysis_allowed,
                notification_allowed=notification_allowed,
                payload=payload,
                client_window_id=client_window_id,
                started_at=started_at,
                ended_at=ended_at,
                canonical=canonical,
                checksum=checksum,
            )

    async def _ingest_locked(
        self,
        *,
        patient_id: UUID,
        consent_snapshot_id: UUID,
        ai_analysis_allowed: bool,
        notification_allowed: bool,
        payload: dict[str, Any],
        client_window_id: UUID,
        started_at: datetime,
        ended_at: datetime,
        canonical: bytes,
        checksum: str,
    ) -> dict[str, Any]:
        recording = await self.repository.find_sensor_result(patient_id, client_window_id)
        if recording is not None and recording.checksum_sha256 != checksum:
            await self._audit_conflict(patient_id, recording.recording_id)
            raise SensorPayloadConflict("clientWindowId is already used")

        if recording is None:
            recording_id = uuid4()
            stored = self.storage.store(
                patient_id=patient_id, recording_id=recording_id, canonical=canonical
            )
            try:
                recording = await self.repository.persist_sensor_recording(
                    recording_id=recording_id,
                    patient_id=patient_id,
                    client_window_id=client_window_id,
                    storage_uri=stored.relative_path,
                    modalities=self._modalities(payload),
                    device_info=payload.get("deviceInfo") or {},
                    sample_rates=payload.get("sampleRates") or {},
                    started_at=started_at,
                    ended_at=ended_at,
                    bytes=stored.byte_size,
                    checksum=stored.checksum_sha256,
                    key_version=stored.key_id,
                    nonce=stored.nonce,
                    consent_snapshot_id=consent_snapshot_id,
                )
            except RepositoryConflictError:
                self.storage.delete(stored.relative_path)
                recording = await self.repository.find_sensor_result(patient_id, client_window_id)
                if recording is None or recording.checksum_sha256 != checksum:
                    await self._audit_conflict(patient_id, recording.recording_id if recording else None)
                    raise SensorPayloadConflict("clientWindowId is already used")
            except Exception:
                self.storage.delete(stored.relative_path)
                raise

        if not ai_analysis_allowed:
            return self._stored_response(recording)
        if recording.prediction_id is not None:
            return self._response(recording, notification_allowed=notification_allowed)
        if not self.predictor.ready:
            raise SensorModelUnavailable("Prediction model is unavailable")

        model_version_id = await self.repository.ensure_craving_model_version(
            model_name=self.predictor.model_name,
            model_version=self.predictor.model_version,
            artifact_uri=self.predictor.artifact_uri,
            inference_task=getattr(self.predictor, "inference_task", "binary_classification"),
            output_schema=getattr(self.predictor, "output_schema", {
                "predictionSchema": "binary-craving-v1",
                "classes": [{"index": 0, "code": "low"}, {"index": 1, "code": "high"}],
            }),
            config=getattr(self.predictor, "registration_config", {"window_sec": 10}),
        )
        existing_prediction = await self.repository.find_sensor_prediction(
            patient_id=patient_id,
            started_at=started_at,
            ended_at=ended_at,
            model_version_id=model_version_id,
        )
        if existing_prediction is not None:
            return self._response(
                existing_prediction,
                notification_allowed=notification_allowed,
            )

        try:
            prediction = await self.predictor.predict(payload)
        except Exception as exc:
            raise SensorModelUnavailable("Prediction model is unavailable") from exc
        if self.demo_playback is not None:
            prediction = self.demo_playback.apply(
                patient_id=patient_id,
                payload=payload,
                prediction=prediction,
            )
        if int(prediction.get("class", -1)) not in (0, 1):
            raise SensorModelUnavailable("Prediction model returned an incompatible class")
        public_prediction = {
            key: value for key, value in {
                **prediction,
                "alertRequired": False,
                "alertAction": "none",
                "source": "watch_sensor",
            }.items()
            if not str(key).startswith("_")
        }
        result = await self.repository.persist_sensor_prediction(
            recording_id=recording.recording_id,
            prediction_id=uuid4(),
            alert_id=None,
            patient_id=patient_id,
            model_version_id=model_version_id,
            modalities=self._modalities(payload),
            started_at=started_at,
            ended_at=ended_at,
            class_index=int(prediction["class"]),
            class_code=str(prediction.get("classCode") or ("high" if int(prediction["class"]) == 1 else "low")),
            confidence=prediction.get("confidence"),
            class_probabilities=prediction.get("classProbabilities") or {},
            continuous_value=prediction.get("cravingProbability"),
            prediction=public_prediction,
            alert={"alertRequired": False, "alertAction": "none"},
            notification_allowed=notification_allowed,
            predicted_at=self._timestamp(prediction.get("timestampMs")),
        )
        response = self._response(result, notification_allowed=notification_allowed)
        sse_event = dict(response)
        sse_event.update({
            key: prediction[key] for key in ("_lat", "_readyPerf") if key in prediction
        })
        await self.hub.publish(patient_id, sse_event)
        return response

    @staticmethod
    def _stored_response(result: SensorResultRecord) -> dict[str, Any]:
        return {"recordingId": str(result.recording_id), "predictionId": None, "alertId": None}

    @staticmethod
    def _response(result: SensorResultRecord, *, notification_allowed: bool = True) -> dict[str, Any]:
        prediction = dict(result.prediction)
        prediction.setdefault("source", "watch_sensor")
        if not notification_allowed:
            prediction.update({"alertRequired": False, "alertAction": "none"})
            prediction.pop("triggerReason", None)
        return {
            **prediction,
            "recordingId": str(result.recording_id),
            "predictionId": str(result.prediction_id) if result.prediction_id else None,
            "alertId": str(result.alert_id) if result.alert_id and notification_allowed else None,
        }

    async def _audit_conflict(self, patient_id: UUID, recording_id: UUID | None) -> None:
        await self.repository.audit(
            actor_id=patient_id, actor_role="patient", action="sensor.idempotency_conflict",
            resource_type="sensor_recording", resource_id=recording_id,
        )

    @staticmethod
    def _timestamp(value: Any) -> datetime:
        try:
            return datetime.fromtimestamp(float(value) / 1000.0, tz=timezone.utc)
        except (TypeError, ValueError, OSError, OverflowError) as exc:
            raise SensorPayloadInvalid("Invalid sensor timestamp") from exc

    @staticmethod
    def _modalities(payload: dict[str, Any]) -> list[str]:
        mapping = {"PPG": "ppg", "EDA": "eda", "GSR": "eda", "ACC": "acc", "HR": "hr", "IBI": "ibi"}
        values = {
            mapping[name]
            for sample in payload.get("samples") or []
            if (name := str(sample.get("sensor", "")).upper()) in mapping
        }
        return sorted(values) or ["ppg"]
