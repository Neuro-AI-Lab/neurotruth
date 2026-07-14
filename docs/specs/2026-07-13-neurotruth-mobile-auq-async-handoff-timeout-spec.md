# NeuroTruth Mobile AUQ Suppression, Async Handoff, and Configurable Chat Timeout Spec

- **Language role:** English source spec
- **Spec status:** Finalized
- **Spec version:** 1.2
- **Last updated:** 2026-07-13
- **English source:** `neurotruth/docs/specs/2026-07-13-neurotruth-mobile-auq-async-handoff-timeout-spec.md`
- **Korean mirror:** `neurotruth/docs/specs/2026-07-13-neurotruth-mobile-auq-async-handoff-timeout-spec.ko.md`
- **Requester / owner:** NeuroTruth project requester
- **Implementation status:** Implemented

## 0. Codex Implementation Handoff

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: neurotruth/docs/specs/2026-07-13-neurotruth-mobile-auq-async-handoff-timeout-spec.md
korean_mirror_spec: neurotruth/docs/specs/2026-07-13-neurotruth-mobile-auq-async-handoff-timeout-spec.ko.md
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

## 1. Summary

The Android phone app previously allowed later REQUIRED craving alerts to replace an active intervention chat with the eight-item AUQ/state-check screen. Handoff report generation also depended on one synchronous mobile HTTP request whose 20-second read timeout was shorter than the Bedrock operation. This feature adds a shared active-intervention latch, asynchronous handoff job submission/status polling, a persisted administrator chat timeout with a 60-minute default, and an intervention-only reset. Prediction recording and watch state delivery continue, but active intervention now suppresses both phone presentation and watch vibration/notification until chat closes.

## 2. Goals

- G1. Prevent AUQ from reopening while intervention chat is active.
- G2. Continue prediction recording and phone-to-watch state delivery while suppressing phone and watch user-facing alerts during active intervention.
- G3. Generate and persist handoff reports without one long-lived mobile request.
- G4. Default chat response waiting to 60 minutes and allow administrator configuration.
- G5. Reset intervention state without deleting sensor history or server URLs.
- G6. Preserve existing public endpoint compatibility.

## 3. Non-Goals

- NG1. Do not add a conversation turn limit.
- NG2. Do not change craving prediction, alert classification, watch UI, LLM model/provider, prompts, or sensor upload timeout.
- NG3. Do not add a durable distributed job queue; the handoff registry remains bounded and process-local.
- NG4. Do not remove or change the existing full data reset.

## 4. Users and Use Cases

### 4.1 Target Users

- A patient using text intervention after a required craving alert.
- An administrator or researcher configuring and resetting the phone intervention flow.
- A backend operator monitoring handoff generation.

### 4.2 Primary Use Cases

- UC1. A patient completes AUQ and continues chatting while later alerts are recorded without reopening AUQ.
- UC2. A patient starts handoff generation, keeps chatting, and later sees the completed report.
- UC3. An administrator changes the chat response timeout or resets only intervention state.
- UC4. A legacy client continues using synchronous handoff generation.

## 5. Final Decisions

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | State | `PhoneMonitoringState` owns the intervention-active latch used by service and foreground paths. | User decision |
| D2 | Flow | AUQ submission or reopening chat activates the latch; closing chat, full reset, or administrator intervention reset clears it. | User decision |
| D3 | Alerts | Suppressed alerts are still claimed, recorded, and published. Phone-to-watch state delivery continues with an explicit non-presenting action, so active intervention does not launch AUQ or trigger phone/watch vibration or notification. | User decision after physical-device verification |
| D4 | API | Add HTTP 202 `POST /api/intervention/handoff/jobs` and `GET /api/intervention/handoff/jobs/{job_id}` while keeping `POST /api/intervention/handoff`. | User decision |
| D5 | Jobs | Completed jobs reuse the synchronous generation/persistence path; public failures are sanitized. | Agent conservative default |
| D6 | Mobile | Chat and handoff use independent busy states; generation POST is never automatically resubmitted. | User decision |
| D7 | Timeout | Chat read timeout defaults to 3,600,000 ms and is stored as 1–1,440 administrator-configurable minutes. | User decision |
| D8 | Reset | `상담 상태 초기화` clears AUQ/chat/slot/handoff/polling state but preserves sensor samples and server configuration. | User decision |
| D9 | Capacity | The process-local registry keeps at most 128 jobs; terminal metadata TTL and maximum execution time are both one hour. | Agent conservative default |
| D10 | Shutdown | Shutdown atomically rejects new jobs, marks queued/running jobs failed, and cancels/drains tracked tasks. | Verification refinement |

## 6. Functional Requirements

