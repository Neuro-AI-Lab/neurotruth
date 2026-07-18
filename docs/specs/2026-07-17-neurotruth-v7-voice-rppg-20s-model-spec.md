# NeuroTruth v7 Voice, rPPG, and 20-Second Craving Model — Living Implementation Specification

<!-- feature-planner-control
{
  "workflow": "feature-planner/v7",
  "state": "complete",
  "source_spec": "docs/specs/2026-07-17-neurotruth-v7-voice-rppg-20s-model-spec.md",
  "korean_mirror": "docs/specs/2026-07-17-neurotruth-v7-voice-rppg-20s-model-spec.ko.md",
  "spec_revision": 12,
  "reviewed_revision": 12,
  "selected_strategy": "STRAT-1",
  "implementation_direction": "user-approved-divergence",
  "direction_decision_id": "D-005",
  "minimal_change_policy": "strict",
  "final_domain_gate": "confirmed_none",
  "open_question_ids": [],
  "active_slices": [],
  "next_action": "none"
}
-->

> The English file is authoritative. The Korean mirror is synchronized for review and handoff.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `complete`, revision 12, user-approved plan |
| Outcome | Ship the v7 three-tab Android flow with 20-second craving inference, STT/TTS, 20-second rPPG, verbal AUQ, and patient-safe probability labels. |
| Recommended implementation | `STRAT-1` — extend existing NeuroTruth model, session, rPPG, consent, Compose, and Android flows without replacing their architecture. |
| Planned production targets | `apps/backend/model/weights/final_model_win20s_high2p4/model.py::Conv1DNet`; `apps/backend/model/weights/final_model_win20s_high2p4/model_weights.pt::state_dict`; `apps/backend/model/weights/final_model_win20s_high2p4/model_metadata.json::model metadata`; `apps/backend/app/inference.py::BinaryCravingModel`; `apps/backend/app/v25/rppg_service.py::RppgJobService`; `apps/backend/app/v25/routes_rppg.py::create_rppg_job`; `apps/backend/app/v25/rppg_media.py::probe_video`; `apps/backend/app/settings.py::Settings`; `apps/backend/alembic/versions/20260717_0005_voice_rppg_20s.py::upgrade`; `apps/db/docker-compose.yml::services`; `apps/db/docker-compose.dgx.yml::services`; `apps/stt-service/app.py::transcribe`; `apps/stt-service/Dockerfile::image`; `apps/stt-service/requirements.txt::dependencies`; `apps/backend/app/v25/stt_client.py::SttClient`; `apps/backend/app/v25/routes_stt.py::router`; `apps/backend/app/v25/runtime.py::Runtime`; `apps/backend/app/v25/models.py::ConsentInput`; `apps/backend/app/v25/auth_service.py::consent serialization`; `apps/backend/app/v25/repository.py::consent persistence and message modality`; `apps/backend/app/v25/session_agents.py::free-dialogue-v3`; `apps/backend/app/v25/session_service.py::post_message`; `apps/backend/app/v25/routes_sessions.py::message/transcription contracts`; `apps/backend/app/main.py::router registry`; `apps/mobile/app/src/main/AndroidManifest.xml::RECORD_AUDIO`; `apps/mobile/app/src/main/java/com/example/healthsensor/AuthModels.kt::ConsentSelection`; `apps/mobile/app/src/main/java/com/example/healthsensor/AuthScreen.kt::voice consent`; `apps/mobile/app/src/main/java/com/example/healthsensor/MobileAuthRuntime.kt::voice consent runtime`; `apps/mobile/app/src/main/java/com/example/healthsensor/AuthenticatedApiClient.kt::authenticated multipart`; `apps/mobile/app/src/main/java/com/example/healthsensor/AuthenticatedSessionApi.kt::transcription/message API`; `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt::monitoring/chat state`; `apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringService.kt::sensor cadence`; `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt::three-tab voice/TTS/AUQ UI`; `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt::probability bands and AUQ axis`; `apps/mobile/app/src/main/java/com/example/healthsensor/RppgCameraScreen.kt::20-second capture`; `apps/mobile/app/src/main/java/com/example/healthsensor/RppgModels.kt::20-second contract`; `apps/mobile/app/src/main/java/com/example/healthsensor/RppgApi.kt::20-second upload`; `apps/mobile/app/src/main/java/com/example/healthsensor/RppgViewModel.kt::20-second state` |
| Expected additions | New production files: `apps/backend/model/weights/final_model_win20s_high2p4/model.py`, `apps/backend/model/weights/final_model_win20s_high2p4/model_weights.pt`, `apps/backend/model/weights/final_model_win20s_high2p4/model_metadata.json`, `apps/backend/alembic/versions/20260717_0005_voice_rppg_20s.py`, `apps/stt-service/app.py`, `apps/stt-service/Dockerfile`, `apps/stt-service/requirements.txt`, `apps/backend/app/v25/stt_client.py`, `apps/backend/app/v25/routes_stt.py`; dependency: `faster-whisper`; shared abstraction: `One STT client boundary`; focused tests and v9 handoff artifacts. |
| Work plan | Four slices: model/rPPG, STT/dialogue, Android, then handoff artifacts. WS1 and WS2 may run in parallel; WS3 consumes their fixed API contracts; WS4 follows code. |
| Open questions | None. |
| Agent decisions to review | None; all material direction is in the approved user plan. |
| Last material change | Revision 12 — verified v9 handoff rendering and completed local Docker migration, readiness, web, STT-disabled health, and 1,024-sample model smoke checks. |

## 2. Outcome and Scope

