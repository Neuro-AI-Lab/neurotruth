# NeuroTruth Phone–Backend API

Last updated: 2026-07-21

This is the single human-readable API contract for the Android client. The
running FastAPI application's `/openapi.json` is the machine-readable authority
for registered routes, methods, and request schemas. This document is the
detailed response-payload authority because several generated OpenAPI response
schemas remain intentionally generic; a generated snapshot is not committed.

## Conventions and authentication

- JSON fields use camelCase. UUIDs are canonical strings and timestamps are UTC
  ISO-8601 unless a field is explicitly named `*Ms` (Unix epoch milliseconds).
- Patient ownership comes from the access JWT. Patient requests must not send a
  patient ID unless the documented field identifies another resource.
- Send `Authorization: Bearer <accessToken>` on protected routes. Signup,
  login, refresh, logout, `/health`, `/ready`, and `/model/status` are the only
  routes below that do not require an access bearer.
- Access tokens expire after 15 minutes by default. Refresh tokens are opaque,
  last 30 days by default, rotate on every refresh, and are stored only as
  SHA-256 hashes by the backend.
- Request bodies reject unknown fields unless the endpoint is multipart.

## Registered route inventory

The current application registers the method/path pairs listed below.

| Area | Method and path |
|---|---|
| Runtime | `GET /health`, `GET /ready`, `GET /model/status` |
| Auth | `POST /api/auth/patient/signup`, `POST /api/auth/admin/signup`, `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `POST /api/auth/change-password` |
| Profile/consent | `GET /api/me`, `PATCH /api/me`, `POST /api/me/consents` |
| Sensor/prediction | `POST /api/sensor-windows`, `GET /api/predictions/stream` |
| Sessions | `POST /api/sessions`, `GET /api/sessions/{session_id}`, `POST /api/sessions/{session_id}/messages`, `POST /api/sessions/{session_id}/assessments`, `POST /api/sessions/{session_id}/finish`, `POST /api/sessions/{session_id}/reports`, `GET /api/sessions/{session_id}/reports` |
| Voice STT | `GET /api/stt/status`, `POST /api/sessions/{session_id}/transcriptions` |
| Patient dashboards | `GET /api/me/dashboard`, `GET /api/me/craving-probability-series`, `GET /api/me/craving-dashboard`, `GET /api/me/craving-calendar`, `GET /api/me/predictions/{prediction_id}/ppg-preview` |
| Patient rPPG | `GET /api/rppg/status`, `POST /api/rppg/jobs`, `GET /api/rppg/jobs/{job_id}`, `POST /api/rppg/jobs/{job_id}/retry` |
| Administrator | `GET /api/admin/patients`, `GET /api/admin/patients/{patient_id}/timeline`, `GET /api/admin/patients/{patient_id}/dashboard`, `POST /api/admin/resources/{resource_type}/{resource_id}/reveal`, `POST /api/admin/patients/{patient_id}/temporary-password`, `DELETE /api/admin/patients/{patient_id}`, `GET /api/admin/settings`, `PATCH /api/admin/settings` |
| Administrator rPPG | `GET /api/admin/rppg/captures`, `POST /api/admin/rppg/captures/{capture_id}/reveal-video`, `DELETE /api/admin/rppg/captures/{capture_id}` |

## Authentication, profile, and consent

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
    "voice": false,
    "aiAnalysis": true,
    "notification": true,
    "reportGeneration": true,
    "cameraRppg": false,
    "faceVideoRetention": false,
    "tosVersion": "1.0",
    "privacyVersion": "1.0",
    "consentFormVersion": "1.0"
  }
}
```

Signup and login return the token pair and public user:

```json
{
  "user": {"id": "uuid", "role": "patient"},
  "accessToken": "...",
  "refreshToken": "...",
  "expiresIn": 900
}
```

- `POST /api/auth/admin/signup` requires `email`, `password`, and `signupCode`.
- `POST /api/auth/login` accepts `email`, `password`, and optional `device`.
- `POST /api/auth/refresh` accepts `refreshToken` and optional `device` and
  replaces the submitted refresh token. Reuse revokes the token family.
