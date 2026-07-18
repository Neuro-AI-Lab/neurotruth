from __future__ import annotations

import asyncio
import logging
import os
import subprocess
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace
from threading import Lock
from typing import Any

from fastapi import FastAPI, File, Form, HTTPException, UploadFile


MODEL_NAME = "whisper-large-v3-turbo"
LOGGER = logging.getLogger("neurotruth.stt")
SAMPLE_RATE = 16_000
SUPPORTED_CONTENT_TYPES = {
    "audio/mp4",
    "audio/m4a",
    "audio/x-m4a",
    "audio/wav",
    "audio/x-wav",
    "audio/wave",
}


def _enabled(value: str | None) -> bool:
    return str(value or "").strip().lower() in {"1", "true", "yes", "on"}


class TorchWhisperModel:
    """Small compatibility adapter around Hugging Face Whisper on PyTorch CUDA."""

    def __init__(self, model_path: Path, *, device: str) -> None:
        import torch
        from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor

        self._torch = torch
        self._device = torch.device(device)
        self._dtype = torch.float16 if self._device.type == "cuda" else torch.float32
        self._processor = AutoProcessor.from_pretrained(
            str(model_path),
            local_files_only=True,
        )
        self._model = AutoModelForSpeechSeq2Seq.from_pretrained(
            str(model_path),
            torch_dtype=self._dtype,
            low_cpu_mem_usage=True,
            local_files_only=True,
        )
        self._model.to(self._device)
        self._model.eval()

        # A real tensor inference catches an unusable CUDA runtime during startup.
        if self._device.type == "cuda":
            mel_bins = int(getattr(self._model.config, "num_mel_bins", 128))
            probe = torch.zeros((1, mel_bins, 3000), dtype=self._dtype, device=self._device)
            with torch.inference_mode():
                self._model.model.encoder(probe)
            torch.cuda.synchronize(self._device)

    @staticmethod
    def _decode_audio(path: Path) -> tuple[Any, int]:
        import numpy as np

        result = subprocess.run(
            [
                "ffmpeg",
                "-nostdin",
                "-v",
                "error",
                "-i",
                str(path),
                "-f",
                "f32le",
                "-acodec",
                "pcm_f32le",
                "-ac",
                "1",
                "-ar",
                str(SAMPLE_RATE),
                "pipe:1",
            ],
            check=True,
            capture_output=True,
            timeout=40,
        )
        audio = np.frombuffer(result.stdout, dtype=np.float32)
        duration_ms = int(round((len(audio) / SAMPLE_RATE) * 1000))
        return audio, duration_ms

    def transcribe(self, path: str, **values: Any) -> tuple[list[Any], Any]:
        language = str(values.get("language") or "ko")
        try:
            audio, duration_ms = self._decode_audio(Path(path))
        except (OSError, subprocess.SubprocessError) as exc:
            raise RuntimeError("audio_decode_failed") from exc
        if audio.size == 0:
            return [], SimpleNamespace(duration=0.0)

        inputs = self._processor(
            audio,
            sampling_rate=SAMPLE_RATE,
            return_attention_mask=True,
            return_tensors="pt",
        )
        input_features = inputs.input_features.to(device=self._device, dtype=self._dtype)
        attention_mask = getattr(inputs, "attention_mask", None)
        if attention_mask is not None:
            attention_mask = attention_mask.to(self._device)

        generate_values: dict[str, Any] = {
            "input_features": input_features,
            "language": language,
            "task": "transcribe",
            "num_beams": 1,
            "do_sample": False,
            "max_new_tokens": 256,
        }
        if attention_mask is not None:
            generate_values["attention_mask"] = attention_mask
        with self._torch.inference_mode():
            generated = self._model.generate(**generate_values)
        text = self._processor.batch_decode(generated, skip_special_tokens=True)[0].strip()
        return [SimpleNamespace(text=text)] if text else [], SimpleNamespace(duration=duration_ms / 1000)