### Outcome

Patients use the Android `홈 · 대시보드 · 챗봇` flow, receive a four-band textual craving indication from the new 20-second binary model, can speak or type to the chatbot, can listen to replies through Android TTS, can optionally run a 20-second face/rPPG measurement, and see AUQ values on their native 8–56 scale.

### In Scope

- Replace the deployed 512-sample model artifact with the approved `final_model_win20s_high2p4` 1,024-sample artifact and checksum.
- Preserve linear interpolation, per-channel MinMax, constant-to-zero behavior, and no filtering.
- Collect 20 seconds on Android and send a new window every 10 seconds.
- Add a private STT Docker service and authenticated backend proxy; connect the existing voice consent.
- Add editable Android voice transcription and local per-message/automatic TTS.
- Upgrade free dialogue to `free-dialogue-v3`.
- Change rPPG capture/inference to 20 seconds and 1,024 rPPG plus 1,024 zero-EDA samples while preserving encrypted asynchronous storage.
- Use verbal AUQ choices, raw 8–56 visualization, and four textual probability bands.
- Produce synchronized v9 mobile handoff Markdown and PowerPoint while preserving the v7 source artifacts.

### Out of Scope / Non-Goals

- No Whisper model weights in Git and no public STT port.
- No audio persistence, transcript persistence before explicit user send, new server TTS API, or Watch TTS.
- No model retraining, new filter, AUQ clinical cutoff, diagnosis, treatment-effect claim, or validated clinical-risk claim.
- No destructive rewrite of migrations `0001`–`0004` or legacy 10-second rPPG history.
- No administrator-web redesign or STT/rPPG provider-source changes outside the NeuroTruth integration boundary.

### Users and Primary Flow

1. A consented patient signs in and lands on the three-tab v7 shell.
2. Watch data warms up for 20 seconds, then the phone uploads the latest 20-second PPG/GSR window every 10 seconds.
3. The backend produces binary probabilities and the phone maps class-1 probability to one of four non-clinical phrases.
4. The patient may answer AUQ, type or dictate a chat message, listen to the assistant, and finish the session.
5. The patient may trigger a 20-second face measurement; the existing rPPG job pipeline stores the encrypted capture and produces a camera-sourced prediction.
6. Dashboard and v9 handoff materials present the same terms and navigation.

### Current Assumptions and Constraints

- `final_model_win20s_high2p4/model_weights.pt` SHA-256 is `2493e5d75fcdc9b066b341deb47f81c9db49aac37c626619b7afe1a6b2fc354c` (D-001).
- Training stride 1 second and operational stride 10 seconds are recorded separately (D-002).
- Metadata mentions a 5 Hz low-pass, but the user explicitly requires the current no-filter service preprocessing; this difference must be recorded, not silently changed (D-003).
- `STT_ENABLED=false` and `RPPG_ENABLED=false` independently disable optional features (D-005).
- Docker Desktop may be unavailable locally; code-level checks proceed, but Compose acceptance remains incomplete until the engine runs.

## 3. Repository Pattern Baseline

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Model inference | SHA-checked PyTorch artifact, 2-channel normalization, CUDA smoke fallback | `apps/backend/app/inference.py::BinaryCravingModel` | Checksum, `eval`, inference mode, CPU fallback, binary response schema |
| Sensor ingestion | Authenticated window upload, resampling, prediction persistence and SSE | `apps/backend/app/v25/sensor_service.py`, `routes_sensor.py` | Patient ownership, encryption/persistence, alert flow |
| Free dialogue | Retry-safe Bedrock call with bounded history and question ledger | `apps/backend/app/v25/session_agents.py`, `session_service.py` | Idempotency, encrypted final text, repeat repair, safety/prohibited-claim guard |
| rPPG | CameraX/ML Kit capture, async job, DGX client, encrypted media/provider payload | `apps/mobile/.../RppgCameraScreen.kt`, `apps/backend/app/v25/rppg_service.py` | Job/API shape, idempotency, encrypted retention, legacy reads |
| Consent | Snapshot-based patient consent | `apps/backend/app/v25/models.py`, `auth_service.py`, Android auth models | Immutable snapshots and feature gating |
| Android UI | Compose screen state coordinated by `MainActivity` and view models | `apps/mobile/.../MainActivity.kt`, `SensorViewModel.kt` | Existing auth/session/Watch integrations and local error handling |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | Binary model loader and preprocessing | `apps/backend/app/inference.py` | Change length/artifact contract only. |
| R-002 | Sensor upload/prediction flow | `apps/backend/app/v25/sensor_service.py` | Accept 20-second source windows and retain DB/SSE flow. |
| R-003 | Retry-safe dialogue engine | `apps/backend/app/v25/session_service.py` | Add modality metadata and v3 prompt without a second dialogue path. |
| R-004 | rPPG persistence/job stack | `apps/backend/app/v25/rppg_service.py` | Change duration/sample length only and preserve legacy history. |
| R-005 | Authenticated Android API client | `apps/mobile/.../AuthenticatedApiClient.kt` | Add multipart STT endpoint and modality request. |
| R-006 | Existing presentation lineage | `../neurotruth_app_design_handoff_v8.pptx`, v7 inspection assets | Preserve v7/v8 visual language for v9 workflow handoff. |

