from __future__ import annotations

"""Realtime binary craving inference for twenty-second PPG/GSR windows."""

import hashlib
import importlib.util
import json
import logging
import os
import time
from pathlib import Path
from typing import Any

import numpy as np

LOGGER = logging.getLogger(__name__)
WATCH_PPG_FS = 25.0
WATCH_EDA_FS = 1.0
PPG_SENSORS = ("PPG_GREEN", "PPG_IR", "PPG_RED")
GSR_SENSORS = ("EDA",)
EXPECTED_WEIGHTS_SHA256 = "2493e5d75fcdc9b066b341deb47f81c9db49aac37c626619b7afe1a6b2fc354c"


class ModelUnavailableError(RuntimeError):
    """Raised when prediction is requested while the model is unavailable."""


class InvalidSensorWindow(ValueError):
    """Raised when a sensor window cannot satisfy the model input contract."""


class CravingModel:
    """Loads the supplied Conv1DNet state dict and performs binary inference."""

    def __init__(
        self,
        model_path: Path,
        metadata_path: Path | None = None,
        *,
        expected_sha256: str | None = None,
        requested_device: str | None = None,
    ) -> None:
        self.model_path = Path(model_path)
        self.metadata_path = Path(metadata_path) if metadata_path else self.model_path.with_name("model_metadata.json")
        self.expected_sha256 = (expected_sha256 or EXPECTED_WEIGHTS_SHA256).lower()
        self.requested_device = (requested_device or "auto").lower()
        self.actual_sha256: str | None = None
        self.actual_device: str | None = None
        self.fallback = False
        self.fallback_reason: str | None = None
        self.model: Any | None = None
        self.torch: Any | None = None
        self.metadata: dict[str, Any] = {}
        self.model_name = "Conv1DNet"
        self.model_version = f"moving-average-high2p4-win20s-{self.expected_sha256[:12]}"
        self.class_labels = ["low", "high"]
        self.fs_raw = 51.2
        self.fs_gsr = 51.2
        self.win_sec = 20.0
        self.raw_win_len = 1024
        self.last_debug: dict[str, Any] | None = None

    @property
    def ready(self) -> bool:
        return self.model is not None and self.torch is not None and self.actual_device is not None

    @property
    def safe_artifact_uri(self) -> str:
        return f"model/weights/final_model_win20s_high2p4/{self.model_path.name}"

    @property
    def registration_config(self) -> dict[str, Any]:
        return {
            "window_sec": self.win_sec,
            "training_stride_sec": 1.0,
            "operational_stride_sec": 10.0,
            "sampling_hz": self.fs_raw,
            "window_length": self.raw_win_len,
            "channels": ["PPG", "GSR"],
            "normalization": "per_channel_minmax",
            "filter": "none",
            "source_metadata_filter": "ppg_lowpass_5hz_not_applied_by_user_decision",
            "artifact_sha256": self.actual_sha256 or self.expected_sha256,
            "torch_version": getattr(self.torch, "__version__", None),
            "requested_device": self.requested_device,
            "actual_device": self.actual_device,
        }

    def load(self) -> None:
        if not self.model_path.is_file():
            raise FileNotFoundError("Craving model weights are missing")
        if not self.metadata_path.is_file():
            raise FileNotFoundError("Craving model metadata is missing")
        self.actual_sha256 = _sha256_file(self.model_path)
        if self.actual_sha256.lower() != self.expected_sha256:
            raise ValueError("Craving model checksum mismatch")
        self.metadata = json.loads(self.metadata_path.read_text(encoding="utf-8"))
        self._validate_metadata()

        import torch

        self.torch = torch
        module = self._load_model_module()
        state_dict = torch.load(self.model_path, map_location="cpu", weights_only=True)
        self.model = self._load_for_requested_device(module.Conv1DNet, state_dict)
        LOGGER.info(
            "Loaded binary craving model name=%s checksum=%s device=%s fallback=%s",
            self.model_name,
            self.actual_sha256,
            self.actual_device,
            self.fallback,
        )

    def _validate_metadata(self) -> None:
        preprocessing = self.metadata.get("preprocessing") or {}
        architecture = self.metadata.get("architecture") or {}
        if (
            float(preprocessing.get("fs", 0)) != 51.2
            or float(preprocessing.get("window_seconds", 0)) != 20.0
            or int(preprocessing.get("window_length", 0)) != 1024
            or float(preprocessing.get("stride_seconds", 0)) != 1.0
            or list(preprocessing.get("channels") or []) != ["PPG", "GSR"]
            or (architecture.get("class") or architecture.get("cls")) != "Conv1DNet"
            or int(architecture.get("out_dim", 0)) != 2
        ):
            raise ValueError("Craving model metadata is incompatible")

    def _load_model_module(self) -> Any:
        source = self.model_path.with_name("model.py")
        if not source.is_file():
            raise FileNotFoundError("Craving model architecture is missing")
        spec = importlib.util.spec_from_file_location("neurotruth_binary_craving_model", source)
        if spec is None or spec.loader is None:
            raise ImportError("Craving model architecture cannot be loaded")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def _new_loaded_model(self, model_class: Any, state_dict: Any, device: str) -> Any:
        model = model_class()
        model.load_state_dict(state_dict)
        model.to(device)
        model.eval()
        return model

    def _load_for_requested_device(self, model_class: Any, state_dict: Any) -> Any:
        assert self.torch is not None
        requested = self.requested_device
        if requested not in {"auto", "cpu", "cuda", "cuda:0"}:
            raise ValueError("CRAVING_INFERENCE_DEVICE must be auto, cpu, cuda, or cuda:0")
        wants_cuda = requested in {"auto", "cuda", "cuda:0"}
        if wants_cuda:
            try:
                if not self.torch.cuda.is_available():
                    raise RuntimeError("cuda_unavailable")
                candidate = self._new_loaded_model(model_class, state_dict, "cuda:0")
                with self.torch.inference_mode():
                    output = candidate(self.torch.zeros((1, 2, 1024), dtype=self.torch.float32, device="cuda:0"))
                if tuple(output.shape) != (1, 2) or not bool(self.torch.isfinite(output).all().item()):
                    raise RuntimeError("cuda_smoke_invalid")
                self.actual_device = "cuda:0"
                return candidate
            except Exception as exc:
                self.fallback = True
                self.fallback_reason = _fallback_code(exc)
                LOGGER.warning("CUDA craving inference unavailable; using CPU (%s)", self.fallback_reason)
                try:
                    self.torch.cuda.empty_cache()
                except Exception:
                    pass
        model = self._new_loaded_model(model_class, state_dict, "cpu")
        with self.torch.inference_mode():
            output = model(self.torch.zeros((1, 2, 1024), dtype=self.torch.float32))
        if tuple(output.shape) != (1, 2) or not bool(self.torch.isfinite(output).all().item()):
            raise RuntimeError("cpu_smoke_invalid")
        self.actual_device = "cpu"
        return model

    def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not self.ready:
            raise ModelUnavailableError("Model is not loaded")
        assert self.torch is not None and self.model is not None and self.actual_device is not None
        preprocess_t0 = time.perf_counter()
        input_array, quality = self.preprocess(payload)
        preprocess_ms = (time.perf_counter() - preprocess_t0) * 1000.0
        tensor = self.torch.from_numpy(input_array).to(self.actual_device)
        model_t0 = time.perf_counter()
        with self.torch.inference_mode():
            logits = self.model(tensor)
            probabilities = self.torch.softmax(logits, dim=1).detach().to("cpu").numpy()[0]
        model_ms = (time.perf_counter() - model_t0) * 1000.0
        if probabilities.shape != (2,) or not np.all(np.isfinite(probabilities)):
            raise RuntimeError("Model returned invalid probabilities")
        p0, p1 = (float(np.clip(value, 0.0, 1.0)) for value in probabilities)
        craving_class = 1 if p1 > p0 else 0
        timestamp_ms = int(payload.get("windowEndMs") or time.time() * 1000)
        event: dict[str, Any] = {
            "predictionSchema": "binary-craving-v1",
            "class": craving_class,
            "classCode": self.class_labels[craving_class],
            "confidence": round(max(p0, p1), 6),
            "cravingProbability": round(p1, 6),
            "classProbabilities": {"low": round(p0, 6), "high": round(p1, 6)},
            "timestampMs": timestamp_ms,
            "inputQuality": quality,
        }
        if payload.get("sequence") is not None:
            event["sequence"] = payload["sequence"]
        self.last_debug = {
            "class": craving_class,
            "confidence": event["confidence"],
            "cravingProbability": event["cravingProbability"],
            "preprocessMs": round(preprocess_ms, 3),
            "modelMs": round(model_ms, 3),
            "inputQuality": quality,
        }
        return event

    def preprocess(self, payload: dict[str, Any]) -> tuple[np.ndarray, dict[str, Any]]:
        samples = payload.get("samples") or []
        target_ms = self._target_timestamps(payload, samples)
        ppg, ppg_info = self._resample_channel(samples, PPG_SENSORS, target_ms, required=True)
        gsr, gsr_info = self._resample_channel(samples, GSR_SENSORS, target_ms, required=False)
        normalized = np.stack((_minmax(ppg), _minmax(gsr)), axis=0)[None, ...].astype(np.float32)
        if normalized.shape != (1, 2, 1024) or not np.all(np.isfinite(normalized)):
            raise InvalidSensorWindow("Invalid model input")
        quality = {
            "ppgSensor": ppg_info["selectedSensor"],
            "ppgSampleCount": ppg_info["rawSampleCount"],
            "gsrSensor": gsr_info["selectedSensor"],
            "gsrSampleCount": gsr_info["rawSampleCount"],
            "gsrMissing": gsr_info["selectedSensor"] is None,
        }
        return normalized, quality

    def _target_timestamps(self, payload: dict[str, Any], samples: list[dict[str, Any]]) -> np.ndarray:
        start = _safe_float(payload.get("windowStartMs"))
        end = _safe_float(payload.get("windowEndMs"))
        if start is None or end is None or end <= start:
            stamps = [stamp for sample in samples if (stamp := _safe_float(sample.get("timestampMs"))) is not None]
            if not stamps:
                raise InvalidSensorWindow("Sensor timestamps are missing")
            start, end = min(stamps), max(stamps)
        if end <= start:
            raise InvalidSensorWindow("Sensor window has no duration")
        return np.linspace(start, end, self.raw_win_len, endpoint=False, dtype=np.float64)

    @staticmethod
    def _resample_channel(
        samples: list[dict[str, Any]],
        sensor_names: tuple[str, ...],
        target_ms: np.ndarray,
        *,
        required: bool,
    ) -> tuple[np.ndarray, dict[str, Any]]:
        by_sensor: dict[str, list[tuple[float, float]]] = {name: [] for name in sensor_names}
        for sample in samples:
            sensor = str(sample.get("sensor", "")).upper()
            if sensor not in by_sensor:
                continue
            stamp, value = _safe_float(sample.get("timestampMs")), _safe_float(sample.get("value"))
            if stamp is not None and value is not None and np.isfinite(value):
                by_sensor[sensor].append((stamp, value))
        selected_sensor = next((name for name in sensor_names if by_sensor[name]), None)
        if selected_sensor is None:
            if required:
                raise InvalidSensorWindow("Finite PPG samples are required")
            return np.zeros(len(target_ms), dtype=np.float32), {"selectedSensor": None, "rawSampleCount": 0}
        selected = by_sensor[selected_sensor]
        stamps = np.asarray([item[0] for item in selected], dtype=np.float64)
        values = np.asarray([item[1] for item in selected], dtype=np.float64)
        order = np.argsort(stamps)
        stamps, values = stamps[order], values[order]
        unique, inverse = np.unique(stamps, return_inverse=True)
        if unique.size != stamps.size:
            sums, counts = np.zeros(unique.size), np.zeros(unique.size)
            np.add.at(sums, inverse, values)
            np.add.at(counts, inverse, 1.0)
            stamps, values = unique, sums / np.maximum(counts, 1.0)
        if stamps.size == 1:
            resampled = np.full(target_ms.size, values[0], dtype=np.float32)
        else:
            resampled = np.interp(target_ms, stamps, values).astype(np.float32)
        return resampled, {"selectedSensor": selected_sensor, "rawSampleCount": int(values.size)}


