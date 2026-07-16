from __future__ import annotations

"""FastAPI entrypoint for the Android <-> prediction server contract.

The mobile app posts 10-second sensor windows to `/sensor-window` and keeps an
SSE connection open to `/prediction-stream` for craving class results.
"""

import asyncio
import copy
import json
import os
import time
from contextlib import asynccontextmanager
from typing import Any, Awaitable, Callable
from uuid import uuid4

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, ConfigDict, Field

from app.ai.bedrock_agents import (
    CHAT_SYSTEM_PROMPT,
    CRAVING_SLOT_KEYS,
    HANDOFF_SYSTEM_PROMPT,
    SLOTS_SYSTEM_PROMPT,
    BedrockClaudeAdapter,
    build_chat_repair_messages,
    build_chat_messages,
    build_handoff_messages,
    build_slot_messages,
    filter_craving_slots,
    missing_slot_keys,
    parse_json_object,
    question_free_fallback,
    repeats_recent_question,
)
from app.inference import RealtimePredictionService
from app.v25.routes_auth import router as v25_auth_router
from app.v25.routes_admin import router as v25_admin_router
from app.v25.routes_sensor import router as v25_sensor_router
from app.v25.routes_sessions import router as v25_sessions_router
from app.v25.routes_rppg import router as v25_rppg_router
from app.v25.routes_dashboard import router as v25_dashboard_router
from app.v25.runtime import initialize_v25_runtime, shutdown_v25_runtime
from app.v25.sensor_service import SensorService
from app.v25.sensor_storage import EncryptedSensorStorage

SSE_KEEPALIVE_SECONDS = float(os.getenv("SSE_KEEPALIVE_SECONDS", "10"))
HANDOFF_READY_MIN_FILLED_SLOTS = 3
HANDOFF_JOB_MAX_ENTRIES = 128
HANDOFF_JOB_TTL_SECONDS = 3_600.0
HANDOFF_JOB_RUN_TIMEOUT_SECONDS = 3_600.0
HANDOFF_JOB_FAILURE_MESSAGE = "Handoff generation failed"
ALLOWED_CRAVING_SLOT_KEYS = CRAVING_SLOT_KEYS
ALLOWED_CRAVING_SLOT_KEY_SET = set(ALLOWED_CRAVING_SLOT_KEYS)