## 4. Decisions and Questions

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | Model | Deploy the supplied 20-second Conv1D artifact and required SHA. | user | Approved plan and artifact inventory. | Tensor becomes `(1,2,1024)`. | confirmed | resolved |
| D-002 | Cadence | Use 20-second windows, 20-second warm-up, and 10-second operational stride. | user | Explicit corrected sampling decision. | Android upload cadence and model registration change. | confirmed | resolved |
| D-003 | Preprocessing | Keep linear interpolation + per-channel MinMax + no filter. | user | “전처리코드는 그냥 기존거로”. | Metadata discrepancy is documented. | confirmed | resolved |
| D-004 | Voice | Backend/DGX Whisper STT plus Android local TTS; audio is ephemeral. | user | Approved plan. | Adds isolated service/client and mobile voice controls. | confirmed | resolved |
| D-005 | Direction | Add isolated STT service and extend rPPG/mobile surfaces while preserving existing flows. | user | Approved implementation plan. | Authorizes the required production additions. | confirmed | resolved |
| D-006 | AUQ | Retain eight adapted items; show verbal 1–7 choices and raw total `X/56`. | user | Approved plan. | No clinical cutoff or normalization. | confirmed | resolved |
| D-007 | Probability UX | Hide exact class-1 percent and show the four approved bands. | user | Approved plan. | Patient UI changes only; DB/API raw value remains. | confirmed | resolved |
| D-008 | rPPG | Capture 20 seconds and infer from 1,024 rPPG plus 1,024 zero EDA. | user | Approved plan. | Additive duration constraint supports old and new records. | confirmed | resolved |
| D-009 | Repository mapping | Extend WS1 to the existing route and media-probe validators. | repository | `apps/backend/app/v25/routes_rppg.py`, `rppg_media.py` enforce the upload duration before the service. | Makes the approved 19.5–20.5 second contract complete without a new abstraction. | not-required | resolved |
| D-010 | Repository mapping | Store the artifact below `apps/backend/model/weights`, not `apps/backend/app/model/weights`. | repository | `apps/backend/Dockerfile` copies `model/`, and `_default_model_path` resolves from the backend root. | Makes runtime, tests, and Docker use the same artifact. | not-required | resolved |
| D-011 | Repository mapping | Extend WS2 to persist and read voice consent in `repository.py`. | repository | `_insert_consent` hardcodes voice false and `_consent` omits the voice column. | Makes the approved voice feature gate durable. | not-required | resolved |
| D-012 | Repository mapping | Register the STT router in `app/main.py`. | repository | `app/main.py` is the existing router registry. | Avoids an unconventional aggregate session router. | not-required | resolved |
| D-013 | Repository mapping | Bind message modality in `repository.py::append_message`. | repository | The existing INSERT hardcodes `text` although the schema permits voice. | Persists the approved `inputModality` without another message path. | not-required | resolved |
| D-014 | Validation | Update the schema regression expectation from migration `0004` to `0005`. | repository | Runtime and Alembic correctly report `20260717_0005`; one test still expects `0004`. | Restores the migration acceptance test without production changes. | not-required | resolved |
| D-015 | Repository mapping | Reuse `MobileAuthRuntime` and `AuthenticatedApiClient` for voice consent and STT multipart. | repository | These are the existing Android snapshot and authenticated transport boundaries. | Avoids a second auth or HTTP client path. | not-required | resolved |
| D-016 | Validation | Extend the existing auth model and authenticated-client tests. | repository | Voice consent and multipart transport changed their existing contracts. | Keeps new coverage beside the nearest regression tests. | not-required | resolved |
| D-017 | Repository mapping | Update `.env.example` with the shipped 20-second model, 40 MiB rPPG upload, and disabled-by-default STT controls. | repository | The template still points at the retired 10-second artifact, exposes a 20 MiB limit, and omits the new STT variables. | Keeps local and DGX configuration aligned with the implemented Compose contract. | not-required | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | Scope | Is any further product-direction choice required before implementation? | Prevents speculative implementation. | No; implement the approved plan. | D-005 | answered | User supplied an implementation-ready plan. |

## 5. Requirements and Acceptance Criteria

### Functional Requirements

- **FR-001:** Backend inference validates the supplied SHA, consumes `(1,2,1024)`, and emits the existing binary schema using no-filter MinMax preprocessing.
- **FR-002:** Android accumulates 20 seconds of PPG/GSR, warms up once, and submits the latest window every 10 seconds with the specified sample counts.
- **FR-003:** The internal STT service supports Korean `large-v3-turbo`, VAD, beam size 1, CUDA FP16 on DGX, CPU fallback locally, and no public port.
- **FR-004:** Authenticated STT endpoints enforce voice consent, supported media, size/duration, no-speech, timeout, availability, and tmpfs cleanup; no audio or draft transcript reaches durable storage.
- **FR-005:** Android provides tap-to-record up to 30 seconds, editable transcript, explicit send, text fallback, per-message TTS play/stop, and auto-read default OFF.
- **FR-006:** `free-dialogue-v3` accepts text and voice as equivalent final messages, stores `inputModality`, uses recent 20-message context plus ledger, allows zero or one question, avoids repeats, and preserves safety/prohibited-claim repair.
- **FR-007:** rPPG accepts only new 19.5–20.5 second uploads while old 10-second records remain readable; processing creates 1,024 rPPG and zero-EDA samples and preserves source/job/prediction linkage.
- **FR-008:** Android uses exactly three tabs and exposes rPPG from the home craving card.
- **FR-009:** Patient probability surfaces map boundaries `.25`, `.50`, `.75` to the four approved phrases and do not show exact percentages.
- **FR-010:** AUQ uses the seven approved Korean phrases mapped 1–7, stores totals 8–56, graphs against 56, and does not invent categorical interpretation.
- **FR-011:** v9 Markdown and PPT show the implemented workflow and screens while preserving v7 source artifacts.

