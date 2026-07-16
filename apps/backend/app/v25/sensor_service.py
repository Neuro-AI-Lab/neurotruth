from __future__ import annotations

import asyncio
import hashlib
from collections import defaultdict
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable, Protocol
from uuid import UUID, uuid4

from .models import SensorResultRecord
from .repository import RepositoryConflictError, V25Repository
from .sensor_storage import EncryptedSensorStorage, canonical_sensor_json


class SensorModelUnavailable(ValueError):
    pass


class SensorPayloadConflict(ValueError):
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


class PatientPredictionHub:
    def __init__(self) -> None:
        self._queues: dict[UUID, set[asyncio.Queue[dict[str, Any]]]] = defaultdict(set)
        self._lock = asyncio.Lock()

    async def subscribe(self, patient_id: UUID) -> asyncio.Queue[dict[str, Any]]:
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=16)
        async with self._lock:
            self._queues[patient_id].add(queue)
        return queue

    async def unsubscribe(self, patient_id: UUID, queue: asyncio.Queue[dict[str, Any]]) -> None:
        async with self._lock:
            queues = self._queues.get(patient_id)
            if queues is not None:
                queues.discard(queue)
                if not queues:
                    self._queues.pop(patient_id, None)

    async def publish(self, patient_id: UUID, event: dict[str, Any]) -> None:
        async with self._lock:
            queues = list(self._queues.get(patient_id, ()))
        for queue in queues:
            if queue.full():
                try:
                    queue.get_nowait()
                except asyncio.QueueEmpty:
                    pass
            queue.put_nowait(dict(event))


class SensorService:
    def __init__(
        self,
        repository: V25Repository,
        storage: EncryptedSensorStorage,
        predictor: Predictor,
        alert_decider: Callable[[UUID, dict[str, Any]], dict[str, Any]],
    ) -> None:
        self.repository = repository
        self.storage = storage
        self.predictor = predictor
        self.alert_decider = alert_decider
        self.hub = PatientPredictionHub()

    async def ingest(
        self,
        *,
        patient_id: UUID,
        consent_snapshot_id: UUID,
        ai_analysis_allowed: bool,
        notification_allowed: bool,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        client_window_id = UUID(str(payload["clientWindowId"]))
        canonical = canonical_sensor_json(payload)
        checksum = hashlib.sha256(canonical).hexdigest()
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
                    started_at=self._timestamp(payload.get("windowStartMs")),
                    ended_at=self._timestamp(payload.get("windowEndMs")),
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

        try:
            prediction = await self.predictor.predict(payload)
        except Exception as exc:
            raise SensorModelUnavailable("Prediction model is unavailable") from exc
        if int(prediction.get("class", -1)) not in (0, 1):
            raise SensorModelUnavailable("Prediction model returned an incompatible class")
        alert = self.alert_decider(patient_id, prediction) if notification_allowed else {
            "alertRequired": False, "alertAction": "none",
        }
        public_prediction = {
            key: value for key, value in {**prediction, **alert}.items()
            if not str(key).startswith("_")
        }
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
        alert_id = uuid4() if notification_allowed and (
            bool(alert.get("alertRequired")) or alert.get("alertAction") not in (None, "none")
        ) else None
        result = await self.repository.persist_sensor_prediction(
            recording_id=recording.recording_id,
            prediction_id=uuid4(),
            alert_id=alert_id,
            patient_id=patient_id,
            model_version_id=model_version_id,
            modalities=self._modalities(payload),
            started_at=self._timestamp(payload.get("windowStartMs")),
            ended_at=self._timestamp(payload.get("windowEndMs")),
            class_index=int(prediction["class"]),
            class_code=str(prediction.get("classCode") or ("high" if int(prediction["class"]) == 1 else "low")),
            confidence=prediction.get("confidence"),
            class_probabilities=prediction.get("classProbabilities") or {},
            continuous_value=prediction.get("cravingProbability"),
            prediction=public_prediction,
            alert=alert,
            predicted_at=self._timestamp(prediction.get("timestampMs")),
        )
        response = self._response(result, notification_allowed=notification_allowed)
        await self.hub.publish(patient_id, response)
        return response

    @staticmethod
    def _stored_response(result: SensorResultRecord) -> dict[str, Any]:
        return {"recordingId": str(result.recording_id), "predictionId": None, "alertId": None}

    @staticmethod
    def _response(result: SensorResultRecord, *, notification_allowed: bool = True) -> dict[str, Any]:
        prediction = dict(result.prediction)
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
        except (TypeError, ValueError, OSError) as exc:
            raise ValueError("Invalid sensor timestamp") from exc

    @staticmethod
    def _modalities(payload: dict[str, Any]) -> list[str]:
        mapping = {"PPG": "ppg", "EDA": "eda", "GSR": "eda", "ACC": "acc", "HR": "hr", "IBI": "ibi"}
        values = {
            mapping[name]
            for sample in payload.get("samples") or []
            if (name := str(sample.get("sensor", "")).upper()) in mapping
        }
        return sorted(values) or ["ppg"]
