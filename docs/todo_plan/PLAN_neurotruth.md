# PLAN: NeuroTruth

Last updated: 2026-07-15

## Current Product Direction

NeuroTruth now builds an intervention-first supportive research flow on the authenticated encrypted foundation. New sessions use optional AUQ, deterministic safety and first-intervention rules, autonomous non-repeating dialogue, evidence-linked state inference, and patient/admin dashboards. They do not write the retained 13-slot workflow. The product does not claim immediate craving reduction, CBT efficacy, diagnosis, treatment success, or causality.

## Completed

- [x] Patient self-signup, login, rotating refresh, logout, password change, role authorization.
- [x] Append-only mandatory/optional consent snapshots and feature gates.
- [x] Fresh PostgreSQL authenticated baseline with `auth_sessions`, `system_settings`, model/prompt links, retained legacy slots, and one-active-session constraint.
- [x] Versioned AES-256-GCM keyring for sensitive fields and backend-volume sensor encryption.
- [x] Authenticated idempotent sensor upload and patient-owned prediction SSE.
- [x] Server-issued UUID sessions, AUQ, encrypted messages, manual finish, timeout, and persistent asynchronous reports.
- [x] Retained pre-redesign `answered|unknown|declined` slot history and repeated-topic prevention baseline.
- [x] Deterministic intervention type, global intervention toggle, safety 119/109 wording, and no-live-connection guarantee.
- [x] Patient Phone auth/consent/Keystore flow and Phone-only Watch relay.
- [x] Administrator signup/login, patient timeline, reason-gated reveal, temporary password, deletion, and settings UI/API.
- [x] Fresh `postgres_data_v25` and `encrypted_sensor_data` Compose contract with legacy-volume preservation.
- [x] Current README, mobile/server API, PRD, developer, Wear OS, and agent-document synchronization.

## Intervention-First Redesign

- [ ] Apply additive `0003` for interaction phases, encrypted dialogue state, ordered interventions, and `state_inferences` without changing `0001`, `0002`, or baseline SQL.
- [ ] Replace new-session slot collection with optional AUQ, safety-first dialogue, deterministic first intervention, allowlisted later interventions, and manual-complete/timeout-abandoned behavior.
- [ ] Add patient and administrator `24h|7d|30d` dashboards. Administrator views contain no raw PPG, decrypted state summary, or report body.
- [ ] Preserve all pre-`0003` slot sessions/reports as read-only legacy history with no backfill.

## Superseded Completed History

The following work was valid for the 2026-07-09 through 2026-07-13 demo and remains useful implementation history, but its public contracts are not current behavior:

- [x] Backend-owned RF prediction, deterministic alert warm-up/cooldown/high-streak logic, and Bedrock GPT-5.5 adapter.
- [x] Restored Phone/Wear UX, sensor charts, Data Layer batching, prediction display, and device installation.
- [x] Legacy shared string session IDs and unauthenticated sensor/SSE compatibility.
- [x] Legacy nine-slot intervention endpoints, synchronous handoff compatibility, and process-local HTTP 202 handoff jobs.
- [x] Active-intervention latch, configurable 60-minute waiting behavior, and older intervention-only reset controls.
- [x] Earlier backend/Android/Docker smoke tests and GitHub upload documentation.

These entries are not rollback promises. Historical dated specs remain unchanged; rollback restores the preserved legacy image/volume and does not translate new data.

## Current Validation

```powershell
cd apps/backend
.\.venv\Scripts\python.exe -m pytest

cd ../mobile
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug

docker compose -f ../db/docker-compose.yml config --no-env-resolution
git diff --check
```

## Operator Setup

- Maintain valid `DATABASE_URL`, AES keyring/current key, JWT signing key, admin signup code, backend sensor-volume path, and Bedrock/model configuration.
- Back up legacy `postgres_data`; deploy only against fresh `postgres_data_v25` and `encrypted_sensor_data`.
- Use HTTPS in production. Enable insecure HTTP only for explicit development/test LAN experiments.
- Keep Android SDK/JDK 17 and Samsung Health Sensor SDK available; update the Phone LAN base URL when the network changes.
- Never stage real secrets, tokens, decrypted content, database volumes, retained sensor files, machine-specific `local.properties`, or generated build output.

## Next Verification

- [ ] Run fresh PostgreSQL migration/readiness smoke with Docker daemon available.
- [ ] Run full patient signup → consent → Watch relay → encrypted upload → prediction → alert approval → optional AUQ → safety → intervention dialogue → report status → patient/admin dashboards on physical devices.
- [ ] Verify refresh rotation/replay logout and app restart session recovery on the target Phone.
- [ ] Verify safety acceptance/decline wording, 109 display, and absence of any live-contact guarantee.
- [ ] Verify administrator reason audit, settings, temporary password, deletion success/retry, and intervention OFF behavior.
- [ ] Verify HTTPS production deployment and explicit development HTTP override separately.
- [ ] Apply additive rPPG `0002`, run fake-DGX backend tests, and verify encrypted all-video retention plus audited playback/deletion.
- [ ] Run real Phone 10-second capture → backend → DGX FactorizePhys → 512 rPPG + zero EDA → RF → Phone result, confirming no camera result reaches Watch.
- [ ] Before enabling, confirm DGX port is internal-only, upload temp is tmpfs, `DELETE_UPLOADED_VIDEO=true`, and no plaintext remains on DGX/backend.

Voice/STT/TTS, self-event capture, wearable-absent AUQ automation, craving-model balancing/label experiments, live administrator intervention, emergency dispatch, and bulk download remain out of scope. Camera rPPG is a deferred, disabled-by-default extension and remains hidden until enabled, ready, and jointly validated.