### Non-Functional Requirements

- **NFR-001:** Preserve nearest project architecture/style and avoid unrelated refactors or dependencies.
- **NFR-002:** Secrets, audio, draft transcripts, raw provider errors, and decrypted media must not be logged or persisted.
- **NFR-003:** Optional STT/rPPG failures must not break authentication, text chat, TTS text display, or Watch sensor flow.
- **NFR-004:** Backend and Android contracts deploy together; migration is additive and rollback uses the prior image/commit.

### Acceptance Criteria

- **AC-001:** Fixed input matches the artifact model logits/softmax within tolerance and checksum/tensor/NaN failures are rejected.
- **AC-002:** Tests observe 20-second warm-up, 10-second cadence, 500 samples per phone PPG channel, and 20 EDA samples.
- **AC-003:** Fake STT tests cover consent, format, size, no speech, timeout, cleanup, and success; `/api/stt/status` exposes no secret path.
- **AC-004:** Android voice transcript remains editable and is not sent automatically; TTS controls clean up on message/screen change.
- **AC-005:** Text and voice messages share retry/idempotency logic and preserve only final text plus modality metadata.
- **AC-006:** rPPG tests prove 20-second validation, 1,024/1,024 model input, encrypted storage, legacy record readability, and camera prediction persistence.
- **AC-007:** Patient UI boundary tests show the four phrases with no exact percent, and AUQ tests show seven phrases and raw `X/56`.
- **AC-008:** Backend suite, Android unit/build/lint, migration `0005`, health/readiness/status endpoints, and model CPU smoke pass where local dependencies are available.
- **AC-009:** DGX checklist verifies Whisper CUDA, FactorizePhys, craving CUDA, and full app-to-DB flow before release readiness.
- **AC-010:** v9 deck renders without overlap/clipping and matches the final Markdown workflow/API terms.

### Edge and Failure Cases

- Missing or constant EDA → a zero channel, with quality metadata; missing/non-finite PPG → no prediction.
- STT disabled, missing consent, unavailable model, timeout, or no speech → code-specific error while typed chat remains enabled.
- TTS initialization/language failure → assistant text remains usable.
- Camera cancellation/background/face loss → cancel cleanly and restore paused sensor/SSE state.
- Legacy 10-second rPPG row → readable but not accepted as a new upload.
- Probability exactly `.25`, `.50`, or `.75` → enters the higher adjacent band.

## 6. Implementation Strategy and Direction

### STRAT-1 — Contract-Preserving Vertical Extensions

- **Direction:** `user-approved-divergence`
- **Current approach:** Update the one existing inference service and Android sensor producers, add one internal STT boundary, extend one existing rPPG pipeline, and render the changed concepts through existing Compose screens.
- **Existing flow to reuse:** R-001 through R-006.
- **Why this is minimal:** It avoids a second prediction stack, a second chat persistence path, a server TTS service, new report tables, and rPPG schema replacement.
- **Behavior-preserving limitations:** Raw probability remains in API/DB for computation; only patient presentation hides it. Legacy 10-second captures remain read-only.
- **Explicit exclusions:** Retraining, filter changes, admin redesign, Watch voice, durable audio, baseline migration edits, unrelated cleanup.
- **Compatibility and migration posture:** Add migration `0005`; deploy backend and Android together; disable optional features independently; roll back via previous images while retaining additive schema.
- **Direction approval:** D-005 confirms the user-approved additions.
- **Open-question sensitivity:** None.

### Material Alternatives Considered

| Strategy | Direction | Benefit | Additional code or risk | Decision |
| --- | --- | --- | --- | --- |
| Reuse current services and add isolated STT | user-approved-divergence | Smallest change with independent STT failure boundary | One new internal service and client | user-approved |
| Device-only STT | preserve | No server audio transfer | Does not satisfy DGX Whisper requirement | rejected |
| Server-side TTS | user-approved-divergence | Uniform voice | Adds persistence/streaming/privacy surface unnecessarily | rejected |
| Adopt metadata 5 Hz filter | divergence | Matches metadata text | Violates explicit preprocessing decision and changes measured behavior | rejected |

## 7. Modification Map and Change Budget

