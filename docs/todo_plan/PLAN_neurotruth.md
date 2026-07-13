# PLAN: NeuroTruth

Last updated: 2026-07-13

## Current Structure

- `apps/backend`: backend API, local craving model, Bedrock intervention
- `apps/web`: web surface
- `apps/mobile`: Android phone and Wear OS app
- `apps/db`: Postgres schema and Docker stack

## Completed

- Moved toward a simplified backend-owned AI architecture.
- Kept Android public endpoint paths stable.
- Kept local craving model ownership in backend.
- Added DB-centered Docker Compose.
- Added a superseding unified backend structure spec.
- Added Bedrock bearer-token support through `AWS_BEARER_TOKEN_BEDROCK`.
- Restored phone and Wear OS UX from the working `watch_test` environment.
- Reconnected mobile intervention chat and handoff to backend endpoints.
- Set physical-device test URLs to the laptop LAN backend.
- Built Android debug APKs successfully.
- Ran Android unit tests successfully.
- Installed phone and watch debug APKs successfully.
- Isolated backend alert windows, cooldowns, and downtrend state per session.
- Added conservative alert warm-up and class-2 high-streak early-required behavior.
- Unified phone sensor and intervention traffic under one shared session ID.
- Applied backend alert action precedence consistently on phone and watch.
- Fixed Wear OS standalone lint metadata and passed Android lint with zero errors.
- Verified same-session sensor, SSE, Postgres, Bedrock chat, and handoff integration in Docker.
- Updated active repository, backend, web, DB, mobile, PRD, AI-agent, development, and upload documentation to the verified 2026-07-10 state.
- Added ready-to-use English/Korean pull request descriptions, a Korean upload guide, and a Korean PRD mirror.
- Added completion-aware dialogue/slot prompts and deterministic repeated-question repair.
- Added an active-intervention latch so later required alerts do not reopen AUQ during chat.
- Added a persisted administrator chat response timeout setting with a 60-minute default and 1–1,440 minute range.
- Added an intervention-only reset that preserves sensor history and server URLs.
- Added HTTP 202 asynchronous handoff jobs with status polling, sanitized failures, capacity/TTL/shutdown handling, and a one-hour runtime bound.
- Kept the synchronous handoff endpoint backward-compatible and chat usable while handoff generation runs.
- Passed 50 backend tests, 22 Android unit tests, Phone/Wear builds, Android lint, Docker health, and async OpenAPI smoke.
- Migrated the Bedrock default to `openai.gpt-5.5` through Mantle Responses while preserving the Claude Converse rollback path; GPT-5.6 was not available in the verified account/region.
- Reinstalled and launched the latest debug APKs on phone `SM-S926N` and watch `SM-L320`.
- Updated cumulative GitHub upload notes, PR descriptions, and active project docs for the first repository upload while preserving the existing private-repository `.gitignore` policy.

## Verified Checks

```powershell
cd apps/backend
python -m compileall app tests
python -m pytest
```

```powershell
docker compose -f apps/db/docker-compose.yml config --no-env-resolution
git diff --check
```

```powershell
cd apps/mobile
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

## Setup Tasks

- Keep root `.env` present and updated.
- Confirm `rf_dependent.joblib` exists at `apps/backend/model/weights/rf_dependent.joblib` or set `MODEL_PATH`.
- Configure `AWS_BEARER_TOKEN_BEDROCK`, `BEDROCK_MODEL_ID`, and AWS region values.
- Keep Samsung Health Sensor SDK AAR under `apps/mobile/wearos/libs/`.
- Create `apps/mobile/local.properties` separately on each Android development PC or set `ANDROID_HOME`; do not reuse the deleted laptop-specific absolute path on another machine or server.
- Update `apps/mobile/app/src/main/assets/server_config.properties` if the laptop LAN IP changes.

## Next Checks

- Fix and revalidate the GPT-5.5 full `/api/intervention/chat` slot-extraction path; the minimal Mantle call passes, but the latest route smoke returned HTTP 502 (`Bedrock slot request failed`).
- Run a real watch-to-phone-to-backend session for at least several minutes.
- Confirm live PPG/EDA data produces stable `/sensor-window` payloads.
- Confirm `/prediction-stream` alert metadata updates phone and watch UI in real time.
- Confirm the 60-minute default chat timeout and administrator override persist on the physical phone.
- Confirm later required alerts do not reopen AUQ while chat remains active, then confirm a future alert can reopen it after chat closes.
- Confirm asynchronous handoff submission returns promptly, chat remains usable, and the completed report appears on the physical phone.
- Apply the team's private-repository policy deliberately to `.env`, review its values before staging, and keep the repository private if it is included. Do not upload machine-specific `local.properties`, standalone signing keys, or generated APK/build output. Review model weights and the Samsung AAR before including binary artifacts.
- Use `docs/PULL_REQUEST_DESCRIPTION.md` or its Korean mirror as the GitHub pull request body after reviewing secrets and binary artifacts.
