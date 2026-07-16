from __future__ import annotations

from typing import Annotated, Any, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Response

from .dashboard_service import DashboardRangeError, DashboardUnavailable, PpgPreviewNotFound
from .dependencies import get_runtime, patient_user
from .models import UserRecord
from .runtime import V25Runtime


router = APIRouter(prefix="/api/me")


@router.get("/dashboard")
async def dashboard(
    runtime: Annotated[V25Runtime, Depends(get_runtime)],
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
    runtime: Annotated[V25Runtime, Depends(get_runtime)],
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


@router.get("/predictions/{prediction_id}/ppg-preview")
async def ppg_preview(
    prediction_id: UUID,
    runtime: Annotated[V25Runtime, Depends(get_runtime)],
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
