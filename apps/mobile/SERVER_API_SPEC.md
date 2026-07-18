# NeuroTruth Phone–Backend API

Last updated: 2026-07-18

This is the single human-readable API contract for the Android client. The
running FastAPI application's `/openapi.json` is the machine-readable route
authority; a generated snapshot is intentionally not committed.

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

The current application registers the following 42 method/path pairs.

| Area | Method and path |
|---|---|
| Runtime | `GET /health`, `GET /ready`, `GET /model/status` |
| Auth | `POST /api/auth/patient/signup`, `POST /api/auth/admin/signup`, `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `POST /api/auth/change-password` |
| Profile/consent | `GET /api/me`, `PATCH /api/me`, `POST /api/me/consents` |
| Sensor/prediction | `POST /api/sensor-windows`, `GET /api/predictions/stream` |
| Sessions | `POST /api/sessions`, `GET /api/sessions/{session_id}`, `POST /api/sessions/{session_id}/messages`, `POST /api/sessions/{session_id}/assessments`, `POST /api/sessions/{session_id}/finish`, `POST /api/sessions/{session_id}/reports`, `GET /api/sessions/{session_id}/reports` |
| Voice STT | `GET /api/stt/status`, `POST /api/sessions/{session_id}/transcriptions` |
| Patient dashboards | `GET /api/me/dashboard`, `GET /api/me/craving-probability-series`, `GET /api/me/craving-dashboard`, `GET /api/me/predictions/{prediction_id}/ppg-preview` |
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
| Phone alert/AUQ/chat launch | `notification` |
| Session report generation | `reportGeneration` |
| Whisper transcription | `voice` plus an active owned session |
| Camera rPPG | `biosignal`, `aiAnalysis`, `cameraRppg`, `faceVideoRetention` |

Withdrawing optional consent blocks new use of that feature. It does not delete
previously retained data.

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

The same `(patient, clientWindowId)` is idempotent only when content is
identical; conflicting reuse returns `409`. Raw payloads are stored as canonical
JSON → gzip → AES-256-GCM. With `aiAnalysis` consent and a ready model, success
also returns `recordingId`, `predictionId`, nullable `alertId`, and:

```json
{
  "predictionSchema": "binary-craving-v1",
  "class": 1,
  "classCode": "high",
  "confidence": 0.812345,
  "cravingProbability": 0.812345,
  "classProbabilities": {"low": 0.187655, "high": 0.812345},
  "timestampMs": 1784160000000,
  "alertLevel": "recommend",
  "alertAction": "recommend_intervention",
  "windowMean": 0.7,
  "classOneRatio": 0.7
}
```

The backend linearly resamples PPG and GSR to 1,024 values per channel,
independently MinMax-normalizes them without another filter, and runs tensor
shape `(1,2,1024)`. PPG priority is `PPG_GREEN → PPG_IR → PPG_RED`; missing EDA
becomes an all-zero channel. Class `0=low`, class `1=high`, and
`cravingProbability` is softmax `p(class 1)`. It is not a clinical score.

### Prediction SSE

`GET /api/predictions/stream` requires an access bearer and
`Accept: text/event-stream`.

```text
: connected

event: craving
data: {"predictionSchema":"binary-craving-v1","class":1,"cravingProbability":0.812345,"alertLevel":"recommend"}

: ping
```

The phone reconnects with a current token and relays display-safe metadata to
the Watch. The Watch never authenticates directly and must follow server
`alertAction`/`alertLevel`, not create an alert merely from class `1`.

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

### Retry-safe message

`POST /api/sessions/{sessionId}/messages`

```json
{
  "clientMessageId": "8b79722a-bda0-45a4-a1e4-c87dc944dc9c",
  "content": "지금 마음이 복잡해서 잠깐 이야기하고 싶어요.",
  "inputModality": "text"
}
```

`inputModality` is `text` or `voice`; both enter the same dialogue path. A
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
  "reportStatus": "pending",
  "inactivityTimeoutSeconds": 3600
}
```

When the provider or output validation fails, the server returns `502` with
`clientMessageId`, `userMessageId`, `retryable`, and `attemptsRemaining`. Retry
once using the same ID, identical content, and identical modality. The existing
user message is reused; a different payload for that ID returns `409`. Omitting
`clientMessageId` remains accepted for compatibility but disables safe retry.

### Optional AUQ

`POST /api/sessions/{sessionId}/assessments` stores the current eight-item,
seven-choice research adaptation. The Android labels map to values `1..7`:

`매우 그렇지 않다`, `그렇지 않다`, `조금 그렇지 않다`, `보통이다`,
`조금 그렇다`, `그렇다`, `매우 그렇다`.

```json
{
  "instrumentCode": "AUQ",
  "version": "1.0",
  "phase": "pre_intervention",
  "attemptNo": 1,
  "answers": {
    "responses": [4, 5, 3, 4, 6, 2, 4, 5],
    "scoredItems": [4, 5, 3, 4, 6, 2, 4, 5],
    "capturedAtMs": 1784160000000,
    "rawTotalScore": 33
  },
  "rawScore": 33,
  "scaleMin": 8,
  "scaleMax": 56
}
```

The current Korean research adaptation configures all eight items in the same
scoring direction, so `responses` and `scoredItems` currently match. Do not
silently apply scoring rules from another AUQ wording/version. AUQ is optional
and may be skipped without a placeholder request. The UI does not assign
unsupported low/medium/high AUQ cutoffs; a higher total only means more
alcohol-urge-related responses at that time.

- `POST /api/sessions/{sessionId}/finish` completes the session and triggers
  final state inference/report processing.
- `POST /api/sessions/{sessionId}/reports` queues a report for a finished
  session and returns `202`; `GET` on the same path returns status/metadata.
  Public APIs do not reveal decrypted report content.

## Speech-to-text and local TTS

- `GET /api/stt/status` returns `enabled`, `available`, model, requested/actual
  device, inference `engine`, fallback state/reason, and sanitized `errorCode`.
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

Audio is handled in backend/DGX tmpfs and deleted after the request. The app
places the transcript in an editable input field and sends only after explicit
user confirmation. Only the final sent text is retained as an encrypted chat
message. AI speech output uses Android `TextToSpeech`; there is no server TTS
route.

## Patient dashboards

- `GET /api/me/dashboard?range=24h|7d|30d` returns prediction, AUQ, alert,
  session, intervention, report-status, and state-summary history.
- `GET /api/me/craving-probability-series?range=10m|24h|7d|30d` returns
  class-1 probability buckets of `1`, `60`, `600`, or `1800` seconds. Empty
  buckets are omitted, never interpolated.
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

## Camera rPPG

rPPG is disabled by default and hidden unless explicitly enabled.

- `GET /api/rppg/status` exposes feature/model/queue availability without the
  DGX URL.
- `POST /api/rppg/jobs` is multipart: `video`, `clientCaptureId`,
  `capturedAtMs`, `durationMs`, and optional owned `sessionId`. Only MP4 around
  20 seconds (`19500..20500` ms) is accepted; the default maximum is 40 MiB.
  Acceptance returns `202` with `jobId`, `captureId`, and `status="queued"`.
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

- `/health` indicates process health; `/ready` fails closed when database,
  Alembic, encryption, storage, or required model state is unavailable.
- `/model/status` exposes model/checksum/requested and actual device/fallback
  metadata, never credentials or artifact paths.

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
