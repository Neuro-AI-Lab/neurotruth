# NeuroTruth DGX Spark FactorizePhys rPPG Integration Spec

- **Language role:** English source spec
- **Spec status:** Finalized
- **Spec version:** 1.1
- **Last updated:** 2026-07-15
- **English source:** `neurotruth/docs/specs/2026-07-15-neurotruth-dgx-factorizephys-rppg-integration-spec.md`
- **Korean mirror:** `neurotruth/docs/specs/2026-07-15-neurotruth-dgx-factorizephys-rppg-integration-spec.ko.md`
- **Requester / owner:** NeuroTruth project owner
- **Implementation status:** Implemented with release disabled; real-phone live DGX validation pending

## 0. Codex Implementation Handoff

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: neurotruth/docs/specs/2026-07-15-neurotruth-dgx-factorizephys-rppg-integration-spec.md
korean_mirror_spec: neurotruth/docs/specs/2026-07-15-neurotruth-dgx-factorizephys-rppg-integration-spec.ko.md
authoritative_spec: english_source_spec
implementation_requires:
  - use_feature_planner_skill
  - read_english_source_spec_first
  - keep_korean_mirror_synchronized
  - spawn_worker_subagents_for_source_code_edits
  - main_agent_only_orchestrates_and_verifies
  - enforce_minimal_source_changes
  - return_to_targeted_refinement_on_spec_gap
worker_rule: source_code_changes_must_be_delegated_to_worker_subagents
main_agent_allowed:
  - read_files
  - search_repository
  - inspect_diffs
  - run_validation
  - update_spec_progress_records
  - prepare_worker_prompts
main_agent_forbidden:
  - direct_feature_source_code_edits
  - unrelated_refactors
  - formatting_only_churn
  - dependency_upgrades_unless_spec_requires
  - generated_file_churn_unless_spec_requires
