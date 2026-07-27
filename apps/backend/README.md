# NeuroTruth Backend

Last updated: 2026-07-19

FastAPI backend for the authenticated intervention-support platform. Readiness fails closed when PostgreSQL, the expected Alembic revision, required security settings, or the AES-256-GCM keyring are unavailable.

## Run and Validate

```powershell
cd apps/backend
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m compileall -q app tests
.\.venv\Scripts\python.exe -m alembic upgrade head
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 25991
```

Use `ALLOW_INSECURE_HTTP=true` only with `APP_ENV=development|test`. Production requires HTTPS.

## Single-Process Application Structure

`apps/backend` remains one FastAPI process. The internal `api/v1` folder names
the current API package; it does not add `/v1` to any public URL.

```text
app/
├─ main.py                  # application, lifespan, top-level router
├─ api/v1/                  # HTTP validation, dependencies, route registration
├─ schemas/                 # Pydantic request/response and OpenAPI models
├─ models/                  # shared application records
├─ services/                # authentication, session, sensor, STT, rPPG workflows
├─ agents/ and prompts/     # Bedrock adapter plus capability-specific AI owners
├─ ml/craving/              # binary craving model, preprocessing, latency
├─ repositories/            # PostgreSQL and rPPG persistence
├─ adapters/                # external STT, DGX rPPG, and media clients
├─ storage/                 # encrypted sensor and rPPG file storage
├─ core/                    # typed settings, runtime composition, security
└─ maintenance/             # explicit operational maintenance commands
```

Routes validate HTTP input and call services; `core/runtime.py` assembles the
service, repository, adapter, and agent dependencies. The implemented AI/ML
capabilities are intervention dialogue, feature-gated external STT, and craving prediction:

- Text and user-confirmed voice transcripts both call retry-safe
  `POST /api/sessions/{id}/messages`. They use the same intervention agent and
  `clientMessageId` idempotency flow; only `inputModality` is `text` or `voice`.
- STT follows `api/v1/routes/stt.py -> services/stt.py -> adapters/stt_client.py ->
  apps/stt-service`. Transcription does not store or send a chat message until
  the user confirms the editable text.
- Authenticated sensor windows follow `routes/sensor.py -> services/sensor.py ->
  RuntimePredictorAdapter -> RealtimePredictionService`. The prediction service
  owns the process-wide model and request/response worker queue around
  `ml/craving`; `SensorService` owns PostgreSQL persistence and publishes the
  completed public result through the injected patient-scoped SSE hub. Worker
  timing stays internal and is recorded only while authenticated SSE is active.

## Current Authenticated API

| Area | Routes |
|---|---|
| Auth | `POST /api/auth/patient/signup`, `/api/auth/admin/signup`, `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`, `/api/auth/change-password` |
| Profile/consent | `GET/PATCH /api/me`, `POST /api/me/consents` |
| Sensor/prediction | `POST /api/sensor-windows`, `GET /api/predictions/stream` (binary class plus class-1 probability) |
| Session | `POST /api/sessions`, `GET /api/sessions/{id}`, retry-safe `POST .../messages`, `.../assessments`, `.../finish`, `POST/GET .../reports` |
| Voice STT | `GET /api/stt/status`, `POST /api/sessions/{id}/transcriptions`; disabled by default |
| Patient dashboard | `GET /api/me/dashboard?range=24h|7d|30d`, `GET /api/me/craving-dashboard?timezone={iana}&eventRange=7d|30d&auqRange=today|7d|30d`, `GET /api/me/craving-probability-series?range=10m|24h|7d|30d`, `GET /api/me/predictions/{predictionId}/ppg-preview` |
| Administrator | Patients/timeline/dashboard, reason-gated reveal, temporary password, confirmed deletion, settings |
| Camera rPPG | Patient status/job/poll/retry and administrator capture summary/reveal/delete; enabled by default but fail-closed until ready |

The unauthenticated `/sensor-window`, `/prediction-stream`, `/api/llm/chat`, and `/api/intervention/*` paths are not current contracts.

## Persistence and Migration