### Modification Map

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | production | `apps/backend/model/weights/final_model_win20s_high2p4/model.py` | `Conv1DNet` | add | `apps/backend/model/weights/final_moving_average_k5/model.py` | Import approved architecture. | FR-001 | WS1 | preserve |
| CH-002 | production | `apps/backend/model/weights/final_model_win20s_high2p4/model_weights.pt` | `state_dict` | add | supplied artifact | Import approved weights. | FR-001 | WS1 | preserve |
| CH-003 | production | `apps/backend/model/weights/final_model_win20s_high2p4/model_metadata.json` | model metadata | add | supplied artifact | Import approved metadata. | FR-001 | WS1 | preserve |
| CH-004 | production | `apps/backend/app/inference.py` | `BinaryCravingModel` | extend | current 512-sample loader | Switch to 1,024 samples and new artifact while preserving preprocessing. | FR-001, FR-002 | WS1 | preserve |
| CH-005 | production | `apps/backend/app/v25/rppg_service.py` | `RppgJobService` | extend | current 10-second job | Create 1,024 rPPG and zero-EDA samples. | FR-007 | WS1 | preserve |
| CH-006 | production | `apps/backend/app/settings.py` | `Settings` | extend | current rPPG/model settings | Set new model paths, 20-second duration, and 40 MiB default. | FR-001, FR-007 | WS1 | preserve |
| CH-007 | production | `apps/backend/alembic/versions/20260717_0005_voice_rppg_20s.py` | `upgrade` | add | `apps/backend/alembic/versions/20260715_0002_dgx_rppg.py` | Permit legacy 10-second and new 20-second captures. | FR-007 | WS1 | preserve |
| CH-008 | config | `apps/db/docker-compose.yml` | services | extend | backend model mounts | Mount new artifact and defaults. | FR-001 | WS1 | preserve |
| CH-009 | config | `apps/db/docker-compose.dgx.yml` | services | extend | backend GPU override | Preserve CUDA-first deployment with new model. | FR-001 | WS1 | preserve |
| CH-010 | test | `apps/backend/tests/test_binary_craving_model.py` | model contract tests | extend | existing binary tests | Prove SHA, tensor, preprocessing, and logits. | AC-001, AC-002 | WS1 | preserve |
| CH-011 | test | `apps/backend/tests/test_rppg_v25.py` | rPPG service tests | extend | existing fake DGX tests | Prove 20-second 1,024/1,024 flow. | AC-006 | WS1 | preserve |
| CH-012 | test | `apps/backend/tests/test_rppg_routes_v25.py` | rPPG route tests | extend | current upload tests | Prove duration and 40 MiB contract. | AC-006 | WS1 | preserve |
| CH-013 | production | `apps/stt-service/app.py` | `transcribe` | add | existing internal-service conventions | Implement ephemeral Korean Whisper and status. | FR-003, FR-004 | WS2 | approved-divergence |
| CH-014 | production | `apps/stt-service/Dockerfile` | image | add | backend Docker conventions | Build isolated STT runtime. | FR-003 | WS2 | approved-divergence |
| CH-015 | production | `apps/stt-service/requirements.txt` | dependencies | add | isolated service | Pin STT runtime dependencies. | FR-003 | WS2 | approved-divergence |
| CH-016 | production | `apps/backend/app/v25/stt_client.py` | `SttClient` | add | `apps/backend/app/v25/rppg_dgx.py` | Proxy status/transcription with timeouts and error mapping. | FR-004 | WS2 | approved-divergence |
| CH-017 | production | `apps/backend/app/v25/routes_stt.py` | router | add | `apps/backend/app/v25/routes_rppg.py` | Add authenticated status/transcription API. | FR-004 | WS2 | approved-divergence |
| CH-018 | production | `apps/backend/app/v25/runtime.py` | `Runtime` | extend | current router/services wiring | Wire STT client and router. | FR-004 | WS2 | preserve |
| CH-019 | production | `apps/backend/app/v25/models.py` | `ConsentInput` | extend | current consent model | Expose voice consent in API model. | FR-004 | WS2 | preserve |
| CH-020 | production | `apps/backend/app/v25/auth_service.py` | consent serialization | extend | current voice hard-code | Persist and return voice consent. | FR-004 | WS2 | preserve |
| CH-021 | production | `apps/backend/app/v25/session_agents.py` | `free-dialogue-v3` | extend | `free-dialogue-v2` | Update the approved prompt and ledger rules. | FR-006 | WS2 | preserve |
| CH-022 | production | `apps/backend/app/v25/session_service.py` | `post_message` | extend | retry-safe message flow | Persist `inputModality` without a second path. | FR-006 | WS2 | preserve |
| CH-023 | production | `apps/backend/app/v25/routes_sessions.py` | message/transcription contracts | extend | current session router | Validate modality and expose STT route linkage. | FR-004, FR-006 | WS2 | preserve |
| CH-024 | config | `apps/db/docker-compose.yml` | services | extend | private Compose network | Add disabled-by-default CPU STT without host port. | FR-003 | WS2 | approved-divergence |
| CH-025 | config | `apps/db/docker-compose.dgx.yml` | services | extend | DGX GPU overrides | Add CUDA STT override. | FR-003 | WS2 | approved-divergence |
| CH-026 | test | `apps/backend/tests/test_stt_routes_v25.py` | STT route tests | add | fake provider pattern | Prove consent, format, size, no-speech, timeout, cleanup. | AC-003 | WS2 | preserve |
| CH-027 | test | `apps/backend/tests/test_auth_routes_v25.py` | voice consent tests | extend | current consent tests | Prove voice snapshot behavior. | AC-003 | WS2 | preserve |
| CH-028 | test | `apps/backend/tests/test_sessions_v25.py` | modality/dialogue tests | extend | current retry tests | Prove v3 prompt, modality, and idempotency. | AC-005 | WS2 | preserve |
| CH-029 | production | `apps/mobile/app/src/main/AndroidManifest.xml` | `RECORD_AUDIO` | extend | current camera/network permissions | Add microphone permission. | FR-005 | WS3 | preserve |
| CH-030 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/AuthModels.kt` | `ConsentSelection` | extend | current consent selection | Add voice choice. | FR-004 | WS3 | preserve |
| CH-031 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/AuthScreen.kt` | voice consent | extend | current consent UI | Enable voice setting. | FR-004 | WS3 | preserve |
| CH-032 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/AuthenticatedSessionApi.kt` | transcription/message API | extend | current message API | Add multipart STT and modality. | FR-004, FR-005 | WS3 | preserve |
| CH-033 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt` | monitoring/chat state | extend | current 10-second and free chat state | Use 20-second/10-second cadence and voice/TTS UI state. | FR-002, FR-005 | WS3 | preserve |
| CH-034 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringService.kt` | sensor cadence | extend | current 10-second constants | Send newest 20 seconds every 10 seconds. | FR-002 | WS3 | preserve |
| CH-035 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt` | three-tab voice/TTS/AUQ UI | extend | current Compose navigation/chat | Implement v7 shell and voice controls. | FR-005, FR-008, FR-010 | WS3 | preserve |
| CH-036 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt` | probability bands and AUQ axis | extend | current percentage/normalized cards | Hide exact percent and use 8–56 AUQ. | FR-009, FR-010 | WS3 | preserve |
| CH-037 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgCameraScreen.kt` | 20-second capture | extend | current 10-second capture | Record 20 seconds. | FR-007, FR-008 | WS3 | preserve |
| CH-038 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgModels.kt` | 20-second contract | extend | current rPPG models | Represent new duration/sample count. | FR-007 | WS3 | preserve |
| CH-039 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgApi.kt` | 20-second upload | extend | current multipart upload | Send new duration metadata. | FR-007 | WS3 | preserve |
| CH-040 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgViewModel.kt` | 20-second state | extend | current job state | Present 20-second capture lifecycle. | FR-007 | WS3 | preserve |
| CH-041 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/AuthenticatedSessionApiContractTest.kt` | voice API tests | extend | current session tests | Prove transcript/modality contract. | AC-004, AC-005 | WS3 | preserve |
| CH-042 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/PatientDashboardParserTest.kt` | label/AUQ tests | extend | current dashboard tests | Prove band boundaries and raw AUQ. | AC-007 | WS3 | preserve |
| CH-043 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt` | rPPG tests | extend | current 10-second tests | Prove 20-second mobile contract. | AC-006 | WS3 | preserve |
| CH-044 | docs | `docs/neurotruth_app_design_handoff_v9.md` | developer handoff | add | v7/v8 handoff assets | Describe implemented screens and workflow. | FR-011 | WS4 | preserve |
| CH-045 | docs | `docs/handoff/neurotruth_app_design_handoff_v9.pptx` | presentation | add | workspace `neurotruth_app_design_handoff_v8.pptx` | Produce and render synchronized v9 without modifying v7. | FR-011 | WS4 | preserve |
| CH-046 | production | `apps/backend/app/v25/routes_rppg.py` | `create_rppg_job` | extend | current 9.5–10.5 second form validation | Enforce the new 19.5–20.5 second upload contract. | FR-007 | WS1 | preserve |
| CH-047 | production | `apps/backend/app/v25/rppg_media.py` | `probe_video` | extend | current 10-second ffprobe validation | Validate actual video duration against the new contract. | FR-007 | WS1 | preserve |
| CH-048 | production | `apps/backend/app/v25/repository.py` | consent persistence and message modality | extend | `_insert_consent`, `_consent`, `append_message` | Persist/read voice consent and bind text-or-voice message modality. | FR-004, FR-006 | WS2 | preserve |
| CH-049 | production | `apps/backend/app/main.py` | router registry | extend | existing `include_router` calls | Import and include the STT router conventionally. | FR-004 | WS2 | preserve |
| CH-050 | test | `apps/backend/tests/test_rppg_schema_v25.py` | required migration revision | extend | stale `20260716_0004` expectation | Expect additive migration `20260717_0005`. | AC-008 | WS1 | preserve |
| CH-051 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/MobileAuthRuntime.kt` | voice consent runtime | extend | current consent snapshot | Expose the persisted voice flag to chat UI. | FR-004 | WS3 | preserve |
| CH-052 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/AuthenticatedApiClient.kt` | authenticated multipart | extend | current authenticated request helper | Reuse auth refresh/error handling for STT upload. | FR-004, FR-005 | WS3 | preserve |
| CH-053 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/ConversationSessionManagerTest.kt` | voice session regression | extend | current session lifecycle tests | Prove voice/text lifecycle does not break session state. | AC-004, AC-005 | WS3 | preserve |
| CH-054 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/AuthenticatedSensorContractTest.kt` | 20-second sensor contract | extend | current window contract tests | Prove warm-up/window/cadence payload values. | AC-002 | WS3 | preserve |
| CH-055 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/AuthModelsTest.kt` | voice consent model tests | extend | current consent mapping tests | Prove voice snapshot round-trip. | AC-004 | WS3 | preserve |
| CH-056 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/AuthenticatedApiClientTest.kt` | multipart authentication tests | extend | current authenticated-client tests | Prove authenticated STT multipart behavior. | AC-004, AC-005 | WS3 | preserve |
| CH-057 | config | `.env.example` | model/rPPG/STT deployment values | extend | stale 10-second model and rPPG defaults | Publish the new paths, SHA, 40 MiB limit, STT feature flag, timeouts, upload limit, and external model mount path without adding secrets. | FR-001, FR-003, FR-007 | WS2 | preserve |