</codex_feature_planner_handoff>
```

Copy-paste implementation prompt:

```text
$feature-planner Implement `neurotruth/docs/specs/2026-07-15-neurotruth-dgx-factorizephys-rppg-integration-spec.md` using IMPLEMENTATION_ORCHESTRATION. Use the English source spec as authoritative, keep `neurotruth/docs/specs/2026-07-15-neurotruth-dgx-factorizephys-rppg-integration-spec.ko.md` synchronized, spawn worker sub-agents for source-code edits, and have the main agent verify minimal diffs and update progress records. If the spec has a gap, use TARGETED_REFINEMENT for only that gap before continuing.
```

## 1. Summary

Add a consent-gated phone camera measurement that records a ten-second face video, uploads it only to the authenticated NeuroTruth backend, and runs FactorizePhys on the existing DGX Spark service. The backend persistently queues inference, permanently retains every accepted video encrypted with AES-256-GCM, converts a quality-approved rPPG waveform to 512 samples at 51.2 Hz, supplies 512 explicit zero-valued EDA samples to the existing three-class RandomForest, and returns a separate camera result to the phone. The DGX endpoint remains backend-only and stateless; the Android application never learns its address. Real phone-video inference and physiological validity remain release gates rather than assumed capabilities.

## 2. Goals

- G1. Provide a reliable `phone -> NeuroTruth backend -> DGX Spark -> RandomForest -> database -> phone` flow.
- G2. Capture a stable, front-camera 720p/30fps ten-second video with clear user state and cancellation behavior.
- G3. Make upload, inference, retry, restart recovery, encryption, idempotency, and patient ownership durable and auditable.
- G4. Keep camera predictions isolated from watch presentation while reusing existing phone alert, AUQ, and chat rules.
- G5. Preserve the V2.5 baseline and all unrelated sensor, watch, SSE, authentication, consent, and conversation behavior.

## 3. Non-Goals

- NG1. Modifying, packaging, or retraining FactorizePhys or any DGX source, checkpoint, container image, or API contract.
- NG2. Direct mobile-to-DGX communication or exposing DGX credentials, address, or port to the app.
- NG3. Treating a failed or low-quality rPPG measurement as no craving, making clinical claims, or claiming validated HR accuracy.
- NG4. Sending camera predictions to the watch, changing the existing watch authentication model, or stopping watch-to-phone reception during capture.
- NG5. Automatic server retry, quality-failure reanalysis of the same video, bulk data download, automatic retention deletion, or editing Alembic `0001` and the authoritative 18-table baseline SQL.
- NG6. Public exposure of the current unauthenticated DGX HTTP endpoint.

## 4. Users and Use Cases

### 4.1 Target Users

- Authenticated, consented patients using the NeuroTruth Android phone app.
- Administrators reviewing metadata, reason-gated video playback, and confirmed deletion.
- Operators configuring NeuroTruth-to-DGX networking and validating secure deployment.

### 4.2 Primary Use Cases

- UC1. A patient starts camera measurement, holds one face steadily for one second, records ten seconds, and later sees an rPPG/craving result or an actionable retry state.
- UC2. The phone restores polling and pause state after process restart without uploading the same accepted video twice.
- UC3. An administrator views capture metadata and may temporarily decrypt an inline video or delete it only with a reason and audit trail.
- UC4. An operator disables the entire feature with `RPPG_ENABLED=false` without disrupting existing NeuroTruth data or services.

## 5. Final Decisions

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | Topology | Only the backend calls DGX at `RPPG_BASE_URL`; the phone calls authenticated NeuroTruth APIs only. | User decision |
| D2 | Capture | CameraX records silent 720p/30fps MP4 for ten seconds after bundled ML Kit sees one stable face for one second; one second of face loss or app backgrounding cancels. | User decision |
| D3 | Processing | A persistent database queue has one active inference by default, a 10-second connect timeout, a 180-second read timeout, and no automatic retry. | User decision |
| D4 | Model input | Quality-approved finite, non-constant rPPG is linearly resampled to 512 points at 51.2 Hz; the matching EDA channel contains 512 literal `0.0` values. | User decision |
| D5 | Retention | Every accepted success, quality-failure, and technical-failure video is permanently retained encrypted until audited deletion; no automatic expiry applies. | User decision |
| D6 | Consent | New capture requires current `biosignal`, `ai_analysis`, `camera_rppg`, and `face_video_retention` consent; withdrawal blocks only new captures. | User decision |
| D7 | Retry | Timeout, connection, and DGX 5xx failure allow one user-triggered new attempt per retry action; `retry_required` and quality failure require a new capture. | User decision |
| D8 | Presentation | Camera results have a persistent phone card and may trigger existing phone alert/AUQ/chat cooldown logic when notification consent is active; they are never relayed to the watch. | User decision |
| D9 | Migration | Add Alembic `0002` only; never edit `0001` or `neurotruth_schema_v2_5.sql`. | User decision |
| D10 | Rollout | `RPPG_ENABLED=false` is the default and remains false until real-device end-to-end validation passes. | User decision |

## 6. Functional Requirements

- FR1. The phone must request camera permission, show a centered face guide, reject zero or multiple faces, and show `finding_face -> stabilizing -> recording -> uploading -> analyzing -> completed|retry_required|failed`.
- FR2. Stability means the same single face remains inside the guide for 1,000 continuous milliseconds. Recording starts automatically, lasts 10,000 milliseconds, has no audio track, and is cancelled if that face is absent or outside the guide for 1,000 continuous milliseconds.
- FR3. Accepted input is MP4 `video/mp4`, no larger than 20 MiB, and has declared `durationMs` from 9,500 through 10,500. Backend media inspection must also confirm a decodable video, duration in that interval, and an approximately 30fps stream before returning 202.
- FR4. The backend must encrypt and persist the accepted video and transactionally create capture/job metadata before returning HTTP 202. It must never return 202 while only plaintext or process memory holds the video.
- FR5. The worker calls `POST /v1/rppg/infer` with `video`, `session_id` equal to the NeuroTruth job UUID, and `include_waveform=true`.
- FR6. DGX success requires HTTP 200, `status == "success"`, `quality.passed == true`, a non-null finite waveform of at least two non-equal values, and finite positive `rppg_sample_rate_hz`. Otherwise no craving prediction is created.
- FR7. A quality failure, including HTTP 200 `status == "retry_required"` or `quality.passed == false`, ends as `retry_required`; the phone instructs a new recording and must not label it as no craving.
- FR8. A quality-approved waveform is linearly interpolated over its duration to the existing RandomForest ten-second contract: 512 `PPG_GREEN` values, 512 `EDA` values fixed at `0.0`, and the same 51.2 Hz timestamp grid. Non-finite interpolation output is a sanitized technical failure.
- FR9. The existing three-class craving predictor produces class `0|1|2`, confidence, and existing alert metadata. Persistence records `source=camera_rppg`, the originating rPPG job, EDA-zero adaptation, the craving model version, and the active `rppg_model` version.
- FR10. From capture start until a terminal job or local cancellation, existing sensor upload and prediction-SSE application are paused while watch-to-phone reception continues. The exact prior monitoring states are restored on every success, failure, cancellation, logout, and process-recovery path.
- FR11. After HTTP 202, the phone deletes its MP4 and persists `jobId`, `captureId`, and pause-latch state. It resumes polling after restart and never automatically reuploads. Before HTTP 202, manual upload retry is available only while the measurement screen remains alive; leaving the screen or terminating the app deletes the temporary MP4.
- FR12. The camera card retains class, confidence, HR when present, quality score, capture time, and processing time independently of later watch predictions. Notification consent gates only phone alert/AUQ/chat presentation, not rPPG inference or craving persistence.
- FR13. Background workers recover `queued` jobs and requeue stale `running` jobs on backend startup. A row lock or equivalent atomic claim guarantees at most `RPPG_MAX_CONCURRENCY` active DGX calls across workers.

## 7. User Experience / UI Requirements

- UI1. Add `카메라로 10초 측정` to the patient dashboard. Hide or disable it with an explanatory message when the feature, required consent, authentication, or backend availability is missing.
- UI2. The measurement screen shows front-camera preview, face guide, stability progress, ten-second countdown, upload/analysis progress, cancel, pre-202 upload retry, post-failure analysis retry when eligible, and recapture for quality failure.
- UI3. Quality guidance is: `얼굴을 화면 중앙에 두고 밝은 곳에서 움직이지 않은 상태로 다시 측정해 주세요.` It must not imply no craving.
- UI4. The result card uses the existing localized labels for class `0|1|2`, visibly labels the source as camera rPPG, and distinguishes unavailable HR from zero BPM.
- UI5. Consent settings add separate optional `camera_rppg` and `face_video_retention` controls and clearly state permanent server retention until administrator deletion. Existing historic data remains after withdrawal.
- UI6. Administrator web lists capture time, patient, job status, quality, HR, model/checkpoint, processing time, and failure code without waveform, provider payload, filesystem path, or decrypted video by default.

## 8. API / Data / State Requirements

### 8.1 Patient API

- API1. `GET /api/rppg/status` requires a patient JWT and returns `{enabled, available, modelLoaded, device, checkpoint, queue:{queued,running,maxConcurrency}}`. It exposes no DGX URL, filesystem path, or raw health response. Disabled returns HTTP 200 with `enabled=false`; unreachable/unready returns HTTP 200 with `available=false`.
- API2. `POST /api/rppg/jobs` requires a patient JWT and multipart fields: `video` binary MP4, `clientCaptureId` UUID, `capturedAtMs` positive epoch milliseconds, `durationMs` integer `9500..10500`, and optional `sessionId` UUID owned by the same patient. Accepted new or byte-identical idempotent input returns HTTP 202 `{jobId,captureId,status}`.
- API3. `GET /api/rppg/jobs/{jobId}` requires ownership and returns common fields `{jobId,captureId,status,capturedAtMs,createdAt,updatedAt}`. `completed` also returns `{source:"camera_rppg",classIndex,confidence,heartRateBpm,qualityScore,modelName,checkpoint,inferenceDevice,rppgSampleCount:512,rppgSamplingHz:51.2,processingMs}`. `retry_required` returns `{failureCode:"RPPG_QUALITY_RECAPTURE",qualityScore,reasons}`. `failed` returns a sanitized `failureCode` and `retryAllowed`.
- API4. `POST /api/rppg/jobs/{jobId}/retry` requires ownership and no body. It accepts only terminal `failed` jobs with a transient timeout, connection, HTTP 500, or HTTP 503 code; it creates the next attempt for the same capture and returns HTTP 202 with the new `jobId`. It returns 409 for active, completed, validation failure, or quality-recapture state.

### 8.2 Administrator API

- API5. `GET /api/admin/rppg/captures` uses existing admin pagination and returns summary metadata only.
- API6. `POST /api/admin/rppg/captures/{captureId}/reveal-video` requires JSON `{reason}` with a nonblank reason, audits before content leaves the service, and streams `video/mp4` inline with `Cache-Control: no-store`, `Content-Disposition: inline`, and no persisted plaintext output.
- API7. `DELETE /api/admin/rppg/captures/{captureId}` requires JSON `{reason,confirmCaptureId}` and exact UUID confirmation. It deletes linked camera prediction, all job rows, and encrypted video, records a non-sensitive audit event, and is retryable after partial storage failure without falsely reporting success.

### 8.3 Database and encrypted storage

- DATA1. Alembic `0002` adds immutable optional booleans `camera_rppg` and `face_video_retention` to `consent_snapshots`, both `NOT NULL DEFAULT false`, and extends the `model_versions.component` check with `rppg_model`.
- DATA2. `rppg_captures` contains UUID `id`, `patient_id`, optional owned `session_id`, `client_capture_id`, `consent_snapshot_id`, `captured_at`, `duration_ms`, `content_type`, `byte_size`, plaintext-video SHA-256, encrypted relative storage URI, encryption key version/nonce, capture metadata, deletion state, and timestamps. It has unique `(patient_id, client_capture_id)` and patient/time and deletion-state indexes.
- DATA3. `rppg_analysis_jobs` contains UUID `id`, `capture_id`, `attempt_no`, nullable `retry_of_job_id`, status, DGX `measurement_id`, `model_version_id` for `rppg_model`, searchable HR/quality/video/timing/model metadata, encrypted full provider response and waveform envelopes, encryption key version, sanitized failure code, start/finish timestamps, and timestamps. It has unique `(capture_id, attempt_no)` and status/created indexes.
- DATA4. Add nullable `craving_predictions.rppg_analysis_job_id` referencing `rppg_analysis_jobs` with a unique partial index when non-null. Camera predictions keep the existing nullable sensor relationship and existing three-class output contract. Administrative deletion removes the prediction before its referenced job.
- DATA5. Full DGX JSON and original/resampled waveform are encrypted with the V2.5 keyring and AAD bound to table, column, patient UUID, and record UUID. Only the approved searchable metadata in DATA2/DATA3 remains plaintext.
- STATE1. Job transitions are `queued -> running -> completed|retry_required|failed`; retry creates a new `queued` attempt rather than changing a terminal row. Startup recovery changes stale `running` to `queued` with a sanitized recovery marker before claiming it again.
- STATE2. `(patient_id, client_capture_id)` plus plaintext-video SHA-256 defines idempotency. Same UUID and hash returns the latest existing job; same UUID and a different hash returns 409 `RPPG_CAPTURE_CONFLICT` and an audit record.

## 9. Permissions, Security, Privacy, and Audit

- SEC1. All public NeuroTruth rPPG routes require existing JWT role and ownership checks. DGX receives no patient JWT, patient UUID, name, or app credential; its `session_id` is the random job UUID.
- SEC2. Accepted MP4 files use the existing versioned AES-256-GCM keyring with fresh nonces and `rppg_captures/video/patient/capture` AAD. Permanent storage contains only the packed encrypted envelope under `RPPG_STORAGE_ROOT`.
- SEC3. Plaintext exists only in a restrictive tmpfs file while validating or streaming to DGX and is deleted in `finally` on cancellation, exception, timeout, and shutdown. Logs, errors, audit metadata, and DGX-facing filenames contain no patient data, path, credential, waveform, or provider response.
- PRIV1. All successful and failed accepted videos are retained permanently. Consent withdrawal stops new capture but does not delete existing capture, job, prediction, or audit data.
- AUDIT1. Conflicting idempotency reuse, administrator playback attempts/results, delete attempts/results, and patient-wide deletion of rPPG resources write actor, action, target, reason where applicable, time, and sanitized outcome to `audit_logs`.
- AUDIT2. Playback is inline only and no product download endpoint or button is provided. Documentation must state that a privileged viewer can technically preserve rendered bytes and that policy, authorization, and audit are the controls.

## 10. Error, Edge-Case, and Concurrency Behavior

- ERR1. Backend upload validation returns 401/403 for authentication/consent, 409 for idempotency conflict or invalid retry state, 413 over 20 MiB, 415 for non-MP4, 422 for malformed fields or invalid video/duration/fps, and 503 when disabled or storage/queue prerequisites are unavailable. No accepted artifact is lost silently.
- ERR2. DGX 413/415/422 map to terminal `failed` non-retryable sanitized codes. Connection error, 180-second timeout, HTTP 500, and HTTP 503 map to `failed` with `retryAllowed=true`. Other non-200, malformed JSON, missing required success fields, and invalid waveform are terminal sanitized failures unless explicitly classified transient above.
- ERR3. HTTP 200 `retry_required` or `quality.passed=false` maps only to `retry_required` and demands recapture. It never calls RandomForest and cannot use the analysis retry endpoint.
- EDGE1. Logout or withdrawn consent during an accepted job does not cancel durable server processing; the patient must authenticate and own the job to retrieve it later. Withdrawal prevents new capture/retry attempts.
- EDGE2. Optional `sessionId` absence does not block measurement. A supplied session must belong to the patient. Session completion after upload does not invalidate the camera job.
- CONC1. The queue atomically claims jobs with database locking, honors default concurrency one, serializes duplicate capture creation, and never runs two active attempts for one capture.
- CONC2. Patient-wide deletion and individual capture deletion lock affected rows, stop new claims, remove linked predictions/jobs and encrypted storage, and record retryable deletion failure if file removal cannot be confirmed.

## 11. Dependencies and Configuration

- DEP1. Backend configuration: `RPPG_ENABLED=false`, `RPPG_BASE_URL=http://192.168.68.50:8000`, `RPPG_CONNECT_TIMEOUT_SECONDS=10`, `RPPG_READ_TIMEOUT_SECONDS=180`, `RPPG_MAX_UPLOAD_MIB=20`, `RPPG_MAX_CONCURRENCY=1`, and required writable `RPPG_STORAGE_ROOT` when enabled.
- DEP2. The verified development DGX reports `status=ok`, `model_loaded=true`, `device=cuda:0`, checkpoint `PURE_FactorizePhys_FSAM_Res.pth`, and accepts `video`, `session_id`, `include_waveform` at `/v1/rppg/infer`.
- DEP3. Android adds CameraX VideoCapture/ImageAnalysis and the bundled, on-device ML Kit face detector. It must not depend on network-based face analysis and must not preprocess the model input beyond recording the original MP4.
- DEP4. Test/LAN operation may use internal HTTP. Co-deployment changes `RPPG_BASE_URL` to `http://rppg:8000`, does not publish DGX port 8000, mounts DGX temporary upload storage as tmpfs, and confirms `DELETE_UPLOADED_VIDEO=true`.

