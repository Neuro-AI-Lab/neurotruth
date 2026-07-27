from __future__ import annotations

"""FastAPI entrypoint for authenticated NeuroTruth services and diagnostics."""

import os
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.adapters.rppg_dgx import DgxClient
from app.adapters.rppg_media import FfprobeMediaInspector
from app.api.v1.router import api_router
from app.core.runtime import initialize_runtime, shutdown_runtime
from app.maintenance.seed_vp012_demo import DEMO_PATIENT_ID
from app.services.demo_prediction_playback import DemoPredictionPlayback
from app.services.prediction import RuntimePredictorAdapter, prediction_service
from app.services.rppg import RppgService
from app.services.sensor import SensorService
from app.storage.sensor import EncryptedSensorStorage


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load the model at startup and stop the inference worker on shutdown."""

    try:
        runtime = await initialize_runtime(app)
        await prediction_service.start()
        if runtime.ready:
            runtime_predictor = RuntimePredictorAdapter(prediction_service)

            def decide_alert(patient_id, event):
                return prediction_service.alerts.for_session(f"patient:{patient_id}").evaluate(
                    int(event["class"]), now_ms=int(event.get("timestampMs") or time.time() * 1000)
                ).as_dict()

            app.state.sensor_service = SensorService(
                runtime.repository,
                EncryptedSensorStorage(runtime.settings.sensor_storage_root, runtime.settings.keyring()),
                runtime_predictor,
                decide_alert,
                prediction_service.hub,
                prediction_service.latency,
                demo_playback=DemoPredictionPlayback(
                    enabled=runtime.settings.demo_scenario_enabled,
                    patient_id=DEMO_PATIENT_ID,
                ),
            )
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
        yield
    finally:
        try:
            await shutdown_runtime(app)
        finally:
            await prediction_service.stop()


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
app.include_router(api_router)
