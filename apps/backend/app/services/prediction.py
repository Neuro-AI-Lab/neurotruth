from __future__ import annotations

"""Process-wide craving prediction orchestration."""

import asyncio
import logging
import os
import time
from collections import defaultdict
from pathlib import Path
from typing import Any
from uuid import UUID

from app.ml.craving.latency import LatencyRecorder
from app.ml.craving.model import (
    CravingModel,
    EXPECTED_WEIGHTS_SHA256,
    InvalidSensorWindow,
    ModelUnavailableError,
    _default_model_path,
    _safe_float,
    _startup_error_code,
)
from app.ml.craving.pipeline import AlertEvaluatorRegistry


LOGGER = logging.getLogger(__name__)


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


class RealtimePredictionService:
    def __init__(self) -> None:
        backend_dir = Path(__file__).resolve().parents[2]
        default_model_path = _default_model_path(backend_dir)
        default_metadata_path = default_model_path.with_name("model_metadata.json")
        self.model = CravingModel(
            Path(os.getenv("CRAVING_MODEL_PATH", str(default_model_path))),
            Path(os.getenv("CRAVING_MODEL_METADATA_PATH", str(default_metadata_path))),
            expected_sha256=os.getenv("CRAVING_MODEL_SHA256", EXPECTED_WEIGHTS_SHA256),
            requested_device=os.getenv("CRAVING_INFERENCE_DEVICE", "auto"),
        )
        self.hub = PatientPredictionHub()
        self.latency = LatencyRecorder()
        self.alerts = AlertEvaluatorRegistry()
        self._queue_max = int(os.getenv("INFERENCE_QUEUE_MAX", "100"))
        self.queue: asyncio.Queue[
            tuple[dict[str, Any], asyncio.Future[dict[str, Any]]]
        ] = asyncio.Queue(maxsize=self._queue_max)
        self.worker_task: asyncio.Task[None] | None = None
        self._model_task: asyncio.Task[dict[str, Any]] | None = None
        self._stop_task: asyncio.Task[None] | None = None
        self.startup_error: str | None = None
        self._running = False
        self._stopping = False

    async def start(self) -> None:
        if self._stopping:
            raise RuntimeError("Prediction service is stopping")
        if self._model_task is not None and not self._model_task.done():
            raise RuntimeError("Prediction model call is still running")
        if self.worker_task is not None and not self.worker_task.done():
            return
        self._fail_queued("prediction_service_restarted")
        self.queue = asyncio.Queue(maxsize=self._queue_max)
        self.startup_error = None
        try:
            self.model.load()
        except Exception as exc:
            self.startup_error = _startup_error_code(exc)
            LOGGER.exception("Craving model failed to load")
        self.worker_task = asyncio.create_task(self._worker(), name="craving-inference-worker")
        self._running = True

    async def stop(self) -> None:
        task = self._stop_task
        if task is None or task.done():
            task = asyncio.create_task(self._stop_impl(), name="craving-inference-stop")
            self._stop_task = task
        try:
            await asyncio.shield(task)
        finally:
            if task.done() and self._stop_task is task:
                self._stop_task = None

    async def _stop_impl(self) -> None:
        task = self.worker_task
        self._stopping = True
        self._running = False
        if task is not None:
            task.cancel()
        self._fail_queued("prediction_service_stopping")
        try:
            if task is not None:
                await task
        except asyncio.CancelledError:
            pass
        finally:
            self._fail_queued("prediction_service_stopping")
            if self.worker_task is task:
                self.worker_task = None
            self._stopping = False

    def _fail_queued(self, code: str) -> None:
        while True:
            try:
                _, future = self.queue.get_nowait()
            except asyncio.QueueEmpty:
                return
            if not future.done():
                future.set_exception(ModelUnavailableError(code))
            self.queue.task_done()

    async def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        worker = self.worker_task
        if self._stopping or not self._running or worker is None or worker.done():
            raise ModelUnavailableError("prediction_service_not_running")
        if not self.model.ready:
            raise ModelUnavailableError(self.startup_error or "model_unavailable")
        queued = dict(payload)
        queued["_enqueuePerf"] = time.perf_counter()
        queued["_recvWallMs"] = time.time() * 1000.0
        future = asyncio.get_running_loop().create_future()
        try:
            self.queue.put_nowait((queued, future))
        except asyncio.QueueFull as exc:
            raise ModelUnavailableError("inference_queue_full") from exc
        return await future

    def status(self) -> dict[str, Any]:
        return {
            "ready": self.model.ready,
            "modelName": self.model.model_name,
            "modelVersion": self.model.model_version,
            "expectedChecksum": self.model.expected_sha256,
            "actualChecksum": self.model.actual_sha256,
            "requestedDevice": self.model.requested_device,
            "actualDevice": self.model.actual_device,
            "fallback": self.model.fallback,
            "fallbackReason": self.model.fallback_reason,
            "classLabels": self.model.class_labels,
            "samplingHz": self.model.fs_raw,
            "windowSeconds": self.model.win_sec,
            "windowLength": self.model.raw_win_len,
            "queueSize": self.queue.qsize(),
            "errorCode": self.startup_error,
        }

    async def _worker(self) -> None:
        try:
            while True:
                payload, future = await self.queue.get()
                try:
                    dequeue = time.perf_counter()
                    enqueue = payload.get("_enqueuePerf", dequeue)
                    model_task = asyncio.create_task(asyncio.to_thread(self.model.predict, payload))
                    self._model_task = model_task
                    try:
                        event = await asyncio.shield(model_task)
                    except asyncio.CancelledError:
                        if not future.done():
                            future.set_exception(ModelUnavailableError("prediction_service_stopping"))
                        try:
                            await asyncio.shield(model_task)
                        except Exception:
                            pass
                        raise
                    finally:
                        if self._model_task is model_task and model_task.done():
                            self._model_task = None
                    debug = self.model.last_debug or {}
                    sent_ms, recv_ms = _safe_float(payload.get("sentAtMs")), payload.get("_recvWallMs")
                    event["_lat"] = {
                        "comm_ms": (recv_ms - sent_ms) if sent_ms is not None and recv_ms is not None else None,
                        "queue_ms": (dequeue - enqueue) * 1000.0,
                        "feature_ms": debug.get("preprocessMs"),
                        "model_ms": debug.get("modelMs"),
                        "server_ms": (time.perf_counter() - enqueue) * 1000.0,
                    }
                    event["_readyPerf"] = time.perf_counter()
                    if not future.done():
                        future.set_result(event)
                except asyncio.CancelledError:
                    if not future.done():
                        future.set_exception(ModelUnavailableError("prediction_service_stopping"))
                    raise
                except InvalidSensorWindow as exc:
                    LOGGER.warning("Rejected invalid sensor window")
                    if not future.done():
                        future.set_exception(exc)
                except Exception as exc:
                    LOGGER.exception("Prediction failed")
                    if not future.done():
                        future.set_exception(exc)
                finally:
                    self.queue.task_done()
        finally:
            self._running = False


class RuntimePredictorAdapter:
    """Expose the asynchronously loaded craving model to authenticated services."""

    def __init__(self, service: RealtimePredictionService) -> None:
        self.service = service

    @property
    def ready(self) -> bool:
        return bool(self.service.model.ready)

    @property
    def model_name(self) -> str:
        return self.service.model.model_name

    @property
    def model_version(self) -> str:
        return os.getenv("CRAVING_MODEL_VERSION", self.service.model.model_version)

    @property
    def artifact_uri(self) -> str:
        return self.service.model.safe_artifact_uri

    @property
    def inference_task(self) -> str:
        return "binary_classification"

    @property
    def output_schema(self) -> dict[str, Any]:
        return {
            "predictionSchema": "binary-craving-v1",
            "classes": [{"index": 0, "code": "low"}, {"index": 1, "code": "high"}],
        }

    @property
    def registration_config(self) -> dict[str, Any]:
        return self.service.model.registration_config

    async def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self.service.predict(payload)


def public_sse_event(event: dict[str, Any]) -> dict[str, Any]:
    """Strip worker-only keys while preserving legacy/public prediction fields."""

    return {key: value for key, value in event.items() if not key.startswith("_")}


prediction_service = RealtimePredictionService()