- `POST /api/auth/logout` accepts `refreshToken` and returns `204`.
- `POST /api/auth/change-password` accepts `currentPassword` and a 12+ character
  `newPassword`; it requires an access bearer and returns `204`.
- `GET /api/me` returns the public user and latest consent snapshot.
- `PATCH /api/me` updates patient `name`, `birthYear`, and/or `gender`.
- `POST /api/me/consents` appends an immutable consent snapshot; it does not
  overwrite historical consent rows.

`tos`, `privacy`, and `sensitive` must always be accepted. Feature gates are:

| Feature | Required current consent |
|---|---|
| Watch sensor retention | `biosignal` |
| Model prediction/session dialogue | `aiAnalysis` |
| Alert metadata/action presentation | `notification` |
| Session report generation | `reportGeneration` |
| Whisper transcription | `voice` plus an active owned session |
| Camera rPPG | `biosignal`, `aiAnalysis`, `cameraRppg`, `faceVideoRetention` |

Withdrawing optional consent blocks new use of that feature. It does not delete
previously retained data.

`notification=false` suppresses alert metadata/action presentation only; it
does not block a patient from manually opening AUQ or chat. `aiAnalysis` gates
model prediction and session/agent processing.

## Watch sensor upload and binary prediction

`POST /api/sensor-windows` requires patient authentication and `biosignal`
consent. The phone waits for a 20-second warm-up, then submits the latest
20-second window every 10 seconds.

```json
{
  "clientWindowId": "8e20b8d2-cd87-4be2-b54f-ed2e00472329",
  "sessionStartedAtMs": 1750000000000,
  "sequence": 42,
  "sentAtMs": 1750000042000,
  "windowStartMs": 1750000022000,
  "windowEndMs": 1750000042000,
  "windowMs": 20000,
  "sync": {
    "mode": "fixed_grid_ppg_25hz_eda_1hz_continuous",
    "fillMode": "linear_interpolation_nearest_edge_hold",
    "ppgHz": 25,
    "ppgSamplesPerChannel": 500,
    "edaHz": 1,
    "edaSamples": 20
  },
  "samples": [
    {"sensor": "PPG_GREEN", "timestampMs": 1750000022000, "value": 32451.0},
    {"sensor": "EDA", "timestampMs": 1750000022000, "value": 0.27}
  ]
}
```

`windowMs` must be `19500..20500`, must equal `windowEndMs-windowStartMs`, and
must have a positive interval; invalid timing returns `422`. The same
`(patient, clientWindowId)` is idempotent only when content is identical;
conflicting reuse returns `409`. Identical concurrent retries are serialized and
persist/predict once, then return the same stored result. Raw payloads are stored
as canonical JSON → gzip → AES-256-GCM. Every successful accepted upload returns
`recordingId`. With `aiAnalysis` consent and a ready model, the response also
contains `predictionId`, nullable `alertId`, and:

```json
{
  "predictionSchema": "binary-craving-v1",
  "class": 1,
  "classCode": "high",
  "confidence": 0.812345,
  "cravingProbability": 0.812345,
  "classProbabilities": {"low": 0.187655, "high": 0.812345},
  "timestampMs": 1784160000000,
  "source": "watch_sensor",
  "alertLevel": "required",
  "alertAction": "required_intervention",
  "windowMean": 0.81,
  "classOneRatio": 0.81,
  "triggerReason": "danger_streak",
  "alertRequired": true
}
```

The backend linearly resamples PPG and GSR to 1,024 values per channel,
independently MinMax-normalizes them without another filter, and runs tensor
shape `(1,2,1024)`. PPG priority is `PPG_GREEN → PPG_IR → PPG_RED`; missing EDA
becomes an all-zero channel. Class `0=low`, class `1=high`, and
`cravingProbability` is softmax `p(class 1)`. It is not a clinical score.
Watch sensor responses, persisted display metadata, and SSE events identify this
path with `source="watch_sensor"`. Camera results retain
`source="camera_rppg"`; clients must compare `timestampMs` before replacing the
latest Home state.

Only `watch_sensor` predictions can create alerts. Three consecutive
`cravingProbability >= 0.75` measurements, each no more than 20 seconds apart,
create one persisted `required_intervention` alert. Any lower stage or longer
gap resets the streak. The patient-wide cooldown is 900 seconds and survives
backend restarts; Phone and Watch deduplicate the same persisted `alertId`.
Camera rPPG predictions never create an alert.

