from __future__ import annotations

"""Realtime craving inference service.

This module adapts the Android sensor payload to the current training code in
`backend/model`: DualBranchNet expects PPG `(B, 1, 250)` and GSR `(B, 1, 10)`.
The preprocessing and scaling settings are read from the model checkpoint and
model/config.py so server inference stays aligned with training.
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


class TorchCravingModel:
    """Loads the torch checkpoint and performs one-window predictions."""

    def __init__(self, model_path: Path, device: str = "auto") -> None:
        self.model_path = model_path
        self.requested_device = device
        self.device: Any | None = None
        self.model: Any | None = None
        self.torch: Any | None = None
        self.preprocessing: Any | None = None
        self.config: Any | None = None

        self.n_classes = 3
        self.scale_mode = "perwin"
        self.scale_method = "minmax"
        self.ppg_scaler: tuple[np.ndarray | None, np.ndarray | None] = (None, None)
        self.gsr_scaler: tuple[np.ndarray | None, np.ndarray | None] = (None, None)
        self.last_debug: dict[str, Any] | None = None

        self.fs_raw = 51.2
        self.fs_ppg = 25.0
        self.fs_gsr = 1.0
        self.win_sec = 10.0
        self.raw_win_len = 512
        self.ppg_win = 250
        self.gsr_win = 10

    @property
    def ready(self) -> bool:
        return self.model is not None and self.torch is not None

    def load(self) -> None:
        """Load model code, preprocessing code, config, checkpoint, and weights."""

        if not self.model_path.exists():
            raise FileNotFoundError(f"Model checkpoint not found: {self.model_path}")

        torch = importlib.import_module("torch")
        self.torch = torch
        self.device = self._resolve_device(torch)

        model_dir = self._find_model_code_dir()
        if str(model_dir) not in sys.path:
            sys.path.insert(0, str(model_dir))
        # The training package uses bare imports (`import model`, `import config`).
        # Drop any earlier model package so reloads or model swaps use this path.
        for module_name in ("model", "preprocessing", "config"):
            sys.modules.pop(module_name, None)
        self.config = importlib.import_module("config")
        model_module = importlib.import_module("model")
        self.preprocessing = importlib.import_module("preprocessing")
        self._load_runtime_config()

        checkpoint = self._load_checkpoint(torch)
        state_dict = checkpoint
        if isinstance(checkpoint, dict):
            state_dict = checkpoint.get("state_dict", checkpoint)
            self.n_classes = int(checkpoint.get("n_classes", self.n_classes))
            self.scale_mode = str(checkpoint.get("scale_mode", self.scale_mode))
            self.scale_method = str(checkpoint.get("scale_method", self.scale_method))
            self.ppg_scaler = self._as_scaler_tuple(checkpoint.get("ppg_scaler"))
            self.gsr_scaler = self._as_scaler_tuple(checkpoint.get("gsr_scaler"))

        DualBranchNet = getattr(model_module, "DualBranchNet")
        self.model = DualBranchNet(out_dim=self.n_classes)
        try:
            self.model.load_state_dict(state_dict)
        except RuntimeError:
            stripped = {
                key.removeprefix("module."): value for key, value in state_dict.items()
            }
            self.model.load_state_dict(stripped)
        self.model.to(self.device)
        self.model.eval()
        LOGGER.info(
            "Loaded craving model from %s on %s (PPG=%s, GSR=%s, scale=%s/%s)",
            self.model_path,
            self.device,
            self.ppg_win,
            self.gsr_win,
            self.scale_mode,
            self.scale_method,
        )

    def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Run preprocessing, scaling, torch inference, and SSE event shaping."""

        if not self.ready:
            raise ModelUnavailableError("Model is not loaded")

        xp, xg, debug = self._payload_to_tensors(payload)
        torch = self.torch
        assert torch is not None
        assert self.model is not None
        with torch.no_grad():
            ppg_tensor = torch.from_numpy(xp).to(self.device)
            gsr_tensor = torch.from_numpy(xg).to(self.device)
            logits = self.model(ppg_tensor, gsr_tensor)
            probabilities = torch.softmax(logits, dim=1).cpu().numpy()[0]

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
            }
        )
        self.last_debug = debug
        LOGGER.info(
            "prediction sequence=%s class=%s probs=%s ppg_raw=%s gsr_raw=%s "
            "ppg_scaled=[%.4f,%.4f] gsr_scaled=[%.4f,%.4f]",
            sequence,
            craving_class,
            debug["probabilities"],
            debug["channels"]["ppg"]["rawSampleCount"],
            debug["channels"]["gsr"]["rawSampleCount"],
            debug["scaled"]["ppg"]["min"],
            debug["scaled"]["ppg"]["max"],
            debug["scaled"]["gsr"]["min"],
            debug["scaled"]["gsr"]["max"],
        )
        return event

    def _resolve_device(self, torch: Any) -> Any:
        requested = self.requested_device.strip().lower()
        if requested == "auto":
            selected = "cuda" if torch.cuda.is_available() else "cpu"
        else:
            selected = requested
        if selected.startswith("cuda") and not torch.cuda.is_available():
            LOGGER.warning("CUDA requested but unavailable; falling back to CPU")
            selected = "cpu"
        return torch.device(selected)

    def _load_checkpoint(self, torch: Any) -> Any:
        try:
            return torch.load(self.model_path, map_location="cpu", weights_only=False)
        except TypeError:
            return torch.load(self.model_path, map_location="cpu")

    def _find_model_code_dir(self) -> Path:
        for candidate in (self.model_path.parent, *self.model_path.parents):
            if (candidate / "model.py").exists():
                return candidate
        return Path(__file__).resolve().parents[1] / "model"

    def _load_runtime_config(self) -> None:
        """Mirror model/config.py signal lengths and sampling rates."""

        config = self.config
        if config is None:
            return
        self.fs_raw = float(getattr(config, "FS_RAW", self.fs_raw))
        self.fs_ppg = float(getattr(config, "FS_PPG", self.fs_ppg))
        self.fs_gsr = float(getattr(config, "FS_GSR", self.fs_gsr))
        self.win_sec = float(getattr(config, "WIN_SEC", self.win_sec))
        self.raw_win_len = int(round(self.fs_raw * self.win_sec))
        self.ppg_win = int(getattr(config, "PPG_WIN", round(self.fs_ppg * self.win_sec)))
        self.gsr_win = int(getattr(config, "GSR_WIN", round(self.fs_gsr * self.win_sec)))

    def _payload_to_tensors(
        self, payload: dict[str, Any]
    ) -> tuple[np.ndarray, np.ndarray, dict[str, Any]]:
        """Convert one JSON payload into model-ready PPG/GSR tensors."""

        samples = payload.get("samples") or []
        raw_target_ms = self._target_timestamps(payload, samples, self.raw_win_len)

        # First place sparse/irregular app samples on the training raw grid
        # (51.2Hz, 512 points). model/preprocessing.py then performs the same
        # filter/resample path used in training: PPG 25Hz and GSR 1Hz.
        ppg_raw, ppg_info = self._resample_payload_channel(samples, PPG_SENSORS, raw_target_ms)
        gsr_raw, gsr_info = self._resample_payload_channel(samples, GSR_SENSORS, raw_target_ms)

        ppg, ppg_finite = self._preprocess_ppg(ppg_raw)
        gsr, gsr_finite = self._preprocess_gsr(gsr_raw)
        ppg = self._fit_length(ppg, self.ppg_win)
        gsr = self._fit_length(gsr, self.gsr_win)
        ppg_finite = self._fit_length(ppg_finite.astype(np.float32), self.ppg_win) > 0.5
        gsr_finite = self._fit_length(gsr_finite.astype(np.float32), self.gsr_win) > 0.5

        xp = ppg[None, None, :].astype(np.float32)
        xg = gsr[None, None, :].astype(np.float32)
        # The checkpoint currently records per-window MinMax scaling. If future
        # checkpoints store global scalers, _scale_channel will use them.
        xp_scaled = self._scale_channel(xp, self.ppg_scaler)
        xg_scaled = self._scale_channel(xg, self.gsr_scaler)

        debug = {
            "windowMs": payload.get("windowMs"),
            "windowStartMs": payload.get("windowStartMs"),
            "windowEndMs": payload.get("windowEndMs"),
            "scaleMode": self.scale_mode,
            "scaleMethod": self.scale_method,
            "rawSamplingHz": self.fs_raw,
            "ppgSamplingHz": self.fs_ppg,
            "gsrSamplingHz": self.fs_gsr,
            "ppgSamples": self.ppg_win,
            "gsrSamples": self.gsr_win,
            "channels": {
                "ppg": {
                    **ppg_info,
                    "preprocessedLength": int(ppg.size),
                    "preprocessedFiniteRatio": _finite_ratio(ppg_finite),
                    "preprocessedMin": _finite_min(ppg),
                    "preprocessedMax": _finite_max(ppg),
                },
                "gsr": {
                    **gsr_info,
                    "preprocessedLength": int(gsr.size),
                    "preprocessedFiniteRatio": _finite_ratio(gsr_finite),
                    "preprocessedMin": _finite_min(gsr),
                    "preprocessedMax": _finite_max(gsr),
                },
            },
            "scaled": {
                "ppg": {
                    "shape": list(xp_scaled.shape),
                    "min": _finite_min(xp_scaled),
                    "max": _finite_max(xp_scaled),
                    "mean": _finite_mean(xp_scaled),
                    "std": _finite_std(xp_scaled),
                },
                "gsr": {
                    "shape": list(xg_scaled.shape),
                    "min": _finite_min(xg_scaled),
                    "max": _finite_max(xg_scaled),
                    "mean": _finite_mean(xg_scaled),
                    "std": _finite_std(xg_scaled),
                },
            },
        }
        return xp_scaled.astype(np.float32), xg_scaled.astype(np.float32), debug

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

        by_sensor = {name: [] for name in sensor_names}
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

    def _preprocess_ppg(self, ppg_raw: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        if self.preprocessing is None:
            raise ModelUnavailableError("Model preprocessing module is not loaded")
        try:
            ppg, finite = self.preprocessing.preprocess_ppg(ppg_raw)
            return ppg.astype(np.float32), np.asarray(finite, dtype=bool)
        except Exception:
            LOGGER.exception("PPG preprocessing failed; using raw resampled channel")
            return ppg_raw.astype(np.float32), np.isfinite(ppg_raw)

    def _preprocess_gsr(self, gsr_raw: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        if self.preprocessing is None:
            raise ModelUnavailableError("Model preprocessing module is not loaded")
        try:
            gsr, finite = self.preprocessing.preprocess_gsr(gsr_raw)
            return gsr.astype(np.float32), np.asarray(finite, dtype=bool)
        except Exception:
            LOGGER.exception("GSR preprocessing failed; using raw resampled channel")
            return gsr_raw.astype(np.float32), np.isfinite(gsr_raw)

    def _fit_length(self, values: np.ndarray, length: int) -> np.ndarray:
        """Guarantee exact model input lengths even after signal resampling."""

        values = np.asarray(values)
        if values.size == length:
            return values.astype(np.float32)
        if values.size == 0:
            return np.zeros(length, dtype=np.float32)
        source_x = np.linspace(0.0, 1.0, values.size, endpoint=False)
        target_x = np.linspace(0.0, 1.0, length, endpoint=False)
        return np.interp(target_x, source_x, values.astype(float)).astype(np.float32)

    def _scale_channel(
        self,
        x: np.ndarray,
        scaler: tuple[np.ndarray | None, np.ndarray | None],
    ) -> np.ndarray:
        """Apply checkpoint scaling semantics to one branch input."""

        if self.scale_mode == "perwin" or scaler[0] is None or scaler[1] is None:
            offset, scale = self._stats(x, axis=2)
        else:
            offset, scale = scaler
            assert offset is not None
            assert scale is not None
            expected_shape = (1, x.shape[1], 1)
            if offset.shape != expected_shape:
                offset = offset.reshape(expected_shape)
            if scale.shape != expected_shape:
                scale = scale.reshape(expected_shape)
        return (x - offset) / (scale + 1e-8)

    def _stats(self, x: np.ndarray, axis: int | tuple[int, ...]) -> tuple[np.ndarray, np.ndarray]:
        if self.scale_method == "minmax":
            offset = x.min(axis=axis, keepdims=True)
            scale = x.max(axis=axis, keepdims=True) - offset
        else:
            offset = x.mean(axis=axis, keepdims=True)
            scale = x.std(axis=axis, keepdims=True)
        return offset.astype(np.float32), (scale + 1e-8).astype(np.float32)

    def _as_numpy(self, value: Any) -> np.ndarray | None:
        if value is None:
            return None
        if hasattr(value, "detach"):
            value = value.detach().cpu().numpy()
        return np.asarray(value, dtype=np.float32)

    def _as_scaler_tuple(self, value: Any) -> tuple[np.ndarray | None, np.ndarray | None]:
        if not isinstance(value, (tuple, list)) or len(value) != 2:
            return None, None
        return self._as_numpy(value[0]), self._as_numpy(value[1])


class RealtimePredictionService:
    """Owns the loaded model, input queue, worker task, and SSE hub."""

    def __init__(self) -> None:
        backend_dir = Path(__file__).resolve().parents[1]
        default_model_path = _default_model_path(backend_dir)
        self.model = TorchCravingModel(
            Path(os.getenv("MODEL_PATH", str(default_model_path))),
            os.getenv("MODEL_DEVICE", "auto"),
        )
        self.hub = PredictionHub()
        self.queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(
            maxsize=int(os.getenv("INFERENCE_QUEUE_MAX", "100"))
        )
        self.worker_task: asyncio.Task[None] | None = None
        self.startup_error: str | None = None

    async def start(self) -> None:
        """Load model once and start the always-on inference worker."""

        try:
            self.model.load()
        except Exception as exc:
            self.startup_error = str(exc)
            LOGGER.exception("Craving model failed to load")
        self.worker_task = asyncio.create_task(self._worker(), name="craving-inference-worker")

    async def stop(self) -> None:
        if self.worker_task is None:
            return
        self.worker_task.cancel()
        try:
            await self.worker_task
        except asyncio.CancelledError:
            pass

    async def submit(self, payload: dict[str, Any]) -> None:
        """Queue a payload without letting stale windows pile up forever."""

        if not self.model.ready:
            detail = self.startup_error or "Model is not loaded"
            raise ModelUnavailableError(detail)
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
            "device": str(self.model.device) if self.model.device is not None else None,
            "scaleMode": self.model.scale_mode,
            "scaleMethod": self.model.scale_method,
            "ppgScalerStored": self.model.ppg_scaler[0] is not None,
            "gsrScalerStored": self.model.gsr_scaler[0] is not None,
            "rawSamplingHz": self.model.fs_raw,
            "ppgSamplingHz": self.model.fs_ppg,
            "gsrSamplingHz": self.model.fs_gsr,
            "ppgSamples": self.model.ppg_win,
            "gsrSamples": self.model.gsr_win,
            "watchPpgSamplingHz": WATCH_PPG_FS,
            "watchEdaSamplingHz": WATCH_EDA_FS,
            "queueSize": self.queue.qsize(),
            "startupError": self.startup_error,
            "lastPrediction": self.model.last_debug,
        }

    async def _worker(self) -> None:
        """Continuously process queued windows and broadcast predictions."""

        while True:
            payload = await self.queue.get()
            try:
                event = await asyncio.to_thread(self.model.predict, payload)
                await self.hub.broadcast(event)
            except Exception:
                LOGGER.exception("Prediction failed")
            finally:
                self.queue.task_done()


def _default_model_path(backend_dir: Path) -> Path:
    return backend_dir / "model" / "weights" / "fold3_best.pt"


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


def _finite_mean(values: Any) -> float | None:
    finite = np.asarray(values, dtype=float)
    finite = finite[np.isfinite(finite)]
    return round(float(finite.mean()), 6) if finite.size else None


def _finite_std(values: Any) -> float | None:
    finite = np.asarray(values, dtype=float)
    finite = finite[np.isfinite(finite)]
    return round(float(finite.std()), 6) if finite.size else None


def _finite_ratio(values: Any) -> float:
    arr = np.asarray(values)
    return round(float(np.isfinite(arr).mean()), 6) if arr.size else 0.0
