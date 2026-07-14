# NeuroTruth

Last updated: 2026-07-13

NeuroTruth is a wearable-assisted alcohol craving intervention prototype. It combines Galaxy Watch sensor windows, backend craving prediction, deterministic alert rules, AWS Bedrock GPT-5.5 text intervention, craving slot extraction, and Markdown handoff reporting.

## Structure

```text
neurotruth/
+-- apps/
|   +-- backend/   # FastAPI prediction, alerts, Postgres memory, Bedrock intervention
|   +-- web/       # React/nginx web surface
|   +-- mobile/    # Android phone + Wear OS project
|   +-- db/        # Postgres schema and Docker Compose stack
+-- docs/          # Product, AI, specs, and GitHub upload docs
```

## Demo Flow

```text
Galaxy Watch sensors
  -> Android phone
  -> POST /sensor-window
  -> backend prediction
  -> GET /prediction-stream
  -> rule-based alert
  -> text intervention chat
  -> craving slot extraction
  -> async handoff job
  -> handoff report persistence and preview
```

## Quick Start

Create local environment:

```powershell
Copy-Item .env.example .env
```

Validate Docker config:

```powershell
docker compose -f apps/db/docker-compose.yml config --no-env-resolution
```

Run the local stack:

```powershell
docker compose -f apps/db/docker-compose.yml up -d --build
```

Backend checks:

```powershell
cd apps/backend
python -m compileall app tests
python -m pytest
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Android build:

```powershell
cd apps/mobile
.\gradlew.bat assembleDebug
```

Android unit tests:

```powershell
cd apps/mobile
.\gradlew.bat testDebugUnitTest lintDebug
```

Install debug APKs when phone and watch are visible in `adb devices`:

```powershell
adb install -r apps/mobile/app/build/outputs/apk/debug/app-debug.apk
adb -s <WATCH_SERIAL> install -r apps/mobile/wearos/build/outputs/apk/debug/wearos-debug.apk
```

## Runtime Notes

- The local craving classifier belongs to backend and is loaded from `apps/backend/model/weights/rf_dependent.joblib` unless `MODEL_PATH` overrides it.
- Model binaries, `.env`, and Samsung SDK AAR/JAR files can be tracked when the team deliberately includes them in the private repository. Android `local.properties` is not portable and must be recreated per development machine or replaced with `ANDROID_HOME`.
- Bedrock API key bearer tokens are read from `AWS_BEARER_TOKEN_BEDROCK`.
- The default `openai.gpt-5.5` model uses the Bedrock Mantle Responses API and requires `AWS_BEARER_TOKEN_BEDROCK`. Non-OpenAI model IDs retain the existing Bedrock Runtime Converse bearer/IAM path for rollback.
- Existing Android endpoint paths remain available. Mobile now uses the asynchronous handoff job endpoints while the synchronous handoff endpoint stays backward-compatible.
- Chat response waiting defaults to 60 minutes and can be changed from 1 to 1,440 minutes in phone administrator mode.
- While intervention chat is active, later required alerts remain recorded and watch state stays current, but the phone sends `alertAction=none` so neither AUQ nor repeated watch vibration/notification is presented.
- The current phone asset URL is a LAN backend URL, not `localhost`: `http://192.168.68.51:8000`.

## Latest Verified State

| Area | Status |
|---|---|
| Docker stack | `db`, `backend`, and `web` run from `apps/db/docker-compose.yml` |
| Backend health | `/health` returns `status=ok` with the local RF model loaded |
| Backend tests | `python -m pytest` passed: 50 tests |
| Session integration | Sensor, alert, chat, slot, and handoff records were linked under one session in Postgres |
| Bedrock integration | GPT-5.5 availability and a minimal Mantle Responses request passed in `us-east-1`; the previous Claude Sonnet 4.6 chat/handoff pass remains historical validation |
| Async handoff API | Job submission returns HTTP 202; status lookup, sanitized failure, TTL/capacity, shutdown, and one-hour runtime bounds are covered by tests |
| Android build | `apps/mobile/gradlew.bat assembleDebug` passed after the AUQ/async-handoff changes |
| Android unit tests | `apps/mobile/gradlew.bat testDebugUnitTest` passed with AUQ latch, timeout policy, and single-job gate coverage |
| Android lint | `apps/mobile/gradlew.bat lintDebug` passed after the latest mobile changes |
| Device install | Latest debug APKs installed and launched on Phone `SM-S926N` and Watch `SM-L320` |

## Key Docs

| Document | Purpose |
|---|---|
| [GitHub upload changes](docs/GITHUB_UPLOAD_CHANGES.md) | Upload/PR summary and checklist |
| [GitHub upload changes Korean](docs/GITHUB_UPLOAD_CHANGES.ko.md) | Korean upload summary and checklist |
| [Pull request description](docs/PULL_REQUEST_DESCRIPTION.md) | Ready-to-use English PR body |
| [Pull request description Korean](docs/PULL_REQUEST_DESCRIPTION.ko.md) | Korean mirror of the PR body |
| [Development environment](docs/dev-environment.md) | Local setup and validation |
| [Product requirements](docs/prd/PRD_neurotruth.md) | Product scope |
| [Product requirements Korean](docs/prd/PRD_neurotruth.ko.md) | Korean PRD mirror |
| [Implementation plan](docs/todo_plan/PLAN_neurotruth.md) | Current follow-up plan |
| [AI workspace](docs/ai/README.md) | Backend-owned Bedrock agent boundaries |
| [Unified backend structure spec](docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.md) | Authoritative consolidation spec |
| [Session and alert stabilization spec](docs/specs/2026-07-10-neurotruth-session-alert-ui-stabilization-spec.md) | Current session, alert, phone, and watch behavior |
| [LLM repetition-control spec](docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.md) | Completion-aware prompting and deterministic repeated-question repair |
| [AUQ, async handoff, and timeout spec](docs/specs/2026-07-13-neurotruth-mobile-auq-async-handoff-timeout-spec.md) | Current intervention latch, handoff job, administrator timeout, and reset behavior |
| [Bedrock GPT-5.5 migration spec](docs/specs/2026-07-13-neurotruth-bedrock-gpt-55-migration-spec.md) | Mantle Responses routing and Converse rollback behavior |