- FR1. `registerAlertAction` must not request user-facing presentation while intervention is active.
- FR2. Suppression must not stop prediction publication, audit state, or watch state delivery, but the watch payload must resolve to a non-presenting action while intervention is active.
- FR3. Closing chat must enable only future distinct alerts and must not replay an already claimed alert.
- FR4. Administrator intervention reset must cancel local chat/handoff jobs and clear intervention UI/state without clearing sensor history or URLs.
- FR5. Async handoff submission must capture an immutable request snapshot and return a unique opaque job ID immediately.
- FR6. Job status must expose `jobId`, `sessionId`, and queued/running/completed/failed; completed includes `result`, failed includes a safe error.
- FR7. Mobile polling must stop on completion, failure, 404, deadline, reset, new session, or ViewModel destruction.
- FR8. Chat must remain usable while a handoff job runs.
- FR9. Timeout settings must survive app/activity recreation and fall back to 60 minutes when absent or invalid.
- FR10. Hung jobs and server shutdown must release registry capacity safely.
- FR11. Foreground and background prediction paths must apply the same active-intervention watch suppression decision.

## 7. User Experience / UI Requirements

- UI1. Active chat remains visible when later required alerts arrive.
- UI2. The handoff card shows queued/running/completed/failed independently from chat-send state.
- UI3. Administrator mode exposes `채팅 응답 제한시간(분)`, `제한시간 적용`, and `상담 상태 초기화`.
- UI4. Invalid timeout values show a Korean 1–1,440 minute validation message.
- UI5. Chat timeout preserves the visible user message and instructs manual retry; it does not retry automatically.
- UI6. During active intervention the watch may continue showing the latest craving class/alert level, but it must not vibrate or post a craving notification.

## 8. API / Data / State Requirements

- API1. `POST /api/intervention/handoff/jobs` accepts `HandoffRequest` and returns HTTP 202 with `jobId`, `sessionId`, and `status=queued`.
- API2. `GET /api/intervention/handoff/jobs/{job_id}` returns public job state; unknown or expired jobs return HTTP 404.
- API3. `POST /api/intervention/handoff` remains backward-compatible.
- DATA1. Completed async reports use the existing Postgres handoff persistence path and session ID.
- DATA2. Administrator preferences store timeout minutes only.
- STATE1. One mobile handoff job may be active at a time; stale completion cannot release a newer gate token.
- STATE2. Process-local jobs are bounded to 128 entries, terminal TTL is one hour, and runtime is bounded to one hour.
- STATE3. When intervention is active, the phone-to-watch payload preserves prediction metadata but overrides `alertAction` with `none`; after chat closes, future payloads retain their original action.

## 9. Permissions, Security, Privacy, and Audit

- SEC1. No new Android or backend permission is required.
- PRIV1. Do not log full conversations, credentials, bearer tokens, or raw provider failures.
- AUDIT1. Suppressed alerts remain in prediction/alert state and watch state delivery; only phone/watch user-facing presentation is suppressed.
- AUDIT2. Job IDs are opaque UUIDs and failed status returns `Handoff generation failed` only.

## 10. Error, Edge-Case, and Concurrency Behavior

- ERR1. Transient status-read failures retry polling until the configured deadline without resubmitting generation.
- ERR2. HTTP 404 reports a possible backend restart and stops polling.
- ERR3. Provider errors, runtime timeout, or shutdown produce sanitized failed state.
- EDGE1. A suppressed alert stays claimed so closing chat does not immediately replay stale AUQ.
- EDGE2. Missing/invalid stored timeout returns to 60 minutes.
- EDGE3. Suppression is evaluated at watch-send time so both foreground and background receivers use the current intervention latch state.
- CONC1. A token gate rejects duplicate mobile handoff submissions and ignores stale releases after reset.
- CONC2. Registry submit/shutdown/status transitions are protected by an event-loop lock and terminal state cannot be overwritten by a late task result.

## 11. Dependencies and Configuration

- DEP1. Reuse FastAPI, asyncio, Android `HttpURLConnection`, `SharedPreferences`, StateFlow, and coroutines already present.
- DEP2. No new dependency or schema migration is required.
- DEP3. Mobile timeout range is 1–1,440 minutes with a 60-minute default; backend job runtime/TTL defaults to one hour.

## 12. Migration, Rollout, and Rollback

- MIG1. No database migration is required; async reports use the existing table.
- ROLL1. Rebuild/restart backend, build the mobile APK, then install on the phone when ADB is connected.
- BACK1. Roll back backend/mobile source changes together; the synchronous endpoint permits older mobile clients to continue working.

## 13. Implementation Boundaries

### 13.1 Expected Change Areas

