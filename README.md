# NeuroTruth

Last updated: 2026-07-19

NeuroTruth is an authenticated wearable-assisted supportive intervention research prototype. It is intended for people receiving CBT or willing to seek treatment who need ongoing records and dialogue support in craving situations; it is not a treatment, diagnostic, or emergency-response app. A Watch is optional: Android checks connected Wear nodes client-side, uses Watch sensor windows when connected, and otherwise promotes a manual foreground 20-second camera-rPPG action. The FastAPI backend owns patient identity, AES-256-GCM persistence, model/prompt traceability, state inference, reports, and audit records. The React web surface is administrator-only.

## Intervention-First Flow

```text
Patient signup/login on phone
  -> watch sensor batches relayed through authenticated phone
  -> POST /api/sensor-windows (encrypted raw retention)
  -> GET /api/predictions/stream
  -> user chooses Talk now or Later
  -> optional AUQ
  -> retry-safe free dialogue by text or optional Korean STT
  -> evidence-linked state inference + report status (`not_started` by default)
  -> patient/admin dashboards
```

Access tokens last 15 minutes by default. Opaque 30-day refresh tokens rotate on every refresh and are stored only as hashes in PostgreSQL. The watch never holds backend credentials or calls the backend directly.

## Repository Layout

```text
apps/backend  Single FastAPI process: api/v1, schemas, services, agents/prompts, ml, models, repositories, adapters, storage, core, maintenance
apps/mobile   Android phone and Wear OS relay
apps/web      Administrator-only React console
apps/db       PostgreSQL 16 Compose stack and extension bootstrap
docs          Current developer/AI docs and historical dated specs
```

## Quick Start

Create `.env` and replace every placeholder secret:

```powershell
Copy-Item .env.example .env
docker compose -f apps/db/docker-compose.yml config --no-env-resolution
docker compose -f apps/db/docker-compose.yml up -d --build
```

The authenticated encrypted schema is a hard cut. Compose applies the fresh Alembic baseline to `postgres_data_v25` and stores encrypted raw windows in `encrypted_sensor_data`. Back up and preserve any legacy `postgres_data` volume; do not point Alembic at it and do not expect backfill or downgrade conversion.

The repository-contained schema authorities are `neurotruth_schema_definition_v2_5.md` and `neurotruth_schema_v2_5.sql` at this repository root. Alembic and Compose resolve only the in-repository SQL and do not depend on files in the parent workspace.

Backend and Android validation:

