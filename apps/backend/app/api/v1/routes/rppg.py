from __future__ import annotations

import asyncio
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import Response
from app.adapters.rppg_media import InvalidRppgMedia
from app.api.v1.dependencies import get_runtime, password_ready_user, patient_user
from app.api.v1.routes.admin import admin_user
from app.core.runtime import BackendRuntime
from app.models.records import UserRecord
from app.repositories.rppg import RppgConflict
from app.schemas.rppg import DeleteCaptureBody, RevealVideoBody
from app.services.rppg import RppgConsentRequired, RppgJobNotFound, RppgService, RppgUnavailable


router = APIRouter()


def get_rppg_service(runtime: Annotated[BackendRuntime, Depends(get_runtime)]) -> RppgService:
    if runtime.rppg_service is None:
        raise HTTPException(status_code=503, detail="RPPG_UNAVAILABLE")
    return runtime.rppg_service


@router.get("/api/rppg/status")
async def status(_: Annotated[UserRecord, Depends(password_ready_user)],
                 service: Annotated[RppgService, Depends(get_rppg_service)]) -> dict[str, Any]:
    return await service.status()


@router.post("/api/rppg/jobs", status_code=202)
async def create_job(
    runtime: Annotated[BackendRuntime, Depends(get_runtime)],
    user: Annotated[UserRecord, Depends(patient_user)],
    service: Annotated[RppgService, Depends(get_rppg_service)],
    video: Annotated[UploadFile, File()],
    client_capture_id: Annotated[UUID, Form(alias="clientCaptureId")],
    captured_at_ms: Annotated[int, Form(alias="capturedAtMs", gt=0)],
    duration_ms: Annotated[int, Form(alias="durationMs", ge=19500, le=20500)],
    session_id: Annotated[UUID | None, Form(alias="sessionId")] = None,
) -> dict[str, Any]:
    if not service.enabled:
        raise HTTPException(status_code=503, detail="RPPG_DISABLED")
    try:
        consent = await runtime.repository.current_consent(user.id)
    except Exception as exc:
        raise HTTPException(status_code=503, detail="RPPG_QUEUE_UNAVAILABLE") from exc
    try:
        service._require_enabled_and_consent(consent)
    except RppgConsentRequired as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    if video.content_type != "video/mp4" or Path(video.filename or "").suffix.lower() != ".mp4":
        raise HTTPException(status_code=415, detail="RPPG_MP4_REQUIRED")
    try:
        datetime.fromtimestamp(captured_at_ms / 1000, tz=timezone.utc)
    except (OSError, OverflowError, ValueError) as exc:
        raise HTTPException(status_code=422, detail="RPPG_INVALID_CAPTURE_TIME") from exc
    fd: int | None = None
    path: Path | None = None
    size = 0
    try:
        root = service.storage.tmpfs_root
        root.mkdir(parents=True, exist_ok=True)
        fd, name = tempfile.mkstemp(prefix="nt-upload-", suffix=".mp4", dir=root)
        path = Path(name)
        os.chmod(path, 0o600)
        with os.fdopen(fd, "wb") as stream:
            while chunk := await video.read(1024 * 1024):
                size += len(chunk)
                if size > runtime.settings.rppg_max_upload_mib * 1024 * 1024:
                    raise HTTPException(status_code=413, detail="RPPG_UPLOAD_TOO_LARGE")
                stream.write(chunk)
        if size == 0:
            raise HTTPException(status_code=422, detail="RPPG_EMPTY_VIDEO")
        inspection = await asyncio.to_thread(service.inspector.inspect, path)
        if abs(inspection.duration_seconds * 1000 - duration_ms) > 750:
            raise HTTPException(status_code=422, detail="RPPG_DURATION_MISMATCH")
        return await service.accept(
            patient_id=user.id, consent=consent, client_capture_id=client_capture_id,
            captured_at_ms=captured_at_ms, duration_ms=duration_ms, session_id=session_id,
            plaintext_path=path, inspection=inspection,
        )
    except InvalidRppgMedia as exc:
        raise HTTPException(status_code=422, detail="RPPG_INVALID_VIDEO") from exc
    except OSError as exc:
        raise HTTPException(status_code=503, detail="RPPG_STORAGE_UNAVAILABLE") from exc
    except RppgUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except RppgConflict as exc:
        code = str(exc)
        status_code = 403 if code == "RPPG_SESSION_NOT_OWNED" else 409
        raise HTTPException(status_code=status_code, detail=code) from exc
    finally:
        cleanup_error: Exception | None = None
        try:
            await video.close()
        except Exception as exc:
            cleanup_error = exc
        if fd is not None:
            try: os.close(fd)
            except OSError:
                pass
        if path is not None:
            try:
                path.unlink(missing_ok=True)
            except OSError as exc:
                cleanup_error = cleanup_error or exc
        if cleanup_error is not None:
            raise HTTPException(status_code=503, detail="RPPG_STORAGE_UNAVAILABLE") from cleanup_error


@router.get("/api/rppg/jobs/{job_id}")
async def get_job(job_id: UUID, user: Annotated[UserRecord, Depends(patient_user)],
                  service: Annotated[RppgService, Depends(get_rppg_service)]) -> dict[str, Any]:
    try: return await service.job(user.id, job_id)
    except RppgJobNotFound as exc: raise HTTPException(status_code=404, detail=str(exc)) from exc


