from __future__ import annotations

import os
import tempfile
from pathlib import Path
from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile

from app.adapters.stt_client import SttError
from app.api.v1.dependencies import get_runtime, patient_user
from app.core.runtime import BackendRuntime
from app.models.records import UserRecord
from app.schemas.stt import SttLanguage
from app.services.auth import AuthorizationError
from app.services.session import SessionNotFound


router = APIRouter()
SUPPORTED_AUDIO = {
    ".m4a": {"audio/mp4", "audio/m4a", "audio/x-m4a"},
    ".wav": {"audio/wav", "audio/x-wav", "audio/wave"},
}


@router.get("/api/stt/status")
async def status(
    runtime: Annotated[BackendRuntime, Depends(get_runtime)],
    _: Annotated[UserRecord, Depends(patient_user)],
) -> dict[str, Any]:
    if runtime.stt_service is None:
        return {
            "enabled": False,
            "available": False,
            "model": "whisper-large-v3-turbo",
            "requestedDevice": None,
            "actualDevice": None,
            "engine": None,
            "fallback": False,
            "fallbackReason": None,
            "errorCode": "stt_unavailable",
        }
    return await runtime.stt_service.status()


@router.post("/api/sessions/{session_id}/transcriptions")
async def transcribe(
    session_id: UUID,
    audio: Annotated[UploadFile, File()],
    runtime: Annotated[BackendRuntime, Depends(get_runtime)],
    user: Annotated[UserRecord, Depends(patient_user)],
    language: Annotated[SttLanguage, Form()] = "ko",
) -> dict[str, Any]:
    stt_service = runtime.stt_service
    if stt_service is None or not stt_service.enabled:
        raise HTTPException(status_code=503, detail="stt_unavailable")
    try:
        await runtime.service.require_consent(user.id, "voice")
    except AuthorizationError as exc:
        raise HTTPException(status_code=403, detail="voice_consent_required") from exc
    try:
        session = await runtime.session_service.get(user, session_id)
    except SessionNotFound as exc:
        raise HTTPException(status_code=404, detail="session_not_found") from exc
    if session.get("status") not in {"created", "in_progress"}:
        raise HTTPException(status_code=409, detail="session_not_active")
    suffix = Path(audio.filename or "").suffix.lower()
    if suffix not in SUPPORTED_AUDIO or audio.content_type not in SUPPORTED_AUDIO[suffix]:
        raise HTTPException(status_code=415, detail="unsupported_audio")

    path: Path | None = None
    try:
        stt_service.tmpfs_root.mkdir(parents=True, exist_ok=True)
        descriptor, name = tempfile.mkstemp(prefix="nt-stt-", suffix=suffix, dir=stt_service.tmpfs_root)
        os.close(descriptor)
        path = Path(name)
        size = 0
        with path.open("wb") as handle:
            while chunk := await audio.read(1024 * 1024):
                size += len(chunk)
                if size > stt_service.max_upload_bytes:
                    raise HTTPException(status_code=413, detail="audio_too_large")
                handle.write(chunk)
        if size == 0:
            raise HTTPException(status_code=422, detail="no_speech")
        try:
            return await stt_service.transcribe(path, content_type=audio.content_type)
        except SttError as exc:
            raise HTTPException(status_code=exc.status_code, detail=exc.code) from exc
    except HTTPException:
        raise
    except OSError as exc:
        raise HTTPException(status_code=503, detail="stt_unavailable") from exc
    finally:
        await audio.close()
        if path is not None:
            path.unlink(missing_ok=True)
