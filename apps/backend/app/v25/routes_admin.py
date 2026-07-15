from __future__ import annotations

from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator

from .admin_service import AdminDeletionUnavailable, AdminNotFound, AdminOperationError
from .dashboard_service import DashboardRangeError, DashboardUnavailable
from .dependencies import get_runtime, password_ready_user
from .models import UserRecord
from .runtime import V25Runtime


router = APIRouter(prefix="/api/admin")


async def admin_user(user: Annotated[UserRecord, Depends(password_ready_user)]) -> UserRecord:
    if user.role != "admin": raise HTTPException(status_code=403, detail="Administrator role required")
    return user


class ReasonBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    reason: str = Field(min_length=1, max_length=1000)

    @field_validator("reason")
    @classmethod
    def non_blank_reason(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("Reason is required")
        return value


class TemporaryPasswordBody(ReasonBody):
    temporaryPassword: str = Field(min_length=12, max_length=1024)


class DeleteBody(ReasonBody):
    confirmation: str


class SettingsPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    interventionsEnabled: bool | None = None
    chatTimeoutSeconds: int | None = Field(default=None, ge=60, le=86400)
    adminSignupCode: str | None = Field(default=None, min_length=16, max_length=1024)


def _map(exc: Exception) -> None:
    if isinstance(exc, AdminNotFound): raise HTTPException(status_code=404, detail=str(exc)) from exc
    if isinstance(exc, AdminDeletionUnavailable): raise HTTPException(status_code=503, detail=str(exc)) from exc
    if isinstance(exc, AdminOperationError): raise HTTPException(status_code=409, detail=str(exc)) from exc
    raise exc


@router.get("/patients")
async def patients(runtime: Annotated[V25Runtime, Depends(get_runtime)], _: Annotated[UserRecord, Depends(admin_user)]) -> list[dict[str, Any]]:
    return await runtime.admin_service.patients()


@router.get("/patients/{patient_id}/timeline")
async def timeline(patient_id: UUID, runtime: Annotated[V25Runtime, Depends(get_runtime)], _: Annotated[UserRecord, Depends(admin_user)]) -> list[dict[str, Any]]:
    return await runtime.admin_service.timeline(patient_id)


@router.get("/patients/{patient_id}/dashboard")
async def patient_dashboard(patient_id: UUID, runtime: Annotated[V25Runtime, Depends(get_runtime)],
                            _: Annotated[UserRecord, Depends(admin_user)], range: str = "24h") -> dict[str, Any]:
    try:
        return await runtime.admin_service.dashboard(patient_id, range)
    except DashboardRangeError as exc:
        raise HTTPException(status_code=422, detail={"code": "invalid_dashboard_range", "message": "Unsupported dashboard range"}) from exc
    except (DashboardUnavailable, AdminOperationError) as exc:
        raise HTTPException(status_code=503, detail={"code": "dashboard_unavailable", "message": "Dashboard data is unavailable"}) from exc


@router.post("/resources/{resource_type}/{resource_id}/reveal")
async def reveal(resource_type: str, resource_id: UUID, body: ReasonBody,
                 runtime: Annotated[V25Runtime, Depends(get_runtime)], admin: Annotated[UserRecord, Depends(admin_user)],
                 response: Response) -> dict[str, Any]:
    response.headers["Cache-Control"] = "no-store"
    try: return await runtime.admin_service.reveal(admin, resource_type, resource_id, body.reason.strip())
    except AdminOperationError as exc: _map(exc)


@router.post("/patients/{patient_id}/temporary-password", status_code=204, response_class=Response)
async def temporary_password(patient_id: UUID, body: TemporaryPasswordBody,
                             runtime: Annotated[V25Runtime, Depends(get_runtime)], admin: Annotated[UserRecord, Depends(admin_user)]) -> Response:
    try: await runtime.admin_service.temporary_password(admin, patient_id, body.temporaryPassword, body.reason.strip())
    except AdminOperationError as exc: _map(exc)
    return Response(status_code=204)


@router.delete("/patients/{patient_id}", status_code=204, response_class=Response)
async def delete_patient(patient_id: UUID, body: DeleteBody,
                         runtime: Annotated[V25Runtime, Depends(get_runtime)], admin: Annotated[UserRecord, Depends(admin_user)]) -> Response:
    try: await runtime.admin_service.delete_patient(admin, patient_id, body.confirmation, body.reason.strip())
    except AdminOperationError as exc: _map(exc)
    return Response(status_code=204)


@router.get("/settings")
async def get_settings(runtime: Annotated[V25Runtime, Depends(get_runtime)], _: Annotated[UserRecord, Depends(admin_user)]) -> dict[str, Any]:
    try: return await runtime.admin_service.settings()
    except AdminOperationError as exc: _map(exc)


@router.patch("/settings")
async def patch_settings(body: SettingsPatch, runtime: Annotated[V25Runtime, Depends(get_runtime)], admin: Annotated[UserRecord, Depends(admin_user)]) -> dict[str, Any]:
    try:
        return await runtime.admin_service.update_settings(
            admin, interventions_enabled=body.interventionsEnabled,
            chat_timeout_seconds=body.chatTimeoutSeconds,
            admin_signup_code=body.adminSignupCode,
        )
    except AdminOperationError as exc: _map(exc)
