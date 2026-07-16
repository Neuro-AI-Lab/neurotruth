# NeuroTruth

Last updated: 2026-07-16

NeuroTruth is an authenticated wearable-assisted supportive intervention research prototype. It is intended for people receiving CBT or willing to seek treatment who need ongoing records and dialogue support in craving situations; it is not a treatment, diagnostic, or emergency-response app. Patients register on Android, grant feature-specific consent, upload Watch sensor windows, receive possible-craving alerts, and choose whether to begin a safety-aware intervention conversation. The FastAPI backend owns patient identity, AES-256-GCM persistence, model/prompt traceability, state inference, reports, and audit records. The React web surface is administrator-only.

## Intervention-First Flow

```text
Patient signup/login on phone
  -> watch sensor batches relayed through authenticated phone
  -> POST /api/sensor-windows (encrypted raw retention)
  -> GET /api/predictions/stream
  -> user chooses Talk now or Later
  -> optional AUQ + safety check + deterministic first intervention
  -> structured autonomous dialogue without slot completion targets
  -> evidence-linked state inference + async report status
  -> patient/admin dashboards
```

Access tokens last 15 minutes by default. Opaque 30-day refresh tokens rotate on every refresh and are stored only as hashes in PostgreSQL. The watch never holds backend credentials or calls the backend directly.

## Repository Layout

```text
apps/backend  FastAPI authenticated API, binary PyTorch prediction, Bedrock agents, encryption
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
python -m pytest

cd ../mobile
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

## Binary craving model deployment

The active model is the two-class PyTorch `Conv1DNet`. It consumes a ten-second PPG/GSR window every second and stores/streams softmax `p(class 1)` as a research-use model craving likelihood. It is not a diagnosis, calibrated clinical severity, or treatment-effect measure. The final weights used all available training data and have no independent final-weight test evaluation.

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
| `RPPG_ENABLED` | Optional camera-rPPG feature flag; defaults to `false` |
| `RPPG_BASE_URL` | Backend-only DGX Spark service URL; never shipped to mobile |
| `RPPG_STORAGE_ROOT` | Backend-only AES-256-GCM face-video storage |

Missing database, migration, or encryption configuration makes readiness fail. Production requires HTTPS. Do not commit real secrets, decrypted content, databases, sensor files, or tokens.

## Current Public API

- Authentication: `/api/auth/patient/signup`, `/api/auth/admin/signup`, `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`, `/api/auth/change-password`.
- Patient profile/consent/dashboard: `GET/PATCH /api/me`, `POST /api/me/consents`, `GET /api/me/dashboard`.
- Sensors: `POST /api/sensor-windows`, `GET /api/predictions/stream`.
- Sessions: `POST /api/sessions`, then `GET`, `messages`, `assessments`, `finish`, and `reports` under `/api/sessions/{uuid}`.
- Administrator: patients/timeline, restricted patient dashboards, reason-gated reveal, temporary password, confirmed deletion, and global settings under `/api/admin`.
- Camera rPPG (disabled by default): patient status/upload/job polling/manual retry under `/api/rppg`; administrator summary, reason-gated inline playback, and confirmed deletion under `/api/admin/rppg`.

The unauthenticated `/sensor-window`, `/prediction-stream`, `/api/llm/chat`, and `/api/intervention/*` contracts are not current APIs.

## Boundaries

- New sessions do not write the legacy 13 slots or expose `handoffReady`; pre-redesign slot sessions remain read-only history without backfill.
- Voice/STT/TTS, self-event capture, wearable-absent AUQ automation, and model retraining experiments are deferred. Camera rPPG is an experimental, disabled-by-default extension and is not release-ready until the real-phone/DGX validation gate passes.
- Binary low/high predictions and class-1 probability, AUQ, dialogue, and interventions are presented as separate evidence. The Phone alone shows the probability graph; administrator web and Watch do not. The UI never claims immediate craving reduction, CBT efficacy, diagnosis, treatment success, calibrated severity, or causal effect.
- There is no bulk dataset-download endpoint.
- Every accepted camera video, including quality and technical failures, is retained encrypted until audited administrator deletion. Inline playback has no download button, but a privileged viewer can technically preserve rendered bytes; least privilege, policy, and audit remain required.
- Safety-risk dialogue may offer administrator involvement once and show the Korean 109 resource, but no live administrator chat, emergency queue, automatic contact, or connection guarantee exists.
- `interventionsEnabled=false` suppresses normal interventions only; safety guidance remains available.

See [backend operations](apps/backend/README.md), [database operations](apps/db/README.md), [mobile operations](apps/mobile/README.md), [server API](apps/mobile/SERVER_API_SPEC.md), [development environment](docs/dev-environment.md), and [agent behavior](docs/ai/agents/README.md).

## GitHub Upload and Review

- [Cumulative GitHub upload changes](../260715/GITHUB_UPLOAD_CHANGES.md)
- [Ready-to-paste pull request body](../260715/PULL_REQUEST_DESCRIPTION.md)
- [Git commit and pull request guide](../260715/GIT_COMMIT_AND_PULL_REQUEST_GUIDE.md)
- Korean: [업로드 변경사항](../260715/GITHUB_UPLOAD_CHANGES.ko.md), [PR 본문](../260715/PULL_REQUEST_DESCRIPTION.ko.md), [커밋·PR 가이드](../260715/GIT_COMMIT_AND_PULL_REQUEST_GUIDE.ko.md)