### Change Budget

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 18 | 12 | 4 | 700 | None | None |
| WS2 | 20 | 15 | 5 | 970 | `faster-whisper` | One STT client boundary |
| WS3 | 22 | 15 | 2 | 1350 | None | None |
| WS4 | 6 | 0 | 0 | 0 | None | None |

The production-line budget is an expansion alarm, not a compression target. Revise the design if the clear minimal implementation exceeds it.

## 8. Work Plan

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | Integrate 20-second model and rPPG contracts | None | Serial | CH-001, CH-002, CH-003, CH-004, CH-005, CH-006, CH-007, CH-008, CH-009, CH-010, CH-011, CH-012, CH-046, CH-047, CH-050 | `apps/backend/model/weights/final_model_win20s_high2p4/**`; `apps/backend/app/inference.py`; `apps/backend/app/v25/rppg_service.py`; `apps/backend/app/v25/routes_rppg.py`; `apps/backend/app/v25/rppg_media.py`; `apps/backend/app/settings.py`; `apps/backend/alembic/versions/20260717_0005_voice_rppg_20s.py`; `apps/db/*.yml`; `apps/backend/tests/test_binary_craving_model.py`; `apps/backend/tests/test_rppg*.py` | Session/auth/mobile/web | FR-001, FR-002, FR-007, NFR-001, NFR-003, NFR-004, AC-001, AC-002, AC-006, AC-008, AC-009 | Focused backend pytest plus artifact smoke | verified |
| WS2 | Add ephemeral STT, voice consent, and free-dialogue-v3 | WS1 | Serial | CH-013, CH-014, CH-015, CH-016, CH-017, CH-018, CH-019, CH-020, CH-021, CH-022, CH-023, CH-024, CH-025, CH-026, CH-027, CH-028, CH-048, CH-049, CH-057 | `.env.example`; `apps/stt-service/**`; `apps/backend/app/v25/**`; `apps/backend/app/main.py`; `apps/db/*.yml`; `apps/backend/tests/test_stt_routes_v25.py`; `apps/backend/tests/test_auth_routes_v25.py`; `apps/backend/tests/test_sessions_v25.py` | Inference/rPPG/mobile/web | FR-001, FR-003, FR-004, FR-006, FR-007, NFR-001, NFR-002, NFR-003, AC-003, AC-005, AC-008, AC-009 | Fake STT/session/auth pytest and environment-template cross-check | verified |
| WS3 | Implement Android v7 voice, cadence, rPPG, AUQ, and label UI | WS2 | Serial | CH-029, CH-030, CH-031, CH-032, CH-033, CH-034, CH-035, CH-036, CH-037, CH-038, CH-039, CH-040, CH-041, CH-042, CH-043, CH-051, CH-052, CH-053, CH-054, CH-055, CH-056 | `apps/mobile/app/src/main/**`; `apps/mobile/app/src/test/**` | Wear OS/admin web/backend internals | FR-002, FR-004, FR-005, FR-007, FR-008, FR-009, FR-010, NFR-001, NFR-003, NFR-004, AC-002, AC-004, AC-005, AC-006, AC-007, AC-008, AC-009 | Unit tests, assembleDebug, lintDebug | verified |
| WS4 | Produce v9 synchronized handoff artifacts | WS3 | Serial | CH-044, CH-045 | `docs/neurotruth_app_design_handoff_v9.md`; `docs/handoff/neurotruth_app_design_handoff_v9.pptx` | v7 source artifacts and production code | FR-011, NFR-001, AC-010 | Render all slides and cross-check terms | verified |