## 12. Migration, Rollout, and Rollback

- MIG1. Create additive Alembic revision `0002` for DATA1-DATA4. Do not edit `0001`, `neurotruth_schema_v2_5.sql`, or the existing 18 tables beyond the explicitly listed additive columns/check changes.
- MIG2. Existing consent snapshots receive both new fields as false. No existing patient is automatically eligible; the mobile app must create a new immutable consent snapshot after an explicit choice.
- ROLL1. Deploy migration/backend first with the feature disabled, then compatible admin web and Android. Enable only in the controlled LAN after status, encryption, queue, and real-device checks pass.
- ROLL2. Release readiness additionally requires real ten-second phone video success, no DGX or backend plaintext residue, valid 512-point RF input, correct phone-only result routing, and recorded HR comparison with contact PPG.
- BACK1. Roll back behavior by setting `RPPG_ENABLED=false`; keep migration, encrypted videos, jobs, predictions, and unrelated services intact. Do not downgrade `0002` while retained rPPG data exists.

## 13. Implementation Boundaries

### 13.1 Expected Change Areas

- Backend V2.5 settings/runtime/routes, a focused DGX adapter/queue/storage/service/repository slice, additive Alembic `0002`, and focused tests.
- Android phone app camera screen, consent/auth models, API client, durable job polling/pause latch, result card, and tests; Wear OS code changes only if needed to ensure camera results are not sent.
- Administrator web capture summary/playback/delete controls plus current API, privacy, deployment, and operator documentation.

