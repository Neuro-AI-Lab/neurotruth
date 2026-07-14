# NeuroTruth Development Environment

Last updated: 2026-07-13

## Layout

```text
apps/backend   FastAPI prediction, alerts, memory, Bedrock intervention
apps/web       React/nginx web surface
apps/mobile    Android phone + Wear OS project
apps/db        Postgres schema and Docker Compose stack
```

## Environment

Create root `.env` if it does not already exist:

```powershell
Copy-Item .env.example .env
```

Important variables:

| Variable | Purpose |
|---|---|
| `POSTGRES_USER` | Docker Postgres user |
| `POSTGRES_PASSWORD` | Docker Postgres password |
| `POSTGRES_DB` | Docker Postgres database |
| `DATABASE_URL` | Optional non-Docker backend DB URL |
| `MODEL_PATH` | Optional local model bundle override |
| `AWS_BEARER_TOKEN_BEDROCK` | Bedrock API key bearer token |
| `BEDROCK_MODEL_ID` | Bedrock model ID; defaults to `openai.gpt-5.5` |
| `AWS_REGION` | Bedrock region |
| `AWS_DEFAULT_REGION` | Optional fallback region |
| `ALERT_*` | Alert rule tuning |

## Backend

```powershell
cd apps/backend
python -m compileall app tests
python -m pytest
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Latest backend validation is 50 passing tests. The handoff API exposes the backward-compatible synchronous route plus asynchronous job submission/status routes:

```text
POST /api/intervention/handoff
POST /api/intervention/handoff/jobs
GET  /api/intervention/handoff/jobs/{job_id}
```

System prompts and Bedrock agent behavior live in:

```text
apps/backend/app/ai/bedrock_agents.py
```

Prompt constants:

```text
CHAT_SYSTEM_PROMPT
SLOTS_SYSTEM_PROMPT
HANDOFF_SYSTEM_PROMPT
```

`openai.*` model IDs use the Bedrock Mantle Responses API and require
`AWS_BEARER_TOKEN_BEDROCK`. Other model IDs preserve the Bedrock Runtime
Converse bearer/IAM path for rollback. GPT-5.5 was verified in `us-east-1`.

## Docker

```powershell
docker compose -f apps/db/docker-compose.yml config --no-env-resolution
docker compose -f apps/db/docker-compose.yml up -d --build
```

Health check:

```powershell
Invoke-RestMethod -Uri http://localhost:8000/health
```

## Android

```powershell
cd apps/mobile
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

Android currently uses the laptop LAN backend URL in:

```text
apps/mobile/app/src/main/assets/server_config.properties
```

Current local test value:

```properties
sensor_post_url=http://192.168.68.51:8000/sensor-window
prediction_sse_url=http://192.168.68.51:8000/prediction-stream
```

If the laptop network changes, find the active LAN IPv4 address:

```powershell
Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -eq 'Up' }
```

Android build requirements:

| Requirement | Current project location |
|---|---|
| Android SDK path | `apps/mobile/local.properties` or `ANDROID_HOME` |
| JDK 17 | pinned through `apps/mobile/gradle.properties` |
| Samsung Health Sensor SDK AAR | `apps/mobile/wearos/libs/samsung-health-sensor-api-1.4.1.aar` |
| Phone backend URL | `apps/mobile/app/src/main/assets/server_config.properties` |

Install debug APKs:

```powershell
adb devices
adb install -r apps/mobile/app/build/outputs/apk/debug/app-debug.apk
adb -s <WATCH_SERIAL> install -r apps/mobile/wearos/build/outputs/apk/debug/wearos-debug.apk
```

Use `adb devices`, not `adb device`. The second column must show `device`, not `unauthorized` or `offline`.

Latest verified Android state:

| Check | Result |
|---|---|
| `apps/mobile/gradlew.bat assembleDebug` | PASS |
| `apps/mobile/gradlew.bat testDebugUnitTest` | PASS after AUQ/async-handoff changes |
| `apps/mobile/gradlew.bat lintDebug` | PASS after latest mobile changes |
| Phone install | Latest debug APK install and launch PASS on `SM-S926N` |
| Watch install | Latest debug APK install and launch PASS on `SM-L320` |
| Full GPT-5.5 chat smoke | Known issue: HTTP 502 during slot extraction (`Bedrock slot request failed`) |

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Bedrock request fails | For GPT-5.5: missing `AWS_BEARER_TOKEN_BEDROCK`, model access, or Mantle region; for Converse models: missing bearer/IAM credentials, model access, or region |
| Model status is not ready | Missing `rf_dependent.joblib` or wrong `MODEL_PATH` |
| Compose cannot resolve env values | Root `.env` missing |
| Android build fails | SDK path or Samsung SDK AAR missing |
| Phone cannot reach backend | Phone is using `localhost`, wrong LAN IP, different Wi-Fi, firewall, or backend container is down |
| Active chat is replaced by AUQ | Confirm the current APK is installed; the latest app uses the shared active-intervention latch |
| Chat response times out too early | In administrator mode, set `채팅 응답 제한시간(분)`; default is 60 and valid range is 1–1,440 |
| Handoff job returns 404 after submission | Backend may have restarted; process-local jobs are not durable and are not automatically resubmitted |
| `adb.exe: unknown command device` | Command should be `adb devices` |