- `20260715_0001` installs the 18-table authenticated baseline.
- `20260715_0002` adds `rppg_captures`, `rppg_analysis_jobs`, and camera-prediction linkage.
- `20260715_0003` adds `state_inferences`, new session interaction state, and multi-intervention ordering/evidence.
- `20260716_0004` adds the `free_dialogue` phase and retry-idempotency index for client message IDs.
- `20260717_0005` allows retained 10-second and current 20-second rPPG capture durations. It intentionally refuses downgrade while 20-second captures may exist.
- `apps/db/init.sql` installs PostgreSQL extensions only. Alembic is the only business-schema migration path.
- The fresh schema uses `postgres_data_v25`. Back up and preserve legacy `postgres_data`; do not run the new migration against it.
- Sensitive database payloads use AES-256-GCM with fresh nonces and table/column/patient/record AAD.
- Raw sensor windows use canonical JSON → gzip → AES-GCM storage under `SENSOR_STORAGE_ROOT`.
- `(patient_id, client_window_id)` makes identical sensor retries idempotent and conflicting reuse returns `409`.

## Free-Dialogue Session Contract

A patient can have one active `created|in_progress` session. New sessions start in `free_dialogue`, do not write `session_slots` or new `interventions`, and never return `slots`, `missingSlots`, or `handoffReady`. Existing structured sessions remain readable and pre-`0003` slot sessions remain read-only history.

Flow:

```text
optional AUQ → neutral free dialogue → manual finish or inactivity timeout
→ final evidence-linked state inference → `not_started` report status by default
```

Successful create/get/message/finish responses include the current `inactivityTimeoutSeconds`. New mobile messages include a `clientMessageId`; provider failure can be retried once with the same ID without duplicating the user message. Successful message responses add `userMessageId`, `assistantMessageId`, and `phase="free_dialogue"`.

The dialogue agent receives the newest 20 messages and an encrypted question/refusal ledger. It asks at most one short question, rejects normalized prior-question similarity, and must not make diagnostic, prescription, treatment-effect, certainty, or causal claims. Invalid output receives one repair; a second validation failure or provider failure returns structured HTTP `502` to the mobile retry UI rather than storing a fallback response. New free-dialogue turns do not create intervention rows.

Safety interpretation for new free-dialogue sessions is LLM-only and intended for research/demo use. The prompt requests 119/109 guidance for immediate-risk context, but the system does not guarantee detection, live connection, emergency dispatch, or automatic contact.

## State, Reports, and Dashboards

- Deterministic state inference aggregates prediction, alert, AUQ, session, and intervention evidence IDs.
- `STATE_SUMMARY_AI_ENABLED=false` is the default. Deterministic state and its
  evidence still persist, while public state snapshots return
  `summaryStatus="unavailable"` and `summary=null`. The backend makes no state
  summary Bedrock call and registers no state-summary model version.
- `REPORT_AI_ENABLED=false` is the default. Automatic report creation is
  skipped, and `POST /api/sessions/{id}/reports` remains a successful HTTP 202
  no-op with `{"reportId":null,"version":null,"status":"not_started"}`. Historical
  `GET` results remain readable and return `[]` when no report has been created.
  The backend makes no report Bedrock call or report model-version registration.
- Setting either flag to `true` restores its preserved Bedrock-backed behavior.
  When enabled, summaries use only supplied evidence, and reports use dialogue,
  AUQ, prediction, intervention, and state evidence. Public APIs expose report
  status and metadata, not decrypted report content.
- The mobile craving dashboard returns today's 24 local-hour four-stage stacked probability bars, 7/30-day alert-event counts, and today/7/30-day AUQ bars. Each populated hour exposes exact `low|observe|caution|high` counts while preserving average/minimum/maximum fields. Missing data remains distinct from a valid zero-event bucket.
- Patient dashboard PPG preview is owner-only and capped at 512 points.
- Administrator dashboard contains no raw PPG. Sensitive message/state/intervention/report reveal requires a reason, is audited, and returns `Cache-Control: no-store`.

## Binary Craving Model

The active predictor is the supplied two-class PyTorch `Conv1DNet`. The phone
submits the latest 20-second PPG/GSR window every 10 seconds after warm-up. Each
channel is linearly resampled to 1,024 samples, independently MinMax-normalized
without filtering, and evaluated as a `(1,2,1024)` `[PPG,GSR]` tensor.
Class 0 is `low`, class 1 is `high`, and `cravingProbability` is the class-1
softmax output. It is a research model output, not a diagnosis or calibrated
clinical severity; the final weights used all retained training data and have no
independent final-weight test evaluation.

The base Compose build installs the CPU PyTorch wheel. On DGX Spark use:

```powershell
docker compose -f apps/db/docker-compose.yml -f apps/db/docker-compose.dgx.yml up -d --build
```

Runtime status reports `actualDevice=cuda:0` after a successful GPU smoke
inference, or a code-only CPU fallback reason. Legacy three-class derived records
are never removed automatically. Stop prediction processing, back up PostgreSQL,
and run:

