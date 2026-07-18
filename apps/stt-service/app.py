from __future__ import annotations

import asyncio
import os
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path
from threading import Lock
from typing import Any

from fastapi import FastAPI, File, Form, HTTPException, UploadFile


MODEL_NAME = "whisper-large-v3-turbo"
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


class WhisperRuntime:
    def __init__(self) -> None:
        self.enabled = _enabled(os.getenv("STT_ENABLED", "false"))
        self.model_path = Path(os.getenv("STT_MODEL_PATH", "/models/large-v3-turbo"))
        self.requested_device = os.getenv("STT_DEVICE", "auto").strip().lower()
        self.tmpfs_root = Path(os.getenv("STT_TMPFS_ROOT", "/dev/shm/neurotruth-stt"))
        self.max_upload_bytes = int(os.getenv("STT_MAX_UPLOAD_MIB", "10")) * 1024 * 1024
        self.model: Any | None = None
        self.actual_device: str | None = None
        self.fallback = False
        self.error_code: str | None = "stt_disabled" if not self.enabled else "stt_not_loaded"
        self._lock = Lock()

    def load(self) -> None:
        if not self.enabled:
            return
        if not self.model_path.is_dir():
            self.error_code = "stt_model_missing"
            return
        from faster_whisper import WhisperModel

        attempts = (
            (("cuda", "float16"), ("cpu", "int8"))
            if self.requested_device == "auto"
            else ((self.requested_device, "float16" if self.requested_device == "cuda" else "int8"),)
        )
        for index, (device, compute_type) in enumerate(attempts):
            try:
                self.model = WhisperModel(
                    str(self.model_path),
                    device=device,
                    compute_type=compute_type,
                    local_files_only=True,
                )
                self.actual_device = device
                self.fallback = index > 0
                self.error_code = None
                return
            except Exception:
                self.model = None
        self.error_code = "stt_model_load_failed"

    def status(self) -> dict[str, Any]:
        return {
            "enabled": self.enabled,
            "available": self.model is not None,
            "model": MODEL_NAME,
            "requestedDevice": self.requested_device,
            "actualDevice": self.actual_device,
            "fallback": self.fallback,
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