### Parallelization Rationale

WS1 owns inference/rPPG while WS2 owns STT/session/auth. Their shared Compose edits are small and must be reconciled by the main agent. WS3 starts only after the endpoint and sample contracts are stable. WS4 follows final implemented UI terminology.

### Final Integration

Run the backend suite and model smoke, Android unit/build/lint, spec scope/patch checks, migration and status endpoint checks, then Compose fake-provider flows. When Docker is available, rebuild locally and verify `/health`, `/ready`, `/api/stt/status`, and `/api/rppg/status`. DGX CUDA checks remain a deployment acceptance step.

## 9. Validation, Rollout, and Risk

### Validation Plan

- WS1: fixed logits comparison, SHA/tensor/NaN tests, 20-second resampling, rPPG legacy/new duration tests.
- WS2: fake Whisper success/error/cleanup/consent tests and free-dialogue-v3 modality/idempotency tests.
- WS3: Kotlin contract/parser/policy tests plus debug APK and lint.
- WS4: slide PNG rendering, overlap/clipping inspection, and Markdown/PPT terminology cross-check.
- Integration: backend full pytest, migration head, CPU model smoke, Compose health/readiness, Android install when device is connected, DGX CUDA checklist.

### Minimality and Style-Fidelity Review

- Inspect every changed production path against CH-001–CH-018.
- Reject a second prediction/chat/rPPG persistence path, server TTS, baseline migration edits, broad formatting churn, and unrelated dependency upgrades.
- Preserve existing encrypted repositories, retry/idempotency helpers, CameraX job state, and Compose conventions.

### Rollout and Rollback

Back up PostgreSQL, deploy backend plus Android/Wear sensor contract together, apply additive `0005`, then enable `STT_ENABLED` and `RPPG_ENABLED` separately after status/smoke checks. Roll back to the previous image/commit; do not delete new additive rows. Whisper model and credentials remain external mounts/secrets.

### Risks and Mitigations

| Risk | Impact | Mitigation or Evidence |
| --- | --- | --- |
| Metadata/filter mismatch | Model output may differ from training documentation. | Explicit user decision D-003; record preprocessing version and limitation. |
| DGX ARM64/CUDA Whisper compatibility | STT container may fail GPU initialization. | Isolated service, CPU fallback, status endpoint, DGX smoke before readiness. |
| Large mobile/MainActivity edit | Regression in auth/session flow. | Reuse view model/API state, focused tests, debug build/lint, no unrelated refactor. |
| Optional provider failure | Voice or rPPG unavailable. | Independent flags; text chat/Watch/auth remain functional. |
| Misinterpreted bands/AUQ | Patient may read phrases as clinical judgments. | Label as research UI and avoid clinical cutoffs/diagnosis. |

## 10. Revision and Progress

