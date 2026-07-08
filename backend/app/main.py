from __future__ import annotations

"""FastAPI entrypoint for the Android <-> prediction server contract.

The mobile app posts 10-second sensor windows to `/sensor-window` and keeps an
SSE connection open to `/prediction-stream` for craving class results.
"""

import asyncio
import json
import os
import time
from contextlib import asynccontextmanager
from typing import Any

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from app.inference import ModelUnavailableError, RealtimePredictionService

LLM_SERVER_URL = os.getenv("LLM_SERVER_URL", "http://localhost:8001")
LLM_API_KEY = os.getenv("LLM_API_KEY", "")
SSE_KEEPALIVE_SECONDS = float(os.getenv("SSE_KEEPALIVE_SECONDS", "10"))

# One process-wide service keeps the torch model loaded and owns the inference
# queue. FastAPI routes only validate/request data and hand work to this service.
prediction_service = RealtimePredictionService()


class SensorSample(BaseModel):
    """One flat sensor sample from the Android payload."""

    model_config = ConfigDict(extra="allow")

    sensor: str
    timestampMs: int
    value: float


class SensorWindow(BaseModel):
    """10-second rolling window sent by the Android app once per second."""

    model_config = ConfigDict(extra="allow")

    sessionStartedAtMs: int
    sequence: int
    sentAtMs: int
    windowStartMs: int
    windowEndMs: int
    windowMs: int = Field(default=10_000)
    samples: list[SensorSample] = Field(default_factory=list)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load the model at startup and stop the inference worker on shutdown."""

    await prediction_service.start()
    try:
        yield
    finally:
        await prediction_service.stop()


app = FastAPI(
    title="Alcohol Craving Prediction Server API",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
async def health() -> dict[str, Any]:
    """Liveness check plus compact model/runtime status."""

    return {"status": "ok", "model": prediction_service.status()}


@app.get("/model/status")
async def model_status() -> dict[str, Any]:
    """Detailed status for debugging the latest preprocessing/prediction."""

    return prediction_service.status()


@app.post("/sensor-window")
async def sensor_window(payload: SensorWindow) -> dict[str, bool]:
    """Accept one sensor window and enqueue it for background inference."""

    try:
        await prediction_service.submit(payload.model_dump())
    except ModelUnavailableError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return {"ok": True}


@app.get("/prediction-stream")
async def prediction_stream(request: Request) -> StreamingResponse:
    """SSE stream used by the app to receive `event: craving` predictions."""

    async def event_generator():
        queue = await prediction_service.hub.subscribe()
        # Reset the latency log at the start of each connection; append a summary
        # when it closes. Each connect overwrites the previous session's file.
        client = request.client.host if request.client else ""
        prediction_service.latency.start_session(client=client)
        try:
            yield ": connected\n\n"
            while not await request.is_disconnected():
                try:
                    event = await asyncio.wait_for(
                        queue.get(), timeout=SSE_KEEPALIVE_SECONDS
                    )
                    # Downlink latency: time from "prediction ready" (stamped by
                    # the worker) to the moment we hand it to this SSE response.
                    # Log the full per-window row here so it also captures the
                    # send time that only this endpoint can observe. (With N
                    # connected clients each logs its own row; normally N=1.)
                    ready_perf = event.get("_readyPerf")
                    send_ms = (
                        (time.perf_counter() - ready_perf) * 1000.0
                        if ready_perf is not None
                        else None
                    )
                    prediction_service.latency.record(
                        sequence=event.get("sequence"),
                        send_ms=send_ms,
                        **(event.get("_lat") or {}),
                    )
                    # Never leak internal timing keys into the app payload.
                    public = {k: v for k, v in event.items() if not k.startswith("_")}
                    data = json.dumps(public, ensure_ascii=False, separators=(",", ":"))
                    yield f"event: craving\ndata: {data}\n\n"
                except asyncio.TimeoutError:
                    # Android keeps a 15s read timeout, so send a harmless SSE
                    # comment before that timeout can fire.
                    yield ": ping\n\n"
        finally:
            await prediction_service.hub.unsubscribe(queue)
            prediction_service.latency.end_session()

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream; charset=utf-8",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@app.post("/api/llm/chat")
async def llm_chat(body: dict[str, Any]) -> Any:
    """Legacy LLM proxy endpoint kept for the rest of the backend project."""

    async with httpx.AsyncClient(timeout=60.0) as client:
        try:
            response = await client.post(
                f"{LLM_SERVER_URL}/generate",
                json=body,
                headers={"x-api-key": LLM_API_KEY},
            )
            response.raise_for_status()
            return response.json()
        except httpx.ConnectError as exc:
            raise HTTPException(status_code=503, detail="LLM server is unavailable") from exc
        except httpx.HTTPStatusError as exc:
            raise HTTPException(status_code=exc.response.status_code, detail=str(exc)) from exc


@app.get("/api/users")
async def get_users() -> list[dict[str, Any]]:
    """Placeholder route from the original backend scaffold."""

    return [{"id": 1, "name": "test user"}]
