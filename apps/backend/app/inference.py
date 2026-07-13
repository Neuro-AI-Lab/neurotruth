from __future__ import annotations

"""Realtime craving inference service (feature-based RandomForest).

The Android app posts 10-second sensor windows. This service upsamples the sparse
app samples (PPG ~25Hz, EDA ~1Hz) onto the 51.2Hz training grid, extracts the same
NeuroKit PPG features (HR/HRV/SI/RR) + EDA tonic statistics used in training via
`apps/backend/model/features.py`, and runs the saved RandomForest bundle
(keep + imputer + scaler + clf) from `apps/backend/model/weights`. No torch/GPU is used.
"""

import asyncio
import importlib
import logging
import os
import sys
import time
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

try:  # package run (uvicorn app.main) vs. direct import in tests
    from app.alerts import AlertEvaluatorRegistry
    from app.inference_time import LatencyRecorder
    from app.memory import PostgresMemory
except ImportError:  # pragma: no cover
    from alerts import AlertEvaluatorRegistry
    from inference_time import LatencyRecorder
    from memory import PostgresMemory

LOGGER = logging.getLogger(__name__)

# Galaxy Watch sampling rates. The app may include other sensor channels, but
# the current model uses only PPG and EDA/GSR.
WATCH_PPG_FS = 25.0
WATCH_EDA_FS = 1.0
PPG_SENSORS = ("PPG_GREEN", "PPG_IR", "PPG_RED")
GSR_SENSORS = ("EDA",)


class ModelUnavailableError(RuntimeError):
    """Raised when requests arrive before the model is ready."""

    pass


class PredictionHub:
    """Small in-process pub/sub hub for SSE clients."""

    def __init__(self) -> None:
        self._subscribers: set[asyncio.Queue[dict[str, Any]]] = set()
        self._lock = asyncio.Lock()

    async def subscribe(self) -> asyncio.Queue[dict[str, Any]]:
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=16)
        async with self._lock:
            self._subscribers.add(queue)
        return queue

    async def unsubscribe(self, queue: asyncio.Queue[dict[str, Any]]) -> None:
        async with self._lock:
            self._subscribers.discard(queue)

    async def broadcast(self, event: dict[str, Any]) -> None:
        async with self._lock:
            subscribers = list(self._subscribers)
        for queue in subscribers:
            if queue.full():
                # Keep the stream realtime: slow clients lose old predictions
                # instead of blocking the inference worker.
                try:
                    queue.get_nowait()
                except asyncio.QueueEmpty:
                    pass
            try:
                queue.put_nowait(event)
            except asyncio.QueueFull:
                LOGGER.warning("Dropped prediction for a slow SSE subscriber")