class WhisperRuntime:
    def __init__(self) -> None:
        self.enabled = _enabled(os.getenv("STT_ENABLED", "false"))
        self.model_path = Path(os.getenv("STT_MODEL_PATH", "/models/whisper-large-v3-turbo-pytorch"))
        self.cpu_model_path = Path(os.getenv("STT_CPU_MODEL_PATH", "/models/large-v3-turbo"))
        self.requested_device = os.getenv("STT_DEVICE", "auto").strip().lower()
        self.allow_cpu_fallback = _enabled(os.getenv("STT_ALLOW_CPU_FALLBACK", "true"))
        self.tmpfs_root = Path(os.getenv("STT_TMPFS_ROOT", "/dev/shm/neurotruth-stt"))
        self.max_upload_bytes = int(os.getenv("STT_MAX_UPLOAD_MIB", "10")) * 1024 * 1024
        self.model: Any | None = None
        self.actual_device: str | None = None
        self.engine: str | None = None
        self.fallback = False
        self.fallback_reason: str | None = None
        self.error_code: str | None = "stt_disabled" if not self.enabled else "stt_not_loaded"
        self._lock = Lock()

    def load(self) -> None:
        if not self.enabled:
            return
        if not self.model_path.is_dir() and not (
            self.allow_cpu_fallback and self.cpu_model_path.is_dir()
        ):
            self.error_code = "stt_model_missing"
            return

        wants_cuda = self.requested_device in {"auto", "cuda", "cuda:0"}
        if wants_cuda and self.model_path.is_dir():
            try:
                self.model = TorchWhisperModel(self.model_path, device="cuda:0")
                self.actual_device = "cuda:0"
                self.engine = "pytorch"
                self.error_code = None
                return
            except Exception as exc:
                self.model = None
                self.fallback_reason = type(exc).__name__
                LOGGER.warning("PyTorch CUDA STT initialization failed: %s", type(exc).__name__)

        if self.requested_device in {"cuda", "cuda:0"} or not self.allow_cpu_fallback:
            self.error_code = "stt_cuda_unavailable"
            return

        if self.cpu_model_path.is_dir():
            try:
                from faster_whisper import WhisperModel

                self.model = WhisperModel(
                    str(self.cpu_model_path),
                    device="cpu",
                    compute_type="int8",
                    local_files_only=True,
                )
                self.actual_device = "cpu"
                self.engine = "ctranslate2"
                self.fallback = wants_cuda
                self.error_code = None
                return
            except Exception as exc:
                self.model = None
                self.fallback_reason = type(exc).__name__
                LOGGER.warning("CPU STT initialization failed: %s", type(exc).__name__)
        self.error_code = "stt_model_load_failed"

    def status(self) -> dict[str, Any]:
        return {
            "enabled": self.enabled,
            "available": self.model is not None,
            "model": MODEL_NAME,
            "requestedDevice": self.requested_device,
            "actualDevice": self.actual_device,
            "engine": self.engine,
            "fallback": self.fallback,
            "fallbackReason": self.fallback_reason,
            "errorCode": self.error_code,
        }

    def transcribe(self, path: Path) -> dict[str, Any]:
        if self.model is None:
            raise RuntimeError("stt_unavailable")
        with self._lock:
            segments, info = self.model.transcribe(
                str(path),
                language="ko",
                beam_size=1,
                vad_filter=True,
                condition_on_previous_text=False,
            )
            text = " ".join(str(segment.text).strip() for segment in segments).strip()
            duration_ms = max(0, int(round(float(getattr(info, "duration", 0.0)) * 1000)))
        if duration_ms > 30_500:
            raise OverflowError("audio_too_large")
        if not text:
            raise ValueError("no_speech")
        return {
            "text": text,
            "language": "ko",
            "durationMs": duration_ms,
            "model": MODEL_NAME,
        }


runtime = WhisperRuntime()


@asynccontextmanager
async def lifespan(_: FastAPI):
    runtime.tmpfs_root.mkdir(parents=True, exist_ok=True)
    await asyncio.to_thread(runtime.load)
    yield


app = FastAPI(
    title="NeuroTruth internal STT",
    docs_url=None,
    redoc_url=None,
    lifespan=lifespan,
)


@app.get("/health")
async def health() -> dict[str, Any]:
    return runtime.status()


@app.post("/transcribe")
async def transcribe(
    audio: UploadFile = File(),
    language: str = Form(default="ko"),
) -> dict[str, Any]:
    if not runtime.enabled or runtime.model is None:
        raise HTTPException(status_code=503, detail="stt_unavailable")
    suffix = Path(audio.filename or "").suffix.lower()
    if language != "ko" or audio.content_type not in SUPPORTED_CONTENT_TYPES or suffix not in {".m4a", ".wav"}:
        raise HTTPException(status_code=415, detail="unsupported_audio")
    path: Path | None = None
    try:
        descriptor, name = tempfile.mkstemp(prefix="nt-stt-", suffix=suffix, dir=runtime.tmpfs_root)
        os.close(descriptor)
        path = Path(name)
        size = 0
        with path.open("wb") as handle:
            while chunk := await audio.read(1024 * 1024):
                size += len(chunk)
                if size > runtime.max_upload_bytes:
                    raise HTTPException(status_code=413, detail="audio_too_large")
                handle.write(chunk)
        if size == 0:
            raise HTTPException(status_code=422, detail="no_speech")
        try:
            return await asyncio.to_thread(runtime.transcribe, path)
        except OverflowError as exc:
            raise HTTPException(status_code=413, detail="audio_too_large") from exc
        except ValueError as exc:
            raise HTTPException(status_code=422, detail="no_speech") from exc
        except RuntimeError as exc:
            raise HTTPException(status_code=503, detail="stt_unavailable") from exc
    finally:
        await audio.close()
        if path is not None:
            path.unlink(missing_ok=True)