### Prediction SSE

`GET /api/predictions/stream` requires an access bearer and
`Accept: text/event-stream`.

```text
: connected

event: craving
data: {"predictionSchema":"binary-craving-v1","class":1,"cravingProbability":0.812345,"source":"watch_sensor","alertLevel":"required","alertAction":"required_intervention","alertId":"uuid"}

: ping
```

The phone reconnects with a current token and relays display-safe metadata to
the Watch. The Watch never authenticates directly and must follow server
`alertAction`/`alertLevel`, not create an alert merely from class `1`.

### Phone/Watch Data Layer control

- Phone sends `start|stop` plus `requestId` on
  `/control/measurement/request`.
- Watch answers on `/control/measurement/status` with
  `started|stopped|confirmation_required|error`. Phone starts
  `MonitoringService` only after `started`.
- `/dashboard/snapshot` carries the recent stage timeline and today's event/AUQ
  summary. Watch receives stage/timestamp/summary/alert metadata only—never
  backend credentials, raw biosignals, or exact craving probabilities.
- When Wear OS blocks remote health-FGS startup, Watch displays one confirmation
  action and returns `confirmation_required`. Measurement can be stopped from
  either device.

## Free-dialogue sessions and AUQ

`POST /api/sessions` creates or returns the patient's one active session:

```json
{"sessionType": "manual_checkin", "triggerAlertId": null}
```

New sessions use `interactionPhase="free_dialogue"`, begin with a neutral
assistant invitation, and have no slot coverage, handoff gate, or turn limit.
They end by explicit finish or the configured inactivity timeout (3,600 seconds
by default). Pre-redesign sessions remain readable with `legacy=true` and are
mutation-protected.

The Phone uses the same entry contract for an alert action, the Chat tab, and a
completed rPPG job. When `POST /api/sessions` creates a new session, the Phone
offers either AUQ completion or **skip and continue** before displaying free
dialogue. When the endpoint returns the already-active session, the Phone
resumes it directly and does not request another AUQ.

Current free-dialogue safety interpretation is LLM-only. Successful dialogue
turns store messages and the question/refusal ledger but do not create
intervention rows. The agent performs one output-repair attempt; provider failure
or a second invalid output returns sanitized `502` without a fallback reply.

### Retry-safe message

`POST /api/sessions/{sessionId}/messages`

```json
{
  "clientMessageId": "8b79722a-bda0-45a4-a1e4-c87dc944dc9c",
  "content": "지금 마음이 복잡해서 잠깐 이야기하고 싶어요.",
  "inputModality": "text"
}
```

`inputModality` is `text` or `voice`. Typed text and a user-confirmed STT
transcript both enter this same endpoint, intervention agent, and
`clientMessageId` idempotency flow; only the modality value differs. A
successful response includes:

```json
{
  "userMessageId": "uuid",
  "assistantMessageId": "uuid",
  "assistantText": "...",
  "phase": "free_dialogue",
  "safety": {"status": "llm_only", "riskCodes": [], "supportResources": []},
  "activeInterventions": [],
  "stateSnapshot": null,
  "reportStatus": "not_started",
  "inactivityTimeoutSeconds": 3600
}
```

`not_started` is the default while `REPORT_AI_ENABLED=false`; `pending` is used
only when report AI is enabled and a report job has been queued.

When the provider or output validation fails, the server returns `502` with
`clientMessageId`, `userMessageId`, `retryable`, and `attemptsRemaining`. Retry
once using the same ID, identical content, and identical modality. The existing
user message is reused; a different payload for that ID returns `409`. Omitting
`clientMessageId` remains accepted for compatibility but disables safe retry.

### Optional AUQ

`POST /api/sessions/{sessionId}/assessments` stores the current eight-item,
seven-choice research adaptation. The Android labels map to zero-based API
values as follows:

| API value | Android label |
|---:|---|
| `0` | `매우 그렇지 않다` |
| `1` | `그렇지 않다` |
| `2` | `조금 그렇지 않다` |
| `3` | `보통이다` |
| `4` | `조금 그렇다` |
| `5` | `그렇다` |
| `6` | `매우 그렇다` |