```powershell
docker compose run --rm backend python -m app.maintenance.purge_legacy_predictions --dry-run
docker compose run --rm backend python -m app.maintenance.purge_legacy_predictions --confirm DELETE-LEGACY-3CLASS-PREDICTIONS
```

## Fictional VP-012 Demo Dataset

`app.maintenance.seed_vp012_demo` provisions one login-capable fictional patient
(`woosik.jeong@neurotruth.kr`). With `DEMO_SCENARIO_ENABLED=true`, the first
successful login creates 120 days of deterministic dashboard-compatible demo
records relative to that login time. Existing app logout deletes the complete
demo account and scenario; provision it again before another take. The tooling
does not generate raw PPG/EDA files or call Bedrock, STT, rPPG, or the craving
model. Never present these records as research participant data.

For the reserved patient only, the same flag also overlays the first five live
Watch uploads after a fresh app login with the deterministic sequence
`0.38 → 0.62 → 0.82 → 0.86 → 0.89`. With the normal 20-second warm-up and
10-second upload cadence, the third consecutive danger result reaches the
existing PostgreSQL alert rule at about 60 seconds. The resulting alert still
uses the production SSE, Phone/Watch notification, 15-minute cooldown, AUQ, and
chat entry paths. No public demo trigger API exists, and non-demo patients
always keep the model result. App logout clears the client measurement session
and deletes the reserved account so another take starts from the first step.

The login-relative recent hour uses the checked-in 360-point
`Alcohol_Test/1_1_010_V1` demo trace at 10-second intervals. Its existing
MA10/value order and 60-minute time normalization are preserved. This trace
provides the “deep trough, rapid rebound, sustained high” demo shape; it is not
a physiological measurement from VP-012 and must not be described as one.
Historical calendar data uses weighted 15-minute representatives primarily
during deterministic morning, midday, and evening Watch-worn periods. Six of
the 120 days include a rare overnight Watch-worn period; overnight rows never
synthesize alerts. Each representative equals 90 ten-second samples, so a
fully measured hour still returns 360 samples while unmeasured hours remain
empty; no historical day is displayed as a continuous 24-hour measurement.
Each day deterministically receives 2–10 pseudo-random alert events, never more
than 10 and never less than 15 minutes apart. Login-day rows remain bounded by
the real login time. Every event is backed by a real three-danger sequence and
has one linked AUQ/session/report; AUQ values stay within the documented 0..48
research scale. The baseline is rebalanced so `low`/안정 occupies a visible
share of measured history. This is seed contract `vp012-120d-v7`; after
rebuilding the backend, delete and re-provision any older reserved demo account
before login.

The command reads the login password only from `DEMO_PATIENT_PASSWORD`. Do not
put the password in a command argument, committed `.env`, screenshot, terminal
recording, or operator document. The CLI validates password strength before it
opens the database.

On the DGX host, start in the repository root and enter the password without
echoing it. The example anchor below means that the generated 120-day range ends
on 2026-07-26 in `Asia/Seoul`; replace it with the actual recording date. A
future Seoul date is rejected.

```bash
cd ~/AI_Champion/neurotruth

read -rsp "VP-012 demo password: " DEMO_PATIENT_PASSWORD
echo
export DEMO_PATIENT_PASSWORD

DC=(
  sudo --preserve-env=DEMO_PATIENT_PASSWORD docker compose
  --env-file .env
  -f apps/db/docker-compose.yml
  -f apps/db/docker-compose.dgx.yml
)

# 1. Validate the planned dataset; this performs no writes.
"${DC[@]}" exec -T -e DEMO_PATIENT_PASSWORD backend \
  python -m app.maintenance.seed_vp012_demo \
  --dry-run \
  --anchor-date 2026-07-26

# 2. Provision only the reserved login, profile and consent (no history).
"${DC[@]}" exec -T -e DEMO_PATIENT_PASSWORD backend \
  python -m app.maintenance.seed_vp012_demo \
  --provision-confirm PROVISION-VP012-DEMO

# 3. Log in from the app. Login creates the 120-day scenario.
# 4. Print the reserved login, patient ID, date range, and table counts.
"${DC[@]}" exec -T -e DEMO_PATIENT_PASSWORD backend \
  python -m app.maintenance.seed_vp012_demo --status

# 5. End the take with the app logout action; it deletes the account first.
# Recovery only, when app logout cannot be completed:
"${DC[@]}" exec -T -e DEMO_PATIENT_PASSWORD backend \
  python -m app.maintenance.seed_vp012_demo \
  --delete-confirm DELETE-VP012-DEMO

unset DEMO_PATIENT_PASSWORD
```