- `apps/backend/app/main.py`
- `apps/backend/tests/test_intervention_routes.py`
- `apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringState.kt`
- `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt`
- `apps/mobile/app/src/main/java/com/example/healthsensor/ServerUploader.kt`
- `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt`
- `apps/mobile/app/src/main/java/com/example/healthsensor/PhonePredictionSender.kt`
- `apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringService.kt`
- Focused Android unit tests and synchronized documentation.

### 13.2 Forbidden Changes

- Do not change prediction classification, backend alert cadence, watch UI/source, model/provider selection, prompts, DB schema, credentials, or sensor transport.
- No unrelated refactors, formatting churn, dependency upgrades, broad rewrites, or generated-file churn.

### 13.3 Minimal-Change Guidance

- Reuse existing StateFlow/coroutine/HTTP/FastAPI patterns.
- Keep all existing public endpoints backward-compatible.
- Use focused state helpers and tests instead of broad UI or backend rewrites.

## 14. Acceptance Criteria

- AC1. Given active chat, when multiple required predictions arrive, then chat remains visible and AUQ is not launched.
- AC2. Given chat is closed, when a new distinct required prediction arrives, then AUQ may launch again.
- AC3. Given suppression is active, prediction state and watch state delivery continue, but repeated backend REQUIRED events produce no phone or watch vibration/notification.
- AC4. Given a handoff request, the phone receives a job ID quickly, remains interactive, and later displays/persists the completed report.
- AC5. Failed, expired, timed-out, or shutdown jobs expose safe terminal behavior without duplicate generation.
- AC6. Chat timeout defaults to 60 minutes, persists, and accepts administrator values from 1 through 1,440 minutes.
- AC7. `상담 상태 초기화` clears intervention state but preserves sensor history and URL settings.
- AC8. Existing synchronous handoff behavior remains operational.
- AC9. Registry capacity recovers after timeout/shutdown and rejects submit after shutdown begins.

## 15. Validation Plan

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Backend tests | `.venv\\Scripts\\python.exe -m pytest -q` from `apps/backend` | 50 tests pass without external LLM calls |
| Backend compile | `.venv\\Scripts\\python.exe -m compileall -q app tests` | Exit 0 |
| Android tests/build | `.\\gradlew.bat testDebugUnitTest assembleDebug` from `apps/mobile` | Build succeeds with latch/timeout/gate tests |
| Android lint | `.\\gradlew.bat lintDebug` | Build succeeds |
| Runtime smoke | `/health`, OpenAPI, and unknown job GET | Health ok/model ready; POST declares 202; GET exists; unknown job is 404 |
| Device check | `adb devices -l` | Install/physical flow if a device is connected; otherwise record skip |

## 16. Risks and Open Notes

- RISK1. Process-local jobs do not survive backend restart; mobile stops on 404 and does not duplicate generation.
- RISK2. A one-hour mobile timeout can keep one chat request open for a long time; administrator mode can lower the value.
- RISK3. Physical verification found backend REQUIRED events recurring at the configured 30-second cooldown; payload-level suppression prevents these events from repeatedly alerting the watch during intervention without changing backend audit records.

## 17. Implementation Checklist / Progress Record

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Root cause and spec pair | Main | Completed | This spec pair | Repository inspection | User approved async save, one-hour chat timeout, and administrator reset/config. |
| P2 | Backend async handoff | Backend worker | Completed | `apps/backend/app/main.py`, `tests/test_intervention_routes.py` | 44 pytest pass; compile exit 0 | Includes reviewer-requested shutdown and hung-job recovery fixes. |
| P3 | Android flow/settings/UI | Mobile worker | Completed | Phone state, ViewModel, uploader, UI, focused tests | Unit tests/build/lint pass | Watch source unchanged. |
| P4 | Runtime and documentation | Main | Completed | Docker backend and active Markdown docs | Health/OpenAPI/404 smoke pass | Latest APK built; install skipped because ADB had no device. |
| P5 | Suppress watch presentation during active intervention and reinstall | Mobile worker / Main | Completed | `PhonePredictionSender.kt`, `PhoneMonitoringService.kt`, `SensorViewModel.kt`, `PhonePredictionSenderTest.kt`, active docs | 22 unit tests pass; Phone/Wear builds and lint pass; both APK installs succeed | Active payload overrides only `alertAction=none`; post-install physical chat observation remains for the user flow. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.0 | 2026-07-13 | Feature Planner | Initial approved and implemented feature definition. |
| 1.1 | 2026-07-13 | Feature Planner | Normalized the spec pair, added shutdown/runtime refinements, final validation, and documentation status. |
| 1.2 | 2026-07-13 | Feature Planner | Changed physical-device behavior so active intervention suppresses repeated watch vibration/notification while preserving watch state delivery. |