```json
{
  "instrumentCode": "AUQ",
  "version": "2.0",
  "phase": "pre_intervention",
  "attemptNo": 1,
  "answers": {
    "responses": [3, 4, 2, 3, 5, 1, 3, 4],
    "scoredItems": [3, 4, 2, 3, 5, 1, 3, 4],
    "capturedAtMs": 1784160000000,
    "rawTotalScore": 25
  },
  "rawScore": 25,
  "scaleMin": 0,
  "scaleMax": 48
}
```

For `instrumentCode="AUQ"`, the backend accepts only `version="2.0"`, exactly
eight integer `responses` and `scoredItems` in `0..6`, matching arrays, and a
`rawTotalScore`/`rawScore` equal to their sum in `0..48`. `scaleMin` and
`scaleMax` must be `0` and `48`. A mismatch returns `422` with
`code="invalid_auq_scale"` and writes no assessment. The current Korean
research adaptation configures all eight items in the same scoring direction,
so `responses` and `scoredItems` match. Do not silently apply scoring rules from
another AUQ wording/version. AUQ is optional and may be skipped without a
placeholder request. The UI does not assign unsupported low/medium/high AUQ
cutoffs; a higher total only means more alcohol-urge-related responses at that
time.

The patient UI names this flow `자기설문` and describes it as an AUQ-informed
research Korean adaptation, not an official validated Korean version. A
persisted submission shows `총점 X/48` with neutral explanation before the user
continues to Chat. Skip creates no result and goes directly to Chat.

Legacy `1..7` / `8..56` AUQ rows are converted once by an operator from the
backend working directory. The confirmed command decrypts each answer with its
stored AAD, validates every row, re-encrypts with a fresh nonce/current key,
updates version and score metadata, and writes one system audit record. An
invalid or tampered row rolls back the entire transaction.

```powershell
python -m app.maintenance.migrate_auq_zero_based --dry-run
python -m app.maintenance.migrate_auq_zero_based --confirm CONVERT-AUQ-TO-0-48
```

- `POST /api/sessions/{sessionId}/finish` completes the session and always
  persists deterministic state and evidence. With the default
  `STATE_SUMMARY_AI_ENABLED=false`, its state snapshot (and later state reads)
  contains `"summaryStatus":"unavailable"` and `"summary":null`; no summary AI
  call is made.
- With the default `REPORT_AI_ENABLED=false`, finish skips automatic report
  creation. `POST /api/sessions/{sessionId}/reports` remains a successful no-op
  and returns HTTP `202` with exactly:

```json
{"reportId":null,"version":null,"status":"not_started"}
```

  `GET` on the same path preserves historical report status/metadata and returns
  `[]` when no report exists. Public APIs do not reveal decrypted report
  content. Setting either backend flag to `true` restores its preserved
  Bedrock-backed behavior without changing these URLs or consent requirements.

## Speech-to-text and local TTS

- `GET /api/stt/status` requires a patient access bearer and returns `enabled`,
  `available`, model, requested/actual device, inference `engine`, fallback
  state/reason, and sanitized `errorCode`.
  DGX production should report `engine=pytorch`, `actualDevice=cuda:0`, and
  `fallback=false`.
- `POST /api/sessions/{sessionId}/transcriptions` is multipart with `audio`
  (`.m4a` or `.wav`) and `language=ko`. It requires patient authentication,
  `voice` consent, and an active owned session.

```json
{
  "text": "인식된 한국어 문장",
  "language": "ko",
  "durationMs": 8400,
  "model": "whisper-large-v3-turbo"
}
```

Audio is handled in backend/DGX tmpfs and deleted after the request. A successful
transcript is immediately sent as a new message through
`POST /api/sessions/{sessionId}/messages` with `inputModality="voice"` and a new
`clientMessageId`; an existing typed draft is preserved and never merged into
the voice message. Failure keeps text input and recording retry available. The
matching assistant reply is auto-played exactly once with Android
`TextToSpeech`; replies to typed messages continue to follow the existing
auto-read setting. There is no server TTS route.