For Docker Desktop in PowerShell, the same workflow is:

```powershell
Set-Location C:\path\to\neurotruth
$env:DEMO_PATIENT_PASSWORD = Read-Host "VP-012 demo password" -MaskInput
$DC = @(
  "compose", "--env-file", ".env",
  "-f", "apps/db/docker-compose.yml"
)

docker @DC exec -T -e DEMO_PATIENT_PASSWORD backend `
  python -m app.maintenance.seed_vp012_demo `
  --dry-run --anchor-date 2026-07-26

docker @DC exec -T -e DEMO_PATIENT_PASSWORD backend `
  python -m app.maintenance.seed_vp012_demo `
  --provision-confirm PROVISION-VP012-DEMO

# Log in from the app to create the scenario, then inspect its status.
docker @DC exec -T -e DEMO_PATIENT_PASSWORD backend `
  python -m app.maintenance.seed_vp012_demo --status

docker @DC exec -T -e DEMO_PATIENT_PASSWORD backend `
  python -m app.maintenance.seed_vp012_demo `
  --delete-confirm DELETE-VP012-DEMO

Remove-Item Env:DEMO_PATIENT_PASSWORD
```

Set `DEMO_SCENARIO_ENABLED=true` only for an explicit recording environment and
restart the backend before login. Always run `--dry-run` first. Provision,
fallback seeding, and deletion require exact confirmation strings and execute
under a database transaction and advisory lock. Login seeding runs once per
provision. App force-close, refresh, or network loss does not delete the demo;
only existing app logout does. A conflicting identity or incompatible contract
fails instead of partially reconciling data. `--confirm
SEED-VP012-120D-DEMO --anchor-date YYYY-MM-DD` remains an operator fallback.
`--status` never prints the password, encryption keys, or decrypted persona
content. The Korean actual-device recording pack is
[`docs/demo/VP-012_120day_demo_video.ko.md`](../../docs/demo/VP-012_120day_demo_video.ko.md).

## Optional STT and DGX Spark rPPG

`STT_ENABLED=false` is the default. On DGX, the primary service runs Hugging Face Whisper `large-v3-turbo` with PyTorch CUDA; an explicitly configured base-Compose deployment may use the CTranslate2 CPU fallback. The backend proxies active-session Korean `.m4a|.wav` audio to that internal service. Audio exists only in tmpfs and is deleted after the request; only user-confirmed final text is retained by the normal encrypted message path. Android TTS is local and has no backend route.

`RPPG_ENABLED=true` is the default, while operators can set it to `false` as an emergency or deployment-level off switch. Enabled does not mean ready: storage, DGX, model, and runtime readiness checks must still pass before the backend accepts a 20-second camera job. Accepted jobs retain encrypted videos/provider data, call DGX FactorizePhys, validate quality, resample rPPG to 1,024 points at 51.2 Hz, add 1,024 zero-valued EDA points, and call the binary craving model. Legacy 10-second rows remain readable. Mobile never receives the DGX address. The feature is not release-ready before controlled real-phone/DGX validation.

## Required Configuration

Required values include `DATABASE_URL`, `DATA_ENCRYPTION_KEYS_B64`, `DATA_ENCRYPTION_CURRENT_KEY_ID`, `JWT_SIGNING_KEY`, `ADMIN_SIGNUP_CODE`, `SENSOR_STORAGE_ROOT`, `APP_ENV`, transport policy, and binary model configuration. `STATE_SUMMARY_AI_ENABLED=false` and `REPORT_AI_ENABLED=false` are the defaults; live Bedrock credentials/model settings are required for enabled AI calls. STT and rPPG settings are required only when their feature flag is enabled. Production STT mounts `STT_PYTORCH_MODEL_PATH`; `STT_MODEL_PATH` is the CTranslate2 CPU fallback. See the root `.env.example`.

## Latest Validation

| Check | Result |
|---|---|
| Full backend pytest | Layered backend and feature flags PASS: 159 passed, 1 skipped |
| Compileall | Layered backend and feature flags PASS |
| Alembic head | `20260717_0005` |
| Docker runtime | Previously verified at Alembic head `20260717_0005`: live status routes, authenticated dialogue, sensor-to-SSE prediction ID identity, private timing-field filtering, and graceful restart PASS; this documentation-only refresh did not rebuild containers |
| Mobile contract | Existing mobile session API contract test PASS |
| Model smoke | Feature baseline PASS on CPU with 1,024-sample input and configured checksum |
| STT/rPPG | Status routes validated with STT default-off and rPPG default-on/readiness-gated; real DGX acceptance remains required |
