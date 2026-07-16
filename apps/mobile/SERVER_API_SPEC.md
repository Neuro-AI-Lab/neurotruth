# NeuroTruth Phone–Backend API

Last updated: 2026-07-16

All data routes require `Authorization: Bearer <accessToken>`. JSON uses camelCase. The server derives patient ownership from the JWT; clients must not send a patient ID. The backend issues UUID session IDs.

## Authentication

### Patient signup

`POST /api/auth/patient/signup`

```json
{
  "email": "patient@example.com",
  "password": "at-least-12-characters",
  "name": "optional",
  "birthYear": 1990,
  "gender": "prefer_not_to_say",
  "consent": {
    "tos": true,
    "privacy": true,
    "sensitive": true,
    "biosignal": true,
    "aiAnalysis": true,
    "notification": true,
    "reportGeneration": true,
    "tosVersion": "1.0",
    "privacyVersion": "1.0",
    "consentFormVersion": "1.0"
  }
}
```

Signup and `POST /api/auth/login` return:

```json
{"user":{"id":"uuid","role":"patient"},"accessToken":"...","refreshToken":"...","expiresIn":900}
```

`POST /api/auth/refresh` receives `{"refreshToken":"...","device":{}}`, rotates the refresh token, and returns the same token shape. `POST /api/auth/logout` receives `{"refreshToken":"..."}` with the current access bearer. `POST /api/auth/change-password` receives `currentPassword` and a 12+ character `newPassword`.

Use `GET /api/me`, `PATCH /api/me`, and append consent with `POST /api/me/consents`. Required consent is `tos`, `privacy`, and `sensitive`; optional feature gates are `biosignal`, `aiAnalysis`, `notification`, `reportGeneration`, `cameraRppg`, and `faceVideoRetention`. Voice is unavailable. Camera capture requires current `biosignal`, `aiAnalysis`, `cameraRppg`, and `faceVideoRetention` consent.

## Camera rPPG

- `GET /api/rppg/status` returns feature/model/queue availability without exposing the DGX URL.
- `POST /api/rppg/jobs` uses multipart fields `video`, `clientCaptureId`, `capturedAtMs`, `durationMs`, and optional owned `sessionId`; accepted input returns HTTP 202 `{jobId,captureId,status}`.
- `GET /api/rppg/jobs/{jobId}` returns durable status. `completed` returns the phone-only `camera_rppg` result; `retry_required` requires a new capture; transient `failed` may expose `retryAllowed=true`.
- `POST /api/rppg/jobs/{jobId}/retry` has no body and creates a new attempt only for an eligible transient failure. There is no automatic retry.

The phone uploads only to NeuroTruth; it never calls DGX directly. Every accepted success, quality-failure, and technical-failure video is permanently retained encrypted until audited administrator deletion. Camera results may trigger existing phone alert/AUQ/chat cooldown logic when notification consent is active, but are never sent to the Watch.

## Sensor Upload

`POST /api/sensor-windows`

```json
{
  "clientWindowId": "8e20b8d2-cd87-4be2-b54f-ed2e00472329",
  "sessionStartedAtMs": 1750000000000,
  "sequence": 42,
  "sentAtMs": 1750000042000,
  "windowStartMs": 1750000032000,
  "windowEndMs": 1750000042000,
  "windowMs": 10000,
  "samples": [{"sensor":"PPG_GREEN","timestampMs":1750000032520,"value":32451.0}]
}
```

The same patient and `clientWindowId` is idempotent only for identical content. Success returns the binary prediction fields below plus `recordingId`, `predictionId`, and nullable `alertId`. Biosignal consent is required. Backend storage is canonical JSON → gzip → AES-256-GCM in a backend-only volume.

## Prediction SSE

`GET /api/predictions/stream` with access bearer and `Accept: text/event-stream`.

```text
: connected

event: craving
data: {"predictionSchema":"binary-craving-v1","class":1,"classCode":"high","confidence":0.812345,"cravingProbability":0.812345,"classProbabilities":{"low":0.187655,"high":0.812345},"timestampMs":1784160000000,"alertLevel":"recommend","alertAction":"recommend_intervention","windowMean":0.7,"classOneRatio":0.7}

: ping
```

`class` accepts only `0=low` or `1=high`. `confidence` is the probability of the predicted class; `cravingProbability` is always softmax `p(class 1)`. Reconnect after network loss with the current/rotated access token. The phone relays display-safe prediction metadata to the Watch; the Watch does not open this stream. Watch treats class as display-only and follows server `alertAction`/`alertLevel` for vibration and notification.

## Patient craving-probability series