### 13.2 Forbidden Changes

- Do not modify DGX source, checkpoint, Docker project, API, or model behavior.
- Do not edit Alembic `0001`, `neurotruth_schema_v2_5.sql`, or unrelated V2.5 authentication/session/agent contracts.
- Do not expose DGX directly to mobile or public networks, send camera results to watch, add automatic retry/retention deletion, or add video/waveform download APIs.
- No unrelated refactors, formatting churn, dependency upgrades, broad rewrites, or generated-file churn unless explicitly listed above.

### 13.3 Minimal-Change Guidance

- Reuse existing AES-GCM, AAD, JWT ownership, audit, prediction, alert/cooldown, consent snapshot, Android auth storage, and pause-latch patterns.
- Preserve public interfaces outside the new rPPG endpoints and consent fields.
- Add only dependencies required for CameraX and bundled ML Kit at versions compatible with the current Android toolchain.

## 14. Acceptance Criteria

- AC1. Given a logged-in patient with all four current consents, when one face is stable for one second, then the phone records a silent 720p/30fps ten-second MP4 and cancels on one second of face loss or backgrounding.
- AC2. Given an accepted upload, when the backend returns 202, then encrypted permanent video and durable capture/job rows already exist and the phone deletes its local MP4 without losing resumable job state.
- AC3. Given a DGX quality-approved success, when processing completes, then exactly 512 finite rPPG values and 512 zero EDA values enter the existing three-class RandomForest and a traceable `camera_rppg` prediction is stored.
- AC4. Given DGX `retry_required` or failed quality, when the job completes, then no craving prediction exists and the phone requests a new capture rather than displaying no craving or offering same-video retry.
- AC5. Given a transient DGX failure, when the patient manually retries, then a new queued attempt uses the retained encrypted video once and no automatic retry occurs.
- AC6. Given duplicate `clientCaptureId`, when the video hash matches, then the existing latest job is returned; when it differs, then HTTP 409 and an audit event occur without overwrite.
- AC7. Given backend restart with queued/running jobs, when recovery starts, then durable work is safely requeued and no more than configured concurrency reaches DGX.
- AC8. Given any accepted success or failure, when storage is inspected, then the permanent file, DGX response, and waveform contain no plaintext and AES-GCM/AAD tampering is rejected.
- AC9. Given capture or job completion, when monitoring resumes, then prior sensor-upload/SSE states are restored, watch reception was uninterrupted, and no camera prediction is sent to the watch.
- AC10. Given notification consent, when a camera craving prediction qualifies under existing cooldown rules, then phone alert/AUQ/chat may run; without notification consent the prediction persists but presentation does not run.
- AC11. Given an administrator, when video is revealed or deleted with required reason/confirmation, then authorization, no-store inline handling, linked deletion, and complete audit behavior pass; no download endpoint exists.
- AC12. Given missing consent, disabled feature, invalid media, excessive size, ownership failure, timeout, or DGX error, then the specified sanitized status/code is returned and no credential, path, patient data, waveform, or provider payload leaks.
- AC13. Given `RPPG_ENABLED=false`, when existing NeuroTruth workflows run, then camera entry/API processing is unavailable while watch sensor, SSE, alert, AUQ, chat, report, and stored rPPG data remain intact.
- AC14. Given the controlled live environment, when a real phone measurement runs end to end, then app, backend, DGX, RF, DB, and result card complete and both DGX tmpfs and backend plaintext cleanup are verified before release readiness.