### Design Revision History

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-17T12:00:00+09:00 | approved-user-plan | Created implementation-ready bilingual contract and four delegated slices. | D-001, D-002, D-003, D-004, D-005, D-006, D-007, D-008 | Q-001 |
| 2 | 2026-07-17T12:25:00+09:00 | repository-evidence | Added the existing rPPG route and media duration validators to WS1. | D-009 | None |
| 3 | 2026-07-17T12:35:00+09:00 | test-evidence | Corrected the artifact target to the backend model directory used by tests and Docker. | D-010 | None |
| 4 | 2026-07-17T12:50:00+09:00 | repository-evidence | Added the repository voice-consent persistence/read path to WS2. | D-011 | None |
| 5 | 2026-07-17T12:52:00+09:00 | repository-evidence | Added the conventional backend router registration point to WS2. | D-012 | None |
| 6 | 2026-07-17T12:54:00+09:00 | repository-evidence | Added the existing message modality INSERT to the already-approved repository scope. | D-013 | None |
| 7 | 2026-07-17T13:05:00+09:00 | regression-evidence | Added the stale migration-head assertion to the WS1 test scope. | D-014 | None |
| 8 | 2026-07-17T13:20:00+09:00 | repository-evidence | Added Android auth-runtime/multipart anchors and their focused tests to WS3. | D-015 | None |
| 9 | 2026-07-17T20:35:00+09:00 | implementation-evidence | Added nearest Android auth model/client regression targets to WS3. | D-016 | None |
| 10 | 2026-07-17T21:05:00+09:00 | integration-evidence | Added the stale deployment environment template to WS2 before editing it. | D-017 | None |
| 11 | 2026-07-17T21:12:00+09:00 | implementation-evidence | Verified `.env.example` against Compose and resumed the v9 handoff slice. | D-017 | None |
| 12 | 2026-07-17T21:20:00+09:00 | verification-evidence | Completed slide-by-slide v9 rendering review and local Docker integration checks against migration `0005` and the shipped 20-second model. | D-001, D-002, D-004, D-008 | None |

### Implementation Progress Record

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-17T12:00:00+09:00 | 1 | — | ready | User approved all material behavior; no open domain questions. |
| 2026-07-17T12:20:00+09:00 | 1 | WS1 | implementing | Delegated 20-second model and rPPG contract changes to one worker. |
| 2026-07-17T12:25:00+09:00 | 2 | WS1 | implementing | Worker found two existing duration validators; scope updated before those edits. |
| 2026-07-17T12:35:00+09:00 | 3 | WS1 | implementing | Focused tests found the artifact path one directory too deep; corrected before moving files. |
| 2026-07-17T12:45:00+09:00 | 3 | WS1 | completed | Scope PASS; SHA matched; focused tests 24 passed, 1 skipped; compile and Alembic head passed. |
| 2026-07-17T12:46:00+09:00 | 3 | WS2 | implementing | Delegated STT, voice consent, and free-dialogue-v3 to one worker. |
| 2026-07-17T12:50:00+09:00 | 4 | WS2 | implementing | Worker found repository voice hard-coding; scope updated before edits. |
| 2026-07-17T12:52:00+09:00 | 5 | WS2 | implementing | Worker found the router registry; scope updated before import/include edits. |
| 2026-07-17T12:54:00+09:00 | 6 | WS2 | implementing | Worker found the hardcoded text modality; change map updated before edits. |
| 2026-07-17T13:04:00+09:00 | 6 | WS2 | completed | Focused tests 36 passed; full backend 164 passed, 1 skipped before stale migration assertion. |
| 2026-07-17T13:05:00+09:00 | 7 | WS1 | implementing | Reopened only to update the migration-head schema assertion. |
| 2026-07-17T13:10:00+09:00 | 7 | WS1 | completed | Migration tests 3 passed; full backend 166 passed, 1 skipped. |
| 2026-07-17T13:11:00+09:00 | 7 | WS3 | implementing | Delegated Android v7 voice, cadence, rPPG, AUQ, and probability-label UI. |
| 2026-07-17T13:20:00+09:00 | 8 | WS3 | implementing | Worker mapped existing Android consent/HTTP boundaries; scope updated before edits. |
| 2026-07-17T20:35:00+09:00 | 9 | WS3 | implementing | Final changed-test inventory added before main scope/build verification. |
| 2026-07-17T20:45:00+09:00 | 9 | WS3 | completed | Final unit, assembleDebug, lintDebug, and scope checks passed. |
| 2026-07-17T20:46:00+09:00 | 9 | WS4 | implementing | Delegated synchronized v9 Markdown/PPT creation and slide rendering. |
| 2026-07-17T21:05:00+09:00 | 10 | WS2 | implementing | Reopened the slice to align `.env.example` with the implemented 20-second model, rPPG, and STT contract. |
| 2026-07-17T21:12:00+09:00 | 11 | WS2 | completed | Environment-template paths, SHA, rPPG limit, STT feature flag, timeouts, upload limit, and external model mount were cross-checked with Compose. |
| 2026-07-17T21:12:00+09:00 | 11 | WS4 | implementing | Resumed final Markdown/PPT rendering and visual QA after configuration integration. |
| 2026-07-17T21:20:00+09:00 | 12 | WS4 | completed | Verified nine rendered slides, template fidelity, empty placeholders, bounds, patient-safe terminology, and synchronized Markdown. |
| 2026-07-17T21:20:00+09:00 | 12 | integration | completed | Backed up the existing local DB, rebuilt Compose, confirmed Alembic `20260717_0005`, `/health`, `/ready`, web 200, STT-disabled health, and CPU model SHA/1,024-sample smoke behavior. |