class CravingModel:
    """Loads the RandomForest joblib bundle and performs one-window predictions."""

    def __init__(self, model_path: Path) -> None:
        self.model_path = model_path
        self.features: Any | None = None
        self.config: Any | None = None

        # Bundle contents (see feature_classification/classify.py fit_predict).
        self.keep: list[str] | None = None
        self.imputer: Any | None = None
        self.scaler: Any | None = None
        self.clf: Any | None = None
        self.feature_names: list[str] | None = None
        self.class_labels: list[str] = []
        self.model_name: str | None = None
        self.n_classes = 3
        self.last_debug: dict[str, Any] | None = None

        # Raw grid the sparse app samples are interpolated onto before feature
        # extraction. Mirrors model/config.py (training used 51.2Hz x 512).
        self.fs_raw = 51.2
        self.fs_gsr = 51.2
        self.win_sec = 10.0
        self.raw_win_len = 512

    @property
    def ready(self) -> bool:
        return self.clf is not None

    def load(self) -> None:
        """Load feature/config code and the RandomForest bundle."""

        if not self.model_path.exists():
            raise FileNotFoundError(f"Model bundle not found: {self.model_path}")

        import joblib

        model_dir = self._find_model_code_dir()
        if str(model_dir) not in sys.path:
            sys.path.insert(0, str(model_dir))
        # features.py uses bare imports (`import config`). Drop any earlier copy so
        # reloads or model swaps use this path.
        for module_name in ("config", "features"):
            sys.modules.pop(module_name, None)
        self.config = importlib.import_module("config")
        self.features = importlib.import_module("features")
        self._load_runtime_config()

        bundle = joblib.load(self.model_path)
        self.keep = list(bundle["keep"])
        self.imputer = bundle["imputer"]
        self.scaler = bundle["scaler"]
        self.clf = bundle["clf"]
        self.feature_names = list(bundle.get("features", self.keep))
        self.class_labels = list(bundle.get("class_labels", []))
        self.model_name = bundle.get("model_name")
        self.n_classes = int(getattr(self.clf, "n_classes_", len(self.class_labels) or 3))
        LOGGER.info(
            "Loaded %s craving model from %s (%d features, classes=%s)",
            self.model_name,
            self.model_path,
            len(self.keep),
            self.class_labels,
        )

    def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Run feature extraction, preprocessing, RF inference, and SSE shaping."""

        if not self.ready:
            raise ModelUnavailableError("Model is not loaded")
        assert self.imputer is not None and self.scaler is not None and self.clf is not None

        feature_t0 = time.perf_counter()
        feat_row, debug = self._payload_to_features(payload)
        feature_ms = (time.perf_counter() - feature_t0) * 1000.0

        # Match classify._apply_preprocess: select keep columns (missing -> NaN),
        # then imputer(median) + scaler, then predict.
        model_t0 = time.perf_counter()
        frame = pd.DataFrame([feat_row]).reindex(columns=self.keep)
        imputed = self.imputer.transform(frame)
        scaled = self.scaler.transform(imputed)
        probabilities = self.clf.predict_proba(scaled)[0]
        model_ms = (time.perf_counter() - model_t0) * 1000.0

        craving_class = int(np.argmax(probabilities))
        timestamp_ms = int(time.time() * 1000)
        event: dict[str, Any] = {
            "class": craving_class,
            "timestampMs": timestamp_ms,
            "confidence": round(float(probabilities[craving_class]), 6),
        }
        sequence = payload.get("sequence")
        if sequence is not None:
            event["sequence"] = sequence

        debug.update(
            {
                "class": craving_class,
                "probabilities": [round(float(value), 6) for value in probabilities],
                "confidence": event["confidence"],
                "predictionTimestampMs": timestamp_ms,
                "sequence": sequence,
                "featureMs": round(feature_ms, 3),
                "modelMs": round(model_ms, 3),
            }
        )
        self.last_debug = debug
        LOGGER.info(
            "prediction sequence=%s class=%s probs=%s ppg_raw=%s gsr_raw=%s "
            "features_used=%s/%s",
            sequence,
            craving_class,
            debug["probabilities"],
            debug["channels"]["ppg"]["rawSampleCount"],
            debug["channels"]["gsr"]["rawSampleCount"],
            debug["usedFeatureCount"],
            len(self.keep or []),
        )
        return event

    def _find_model_code_dir(self) -> Path:
        for candidate in (self.model_path.parent, *self.model_path.parents):
            if (candidate / "features.py").exists() and (candidate / "config.py").exists():
                return candidate
        return Path(__file__).resolve().parents[1] / "model"

    def _load_runtime_config(self) -> None:
        """Mirror model/config.py signal grid so serving matches training."""

        config = self.config
        if config is None:
            return
        self.fs_raw = float(getattr(config, "FS_RAW", self.fs_raw))
        self.fs_gsr = float(getattr(config, "FS_GSR", self.fs_raw))
        self.win_sec = float(getattr(config, "WIN_SEC", self.win_sec))
        self.raw_win_len = int(getattr(config, "WIN_LEN", round(self.fs_raw * self.win_sec)))

    def _payload_to_features(
        self, payload: dict[str, Any]
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        """Convert one JSON payload into the RF feature row."""

        samples = payload.get("samples") or []
        target_ms = self._target_timestamps(payload, samples, self.raw_win_len)

        # Interpolate sparse/irregular app samples onto the 51.2Hz training grid.
        # PPG ~25Hz and EDA ~1Hz are both upsampled to 512 points so NeuroKit
        # features are computed at the same fs used during training.
        ppg_raw, ppg_info = self._resample_payload_channel(samples, PPG_SENSORS, target_ms)
        gsr_raw, gsr_info = self._resample_payload_channel(samples, GSR_SENSORS, target_ms)

        assert self.features is not None
        feat: dict[str, Any] = {}
        try:
            feat.update(self.features.extract_ppg_features(ppg_raw, fs=self.fs_raw, preprocess=True))
        except Exception:
            LOGGER.exception("PPG feature extraction failed")
        try:
            feat.update(self.features.extract_gsr_features(gsr_raw, fs=self.fs_gsr))
        except Exception:
            LOGGER.exception("GSR feature extraction failed")

        used = [
            c
            for c in (self.keep or [])
            if c in feat and feat[c] is not None and feat[c] == feat[c]  # non-NaN
        ]
        debug = {
            "windowMs": payload.get("windowMs"),
            "windowStartMs": payload.get("windowStartMs"),
            "windowEndMs": payload.get("windowEndMs"),
            "rawSamplingHz": self.fs_raw,
            "gsrSamplingHz": self.fs_gsr,
            "rawWindowLength": self.raw_win_len,
            "modelName": self.model_name,
            "extractedFeatureCount": int(len(feat)),
            "usedFeatureCount": int(len(used)),
            "channels": {"ppg": ppg_info, "gsr": gsr_info},
        }
        return feat, debug

    def _target_timestamps(
        self, payload: dict[str, Any], samples: list[dict[str, Any]], length: int
    ) -> np.ndarray:
        """Build evenly-spaced timestamps for interpolation."""

        start = _safe_float(payload.get("windowStartMs"))
        end = _safe_float(payload.get("windowEndMs"))
        if start is None or end is None or end <= start:
            timestamps = [
                ts
                for ts in (_safe_float(sample.get("timestampMs")) for sample in samples)
                if ts is not None
            ]
            if timestamps:
                start = min(timestamps)
                end = max(timestamps)
            else:
                end = time.time() * 1000.0
                start = end - self.win_sec * 1000.0
        if end <= start:
            end = start + self.win_sec * 1000.0
        return np.linspace(start, end, length, endpoint=False, dtype=np.float64)

    def _resample_payload_channel(
        self,
        samples: list[dict[str, Any]],
        sensor_names: tuple[str, ...],
        target_ms: np.ndarray,
    ) -> tuple[np.ndarray, dict[str, Any]]:
        """Select one sensor channel and interpolate it onto `target_ms`."""

        by_sensor: dict[str, list[tuple[float, float]]] = {name: [] for name in sensor_names}
        for sample in samples:
            sensor = str(sample.get("sensor", "")).upper()
            if sensor not in by_sensor:
                continue
            timestamp = _safe_float(sample.get("timestampMs"))
            value = _safe_float(sample.get("value"))
            if timestamp is None or value is None or not np.isfinite(value):
                continue
            by_sensor[sensor].append((timestamp, value))

        selected: list[tuple[float, float]] = []
        selected_sensor: str | None = None
        for sensor in sensor_names:
            if by_sensor[sensor]:
                selected = by_sensor[sensor]
                selected_sensor = sensor
                break

        if not selected:
            values = np.zeros(len(target_ms), dtype=np.float32)
            return values, {
                "selectedSensor": None,
                "rawSampleCount": 0,
                "rawMin": None,
                "rawMax": None,
                "resampledLength": int(values.size),
                "resampledMin": _finite_min(values),
                "resampledMax": _finite_max(values),
            }

        timestamps = np.asarray([item[0] for item in selected], dtype=np.float64)
        values = np.asarray([item[1] for item in selected], dtype=np.float64)
        order = np.argsort(timestamps)
        timestamps = timestamps[order]
        values = values[order]

        unique_ts, inverse = np.unique(timestamps, return_inverse=True)
        if len(unique_ts) != len(timestamps):
            sums = np.zeros(len(unique_ts), dtype=np.float64)
            counts = np.zeros(len(unique_ts), dtype=np.float64)
            np.add.at(sums, inverse, values)
            np.add.at(counts, inverse, 1.0)
            timestamps = unique_ts
            values = sums / np.maximum(counts, 1.0)

        if len(timestamps) == 1:
            resampled = np.full(len(target_ms), values[0], dtype=np.float32)
        else:
            resampled = np.interp(target_ms, timestamps, values).astype(np.float32)
        return resampled, {
            "selectedSensor": selected_sensor,
            "rawSampleCount": int(len(values)),
            "rawStartMs": int(timestamps[0]),
            "rawEndMs": int(timestamps[-1]),
            "rawMin": _finite_min(values),
            "rawMax": _finite_max(values),
            "resampledLength": int(resampled.size),
            "resampledMin": _finite_min(resampled),
            "resampledMax": _finite_max(resampled),
        }


class RealtimePredictionService:
    """Owns the loaded model, input queue, worker task, and SSE hub."""

    def __init__(self) -> None:
        backend_dir = Path(__file__).resolve().parents[1]
        default_model_path = _default_model_path(backend_dir)
        self.model = CravingModel(
            Path(os.getenv("MODEL_PATH", str(default_model_path))),
        )
        self.hub = PredictionHub()
        self.latency = LatencyRecorder()
        self.alerts = AlertEvaluatorRegistry()
        self.memory = PostgresMemory()
        self.queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(
            maxsize=int(os.getenv("INFERENCE_QUEUE_MAX", "100"))
        )
        self.worker_task: asyncio.Task[None] | None = None
        self.persistence_tasks: set[asyncio.Task[None]] = set()
        self.startup_error: str | None = None
        self._generated_session_id = f"sensor-{int(time.time() * 1000)}"

    async def start(self) -> None:
        """Load model once and start the always-on inference worker."""

        try:
            self.model.load()
        except Exception as exc:
            self.startup_error = str(exc)
            LOGGER.exception("Craving model failed to load")
        await self.memory.start()
        self.worker_task = asyncio.create_task(self._worker(), name="craving-inference-worker")

    async def stop(self) -> None:
        if self.worker_task is None:
            return
        self.worker_task.cancel()
        try:
            await self.worker_task
        except asyncio.CancelledError:
            pass
        if self.persistence_tasks:
            await asyncio.gather(*self.persistence_tasks, return_exceptions=True)
        await self.memory.stop()

    async def submit(self, payload: dict[str, Any]) -> None:
        """Queue a payload without letting stale windows pile up forever."""

        if not self.model.ready:
            detail = self.startup_error or "Model is not loaded"
            raise ModelUnavailableError(detail)
        # Stamp receive time so the worker can compute uplink + queue latency.
        payload["_enqueuePerf"] = time.perf_counter()
        payload["_recvWallMs"] = time.time() * 1000.0
        if self.queue.full():
            try:
                self.queue.get_nowait()
            except asyncio.QueueEmpty:
                pass
        self.queue.put_nowait(payload)

    def status(self) -> dict[str, Any]:
        return {
            "ready": self.model.ready,
            "modelPath": str(self.model.model_path),
            "modelName": self.model.model_name,
            "featureCount": len(self.model.keep) if self.model.keep else None,
            "classLabels": self.model.class_labels,
            "rawSamplingHz": self.model.fs_raw,
            "gsrSamplingHz": self.model.fs_gsr,
            "winSec": self.model.win_sec,
            "rawWindowLength": self.model.raw_win_len,
            "watchPpgSamplingHz": WATCH_PPG_FS,
            "watchEdaSamplingHz": WATCH_EDA_FS,
            "queueSize": self.queue.qsize(),
            "startupError": self.startup_error,
            "lastPrediction": self.model.last_debug,
        }

    def current_session_id(self) -> str:
        return self._generated_session_id

    async def _worker(self) -> None:
        """Continuously process queued windows and broadcast predictions."""

        while True:
            payload = await self.queue.get()
            try:
                dequeue_perf = time.perf_counter()
                enqueue_perf = payload.get("_enqueuePerf", dequeue_perf)
                queue_ms = (dequeue_perf - enqueue_perf) * 1000.0

                event = await asyncio.to_thread(self.model.predict, payload)
                session_id, alert = self._apply_alert(payload, event)
                self._persist_prediction(session_id, event, alert)

                debug = self.model.last_debug or {}
                sent_ms = _safe_float(payload.get("sentAtMs"))
                recv_ms = payload.get("_recvWallMs")
                comm_ms = (recv_ms - sent_ms) if (sent_ms is not None and recv_ms is not None) else None
                server_ms = (time.perf_counter() - enqueue_perf) * 1000.0
                # Stash per-stage latencies on the event so main.py can log them
                # together with the SSE send time (downlink) at the moment the
                # event is actually pushed to the app. `_readyPerf` marks
                # "prediction ready to send"; main.py diffs it against send time.
                event["_lat"] = {
                    "comm_ms": comm_ms,
                    "queue_ms": queue_ms,
                    "feature_ms": debug.get("featureMs"),
                    "model_ms": debug.get("modelMs"),
                    "server_ms": server_ms,
                }
                event["_readyPerf"] = time.perf_counter()
                await self.hub.broadcast(event)
            except Exception:
                LOGGER.exception("Prediction failed")
            finally:
                self.queue.task_done()

    def _session_id(self, payload: dict[str, Any]) -> str:
        session_id = payload.get("sessionId") or payload.get("session_id")
        if session_id:
            return str(session_id)
        session_started_at = payload.get("sessionStartedAtMs")
        if session_started_at is not None:
            return str(session_started_at)
        return self._generated_session_id

    def _apply_alert(
        self,
        payload: dict[str, Any],
        event: dict[str, Any],
    ) -> tuple[str, dict[str, Any]]:
        session_id = self._session_id(payload)
        event["sessionId"] = session_id
        alert = self.alerts.for_session(session_id).evaluate(
            int(event["class"]),
            now_ms=int(event.get("timestampMs") or time.time() * 1000),
        ).as_dict()
        event.update(alert)
        return session_id, alert

    def _persist_prediction(
        self,
        session_id: str,
        event: dict[str, Any],
        alert: dict[str, Any],
    ) -> None:
        task = asyncio.create_task(
            self.memory.record_prediction_event(session_id, event.copy(), alert.copy()),
            name="persist-prediction-event",
        )
        self.persistence_tasks.add(task)

        def _discard(done: asyncio.Task[None]) -> None:
            self.persistence_tasks.discard(done)
            try:
                done.result()
            except Exception:
                LOGGER.warning("Prediction persistence task failed", exc_info=True)

        task.add_done_callback(_discard)


def _default_model_path(backend_dir: Path) -> Path:
    return backend_dir / "model" / "weights" / "rf_dependent.joblib"


def _safe_float(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _finite_min(values: Any) -> float | None:
    finite = np.asarray(values, dtype=float)
    finite = finite[np.isfinite(finite)]
    return round(float(finite.min()), 6) if finite.size else None


def _finite_max(values: Any) -> float | None:
    finite = np.asarray(values, dtype=float)
    finite = finite[np.isfinite(finite)]
    return round(float(finite.max()), 6) if finite.size else None