```powershell
cd apps/backend
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m compileall -q app tests
.\.venv\Scripts\python.exe -m alembic upgrade head

cd ../mobile
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

## Binary craving model deployment

The active model is the two-class PyTorch `Conv1DNet`. After a 20-second warm-up, the phone submits the latest 20-second PPG/GSR window every 10 seconds. The backend resamples each channel to 1,024 points and stores/streams softmax `p(class 1)` as a research-use model likelihood. Patient UI maps the raw value to four descriptive display bands rather than showing an exact percentage. It is not a diagnosis, calibrated clinical severity, or treatment-effect measure.

DGX Spark deployment prefers CUDA and falls back to CPU only when CUDA cannot complete a smoke inference. Start the normal Compose stack with the DGX GPU override and verify the model status reports `actualDevice=cuda:0` before device acceptance. The base Compose remains CPU-capable. No deployment port is changed by the model transition.

Before the binary rollout, back up PostgreSQL and stop prediction processing. Preview and then explicitly remove only legacy three-class derived predictions:

```powershell
docker compose run --rm backend python -m app.maintenance.purge_legacy_predictions --dry-run
docker compose run --rm backend python -m app.maintenance.purge_legacy_predictions --confirm DELETE-LEGACY-3CLASS-PREDICTIONS
```

The command preserves raw sensors, users, consent, AUQ, sessions, messages, interventions, reports, and rPPG captures/jobs. Deleted derived records require the pre-deployment database backup for recovery.

## Required Security Configuration

| Variable | Purpose |
|---|---|
| `DATABASE_URL` | PostgreSQL 16+ connection |
| `DATA_ENCRYPTION_KEYS_B64` | Versioned `keyId:base64(32 bytes)` AES keyring |
| `DATA_ENCRYPTION_CURRENT_KEY_ID` | Key used for new writes |
| `JWT_SIGNING_KEY` | Access-token signing key, at least 32 bytes |
| `ADMIN_SIGNUP_CODE` | Initial/rotatable administrator code, at least 16 characters |
| `SENSOR_STORAGE_ROOT` | Backend-only encrypted sensor volume |
| `APP_ENV` | `development`, `test`, or `production` |
| `ALLOW_INSECURE_HTTP` | Explicit local/LAN HTTP override; forbidden in production |
| `STATE_SUMMARY_AI_ENABLED` | Optional state-summary Bedrock calls; defaults to `false` |
| `REPORT_AI_ENABLED` | Optional report Bedrock calls; defaults to `false` |
| `STT_ENABLED` | Optional internal Korean Whisper feature flag; defaults to `false` |
| `STT_PYTORCH_MODEL_PATH` | Primary host path to the production PyTorch Whisper model |
| `STT_MODEL_PATH` | Host path to the CTranslate2 CPU fallback model |
| `RPPG_ENABLED` | Camera-rPPG feature flag; defaults to `true` and can be set to `false` |
| `RPPG_BASE_URL` | Backend-only DGX Spark service URL; never shipped to mobile |
| `RPPG_STORAGE_ROOT` | Backend-only AES-256-GCM face-video storage |

Missing database, migration, or encryption configuration makes readiness fail. Production requires HTTPS. Do not commit real secrets, decrypted content, databases, sensor files, or tokens.

## Current Public API

- Authentication: `/api/auth/patient/signup`, `/api/auth/admin/signup`, `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`, `/api/auth/change-password`.
- Patient profile/consent/dashboard: `GET/PATCH /api/me`, `POST /api/me/consents`, `GET /api/me/dashboard`.
- Sensors: `POST /api/sensor-windows`, `GET /api/predictions/stream`.
- Sessions: `POST /api/sessions`, then `GET`, `messages`, `assessments`, `finish`, and `reports` under `/api/sessions/{uuid}`.
- Voice STT: authenticated `GET /api/stt/status` and `POST /api/sessions/{uuid}/transcriptions`; disabled by default. AI speech output uses Android-local TTS.
- Administrator: patients/timeline, restricted patient dashboards, reason-gated reveal, temporary password, confirmed deletion, and global settings under `/api/admin`.
- Camera rPPG (enabled by default, readiness-gated): patient status/upload/job polling/manual retry under `/api/rppg`; administrator summary, reason-gated inline playback, and confirmed deletion under `/api/admin/rppg`.

The unauthenticated `/sensor-window`, `/prediction-stream`, `/api/llm/chat`, and `/api/intervention/*` contracts are not current APIs.

## Boundaries

- New sessions do not write the legacy 13 slots or expose `handoffReady`; pre-redesign slot sessions remain read-only history without backfill.
- Korean STT is an optional, disabled-by-default DGX service and TTS runs locally through Android `TextToSpeech`; real-device acceptance is still required. Self-event capture, wearable-absent AUQ automation, and model retraining experiments remain deferred. Camera rPPG is a user-started point-in-time measurement, never continuous/background monitoring. It requires the existing four consents and ready `/api/rppg/status`; unavailable service means no fallback measurement. `RPPG_ENABLED=false` remains the operational off switch, and real-phone/DGX acceptance is still required.
- State-summary and report AI are disabled by default. Deterministic state evidence still persists; summaries return `unavailable`/`null`, report creation returns `not_started`, and enabling the corresponding flag restores the preserved Bedrock behavior.
- Binary low/high predictions and class-1 probability, AUQ, dialogue, and interventions are presented as separate evidence. The Phone alone shows the probability graph; administrator web and Watch do not. The UI never claims immediate craving reduction, CBT efficacy, diagnosis, treatment success, calibrated severity, or causal effect.
- There is no bulk dataset-download endpoint.
- Every accepted camera video, including quality and technical failures, is retained encrypted until audited administrator deletion. Inline playback has no download button, but a privileged viewer can technically preserve rendered bytes; least privilege, policy, and audit remain required.
- Safety-risk dialogue may offer administrator involvement once and show the Korean 109 resource, but no live administrator chat, emergency queue, automatic contact, or connection guarantee exists.
- `interventionsEnabled=false` suppresses normal interventions only; safety guidance remains available.

See [backend operations](apps/backend/README.md), [database operations](apps/db/README.md), [mobile operations](apps/mobile/README.md), [server API](apps/mobile/SERVER_API_SPEC.md), [development environment](docs/dev-environment.md), and [agent behavior](docs/ai/agents/README.md).

## GitHub Upload and Review

- [Current GitHub upload changes](../deployment/GITHUB_UPLOAD_CHANGES.md)
- [Ready-to-paste pull request body](../deployment/PULL_REQUEST_DESCRIPTION.md)
- [Git commit and pull request guide](../deployment/GIT_COMMIT_AND_PULL_REQUEST_GUIDE.md)