## 15. Validation Plan

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Spec pair | `python C:\Users\NeuroAI-Laptop\.codex\skills\feature-planner\scripts\validate_spec_pair.py neurotruth/docs/specs/2026-07-15-neurotruth-dgx-factorizephys-rppg-integration-spec.md` | PASS |
| Backend network-free | `apps/backend/.venv/Scripts/python.exe -m pytest` from `neurotruth/apps/backend` with fake DGX | Multipart/status/errors, 512+zero EDA, idempotency, recovery, encryption, consent, audit, and deletion pass without network |
| Fresh migration | Apply Alembic through `0002` to blank PostgreSQL and inspect schema | Existing 18-table baseline is preserved and only approved additive objects exist |
| Android | `.\gradlew.bat testDebugUnitTest assembleDebug lintDebug` from `neurotruth/apps/mobile` | Camera state, pause/recovery, API polling, consent, phone-only routing tests and build/lint pass |
| Admin web | `npm run build` plus focused tests from `neurotruth/apps/web` | Capture summary, reason playback/delete, permissions, and no-download behavior pass |
| Security inspection | Test tmpfs and encrypted volume after success, quality failure, timeout, cancellation, and restart | No plaintext residue; ciphertext/AAD tampering fails closed |
| Live joint test | Real phone ten-second capture through backend to `192.168.68.50:8000`, then compare HR with contact PPG | Full flow completes, quality failures are not craving results, routing/cleanup are correct, HR validity is recorded |