## Patient dashboards

- `GET /api/me/dashboard?range=24h|7d|30d` returns prediction, AUQ, alert,
  session, intervention, report-status, and state-summary history.
- `GET /api/me/craving-probability-series?range=1h|10m|24h|7d|30d` returns
  class-1 probability buckets of `10`, `1`, `60`, `600`, or `1800` seconds,
  respectively. `range=1h` covers the latest 60 minutes and returns at most 360
  ten-second points. Empty buckets are omitted and are never created or
  interpolated.
- `GET /api/me/predictions/{predictionId}/ppg-preview` temporarily decrypts an
  owned source window, returns at most 512 display points, and uses
  `Cache-Control: no-store`.

### Bar-dashboard aggregation

`GET /api/me/craving-dashboard?timezone=Asia/Seoul&eventRange=7d&auqRange=today`
requires a valid IANA timezone. It returns the latest probability, today's 24
local-hour craving buckets, 7/30-day alert-event buckets, and AUQ buckets for
today/7/30 days. Empty prediction/AUQ buckets remain distinct from a valid
zero-event day.

Each populated hourly craving bucket preserves average/minimum/maximum fields
and adds exact counts for the 100% stacked chart:

```json
{
  "localStart": "2026-07-18T09:00:00+09:00",
  "averageProbability": 0.512,
  "minimumProbability": 0.12,
  "maximumProbability": 0.88,
  "sampleCount": 25,
  "stageCounts": {"low": 5, "observe": 7, "caution": 9, "high": 4}
}
```

The count bands are `low: p<0.25`, `observe: 0.25≤p<0.50`,
`caution: 0.50≤p<0.75`, and `high: p≥0.75`. Counts sum to `sampleCount`; the
phone normalizes them into a four-color 100% stack. Existing clients may ignore
`stageCounts`. These are research UI bands, not validated clinical cutoffs.

Patient-facing Korean stage copy is fixed even though the stable wire keys stay
`low|observe|caution|high`:

| Band | Stage | Patient copy |
|---|---|---|
| `p < 0.25` | `안정` | `아무 문제 없어요!` |
| `0.25 ≤ p < 0.50` | `관찰` | `관찰이 필요해요, 심각하진 않아요!` |
| `0.50 ≤ p < 0.75` | `주의` | `주의가 필요해요, 술이 드시고 싶으신가요?` |
| `p ≥ 0.75` | `위험` | `갈망이 높게 감지됐어요. 챗봇과 대화를 시작할까요?` |

Each AUQ bucket contains `averageScore` on the `0..48` scale and
`averageNormalizedScore` on `0..1` for compatibility. New clients render
`averageScore`; they may fall back to `averageNormalizedScore * 48` only when
the raw-scale field is absent during coordinated rollout.

### Day/week/month calendar aggregation

`GET /api/me/craving-calendar?timezone=Asia/Seoul&view=day|week|month&anchor=YYYY-MM-DD`
keeps the existing dashboard APIs intact and applies one selected period to
stage, event, and AUQ charts. `view=day` returns 24 local-hour buckets;
`view=week` treats the anchor as a date inside its local IANA-timezone week,
normalizes the period to Monday 00:00 through the next Monday 00:00, and returns
exactly seven Monday-through-Sunday daily buckets. `view=month` returns one
bucket per local calendar day in the anchor month.
Each bucket includes `stageCounts`, `sampleCount`, `hasPredictionData`,
`eventCount`, `auqAverageScore`, and `auqResponseCount`. A bucket with
`hasPredictionData=false` is “no measurement”; a populated bucket with
`eventCount=0` is a valid zero-event period. The same day, week, or month
selection always drives the stage, event, and AUQ series together.

## Camera rPPG

rPPG is enabled by default through `RPPG_ENABLED=true`, while operators may set
the flag to `false`. Capture still requires all four current consents
(`biosignal`, `aiAnalysis`, `cameraRppg`, `faceVideoRetention`) and a ready
`GET /api/rppg/status`; an unavailable service means no fallback measurement.

Android chooses presentation client-side from the Wear Data Layer connected-node
list. When there is no connected node, Android promotes a manual foreground
20-second camera action. While a Watch is connected, the camera action is
disabled and explains that the Watch must be disconnected first. Checking or
connection-error states are not treated as confirmed disconnection and also do
not enable capture. This adds no API field or endpoint.

