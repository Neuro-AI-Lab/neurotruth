from __future__ import annotations

from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel, ConfigDict, Field

from .auth_service import AuthenticationError, AuthorizationError
from .dependencies import current_user, get_runtime, password_ready_user, patient_user
from .models import (
    AdminSignupInput,
    ChangePasswordInput,
    ConsentInput,
    LoginInput,
    PatientSignupInput,
    UserRecord,
)
from .repository import RepositoryConflictError
from .runtime import V25Runtime


router = APIRouter()


class RefreshBody(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    refresh_token: str = Field(alias="refreshToken", min_length=1)
    device: dict[str, Any] = Field(default_factory=dict)


class LogoutBody(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    refresh_token: str = Field(alias="refreshToken", min_length=1)


class MePatchBody(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    name: str | None = Field(default=None, max_length=200)
    birth_year: int | None = Field(default=None, alias="birthYear", ge=1900, le=2100)
    gender: str | None = None


def _raise_service_error(exc: Exception) -> None:
    if isinstance(exc, RepositoryConflictError):
        raise HTTPException(status_code=409, detail="Account already exists") from exc
    if isinstance(exc, AuthenticationError):
        raise HTTPException(status_code=401, detail=str(exc)) from exc
    if isinstance(exc, AuthorizationError):
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    raise exc


@router.post("/api/auth/patient/signup")
async def patient_signup(body: PatientSignupInput, runtime: Annotated[V25Runtime, Depends(get_runtime)]) -> dict[str, Any]:
    try:
        return await runtime.service.patient_signup(body)
    except (RepositoryConflictError, AuthenticationError, AuthorizationError) as exc:
        _raise_service_error(exc)


@router.post("/api/auth/admin/signup")
async def admin_signup(body: AdminSignupInput, runtime: Annotated[V25Runtime, Depends(get_runtime)]) -> dict[str, Any]:
    try:
        return await runtime.service.admin_signup(body)
    except (RepositoryConflictError, AuthenticationError, AuthorizationError) as exc:
        _raise_service_error(exc)


@router.post("/api/auth/login")
async def login(body: LoginInput, runtime: Annotated[V25Runtime, Depends(get_runtime)]) -> dict[str, Any]:
    try:
        return await runtime.service.login(body)
    except AuthenticationError as exc:
        _raise_service_error(exc)


@router.post("/api/auth/refresh")
async def refresh(body: RefreshBody, runtime: Annotated[V25Runtime, Depends(get_runtime)]) -> dict[str, Any]:
    try:
        return await runtime.service.refresh(body.refresh_token, body.device)
    except AuthenticationError as exc:
        _raise_service_error(exc)


@router.post("/api/auth/logout", status_code=204, response_class=Response)
async def logout(
    body: LogoutBody,
    runtime: Annotated[V25Runtime, Depends(get_runtime)],
) -> Response:
    await runtime.service.logout(body.refresh_token)
    return Response(status_code=204)


@router.post("/api/auth/change-password", status_code=204, response_class=Response)
async def change_password(
    body: ChangePasswordInput,
    runtime: Annotated[V25Runtime, Depends(get_runtime)],
    user: Annotated[UserRecord, Depends(current_user)],
) -> Response:
    try:
        await runtime.service.change_password(user.id, body)
    except AuthenticationError as exc:
        _raise_service_error(exc)
    return Response(status_code=204)


@router.get("/api/me")
async def me(
    runtime: Annotated[V25Runtime, Depends(get_runtime)],
    user: Annotated[UserRecord, Depends(password_ready_user)],
) -> dict[str, Any]:
    return {
        "user": runtime.service._public_user(user),
        "consent": await runtime.service.current_consent(user.id) if user.role == "patient" else None,
    }


@router.patch("/api/me")
async def patch_me(
    body: MePatchBody,
    runtime: Annotated[V25Runtime, Depends(get_runtime)],
    user: Annotated[UserRecord, Depends(password_ready_user)],
) -> dict[str, Any]:
    if user.role != "patient":
        raise HTTPException(status_code=403, detail="Patient role required")
    changes = body.model_dump(exclude_unset=True, by_alias=False)
    if changes:
        if runtime.admin_service is None:
            raise HTTPException(status_code=501, detail="Profile updates are not available yet")
        await runtime.admin_service.update_own_profile(user, changes)
    return {"ok": True}


@router.post("/api/me/consents", status_code=201)
async def append_consent(
    body: ConsentInput,
    runtime: Annotated[V25Runtime, Depends(get_runtime)],
    user: Annotated[UserRecord, Depends(patient_user)],
) -> dict[str, Any]:
    try:
        return await runtime.service.append_consent(user.id, body)
    except AuthorizationError as exc:
        _raise_service_error(exc)