@router.post("/api/rppg/jobs/{job_id}/retry", status_code=202)
async def retry_job(job_id: UUID, runtime: Annotated[BackendRuntime, Depends(get_runtime)],
                    user: Annotated[UserRecord, Depends(patient_user)],
                    service: Annotated[RppgService, Depends(get_rppg_service)]) -> dict[str, Any]:
    consent = await runtime.repository.current_consent(user.id)
    try: return await service.retry(user.id, consent, job_id)
    except RppgJobNotFound as exc: raise HTTPException(status_code=404, detail=str(exc)) from exc
    except RppgConsentRequired as exc: raise HTTPException(status_code=403, detail=str(exc)) from exc
    except RppgUnavailable as exc: raise HTTPException(status_code=503, detail=str(exc)) from exc
    except RppgConflict as exc: raise HTTPException(status_code=409, detail=str(exc)) from exc


@router.get("/api/admin/rppg/captures")
async def admin_captures(runtime: Annotated[BackendRuntime, Depends(get_runtime)],
                         _: Annotated[UserRecord, Depends(admin_user)],
                         limit: int = Query(default=50, ge=1, le=200),
                         offset: int = Query(default=0, ge=0)) -> list[dict[str, Any]]:
    rows = await runtime.rppg_repository.list_captures(limit=limit, offset=offset)
    mapping = {
        "id": "captureId", "patient_id": "patientId", "captured_at": "capturedAt",
        "duration_ms": "durationMs", "byte_size": "byteSize", "deletion_state": "deletionState",
        "job_id": "jobId", "quality_score": "qualityScore", "heart_rate_bpm": "heartRateBpm",
        "model_name": "modelName", "inference_device": "inferenceDevice",
        "processing_ms": "processingMs", "failure_code": "failureCode",
    }
    return [{mapping.get(key, key): value.isoformat() if hasattr(value, "isoformat") else str(value) if isinstance(value, UUID) else float(value) if key in {"quality_score", "heart_rate_bpm"} and value is not None else value
             for key, value in row.items()} for row in rows]


@router.post("/api/admin/rppg/captures/{capture_id}/reveal-video")
async def reveal_video(capture_id: UUID, body: RevealVideoBody,
                       runtime: Annotated[BackendRuntime, Depends(get_runtime)],
                       admin: Annotated[UserRecord, Depends(admin_user)]) -> Response:
    await runtime.repository.audit(actor_id=admin.id, actor_role="admin", action="admin.rppg_video_reveal_attempt",
                                   resource_type="rppg_capture", resource_id=capture_id,
                                   metadata={"reason": body.reason})
    row = await runtime.rppg_repository.capture(capture_id)
    if row is None:
        await runtime.repository.audit(actor_id=admin.id, actor_role="admin", action="admin.rppg_video_reveal",
                                       resource_type="rppg_capture", resource_id=capture_id,
                                       metadata={"reason": body.reason, "outcome": "not_found"})
        raise HTTPException(status_code=404, detail="RPPG_CAPTURE_NOT_FOUND")
    try:
        raw = runtime.rppg_storage.read(patient_id=row["patient_id"], capture_id=capture_id,
                                        relative_path=row["storage_uri"])
        await runtime.repository.audit(actor_id=admin.id, actor_role="admin", action="admin.rppg_video_reveal",
                                       resource_type="rppg_capture", resource_id=capture_id,
                                       metadata={"reason": body.reason, "outcome": "success"})
    except Exception as exc:
        await runtime.repository.audit(actor_id=admin.id, actor_role="admin", action="admin.rppg_video_reveal",
                                       resource_type="rppg_capture", resource_id=capture_id,
                                       metadata={"reason": body.reason, "outcome": "failed"})
        raise HTTPException(status_code=503, detail="RPPG_VIDEO_UNAVAILABLE") from exc
    return Response(raw, media_type="video/mp4", headers={
        "Cache-Control": "no-store", "Content-Disposition": f'inline; filename="{capture_id}.mp4"',
        "Pragma": "no-cache", "X-Content-Type-Options": "nosniff",
    })


@router.delete("/api/admin/rppg/captures/{capture_id}", status_code=204)
async def delete_capture(capture_id: UUID, body: DeleteCaptureBody,
                         runtime: Annotated[BackendRuntime, Depends(get_runtime)],
                         admin: Annotated[UserRecord, Depends(admin_user)]) -> Response:
    await runtime.repository.audit(actor_id=admin.id, actor_role="admin", action="admin.rppg_capture_delete_attempt",
                                   resource_type="rppg_capture", resource_id=capture_id,
                                   metadata={"reason": body.reason})
    if body.confirmCaptureId != capture_id:
        await runtime.repository.audit(actor_id=admin.id, actor_role="admin", action="admin.rppg_capture_delete_failed",
                                       resource_type="rppg_capture", resource_id=capture_id,
                                       metadata={"reason": body.reason, "outcome": "confirmation_mismatch"})
        raise HTTPException(status_code=409, detail="RPPG_DELETE_CONFIRMATION_MISMATCH")
    row = await runtime.rppg_repository.mark_deleting(capture_id)
    if row is None: raise HTTPException(status_code=404, detail="RPPG_CAPTURE_NOT_FOUND")
    try:
        runtime.rppg_storage.delete(row["storage_uri"])
        await runtime.rppg_repository.delete_capture_rows(capture_id)
    except Exception as exc:
        await runtime.rppg_repository.mark_delete_failed(capture_id)
        await runtime.repository.audit(actor_id=admin.id, actor_role="admin", action="admin.rppg_capture_delete_failed",
                                       resource_type="rppg_capture", resource_id=capture_id,
                                       metadata={"reason": body.reason, "outcome": "retryable"})
        raise HTTPException(status_code=503, detail="RPPG_DELETE_PENDING_RETRY") from exc
    await runtime.repository.audit(actor_id=admin.id, actor_role="admin", action="admin.rppg_capture_deleted",
                                   resource_type="rppg_capture", resource_id=capture_id,
                                   metadata={"reason": body.reason, "outcome": "success"})
    return Response(status_code=204)