class HandoffJobRegistry:
    """Bounded process-local registry for background handoff generation."""

    def __init__(
        self,
        *,
        max_entries: int = HANDOFF_JOB_MAX_ENTRIES,
        ttl_seconds: float = HANDOFF_JOB_TTL_SECONDS,
        run_timeout_seconds: float = HANDOFF_JOB_RUN_TIMEOUT_SECONDS,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.max_entries = max(1, int(max_entries))
        self.ttl_seconds = max(0.0, float(ttl_seconds))
        self.run_timeout_seconds = max(0.001, float(run_timeout_seconds))
        self._clock = clock
        self._jobs: dict[str, dict[str, Any]] = {}
        self._tasks: set[asyncio.Task[None]] = set()
        self._lock = asyncio.Lock()
        self._closed = False

    async def submit(
        self,
        *,
        session_id: str,
        payload: dict[str, Any],
        runner: Callable[[dict[str, Any]], Awaitable[dict[str, Any]]],
    ) -> dict[str, Any]:
        now = self._clock()
        async with self._lock:
            if self._closed:
                raise HTTPException(
                    status_code=503,
                    detail="Handoff job registry is shutting down",
                )
            self._remove_expired_locked(now)
            self._evict_terminal_until_below_capacity_locked()
            if len(self._jobs) >= self.max_entries:
                raise HTTPException(
                    status_code=503,
                    detail="Handoff job capacity reached",
                )
            job_id = str(uuid4())
            self._jobs[job_id] = {
                "jobId": job_id,
                "sessionId": session_id,
                "status": "queued",
                "createdAt": now,
                "updatedAt": now,
            }

        task = asyncio.create_task(
            self._run_job(job_id, copy.deepcopy(payload), runner)
        )
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return {"jobId": job_id, "sessionId": session_id, "status": "queued"}

    async def status(self, job_id: str) -> dict[str, Any] | None:
        async with self._lock:
            self._remove_expired_locked(self._clock())
            job = self._jobs.get(job_id)
            return self._public_job(job) if job is not None else None

    async def shutdown(self) -> None:
        async with self._lock:
            self._closed = True
            now = self._clock()
            for job in self._jobs.values():
                if job["status"] in {"queued", "running"}:
                    job["status"] = "failed"
                    job["error"] = HANDOFF_JOB_FAILURE_MESSAGE
                    job["updatedAt"] = now
            tasks = list(self._tasks)
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self._tasks.clear()

    async def _run_job(
        self,
        job_id: str,
        payload: dict[str, Any],
        runner: Callable[[dict[str, Any]], Awaitable[dict[str, Any]]],
    ) -> None:
        if not await self._set_status(job_id, "running"):
            return
        try:
            result = await asyncio.wait_for(
                runner(payload),
                timeout=self.run_timeout_seconds,
            )
        except asyncio.CancelledError:
            await self._set_status(
                job_id,
                "failed",
                error=HANDOFF_JOB_FAILURE_MESSAGE,
            )
            raise
        except Exception:
            await self._set_status(
                job_id,
                "failed",
                error=HANDOFF_JOB_FAILURE_MESSAGE,
            )
        else:
            await self._set_status(
                job_id,
                "completed",
                result=copy.deepcopy(result),
            )

    async def _set_status(
        self,
        job_id: str,
        status: str,
        **values: Any,
    ) -> bool:
        async with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return False
            if job["status"] in {"completed", "failed"}:
                return False
            if status == "running" and (self._closed or job["status"] != "queued"):
                return False
            job["status"] = status
            job["updatedAt"] = self._clock()
            job.update(values)
            return True

    def _remove_expired_locked(self, now: float) -> None:
        expired = [
            job_id
            for job_id, job in self._jobs.items()
            if job["status"] in {"completed", "failed"}
            and now - float(job["updatedAt"]) >= self.ttl_seconds
        ]
        for job_id in expired:
            self._jobs.pop(job_id, None)

    def _evict_terminal_until_below_capacity_locked(self) -> None:
        terminal = sorted(
            (
                job
                for job in self._jobs.values()
                if job["status"] in {"completed", "failed"}
            ),
            key=lambda job: float(job["updatedAt"]),
        )
        while len(self._jobs) >= self.max_entries and terminal:
            self._jobs.pop(terminal.pop(0)["jobId"], None)

    @staticmethod
    def _public_job(job: dict[str, Any]) -> dict[str, Any]:
        public = {
            "jobId": job["jobId"],
            "sessionId": job["sessionId"],
            "status": job["status"],
        }
        if job["status"] == "completed":
            public["result"] = copy.deepcopy(job.get("result", {}))
        elif job["status"] == "failed":
            public["error"] = HANDOFF_JOB_FAILURE_MESSAGE
        return public

# One process-wide service keeps the craving model loaded and owns the inference
# queue. FastAPI routes only validate/request data and hand work to this service.
prediction_service = RealtimePredictionService()
bedrock_adapter = BedrockClaudeAdapter()
handoff_job_registry = HandoffJobRegistry()


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


class InterventionChatRequest(BaseModel):
    model_config = ConfigDict(extra="allow")

    sessionId: str | None = None
    message: str
    alert: dict[str, Any] | None = None
    conversationHistory: list[dict[str, Any]] = Field(default_factory=list)
    slots: dict[str, Any] = Field(default_factory=dict)


class SlotExtractionRequest(BaseModel):
    model_config = ConfigDict(extra="allow")

    sessionId: str | None = None
    conversationHistory: list[dict[str, Any]] = Field(default_factory=list)
    currentSlots: dict[str, Any] = Field(default_factory=dict)


class HandoffRequest(BaseModel):
    model_config = ConfigDict(extra="allow")

    sessionId: str | None = None
    slots: dict[str, Any] | None = None
    conversationHistory: list[dict[str, Any]] | None = None
    alertEvents: list[dict[str, Any]] | None = None
    predictionSummary: dict[str, Any] | None = None


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
        return self.service.model.model_name or "RandomForest"

    @property
    def model_version(self) -> str:
        return os.getenv(
            "CRAVING_MODEL_VERSION", self.service.model.model_path.name
        )

    @property
    def artifact_uri(self) -> str:
        return str(self.service.model.model_path)

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
        await handoff_job_registry.shutdown()
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


async def llm_chat(body: dict[str, Any]) -> Any:
    """Legacy LLM compatibility endpoint backed by internal Bedrock calls."""

    prompt = str(body.get("prompt", ""))
    max_tokens = int(body.get("max_tokens", 512))
    temperature = float(body.get("temperature", 0.7))
    text = await _bedrock_complete(
        system=CHAT_SYSTEM_PROMPT,
        messages=build_chat_messages(
            message=prompt,
            conversation_history=[],
            slots={},
            alert_context={},
        ),
        max_tokens=max_tokens,
        temperature=temperature,
        failure_detail="Bedrock chat request failed",
    )
    return {"text": text, "model": bedrock_adapter.model_id}


async def intervention_chat(body: InterventionChatRequest) -> Any:
    """Run alert-aware intervention chat through backend-owned Bedrock helpers."""

    session_id = _request_session_id(body.sessionId)
    await prediction_service.memory.record_conversation_turn(
        session_id=session_id,
        role="user",
        content=body.message,
        alert_context=body.alert or {},
    )
    current_slots = _filter_slots(body.slots)
    payload = {
        "sessionId": session_id,
        "message": body.message,
        "conversationHistory": body.conversationHistory,
        "slots": current_slots,
        "alertContext": body.alert or {},
    }
    result = await _ai_chat_respond(payload)
    assistant_text = _assistant_text(result)
    if assistant_text:
        await prediction_service.memory.record_conversation_turn(
            session_id=session_id,
            role="assistant",
            content=assistant_text,
            llm_payload=result if isinstance(result, dict) else {"response": result},
        )
    updated_history = _with_current_turns(
        body.conversationHistory,
        user_message=body.message,
        assistant_message=assistant_text,
    )
    slot_result = await _ai_slots_extract(
        {
            "sessionId": session_id,
            "conversationHistory": updated_history,
            "currentSlots": current_slots,
        }
    )
    merged_slots = _merged_slots(current_slots, slot_result)
    missing_slots = _missing_slots(slot_result)
    await prediction_service.memory.upsert_craving_slots(
        session_id,
        merged_slots,
        missing_slots,
    )

    response = result if isinstance(result, dict) else {"response": result}
    response.setdefault("sessionId", session_id)
    if assistant_text:
        response.setdefault("response", assistant_text)
    response["mergedSlots"] = merged_slots
    if "slots" in response:
        response["slots"] = merged_slots
    response.pop("merged_slots", None)
    response["missingSlots"] = missing_slots
    response.pop("missing_slots", None)
    response["handoffReady"] = _handoff_ready(merged_slots, missing_slots)
    return response


async def intervention_slots(body: SlotExtractionRequest) -> Any:
    """Extract craving slots through backend-owned Bedrock helpers."""

    session_id = _request_session_id(body.sessionId)
    payload = {
        "sessionId": session_id,
        "conversationHistory": body.conversationHistory,
        "currentSlots": _filter_slots(body.currentSlots),
    }
    result = await _ai_slots_extract(payload)
    if isinstance(result, dict):
        slots = _merged_slots(body.currentSlots, result)
        missing_slots = _missing_slots(result)
        await prediction_service.memory.upsert_craving_slots(
            session_id,
            slots,
            missing_slots,
        )
        result["mergedSlots"] = slots
        if "slots" in result:
            result["slots"] = slots
        result.pop("merged_slots", None)
        result["missingSlots"] = missing_slots
        result.pop("missing_slots", None)
        result.setdefault("sessionId", session_id)
    return result


async def intervention_handoff(body: HandoffRequest) -> Any:
    """Generate clinician handoff report through backend-owned Bedrock helpers."""

    payload = await _build_handoff_payload(body)
    return await _generate_and_persist_handoff(payload)


async def submit_intervention_handoff_job(body: HandoffRequest) -> dict[str, Any]:
    """Queue a process-local handoff job and return without waiting for Bedrock."""

    payload = await _build_handoff_payload(body)
    return await handoff_job_registry.submit(
        session_id=payload["sessionId"],
        payload=payload,
        runner=_generate_and_persist_handoff,
    )


async def intervention_handoff_job_status(job_id: str) -> dict[str, Any]:
    """Return public status for one queued handoff generation job."""

    status = await handoff_job_registry.status(job_id)
    if status is None:
        raise HTTPException(status_code=404, detail="Handoff job not found")
    return status


async def _build_handoff_payload(body: HandoffRequest) -> dict[str, Any]:
    """Resolve a handoff request into an immutable job-ready snapshot."""

    session_id = _request_session_id(body.sessionId)
    context = await prediction_service.memory.handoff_context(session_id)
    slots = body.slots
    if slots is None:
        slots = await prediction_service.memory.current_slots(session_id)
    slots = _filter_slots(slots)
    history = body.conversationHistory
    if history is None:
        history = await prediction_service.memory.conversation_history(session_id)
    return copy.deepcopy(
        {
            "sessionId": session_id,
            "slots": slots,
            "conversationHistory": history,
            "alertEvents": (
                body.alertEvents
                if body.alertEvents is not None
                else context["alertEvents"]
            ),
            "predictionSummary": (
                body.predictionSummary
                if body.predictionSummary is not None
                else context["predictionSummary"]
            ),
        }
    )


async def _generate_and_persist_handoff(payload: dict[str, Any]) -> dict[str, Any]:
    """Run the shared synchronous/async handoff generation and persistence path."""

    result = await _ai_handoff_generate(payload)
    if isinstance(result, dict):
        report = _handoff_markdown(result)
        missing_slots = result.get("missingSlots")
        if report:
            await prediction_service.memory.record_handoff_report(
                session_id=payload["sessionId"],
                report_markdown=report,
                missing_slots=missing_slots if isinstance(missing_slots, list) else None,
                source_payload=result,
            )
        result.setdefault("sessionId", payload["sessionId"])
    return result


async def _ai_chat_respond(payload: dict[str, Any]) -> dict[str, Any]:
    history = payload.get("conversationHistory")
    messages = build_chat_messages(
        message=str(payload.get("message", "")),
        conversation_history=history,
        slots=payload.get("slots"),
        alert_context=payload.get("alertContext"),
    )
    text = await _bedrock_complete(
        system=CHAT_SYSTEM_PROMPT,
        messages=messages,
        max_tokens=700,
        temperature=0.4,
        failure_detail="Bedrock chat request failed",
    )
    if repeats_recent_question(text, history):
        try:
            repaired = await bedrock_adapter.complete(
                system=CHAT_SYSTEM_PROMPT,
                messages=build_chat_repair_messages(messages, text),
                max_tokens=700,
                temperature=0.4,
            )
        except Exception:
            text = question_free_fallback(text, history)
        else:
            if not repaired.strip():
                text = question_free_fallback(text, history)
            elif repeats_recent_question(repaired, history):
                text = question_free_fallback(repaired, history)
            else:
                text = repaired
    return {
        "sessionId": payload.get("sessionId"),
        "response": text,
        "text": text,
        "model": bedrock_adapter.model_id,
    }


async def _ai_slots_extract(payload: dict[str, Any]) -> dict[str, Any]:
    current_slots = {
        key: value
        for key, value in filter_craving_slots(payload.get("currentSlots")).items()
        if value not in (None, "", [], {})
    }
    text = await _bedrock_complete(
        system=SLOTS_SYSTEM_PROMPT,
        messages=build_slot_messages(
            conversation_history=payload.get("conversationHistory"),
            current_slots=current_slots,
        ),
        max_tokens=500,
        temperature=0.0,
        failure_detail="Bedrock slot request failed",
    )
    parsed = parse_json_object(text)
    extracted_slots = filter_craving_slots(parsed) if parsed is not None else {}
    slots = dict(current_slots)
    for key, value in extracted_slots.items():
        if value not in (None, "", [], {}):
            slots[key] = value
    return {
        "sessionId": payload.get("sessionId"),
        "slots": slots,
        "missingSlots": missing_slot_keys(slots),
        "model": bedrock_adapter.model_id,
    }


async def _ai_handoff_generate(payload: dict[str, Any]) -> dict[str, Any]:
    slots = filter_craving_slots(payload.get("slots"))
    markdown = await _bedrock_complete(
        system=HANDOFF_SYSTEM_PROMPT,
        messages=build_handoff_messages(
            slots=slots,
            conversation_history=payload.get("conversationHistory"),
            alert_events=payload.get("alertEvents"),
            prediction_summary=payload.get("predictionSummary"),
        ),
        max_tokens=1200,
        temperature=0.2,
        failure_detail="Bedrock handoff request failed",
    )
    return {
        "sessionId": payload.get("sessionId"),
        "markdown": markdown,
        "missingSlots": missing_slot_keys(slots),
        "model": bedrock_adapter.model_id,
    }


async def _bedrock_complete(
    *,
    system: str,
    messages: list[dict[str, str]],
    max_tokens: int,
    temperature: float,
    failure_detail: str,
) -> str:
    try:
        return await bedrock_adapter.complete(
            system=system,
            messages=messages,
            max_tokens=max_tokens,
            temperature=temperature,
        )
    except Exception as exc:
        raise HTTPException(status_code=502, detail=failure_detail) from exc


def _request_session_id(session_id: str | None) -> str:
    return str(session_id) if session_id else prediction_service.current_session_id()


def _public_sse_event(event: dict[str, Any]) -> dict[str, Any]:
    """Strip worker-only keys while preserving legacy/public prediction fields."""

    return {key: value for key, value in event.items() if not key.startswith("_")}


def _assistant_text(result: Any) -> str:
    if isinstance(result, str):
        return result
    if not isinstance(result, dict):
        return ""
    for key in ("assistantResponse", "response", "text", "content"):
        value = result.get(key)
        if isinstance(value, str):
            return value
    message = result.get("message")
    if isinstance(message, dict) and isinstance(message.get("content"), str):
        return message["content"]
    return ""


def _with_current_turns(
    history: list[dict[str, Any]],
    user_message: str,
    assistant_message: str,
) -> list[dict[str, Any]]:
    updated = [dict(item) for item in history]
    updated.append({"role": "user", "content": user_message})
    if assistant_message:
        updated.append({"role": "assistant", "content": assistant_message})
    return updated


def _merged_slots(current_slots: dict[str, Any], slot_result: Any) -> dict[str, Any]:
    merged = _filter_slots(current_slots)
    if not isinstance(slot_result, dict):
        return merged
    extracted = (
        slot_result.get("mergedSlots")
        or slot_result.get("merged_slots")
        or slot_result.get("slots")
    )
    if isinstance(extracted, dict):
        for key, value in extracted.items():
            if key in ALLOWED_CRAVING_SLOT_KEY_SET and value not in (None, "", [], {}):
                merged[key] = value
    return merged


def _missing_slots(slot_result: Any) -> list[Any]:
    if not isinstance(slot_result, dict):
        return []
    value = slot_result.get("missingSlots")
    if value is None:
        value = slot_result.get("missing_slots")
    if not isinstance(value, list):
        return []
    return [slot for slot in value if slot in ALLOWED_CRAVING_SLOT_KEY_SET]


def _filter_slots(slots: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(slots, dict):
        return {}
    return {
        key: value
        for key, value in slots.items()
        if key in ALLOWED_CRAVING_SLOT_KEY_SET and value not in (None, "", [], {})
    }


def _handoff_ready(merged_slots: dict[str, Any], missing_slots: list[Any]) -> bool:
    filled_slots = sum(1 for value in merged_slots.values() if value not in (None, "", [], {}))
    return filled_slots >= HANDOFF_READY_MIN_FILLED_SLOTS and len(missing_slots) <= 1


def _handoff_markdown(result: dict[str, Any]) -> str:
    for key in ("reportMarkdown", "markdown", "report", "content"):
        value = result.get(key)
        if isinstance(value, str):
            return value
    return ""