`GET /api/me/craving-probability-series?range=10m|24h|7d|30d` returns the authenticated patient's class-1 probability history. The fixed buckets are `1s`, `60s`, `600s`, and `1800s`; empty buckets are omitted rather than interpolated.

```json
{
  "range": "24h",
  "from": "2026-07-15T00:00:00Z",
  "to": "2026-07-16T00:00:00Z",
  "bucketSeconds": 60,
  "points": [
    {"at":"2026-07-15T00:01:00Z","averageCravingProbability":0.7132,"sampleCount":57}
  ]
}
```

This series is displayed only on the Phone. It is a research model output, not a diagnosis, calibrated clinical severity, or treatment-effect measure.

## Sessions

Create or obtain the patient’s single active session:

```http
POST /api/sessions
```

```json
{"sessionType":"manual_checkin","triggerAlertId":null}
```

Successful create/get responses include UUID `sessionId`, `status`, `interactionPhase`, timestamps, `assistantText`, `safety`, `activeInterventions`, `stateSnapshot`, `reportStatus`, `legacy`, and the effective `inactivityTimeoutSeconds`. New sessions never return `slots`, `missingSlots`, or `handoffReady`. A pre-redesign session is returned with `legacy=true` and is read-only; mutation returns HTTP 409 `legacy_session_read_only`.

### Message

`POST /api/sessions/{sessionId}/messages`

```json
{"content":"지금 술 생각이 강하게 납니다."}
```

```json
{
  "assistantText":"...",
  "phase":"safety_check",
  "safety":{"status":"awaiting_response"},
  "activeInterventions":[],
  "stateSnapshot":null,
  "reportStatus":"pending",
  "inactivityTimeoutSeconds":3600
}
```

The first phase confirms safety. Deterministic rules select the first ordinary intervention when enabled; later messages use free intervention dialogue without a required question order or coverage target. The server may ask one short question when useful and does not repeat answered or declined topics.

### Optional AUQ assessment

`POST /api/sessions/{sessionId}/assessments`

```json
{
  "instrumentCode":"AUQ",
  "version":"1.0",
  "phase":"pre_intervention",
  "attemptNo":1,
  "answers":{"q1":4},
  "rawScore":4,
  "scaleMin":0,
  "scaleMax":10
}
```

The user may skip AUQ and start dialogue. Skipping sends no placeholder assessment request and does not block intervention.

### Finish and reports

- `POST /api/sessions/{sessionId}/finish`: explicit user finish produces `completed`; inactivity timeout produces `abandoned`. The response includes final state/report status and `inactivityTimeoutSeconds`.
- `POST /api/sessions/{sessionId}/reports`: finished sessions only, HTTP `202`, returns `reportId`, `version`, `status`.
- `GET /api/sessions/{sessionId}/reports`: returns report status and metadata only. Current patient/admin UI does not expose decrypted report body.

Reports require `reportGeneration` consent. Session timeout defaults to 3,600 seconds and is administrator-configurable. There is no client-side turn limit.

## Safety and Intervention

Immediate-risk messages receive 119/109 guidance and a one-time question asking whether to record requested administrator involvement. This records acceptance/refusal only; it does not create live chat, an emergency queue, automatic contact, or a connection guarantee.

After safety confirmation, backend rules choose the first intervention type. The dialogue agent may move among approved intervention types and each proposal is recorded. The administrator can globally disable normal interventions; safety guidance is unaffected.

Generated dialogue, state summaries, and reports are validated to reject repeated/unclassified questions, diagnostic claims, medication instructions, treatment-effect claims, and causal claims. LLM summary failure does not discard deterministic state evidence.

## Errors and Boundaries

| Status | Meaning |
|---:|---|
| `401` | Access/refresh invalid; attempt one refresh, then log out |
| `403` | Role or feature consent missing |
| `404` | Owned session/resource not found |
| `409` | Active-session/state conflict or conflicting `clientWindowId` reuse |
| `422` | Invalid request/assessment range |
| `502` | Sanitized agent/provider failure; session remains resumable |
| `503` | Database, model, storage, migration, or security readiness failure |
| `413` | Camera MP4 exceeds the configured 20 MiB maximum |
| `415` | Camera upload is not an accepted MP4 |

The old unauthenticated `/sensor-window`, `/prediction-stream`, `/api/llm/chat`, and `/api/intervention/*` paths are not current contracts. Voice/STT/TTS, direct Watch authentication, live administrator intervention, and bulk dataset download remain excluded. Camera rPPG is a disabled-by-default experimental extension and is not release-ready until controlled real-device validation passes.
