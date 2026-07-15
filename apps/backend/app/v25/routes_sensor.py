from __future__ import annotations

import asyncio
import json
import os
from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from .dependencies import get_runtime, patient_user
from .models import UserRecord
from .runtime import V25Runtime
from .sensor_service import SensorModelUnavailable, SensorPayloadConflict, SensorService


router = APIRouter()
SSE_KEEPALIVE_SECONDS = float(os.getenv("SSE_KEEPALIVE_SECONDS", "10"))


class SensorSample(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sensor: str
    timestampMs: int
    value: float


class SensorSync(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: str = Field(min_length=1, max_length=128)
    fillMode: str = Field(min_length=1, max_length=128)
    ppgHz: int = Field(gt=0, le=10_000)
    ppgSamplesPerChannel: int = Field(ge=0, le=1_000_000)
    edaHz: int = Field(gt=0, le=10_000)
    edaSamples: int = Field(ge=0, le=1_000_000)


class SensorWindow(BaseModel):
    model_config = ConfigDict(extra="forbid")
    clientWindowId: UUID
    sessionStartedAtMs: int
    sequence: int
    sentAtMs: int
    windowStartMs: int
    windowEndMs: int
    windowMs: int = Field(default=10_000, ge=1)
    samples: list[SensorSample] = Field(default_factory=list)
    sync: SensorSync | None = None


def get_sensor_service(request: Request) -> SensorService:
    service = getattr(request.app.state, "v25_sensor_service", None)
    if service is None:
        raise HTTPException(status_code=503, detail="Sensor service is not ready")
    return service


@router.post("/api/sensor-windows")
async def sensor_windows(
    body: SensorWindow,
    runtime: Annotated[V25Runtime, Depends(get_runtime)],
    user: Annotated[UserRecord, Depends(patient_user)],
    sensor_service: Annotated[SensorService, Depends(get_sensor_service)],
) -> dict[str, Any]:
    try:
        await runtime.service.require_consent(user.id, "biosignal")
        consent = await runtime.repository.current_consent(user.id)
        if consent is None:
            raise HTTPException(status_code=403, detail="Biosignal consent is required")
        return await sensor_service.ingest(
            patient_id=user.id,
            consent_snapshot_id=consent.id,
            ai_analysis_allowed=bool(consent.ai_analysis),
            notification_allowed=bool(consent.notification),
            payload=body.model_dump(mode="json"),
        )
    except SensorPayloadConflict as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except SensorModelUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except ValueError as exc:
        if isinstance(exc, HTTPException):
            raise
        raise HTTPException(status_code=403, detail="Biosignal consent is required") from exc


@router.get("/api/predictions/stream")
async def prediction_stream_v25(
    request: Request,
    user: Annotated[UserRecord, Depends(patient_user)],
    sensor_service: Annotated[SensorService, Depends(get_sensor_service)],
) -> StreamingResponse:
    async def events():
        queue = await sensor_service.hub.subscribe(user.id)
        try:
            yield ": connected\n\n"
            while not await request.is_disconnected():
                try:
                    event = await asyncio.wait_for(queue.get(), timeout=SSE_KEEPALIVE_SECONDS)
                    data = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
                    yield f"event: craving\ndata: {data}\n\n"
                except asyncio.TimeoutError:
                    yield ": ping\n\n"
        finally:
            await sensor_service.hub.unsubscribe(user.id, queue)

    return StreamingResponse(
        events(), media_type="text/event-stream; charset=utf-8",
        headers={"Cache-Control": "no-cache", "Connection": "keep-alive", "X-Accel-Buffering": "no"},
    )
