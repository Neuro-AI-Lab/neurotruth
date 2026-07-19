from __future__ import annotations

from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Response
from app.api.v1.dependencies import get_runtime, password_ready_user
from app.core.runtime import BackendRuntime
from app.models.records import UserRecord
from app.schemas.admin import DeleteBody, ReasonBody, SettingsPatch, TemporaryPasswordBody
from app.services.admin import AdminDeletionUnavailable, AdminNotFound, AdminOperationError
from app.services.dashboard import DashboardRangeError, DashboardUnavailable


router = APIRouter(prefix="/api/admin")


async def admin_user(user: Annotated[UserRecord, Depends(password_ready_user)]) -> UserRecord:
    if user.role != "admin": raise HTTPException(status_code=403, detail="Administrator role required")
    return user


def _map(exc: Exception) -> None:
    if isinstance(exc, AdminNotFound): raise HTTPException(status_code=404, detail=str(exc)) from exc
    if isinstance(exc, AdminDeletionUnavailable): raise HTTPException(status_code=503, detail=str(exc)) from exc
    if isinstance(exc, AdminOperationError): raise HTTPException(status_code=409, detail=str(exc)) from exc
    raise exc


@router.get("/patients")
async def patients(runtime: Annotated[BackendRuntime, Depends(get_runtime)], _: Annotated[UserRecord, Depends(admin_user)]) -> list[dict[str, Any]]:
    return await runtime.admin_service.patients()


@router.get("/patients/{patient_id}/timeline")
async def timeline(patient_id: UUID, runtime: Annotated[BackendRuntime, Depends(get_runtime)], _: Annotated[UserRecord, Depends(admin_user)]) -> list[dict[str, Any]]:
    return await runtime.admin_service.timeline(patient_id)


@router.get("/patients/{patient_id}/dashboard")
async def patient_dashboard(patient_id: UUID, runtime: Annotated[BackendRuntime, Depends(get_runtime)],
                            _: Annotated[UserRecord, Depends(admin_user)], range: str = "24h") -> dict[str, Any]:
    try:
        return await runtime.admin_service.dashboard(patient_id, range)
    except DashboardRangeError as exc:
        raise HTTPException(status_code=422, detail={"code": "invalid_dashboard_range", "message": "Unsupported dashboard range"}) from exc
    except (DashboardUnavailable, AdminOperationError) as exc:
        raise HTTPException(status_code=503, detail={"code": "dashboard_unavailable", "message": "Dashboard data is unavailable"}) from exc


@router.post("/resources/{resource_type}/{resource_id}/reveal")
async def reveal(resource_type: str, resource_id: UUID, body: ReasonBody,
                 runtime: Annotated[BackendRuntime, Depends(get_runtime)], admin: Annotated[UserRecord, Depends(admin_user)],
                 response: Response) -> dict[str, Any]:
    response.headers["Cache-Control"] = "no-store"
    try: return await runtime.admin_service.reveal(admin, resource_type, resource_id, body.reason.strip())
    except AdminOperationError as exc: _map(exc)


@router.post("/patients/{patient_id}/temporary-password", status_code=204, response_class=Response)
async def temporary_password(patient_id: UUID, body: TemporaryPasswordBody,
                             runtime: Annotated[BackendRuntime, Depends(get_runtime)], admin: Annotated[UserRecord, Depends(admin_user)]) -> Response:
    try: await runtime.admin_service.temporary_password(admin, patient_id, body.temporaryPassword, body.reason.strip())
    except AdminOperationError as exc: _map(exc)
    return Response(status_code=204)


@router.delete("/patients/{patient_id}", status_code=204, response_class=Response)
async def delete_patient(patient_id: UUID, body: DeleteBody,
                         runtime: Annotated[BackendRuntime, Depends(get_runtime)], admin: Annotated[UserRecord, Depends(admin_user)]) -> Response:
    try: await runtime.admin_service.delete_patient(admin, patient_id, body.confirmation, body.reason.strip())
    except AdminOperationError as exc: _map(exc)
    return Response(status_code=204)


@router.get("/settings")
async def get_settings(runtime: Annotated[BackendRuntime, Depends(get_runtime)], _: Annotated[UserRecord, Depends(admin_user)]) -> dict[str, Any]:
    try: return await runtime.admin_service.settings()
    except AdminOperationError as exc: _map(exc)


@router.patch("/settings")
async def patch_settings(body: SettingsPatch, runtime: Annotated[BackendRuntime, Depends(get_runtime)], admin: Annotated[UserRecord, Depends(admin_user)]) -> dict[str, Any]:
    try:
        return await runtime.admin_service.update_settings(
            admin, interventions_enabled=body.interventionsEnabled,
            chat_timeout_seconds=body.chatTimeoutSeconds,
            admin_signup_code=body.adminSignupCode,
        )
    except AdminOperationError as exc: _map(exc)