## 16. Risks and Open Notes

- RISK1. The DGX service has not completed real smartphone-video end-to-end validation. Keep the feature disabled by default and treat quality thresholds, waveform validity, and HR as experimental until joint testing.
- RISK2. The existing craving model was trained with real EDA, so all-zero EDA is a deliberate experiment input and must not be presented as clinically validated.
- RISK3. Internal DGX HTTP has no authentication or TLS. Keep it private to LAN/VPN during testing and use an unexposed Docker service network for co-deployment.
- RISK4. Permanent facial-video retention is highly sensitive. Explicit consent, reason-gated access, least privilege, encryption, deletion, and audit are mandatory controls.
- RISK5. Inline playback cannot technically prevent a privileged viewer from preserving rendered bytes; operational policy and audit provide accountability.

## 17. Implementation Checklist / Progress Record

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Finalize and validate English/Korean spec pair | Spec worker | Complete | Two rPPG spec files | `validate_spec_pair.py` | Authoritative decisions locked before source edits. |
| P2 | Backend, DB `0002`, DGX adapter, queue, encryption, prediction integration | Backend worker | Complete | Additive `0002`; focused `app/v25/rppg_*` services/routes/storage/repository; runtime/config and tests | Backend suite: `136 passed`; enabled-runtime, sanitized storage/queue failure, finite sample-rate, quality model-version, AES/AAD, 512 PPG + 512 zero EDA, API and schema tests pass | Baseline `0001` and authoritative SQL were not modified for rPPG. Feature remains disabled by default. |
| P3 | Android capture, consent, polling, pause recovery, phone result | Android worker | Complete | Phone CameraX/ML Kit flow, rPPG API/models/runtime/ViewModel, consent and monitoring integration, focused unit tests | Android: 15 suites / 61 tests / 0 failures; `assembleDebug` succeeds; lint has 0 errors and 26 existing warnings | Backend `alertAction` is preserved, routed-result receipts survive recreation, and camera predictions never go to the watch. |
| P4 | Administrator web, configuration, privacy/API/deployment documentation | Web/docs worker | Complete | Admin capture summary, reason-gated inline playback/delete controls, configuration and operator documentation | `npm run build` compiles successfully; static review confirms no download endpoint/button and object-URL cleanup | Playback remains `no-store`, reason-gated, and audited. |
| P5 | Minimal-diff review, full validation, live integration evidence | Main verifier | Automated verification complete; live gate pending | English/Korean spec pair and cross-contract verification record | `git diff --check` passes; five verifier defects were fixed and rechecked; Backend, Android, lint, APK, and Web validations pass | Real ten-second phone video through Backend -> DGX -> RF -> DB -> phone, DGX/backend plaintext cleanup, and contact-PPG HR comparison remain mandatory before enabling release. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.1 | 2026-07-15 | Feature Planner verifier | Recorded completed backend, Android, admin web, documentation, and automated verification; release remains disabled pending real-phone live DGX validation. |
| 1.0 | 2026-07-15 | Feature Planner | Initial finalized DGX FactorizePhys integration spec pair. |
