# NeuroTruth Backend

Last updated: 2026-07-13

FastAPI backend for sensor upload, craving prediction, rule-based alerts, Postgres memory, Bedrock text intervention, slot extraction, and handoff generation.

## Commands

```powershell
cd apps/backend
python -m compileall app tests
python -m pytest
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## Runtime Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | Liveness and model status |
| `GET` | `/model/status` | Detailed prediction diagnostics |
| `POST` | `/sensor-window` | Android sensor window upload |
| `GET` | `/prediction-stream` | SSE craving class and alert stream |
| `POST` | `/api/intervention/chat` | Text intervention response plus slot update |
| `POST` | `/api/intervention/slots` | Slot extraction |
| `POST` | `/api/intervention/handoff` | Synchronous Markdown handoff report; retained for compatibility |
| `POST` | `/api/intervention/handoff/jobs` | Accept an asynchronous handoff job and return HTTP 202 plus `jobId` |
| `GET` | `/api/intervention/handoff/jobs/{job_id}` | Read queued/running/completed/failed handoff job state |
| `POST` | `/api/llm/chat` | Legacy chat compatibility wrapper |

## Asynchronous Handoff Jobs

- Mobile submits an immutable handoff request snapshot and receives an opaque UUID `jobId` without waiting for Bedrock generation.
- Completed jobs return the existing handoff response under `result` and persist the report through the same path as the synchronous endpoint.
- Provider errors are converted to the fixed public message `Handoff generation failed`; credentials and raw provider payloads are not returned.
- The process-local registry keeps at most 128 entries. Terminal metadata expires after one hour and a job may run for at most one hour.
- Shutdown atomically rejects new work, marks queued/running jobs failed, and cancels/drains tracked tasks.

## Alert Evaluation

- Alert history, cooldown, and downtrend state are isolated by session ID.
- The in-process LRU registry retains at most 256 session evaluators.
- Mean-based decisions wait for the configured 10-window warm-up by default.
- A class-2 streak can still trigger early required intervention after `ALERT_HIGH_STREAK` consecutive values, defaulting to 3.
- Warm-up events remain backward compatible with `class` while returning `alertLevel=none`, `alertAction=none`, and `triggerReason=window_warming_up`.

## Local Model

The craving targeting model belongs to backend.

```text
apps/backend/model/weights/rf_dependent.joblib
```

Place or track the model bundle here before running inference, or set `MODEL_PATH`.

Docker default:

```text
/app/model/weights/rf_dependent.joblib
```

## Bedrock

Bedrock calls are made directly inside backend through `app/ai/bedrock_agents.py`.
The default `openai.gpt-5.5` model calls the Bedrock Mantle Responses endpoint
and requires `AWS_BEARER_TOKEN_BEDROCK`. Non-OpenAI model IDs continue to use
the existing Bedrock Runtime `converse` bearer path or the boto3 client for
IAM/profile-based authentication, providing a configuration-only rollback.

Required runtime configuration:

| Variable | Purpose |
|---|---|
| `AWS_BEARER_TOKEN_BEDROCK` | Bedrock API key bearer token |
| `BEDROCK_MODEL_ID` | Defaults to `openai.gpt-5.5` |
| `AWS_REGION` | AWS Bedrock region |
| `AWS_DEFAULT_REGION` | Optional fallback region |
| `BEDROCK_TIMEOUT_SECONDS` | Optional bearer-token HTTP timeout; defaults to `60` |

GPT-5.5 requires the bearer token and a Mantle-supported region; it was verified
in `us-east-1`. IAM role, AWS profile, or standard AWS environment credentials
remain available only for non-OpenAI model IDs using the Converse path.

## Agent Prompts

System prompts are backend-owned and live in:

```text
apps/backend/app/ai/bedrock_agents.py
```

| Constant | Purpose |
|---|---|
| `CHAT_SYSTEM_PROMPT` | Korean CBT-style text intervention dialogue |
| `SLOTS_SYSTEM_PROMPT` | Structured craving slot extraction |
| `HANDOFF_SYSTEM_PROMPT` | Markdown handoff report generation |

Do not put these prompts in Android; mobile calls backend only.

## Important Files

| File | Role |
|---|---|
| `app/main.py` | FastAPI routes, SSE, intervention orchestration |
| `app/inference.py` | Sensor preprocessing, RandomForest inference, alert event production |
| `app/alerts.py` | Rule-based craving alert evaluator |
| `app/memory.py` | Postgres memory persistence |
| `app/ai/bedrock_agents.py` | Bedrock adapter, prompts, slot helpers |
| `model/features.py` | PPG/GSR feature extraction |
| `model/weights/.gitkeep` | Placeholder for local model bundle directory |

## Latest Validation

| Check | Result |
|---|---|
| `python -m compileall app tests` | PASS |
| `python -m pytest` | PASS, 50 tests |
| Docker health smoke | PASS with local model loaded |
| Async handoff OpenAPI smoke | PASS: POST exposes HTTP 202, GET is present, and an unknown job returns 404 |
| Same-session integration | PASS: 3 predictions, 3 alerts, 2 conversation turns, 1 slot record, and 1 handoff report linked in Postgres |
| Live Bedrock bearer calls | PASS for intervention chat and handoff generation |