- `GET /api/rppg/status` exposes feature/model/queue availability without the
  DGX URL.
- `POST /api/rppg/jobs` is multipart: `video`, `clientCaptureId`,
  `capturedAtMs`, `durationMs`, and optional owned `sessionId`. Only MP4 around
  20 seconds (`19500..20500` ms) is accepted; the default maximum is 40 MiB.
  First acceptance returns `202` with `jobId`, `captureId`, and
  `status="queued"`. An idempotent retry with the same `clientCaptureId` and
  identical video returns HTTP `202` for the existing job with its current
  status, which may already be `queued|running|completed|retry_required|failed`.
- `GET /api/rppg/jobs/{jobId}` returns
  `queued|running|completed|retry_required|failed` and completed prediction
  metadata.
- `POST /api/rppg/jobs/{jobId}/retry` creates one new attempt only for an
  eligible transient failure; quality failures require a new capture.

The phone never calls DGX directly. A valid waveform is resampled to 1,024 rPPG
points at 51.2 Hz and paired with 1,024 zero-valued EDA points before binary
prediction. Accepted videos and provider payloads are retained encrypted until
audited administrator deletion. Legacy 10-second captures remain readable but
the current upload API accepts only 20-second captures.

rPPG is a user-initiated point-in-time measurement, not continuous/background
monitoring. A successful result keeps source `camera_rppg`, remains Phone-only,
never creates a craving alert, and is routed once into the same active-or-new
session flow. A new session
offers AUQ or skip before free dialogue; an existing active session resumes
directly. The Phone compares measurement timestamps, so a delayed older camera
result cannot replace a newer Watch result. Public
rPPG URLs, methods, payloads, database schema, and Alembic history are unchanged.
The Android 16KB baseline is AGP 8.5.2, Gradle 8.7, CameraX 1.4.0, and ML Kit
face detection 16.1.7, with APK zip and ELF alignment verification required for
release.

## Administrator boundary

Administrator routes require an administrator bearer. Patient list/timeline and
dashboard endpoints expose operational summaries. Sensitive reveal requires a
non-blank reason, returns `Cache-Control: no-store`, and writes an audit event.
Temporary passwords and patient deletion also require a reason; deletion uses
an explicit confirmation. Settings can update `interventionsEnabled`,
`chatTimeoutSeconds` (`60..86400`), and a rotated `adminSignupCode`.

rPPG capture listing returns metadata only. Inline video reveal requires a
reason; deletion requires `reason` and matching `confirmCaptureId`. There is no
bulk dataset download route.

## Runtime status, errors, and boundaries

- `/health` indicates process health; `/ready` covers core database, Alembic,
  security/keyring, and required storage readiness. Craving-model readiness is
  separate and does not make the otherwise usable core API unready.
- `/model/status` exposes model/checksum/requested and actual device/fallback
  metadata, never credentials or artifact paths. Sensor prediction returns
  `503` when AI analysis is requested while that model is unavailable.

| Status | Client meaning |
|---:|---|
| `401` | Access/refresh invalid; attempt one refresh, then log out |
| `403` | Role or required feature consent missing |
| `404` | Owned session/job/resource not found |
| `409` | Active-session, idempotency, retry-state, or confirmation conflict |
| `413` | Audio/video exceeds its configured maximum |
| `415` | Unsupported audio or non-MP4 camera media |
| `422` | Invalid body/range/timezone/assessment/media or no speech |
| `502` | Sanitized dialogue provider/output failure; follow retry metadata |
| `503` | Database/model/storage/migration/security/STT/rPPG unavailable |
| `504` | STT timeout |

The retired unauthenticated `/sensor-window`, `/prediction-stream`,
`/api/llm/chat`, and `/api/intervention/*` paths are not contracts. Direct Watch
authentication, live administrator intervention, diagnosis, emergency dispatch,
and treatment-effect claims are outside the system boundary. STT, rPPG, binary
probabilities, AUQ, and dialogue are research-support features, not medical
diagnoses or calibrated clinical severity measures.
