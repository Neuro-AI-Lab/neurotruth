from __future__ import annotations

from datetime import date
from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Response

from app.api.v1.dependencies import get_runtime, patient_user
from app.core.runtime import BackendRuntime
from app.models.records import UserRecord
from app.schemas.dashboard import (
    DashboardAuqRange,
    DashboardCalendarView,
    DashboardEventRange,
)
from app.services.dashboard import (
    DashboardRangeError,
    DashboardTimezoneError,
    DashboardUnavailable,
    PpgPreviewNotFound,
)


router = APIRouter(prefix="/api/me")


@router.get("/dashboard")
async def dashboard(
    runtime: Annotated[BackendRuntime, Depends(get_runtime)],
    user: Annotated[UserRecord, Depends(patient_user)],
    range: str = "24h",
) -> dict[str, Any]:
    try:
        return await runtime.dashboard_service.dashboard(user.id, range)
    except DashboardRangeError as exc:
        raise HTTPException(status_code=422, detail={"code": "invalid_dashboard_range", "message": "Unsupported dashboard range"}) from exc
    except DashboardUnavailable as exc:
        raise HTTPException(status_code=503, detail={"code": "dashboard_unavailable", "message": "Dashboard data is unavailable"}) from exc


@router.get("/craving-probability-series")
async def craving_probability_series(
    runtime: Annotated[BackendRuntime, Depends(get_runtime)],
    user: Annotated[UserRecord, Depends(patient_user)],
    range: str = "10m",
) -> dict[str, Any]:
    try:
        return await runtime.dashboard_service.craving_probability_series(user.id, range)
    except DashboardRangeError as exc:
        raise HTTPException(
            status_code=422,
            detail={"code": "invalid_probability_range", "message": "Unsupported probability range"},
        ) from exc
    except DashboardUnavailable as exc:
        raise HTTPException(
            status_code=503,
            detail={"code": "dashboard_unavailable", "message": "Dashboard data is unavailable"},
        ) from exc


@router.get("/craving-dashboard")
async def craving_dashboard(
    runtime: Annotated[BackendRuntime, Depends(get_runtime)],
    user: Annotated[UserRecord, Depends(patient_user)],
    timezone: str,
    eventRange: DashboardEventRange = "7d",
    auqRange: DashboardAuqRange = "today",
) -> dict[str, Any]:
    try:
        return await runtime.dashboard_service.craving_dashboard(
            user.id, timezone, eventRange, auqRange,
        )
    except DashboardTimezoneError as exc:
        raise HTTPException(
            status_code=422,
            detail={"code": "invalid_timezone", "message": "Invalid IANA timezone"},
        ) from exc
    except DashboardRangeError as exc:
        raise HTTPException(
            status_code=422,
            detail={"code": "invalid_dashboard_range", "message": "Unsupported dashboard range"},
        ) from exc
    except DashboardUnavailable as exc:
        raise HTTPException(
            status_code=503,
            detail={"code": "dashboard_unavailable", "message": "Dashboard data is unavailable"},
        ) from exc


@router.get("/craving-calendar")
async def craving_calendar(
    runtime: Annotated[BackendRuntime, Depends(get_runtime)],
    user: Annotated[UserRecord, Depends(patient_user)],
    timezone: str,
    view: DashboardCalendarView,
    anchor: date,
) -> dict[str, Any]:
    try:
        return await runtime.dashboard_service.craving_calendar(
            user.id, timezone, view, anchor,
        )
    except DashboardTimezoneError as exc:
        raise HTTPException(
            status_code=422,
            detail={"code": "invalid_timezone", "message": "Invalid IANA timezone"},
        ) from exc
    except DashboardRangeError as exc:
        raise HTTPException(
            status_code=422,
            detail={"code": "invalid_dashboard_range", "message": "Unsupported calendar view"},
        ) from exc
    except DashboardUnavailable as exc:
        raise HTTPException(
            status_code=503,
            detail={"code": "dashboard_unavailable", "message": "Dashboard data is unavailable"},
        ) from exc


@router.get("/predictions/{prediction_id}/ppg-preview")
async def ppg_preview(
    prediction_id: UUID,
    runtime: Annotated[BackendRuntime, Depends(get_runtime)],
    user: Annotated[UserRecord, Depends(patient_user)],
    response: Response,
) -> dict[str, Any]:
    response.headers["Cache-Control"] = "no-store"
    try:
        return await runtime.dashboard_service.ppg_preview(user.id, prediction_id)
    except PpgPreviewNotFound as exc:
        raise HTTPException(status_code=404, detail={"code": "ppg_preview_not_found", "message": "PPG preview not found"}) from exc
    except DashboardUnavailable as exc:
        raise HTTPException(status_code=503, detail={"code": "ppg_preview_unavailable", "message": "PPG preview is unavailable"}) from exc
