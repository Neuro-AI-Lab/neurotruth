from __future__ import annotations

"""FastAPI entrypoint for authenticated NeuroTruth services and diagnostics."""

import asyncio
import os
import time
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from app.inference import RealtimePredictionService
from app.v25.routes_auth import router as v25_auth_router
from app.v25.routes_admin import router as v25_admin_router
from app.v25.routes_sensor import router as v25_sensor_router
from app.v25.routes_sessions import router as v25_sessions_router
from app.v25.routes_stt import router as v25_stt_router
from app.v25.routes_rppg import router as v25_rppg_router
from app.v25.routes_dashboard import router as v25_dashboard_router
from app.v25.runtime import initialize_v25_runtime, shutdown_v25_runtime
from app.v25.sensor_service import SensorService
from app.v25.sensor_storage import EncryptedSensorStorage

SSE_KEEPALIVE_SECONDS = float(os.getenv("SSE_KEEPALIVE_SECONDS", "10"))

# One process-wide service keeps the craving model loaded and owns the inference
# queue. FastAPI routes only validate/request data and hand work to this service.
prediction_service = RealtimePredictionService()


class RuntimePredictorAdapter:
    """Expose the asynchronously loaded craving model to authenticated routes."""

    def __init__(self, service: RealtimePredictionService) -> None:
        self.service = service

    @property
    def ready(self) -> bool:
        # Model loading happens on the inference worker. Readiness must be
        # observed dynamically instead of being frozen during app startup.
        return bool(self.service.model.ready)

    @property
    def model_name(self) -> str:
        return self.service.model.model_name

    @property
    def model_version(self) -> str:
        return os.getenv("CRAVING_MODEL_VERSION", self.service.model.model_version)

    @property
    def artifact_uri(self) -> str:
        return self.service.model.safe_artifact_uri

    @property
    def inference_task(self) -> str:
        return "binary_classification"

    @property
    def output_schema(self) -> dict[str, Any]:
        return {
            "predictionSchema": "binary-craving-v1",
            "classes": [{"index": 0, "code": "low"}, {"index": 1, "code": "high"}],
        }

    @property
    def registration_config(self) -> dict[str, Any]:
        return self.service.model.registration_config

    async def predict(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.to_thread(self.service.model.predict, payload)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load the model at startup and stop the inference worker on shutdown."""

    runtime = await initialize_v25_runtime(app)
    # The legacy six-table memory adapter must never create its tables in the
    # fresh V2.5 database. V2.5 repositories own persistence from this point.
    prediction_service.memory.database_url = None
    await prediction_service.start()
    if runtime.ready:
        runtime_predictor = RuntimePredictorAdapter(prediction_service)

        def decide_alert(patient_id, event):
            return prediction_service.alerts.for_session(f"patient:{patient_id}").evaluate(
                int(event["class"]), now_ms=int(event.get("timestampMs") or time.time() * 1000)
            ).as_dict()

        app.state.v25_sensor_service = SensorService(
            runtime.repository,
            EncryptedSensorStorage(runtime.settings.sensor_storage_root, runtime.settings.keyring()),
            runtime_predictor,
            decide_alert,
        )
        from app.v25.rppg_dgx import DgxClient
        from app.v25.rppg_media import FfprobeMediaInspector
        from app.v25.rppg_service import RppgService
        runtime.rppg_service = RppgService(
            repository=runtime.rppg_repository,
            v25_repository=runtime.repository,
            storage=runtime.rppg_storage,
            keyring=runtime.settings.keyring(),
            dgx=DgxClient(
                runtime.settings.rppg_base_url,
                connect_timeout=runtime.settings.rppg_connect_timeout_seconds,
                read_timeout=runtime.settings.rppg_read_timeout_seconds,
            ),
            inspector=FfprobeMediaInspector(runtime.settings.rppg_ffprobe_path),
            predictor=runtime_predictor, alert_decider=decide_alert,
            enabled=runtime.settings.rppg_enabled,
            max_concurrency=runtime.settings.rppg_max_concurrency,
            read_timeout_seconds=runtime.settings.rppg_read_timeout_seconds,
        )
        runtime.admin_service.configure_rppg(runtime.rppg_repository, runtime.rppg_storage)
        await runtime.rppg_service.start()
    try:
        yield
    finally:
        await prediction_service.stop()
        await shutdown_v25_runtime(app)


app = FastAPI(
    title="Alcohol Craving Prediction Server API",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=[origin.strip() for origin in os.getenv(
        "CORS_ALLOWED_ORIGINS",
        "http://127.0.0.1:3000,http://localhost:3000,http://127.0.0.1:8765",
    ).split(",") if origin.strip()],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.include_router(v25_auth_router)
app.include_router(v25_admin_router)
app.include_router(v25_sensor_router)
app.include_router(v25_sessions_router)
app.include_router(v25_stt_router)
app.include_router(v25_rppg_router)
app.include_router(v25_dashboard_router)


@app.get("/health")
async def health() -> dict[str, Any]:
    """Liveness check plus compact model/runtime status."""

    runtime = getattr(app.state, "v25_runtime", None)
    return {
        "status": "ok",
        "model": prediction_service.status(),
        "v25": runtime.public_status() if runtime is not None else {"ready": False, "errorCode": "not_initialized"},
    }


@app.get("/ready")
async def readiness() -> dict[str, Any]:
    runtime = getattr(app.state, "v25_runtime", None)
    status = runtime.public_status() if runtime is not None else {"ready": False, "errorCode": "not_initialized"}
    if not status["ready"]:
        raise HTTPException(status_code=503, detail=status)
    return status


@app.get("/model/status")
async def model_status() -> dict[str, Any]:
    """Detailed status for debugging the latest preprocessing/prediction."""

    return prediction_service.status()


def _public_sse_event(event: dict[str, Any]) -> dict[str, Any]:
    """Strip worker-only keys while preserving legacy/public prediction fields."""

    return {key: value for key, value in event.items() if not key.startswith("_")}
