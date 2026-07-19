# NeuroTruth Development Environment

Last updated: 2026-07-19

## Prerequisites

- Docker Desktop with PostgreSQL 16 image support
- Python environment from `apps/backend/requirements.txt`
- JDK 17 and Android SDK
- Samsung Health Sensor SDK AAR for the Wear OS module

## Environment

```powershell
Copy-Item .env.example .env
```

Replace all secret placeholders. The authenticated encrypted stack requires:

```text
DATABASE_URL
DATA_ENCRYPTION_KEYS_B64=v1:<base64 encoded 32-byte key>[,v2:...]
DATA_ENCRYPTION_CURRENT_KEY_ID=v1
JWT_SIGNING_KEY=<at least 32 random bytes>
ADMIN_SIGNUP_CODE=<at least 16 characters>
SENSOR_STORAGE_ROOT=/var/lib/neurotruth/sensors
APP_ENV=development
ALLOW_INSECURE_HTTP=true
STATE_SUMMARY_AI_ENABLED=false
REPORT_AI_ENABLED=false
STT_ENABLED=false
STT_PYTORCH_MODEL_PATH=../../models/whisper-large-v3-turbo-pytorch
STT_MODEL_PATH=../../models/whisper-large-v3-turbo
STT_CONNECT_TIMEOUT_SECONDS=5
STT_READ_TIMEOUT_SECONDS=120
STT_MAX_UPLOAD_MIB=10
RPPG_ENABLED=true
RPPG_BASE_URL=http://192.168.68.50:8000
RPPG_CONNECT_TIMEOUT_SECONDS=10
RPPG_READ_TIMEOUT_SECONDS=180
RPPG_MAX_UPLOAD_MIB=40
RPPG_MAX_CONCURRENCY=1
RPPG_STORAGE_ROOT=/var/lib/neurotruth/rppg
```

`ALLOW_INSECURE_HTTP=true` is only for explicit development/test LAN use. Production rejects it and requires HTTPS. Never print or commit credentials, keys, tokens, decrypted content, or retained sensor files.

## Fresh Authenticated Stack

```powershell
docker compose -f apps/db/docker-compose.yml config --no-env-resolution
docker compose -f apps/db/docker-compose.yml up -d --build
```

Compose uses `postgres_data_v25` and `encrypted_sensor_data`. The backend runs `alembic upgrade head` before Uvicorn; `apps/db/init.sql` installs extensions only. Back up and preserve any legacy `postgres_data` volume. There is no legacy backfill; rollback means reconnecting the legacy image/volume, not downgrading new-schema data.

If Docker is unavailable, run against a fresh PostgreSQL 16+ database:

```powershell
cd apps/backend
.\.venv\Scripts\python.exe -m alembic upgrade head
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m compileall -q app tests
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 25991
```

`/ready` fails when the core DB, Alembic head, security/keyring, or required storage is unavailable. Craving-model readiness is reported separately by `/model/status`; an unavailable model keeps core auth/chat usable but makes sensor prediction return `503` when AI analysis is requested.

## Intervention-First Coordinated Release

Apply Alembic through required head `20260717_0005`, then deploy backend and Android together because new-session responses remove `slots`, `missingSlots`, and `handoffReady`. Deploy the administrator web immediately after the backend. Do not edit existing revisions or the baseline SQL. Revision `0005` retains legacy 10-second rPPG rows, accepts current 20-second rows, and intentionally refuses downgrade while 20-second captures may exist.

New sessions use optional AUQ and LLM-only safety-aware free dialogue. They write messages and the question/refusal ledger but no new intervention rows. Deterministic evidence-linked state inference continues; state-summary and report AI are default-off, returning `unavailable`/`null` and `not_started` until their flags are enabled. The administrator dashboard calls `/api/admin/patients/{patientId}/dashboard?range=24h|7d|30d` and must never receive raw PPG or render report bodies. Pre-`0003` slot sessions remain read-only history.

## Optional DGX Spark rPPG

`RPPG_ENABLED=true` is the default; set it to `false` when the deployment must disable camera rPPG. The feature flag alone does not make capture available: storage, DGX, model, and `/api/rppg/status` readiness must still pass. In LAN development the backend may call the private DGX address above; Android calls only the authenticated NeuroTruth backend. Every accepted camera video is permanently retained encrypted under `RPPG_STORAGE_ROOT` until audited deletion.

Camera controls and the administrator capture section stay absent unless rPPG status reports both enabled and ready/available. Core Watch PPG/GSR → alert → intervention → database → dashboard behavior must not depend on rPPG.

For co-deployment, change `RPPG_BASE_URL` to `http://rppg:8000`, keep the DGX port unpublished on the internal Docker network, mount its upload temporary directory as tmpfs, and verify `DELETE_UPLOADED_VIDEO=true`. DGX must not retain plaintext uploads. The feature is not release-ready until the current 20-second phone video, 1,024-point rPPG plus 1,024-point zero-EDA input at 51.2 Hz, phone-only result routing, and both backend/DGX plaintext cleanup are verified.

## Android

Set the backend base URL in `apps/mobile/app/src/main/assets/server_config.properties`:

```properties
api_base_url=http://SERVER_HOST:25991
sensor_post_url=http://SERVER_HOST:25991/api/sensor-windows
prediction_sse_url=http://SERVER_HOST:25991/api/predictions/stream
```

Use the laptop LAN address on physical devices; phone `localhost` means the phone itself. HTTP requires the backend development override above.

```powershell
cd apps/mobile
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <WATCH_SERIAL> install -r wearos/build/outputs/apk/debug/wearos-debug.apk
```

The phone owns patient signup/login, rotating refresh credentials, consent, authenticated uploads/SSE, and UUID sessions. The watch sends 20-second sensor windows every 10 seconds to the phone and receives prediction state from it; it has no backend credential or direct backend connection. Optional STT places editable Korean text in the same `/messages` flow as typed input, differing only by `inputModality`; TTS is Android-local.

## Troubleshooting

| Symptom | Check |
|---|---|
| Backend readiness failure | PostgreSQL reachability, Alembic head, AES keyring/current key, JWT key, admin code, storage root; check craving readiness separately at `/model/status` |
| `401` after retry | Refresh token expired/replayed/revoked; sign in again |
| `403` on sensors/AI/report | Required feature consent is missing or withdrawn |
| `409` on sensor upload | Reused `clientWindowId` has conflicting content |
| Phone cannot connect | LAN IP, same network, firewall, HTTPS or explicit development HTTP override |
| Second conversation rejected | Resume the existing active UUID session or finish it; concurrent message handling returns `message_in_progress` |
| rPPG status unavailable | Confirm `RPPG_ENABLED`, DGX health/model load, private routing, writable encrypted storage, and queue prerequisites |

Korean STT is implemented as an optional disabled-by-default DGX service, while TTS is Android-local. Self-event capture, wearable-absent AUQ automation, craving-model balancing/label experiments, live administrator chat, emergency dispatch, and bulk dataset export remain deferred. Camera rPPG is enabled by default but remains consent- and readiness-gated, with `RPPG_ENABLED=false` available as the deployment off switch.
