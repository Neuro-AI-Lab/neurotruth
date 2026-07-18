# NeuroTruth Backend

Last updated: 2026-07-18

FastAPI backend for the authenticated intervention-support platform. Readiness fails closed when PostgreSQL, the expected Alembic revision, required security settings, or the AES-256-GCM keyring are unavailable.

## Run and Validate

```powershell
cd apps/backend
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m compileall -q app tests
alembic upgrade head
uvicorn app.main:app --host 0.0.0.0 --port 25991
```

Use `ALLOW_INSECURE_HTTP=true` only with `APP_ENV=development|test`. Production requires HTTPS.

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
| Camera rPPG | Patient status/job/poll/retry and administrator capture summary/reveal/delete; disabled by default |

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
→ final evidence-linked state inference → asynchronous report status
```

Successful create/get/message/finish responses include the current `inactivityTimeoutSeconds`. New mobile messages include a `clientMessageId`; provider failure can be retried once with the same ID without duplicating the user message. Successful message responses add `userMessageId`, `assistantMessageId`, and `phase="free_dialogue"`.

The dialogue agent receives the newest 20 messages and an encrypted question/refusal ledger. It asks at most one short question, rejects normalized prior-question similarity, and must not make diagnostic, prescription, treatment-effect, certainty, or causal claims. Invalid output receives one repair; provider or validation failure is returned to the mobile retry UI rather than hidden behind a fallback response.

Safety interpretation for new free-dialogue sessions is LLM-only and intended for research/demo use. The prompt requests 119/109 guidance for immediate-risk context, but the system does not guarantee detection, live connection, emergency dispatch, or automatic contact.

## State, Reports, and Dashboards

- Deterministic state inference aggregates prediction, alert, AUQ, session, and intervention evidence IDs.
- The LLM may summarize only supplied evidence. Failure leaves deterministic state intact and marks summary `unavailable`.
- Reports use dialogue, AUQ, prediction, intervention, and state evidence. Public session/report APIs return status and metadata, not decrypted report body.
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

## Optional STT and DGX Spark rPPG

`STT_ENABLED=false` is the default. When enabled, the backend proxies active-session Korean `.m4a|.wav` audio to the internal `faster-whisper` `large-v3-turbo` service. Audio exists only in tmpfs and is deleted after the request; only user-confirmed final text is retained by the normal encrypted message path. Android TTS is local and has no backend route.

`RPPG_ENABLED=false` is also the default. When explicitly enabled and ready, the backend accepts current 20-second camera jobs, retains encrypted videos/provider data, calls DGX FactorizePhys, validates quality, resamples rPPG to 1,024 points at 51.2 Hz, adds 1,024 zero-valued EDA points, and calls the binary craving model. Legacy 10-second rows remain readable. Mobile never receives the DGX address. The feature is not release-ready before controlled real-phone/DGX validation.

## Required Configuration

Required values include `DATABASE_URL`, `DATA_ENCRYPTION_KEYS_B64`, `DATA_ENCRYPTION_CURRENT_KEY_ID`, `JWT_SIGNING_KEY`, `ADMIN_SIGNUP_CODE`, `SENSOR_STORAGE_ROOT`, `APP_ENV`, transport policy, binary model configuration, and live Bedrock credentials/model settings. STT and rPPG settings are required only when their feature flag is enabled. See the root `.env.example`.

## Latest Validation

| Check | Result |
|---|---|
| Full backend pytest | Feature baseline PASS: 166 passed, 1 skipped; rerun after cleanup |
| Compileall | Feature baseline PASS; rerun after cleanup |
| Alembic head | `20260717_0005` |
| Docker runtime | Feature baseline `/health` and `/ready` PASS; rerun after rebuild |
| Model smoke | Feature baseline PASS on CPU with 1,024-sample input and configured checksum |
| STT/rPPG | Status routes validated with both features disabled by default; real DGX acceptance remains required |