def _default_model_path(backend_dir: Path) -> Path:
    return backend_dir / "model" / "weights" / "final_model_win20s_high2p4" / "model_weights.pt"


def _minmax(values: np.ndarray) -> np.ndarray:
    values = np.asarray(values, dtype=np.float32)
    if values.size != 1024 or not np.all(np.isfinite(values)):
        raise InvalidSensorWindow("Channel contains invalid values")
    low, high = float(values.min()), float(values.max())
    if high <= low:
        return np.zeros(values.shape, dtype=np.float32)
    return ((values - low) / (high - low)).astype(np.float32)


def _safe_float(value: Any) -> float | None:
    try:
        result = float(value)
        return result if np.isfinite(result) else None
    except (TypeError, ValueError):
        return None


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _fallback_code(exc: Exception) -> str:
    message = str(exc).lower()
    if "unavailable" in message:
        return "cuda_unavailable"
    if "smoke" in message:
        return "cuda_smoke_failed"
    return "cuda_initialization_failed"


def _startup_error_code(exc: Exception) -> str:
    if isinstance(exc, FileNotFoundError):
        return "model_artifact_missing"
    if "checksum" in str(exc).lower():
        return "model_checksum_mismatch"
    if isinstance(exc, (ImportError, ModuleNotFoundError)):
        return "model_runtime_missing"
    return "model_load_failed"
