from __future__ import annotations

import asyncio
import json
import os
import time
from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse

from app.api.v1.dependencies import get_runtime, patient_user
from app.core.runtime import BackendRuntime
from app.models.records import UserRecord
from app.schemas.sensor import SensorWindow
from app.services.auth import AuthorizationError
from app.services.prediction import public_sse_event
from app.services.sensor import (
    SensorModelUnavailable,
    SensorPayloadConflict,
    SensorPayloadInvalid,
    SensorService,
)


router = APIRouter()
SSE_KEEPALIVE_SECONDS = float(os.getenv("SSE_KEEPALIVE_SECONDS", "10"))


def get_sensor_service(request: Request) -> SensorService:
    service = getattr(request.app.state, "sensor_service", None)
    if service is None:
        raise HTTPException(status_code=503, detail="Sensor service is not ready")
    return service


@router.post("/api/sensor-windows")
async def sensor_windows(
    body: SensorWindow,
    runtime: Annotated[BackendRuntime, Depends(get_runtime)],
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
    except AuthorizationError as exc:
        raise HTTPException(status_code=403, detail="Biosignal consent is required") from exc
    except SensorPayloadInvalid as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@router.get("/api/predictions/stream")
async def prediction_stream(
    request: Request,
    user: Annotated[UserRecord, Depends(patient_user)],
    sensor_service: Annotated[SensorService, Depends(get_sensor_service)],
) -> StreamingResponse:
    async def events():
        queue = await sensor_service.hub.subscribe(user.id)
        sensor_service.latency.start_session(client=str(user.id))
        try:
            yield ": connected\n\n"
            while not await request.is_disconnected():
                try:
                    event = await asyncio.wait_for(queue.get(), timeout=SSE_KEEPALIVE_SECONDS)
                    lat = event.get("_lat") if isinstance(event.get("_lat"), dict) else {}
                    ready = event.get("_readyPerf")
                    sensor_service.latency.record(
                        sequence=event.get("sequence"), comm_ms=lat.get("comm_ms"),
                        queue_ms=lat.get("queue_ms"), feature_ms=lat.get("feature_ms"),
                        model_ms=lat.get("model_ms"), server_ms=lat.get("server_ms"),
                        send_ms=(time.perf_counter() - ready) * 1000.0
                        if isinstance(ready, (int, float)) else None,
                    )
                    data = json.dumps(public_sse_event(event), ensure_ascii=False, separators=(",", ":"))
                    yield f"event: craving\ndata: {data}\n\n"
                except asyncio.TimeoutError:
                    yield ": ping\n\n"
        finally:
            try:
                await sensor_service.hub.unsubscribe(user.id, queue)
            finally:
                sensor_service.latency.end_session()

    return StreamingResponse(
        events(), media_type="text/event-stream; charset=utf-8",
        headers={"Cache-Control": "no-cache", "Connection": "keep-alive", "X-Accel-Buffering": "no"},
    )
