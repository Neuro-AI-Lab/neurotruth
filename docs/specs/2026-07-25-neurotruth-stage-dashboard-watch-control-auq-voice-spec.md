# NeuroTruth Stage Dashboard, Watch Control, AUQ, and Voice UX — Living Implementation Specification

<!-- feature-planner-control
{
  "workflow": "feature-planner/v7",
  "state": "implementing",
  "source_spec": "docs/specs/2026-07-25-neurotruth-stage-dashboard-watch-control-auq-voice-spec.md",
  "korean_mirror": "docs/specs/2026-07-25-neurotruth-stage-dashboard-watch-control-auq-voice-spec.ko.md",
  "spec_revision": 9,
  "reviewed_revision": 9,
  "selected_strategy": "STRAT-1",
  "implementation_direction": "preserve",
  "direction_decision_id": null,
  "minimal_change_policy": "strict",
  "final_domain_gate": "confirmed_none",
  "open_question_ids": [],
  "active_slices": ["WS9"],
  "next_action": "implement"
}
-->

> The English file is authoritative. The Korean file is the synchronized review mirror. The user supplied and approved the complete plan after resolving the final-stage copy, alert streak, calendar scope, AUQ representation, Watch scope/control fallback, rPPG alert policy, and voice/TTS behavior.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `implementing`, revision 9, user-confirmed calendar boundary follow-up |
| Outcome | Preserve the validated runtime while enlarging the recent-hour wearable-stage chart, placing each AUQ response on its own row, replacing the ambiguous stored-measurement preview with live preprocessed PPG and EDA traces, and preventing day/week/month navigation beyond the current local period. |
| Recommended implementation | `STRAT-1` — narrowly extend the current dashboard, prediction persistence, Compose navigation/state, MonitoringService, and Wear Data Layer owners. |
| Planned production targets | `apps/backend/app/ml/craving/pipeline.py::AlertConfig`, `apps/backend/app/ml/craving/pipeline.py::AlertEvaluator`, `apps/backend/app/repositories/postgres.py::persist_sensor_prediction`, `apps/backend/app/repositories/postgres.py::calendar rows`, `apps/backend/app/services/sensor.py::_ingest_locked`, `apps/backend/app/services/rppg.py::RppgService._process`, `apps/backend/app/repositories/rppg.py::finish_success`, `apps/backend/app/services/dashboard.py::DashboardService.craving_calendar`, `apps/backend/app/api/v1/routes/dashboard.py::craving_calendar`, `apps/backend/app/schemas/dashboard.py::calendar query types`, `apps/backend/app/schemas/dashboard.py::DashboardCalendarView`, `apps/mobile/core/src/main/kotlin/com/neurotruth/mobile/core/CravingStage.kt::CravingStage display labels/messages`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/DashboardRepository.kt::calendar DTO/parser/client`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/DashboardRepository.kt::calendar client/constants`, `apps/mobile/core/src/main/kotlin/com/neurotruth/mobile/core/net/ApiEndpoints.kt::cravingCalendar`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt::recent-hour/calendar UI`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt::calendar controls/copy`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt::StageTimelineFrame`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt::calendar selection/state`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt::calendar selection/navigation`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/AuqScreen.kt::copy/layout/result UI`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/AuqViewModel.kt::result state`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/NeuroTruthNavHost.kt::AUQ result navigation`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/chat/ChatViewModel.kt::STT dispatch/TTS correlation`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/chat/ChatScreen.kt::voice progress/notices`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/service/MonitoringService.kt::measurement command/ack and relay`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/service/PredictionPayloadParser.kt::Watch payload`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/home/HomeScreen.kt::Watch measurement control`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/home/HomeViewModel.kt::measurement intent/ack state`, `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/PredictionListenerService.kt::control/prediction/snapshot listener`, `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/SensorTrackingService.kt::start/stop status ack`, `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/MainActivity.kt::three summary pages/local stop`, `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/MainActivity.kt::TimelinePage`, `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/SensorState.kt::display-safe companion state`, `apps/mobile/wearos/src/main/AndroidManifest.xml::Wear listener filters/permissions`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt::StageTimelineFrame`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt::signal section`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/AuqScreen.kt::response layout`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/LivePpgSource.kt::live trace builder`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt::dashboard signal state`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt::calendar future-period guard`, and `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt::next-button/date-picker limits`. Combined symbols: `AlertConfig`, `AlertEvaluator`; `persist_sensor_prediction`, calendar rows; `CravingStage` display labels/messages; `StageTimelineFrame`, signal section; calendar future-period guard; next-button/date-picker limits. |
| Expected additions | New production files: 0; dependencies: None; shared abstractions: None; migrations: None. |
| Work plan | WS1, WS2, WS3, WS4, WS5, WS6, WS7, and WS8 are verified. WS9 implementation, correction review, unit tests, lint, and APK build are complete; installation and screenshot review remain pending because only the Watch is currently visible to ADB. |
| Open questions | None |
| Agent decisions to review | None |
| Last material change | Revision 9 — user requested that day/week/month controls disable forward navigation at the current local period and never enter a future date. |

## 2. Outcome and Scope

### Outcome

An authenticated patient starts Galaxy Watch sensing from the Phone, receives automatically uploaded 20-second windows every 10 seconds after the Watch acknowledges streaming, sees four Korean display stages and day/week/month history, receives one synchronized Phone/Watch alert after three consecutive Watch danger-stage results with a durable 15-minute cooldown, completes optional AUQ through a neutral result screen, and can speak a message that is sent immediately and receives one automatic TTS response.

### In Scope

- Change user-facing stage labels/copy while preserving wire and DB keys.
- Replace the recent-hour probability line with a four-lane categorical step timeline.
- Add a patient calendar aggregation endpoint and Phone day/week/month navigation.
- Rename AUQ UI copy, add an honest information dialog, fit one item per screen, and add a neutral result route.
- Move Watch sensing initiation to Phone with an acknowledged Data Layer command and a Watch confirmation-notification fallback.
- Add three compact Watch pages and synchronized alert identifiers.
- Replace existing rolling binary alert rules with three consecutive `p >= 0.75` Watch predictions and a 900-second patient cooldown.
- Auto-send successful STT transcripts and auto-read only their matching assistant replies.
- Update API, PRD/handoff, and device-test documentation to the implemented behavior.

### Out of Scope / Non-Goals

- No database schema or Alembic revision.
- No new chart, navigation, networking, persistence, or wearable dependency.
- No administrator web redesign, model retraining, Bedrock prompt change, STT server change, DGX/FactorizePhys change, or rPPG capture change.
- No official/validated Korean AUQ claim or unvalidated score bands.
- No alert generated from camera rPPG predictions.
- No Git push, production deployment, or device installation before local verification and user reconnection.

### Users and Primary Flow

1. A signed-in patient with biosignal consent and a connected Watch presses `측정 시작` on Phone.
2. Phone sends a request ID; Watch starts sensing or asks for one Watch-side confirmation when Wear OS blocks a background health FGS.
3. Watch acknowledges `started`; Phone starts MonitoringService, receives samples, and uploads the current 20-second window every 10 seconds.
4. Phone and Watch display `안정`, `관찰`, `주의`, or `위험`. Three consecutive Watch `위험` results create one server alert displayed on both devices.
5. A new chat session offers AUQ or skip. Completed AUQ shows a neutral score result before Chat.
6. Voice recording is transcribed and sent immediately; the matching assistant response is read once.

### Current Assumptions and Constraints

- The current `feat/stage-watch-auq-voice-ux` branch contains intentional uncommitted work inherited from `Master`; it must be preserved.
- Backend and Phone/Wear builds deploy together because Data Layer and calendar contracts change together.
- Target SDK 35 Wear health FGS behavior may require `BODY_SENSORS_BACKGROUND`; background-start rejection must fall back to a user-visible Watch confirmation rather than silently failing.
- Existing internal stage keys remain `low`, `observe`, `caution`, and `high`; only display labels become `안정`, `관찰`, `주의`, and `위험`.

## 3. Repository Pattern Baseline

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Dashboard | `DashboardService` validates time/range input and delegates SQL aggregation to `V25Repository`; Phone parses JSON into local DTOs and draws Canvas charts. | `apps/backend/app/services/dashboard.py::DashboardService`; `apps/mobile/app/.../data/DashboardRepository.kt`; `ui/dashboard/DashboardScreen.kt` | Extend the existing service/repository/parser/Canvas path; do not add a chart library or parallel dashboard service. |
| Prediction persistence | `SensorService` predicts, repository transaction inserts prediction and optional alert, then the service publishes the persisted response to SSE. | `apps/backend/app/services/sensor.py::SensorService._ingest_locked`; `repositories/postgres.py::persist_sensor_prediction` | Keep DB transaction ownership in the repository and SSE ownership in the service. |
| rPPG | `RppgService` stores camera predictions through `RppgRepository.finish_success`. | `apps/backend/app/services/rppg.py`; `repositories/rppg.py` | Preserve prediction/storage flow but force camera alert metadata to none. |
| AUQ | `Auq` owns 8 items, seven labels, and 0–48 scoring; `AuqViewModel` posts; `NeuroTruthNavHost` owns routing. | `apps/mobile/core/.../Auq.kt`; Phone `ui/auq`; `NeuroTruthNavHost.kt` | Reuse scoring and existing session assessment endpoint; add only result state/route and display copy. |
| Voice | `ChatViewModel` records, calls `TranscriptionRepository`, dispatches messages with idempotent client IDs, and owns local TTS. | `apps/mobile/app/.../ui/chat/ChatViewModel.kt` | Reuse dispatch/retry/TTS; change only transcript handoff and reply playback selection. |
| Monitoring | Phone `MonitoringService` owns Watch MessageClient samples, 20-second scheduler, SSE, notifications, and Watch prediction relay. | `apps/mobile/app/.../service/MonitoringService.kt` | Start only after Watch ack; do not move backend authentication to Watch. |
| Wear | Wear service owns Samsung sensor collection; Wear listener consumes display-safe Phone messages; Watch has no Internet permission. | `apps/mobile/wearos/.../SensorTrackingService.kt`; `PredictionListenerService.kt`; Wear manifest | Keep Data Layer-only communication and no credentials/probabilities on Watch. |
| Tests | Backend uses focused pytest repository fakes; Android uses JVM tests, lint, and debug builds. | `apps/backend/tests`; `apps/mobile/app/src/test`; `apps/mobile/core/src/test` | Add focused cases beside existing owners and reuse fakes. |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | Four-stage threshold owner | `apps/mobile/core/.../CravingStage.kt` | Keep thresholds and wire keys; replace display labels/copy. |
| R-002 | One-hour sparse probability series | `DashboardService.craving_probability_series`; `CravingSeries.segments` | Map points to stages and preserve measurement gaps. |
| R-003 | Existing stage-count SQL | `SqlAlchemyV25Repository.craving_dashboard_rows` | Parameterize local start/end and bucket unit for day/week/month calendar data. |
| R-004 | Existing AUQ result builder | `Auq.buildResult` | Reuse total and response labels for the result screen. |
| R-005 | Existing chat idempotency and retry | `ChatViewModel.dispatch`; `ChatRetryPolicy` | Send transcript with one new voice client ID and retain one-shot retry. |
| R-006 | Existing Phone prediction/alert ledger | `PredictionLedger`; `CravingAlertNotifier` | Deduplicate Phone presentation by persisted alert ID. |
| R-007 | Existing Wear sensor FGS | `SensorTrackingService.start/stop` | Invoke through acknowledged Phone commands and retain local stop. |
| R-008 | Existing Watch display-safe relay | `PredictionPayloadParser.toWatchPayload`; `PredictionListenerService` | Add stage code/copy and alert ID without probability. |
| R-009 | Existing live sensor buffer and fixed-grid interpolation | `SensorSampleRepository`; `FixedGridResampler`; backend `CravingModel.preprocess` | Reuse the current 20-second Watch buffer, fixed-grid interpolation, and per-channel MinMax convention for display-only PPG/EDA traces. |

## 4. Decisions and Questions

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | Stage copy | Use `안정`, `관찰`, `주의`, `위험`; danger copy is `갈망이 높게 감지됐어요. 챗봇과 대화를 시작할까요?`. | user | Explicit selection after reviewing current `심각` copy. | Changes patient and Watch display only. | confirmed | resolved |
| D-002 | Alert | Trigger after three consecutive `p >= 0.75` Watch predictions, no more than 20 seconds apart, then suppress all patient Watch alerts for 15 minutes. | user | Explicit selection of three consecutive danger stages and 15-minute cooldown. | Replaces 6/10, binary streak, downtrend, and 30-second level cooldown. | confirmed | resolved |
| D-003 | Alert source | Camera rPPG does not create alerts; Watch alerts appear on both Phone and Watch. | user | Explicitly selected `Watch 신호만 양쪽`. | Preserves rPPG prediction/history but removes camera alert creation. | confirmed | resolved |
| D-004 | Calendar | Apply selected day/month to stage, event, and AUQ charts together. | user | Explicitly selected full-chart calendar linkage. | Adds one additive patient calendar endpoint and selector. | confirmed | resolved |
| D-005 | AUQ identity | Describe the current questions as an AUQ-informed research Korean self-survey, not an official validated Korean version. | user | Explicitly selected transparent source/adaptation wording. | Adds information dialog and neutral result copy. | confirmed | resolved |
| D-006 | AUQ layout | Keep one question per screen and fit seven choices without normal scrolling. | user | Explicit selection. | Adjusts layout only; scoring/API unchanged. | confirmed | resolved |
| D-007 | Watch scope | Provide three compact Watch pages: measurement/current stage, recent-hour timeline, and today event/AUQ summary. | user | Explicit selection. | Adds only display-safe dashboard snapshot relay. | confirmed | resolved |
| D-008 | Watch start | Phone initiates sensing; if background start is rejected, Watch shows a one-tap confirmation; stopping is allowed on both. | user | Explicit selection informed by Android FGS restrictions. | Adds request/status messages and fallback notification. | confirmed | resolved |
| D-009 | Voice | Auto-send a successful transcript; auto-read only the assistant reply to that voice message. | user | Explicit selection. | Typed replies continue to honor the existing auto-read toggle. | confirmed | resolved |
| D-010 | Compatibility | Preserve existing wire/DB stage keys and current APIs; add the calendar API. | user | Approved plan explicitly requires compatibility. | No DB migration and no legacy endpoint removal. | confirmed | resolved |
| D-011 | Test contract | Add the new calendar route to the existing public-operation inventory test. | agent | Full Docker pytest found the approved additive route missing from `EXPECTED_PUBLIC_OPERATIONS`. | Test-only synchronization; no product behavior change. | confirmed | resolved |
| D-012 | Patch accounting | Raise WS2 production added-line cap from 850 to 1000. | agent | The final branch diff is +974 production lines because the user-approved pre-existing dirty Chat/NavHost voice/session work must be preserved and ships with this branch. | Accounting-only adjustment; files, behavior, dependencies, and architecture are unchanged. | confirmed | resolved |
| D-013 | Patch accounting | Raise WS3 production added-line cap from 900 to 930. | agent | Final review required an already-running Watch service to acknowledge a new Phone request and restored the baseline first-launch sensor/notification permission setup needed by the approved confirmation fallback. | Accounting-only adjustment; mapped files, dependencies, architecture, and approved behavior are unchanged. | confirmed | resolved |
| D-014 | Recent-hour visual design | Restyle the Phone and Watch charts like wearable sleep-stage timelines: four named lanes, thick rounded colored runs, narrow vertical transition connectors, subdued lane guides, and explicit gaps. | user | The user referenced the previously supplied Galaxy Watch and Apple Watch sleep-stage examples. | Visual-only change to the two existing Canvas owners; timestamps, stage thresholds, gap logic, API, and stored data remain unchanged. | confirmed | resolved |
| D-015 | Weekly calendar | Add `week` between `day` and `month`; the selected anchor belongs to a Monday-through-Sunday local week and all stage/event/AUQ charts use the same seven daily buckets. | user | The user explicitly requested a weekly dashboard between daily and monthly. Monday-first is the smallest calendar-conventional extension of the current local-time API. | Extends D-004 without changing stored data or adding a migration. | confirmed | resolved |
| D-016 | DGX cooldown rollout | Keep the 900-second source/default contract and document the DGX `.env` update and backend recreation; the prior repeated notifications are expected until that deployment occurs. | user | The user clarified the current DGX server has not received the cooldown deployment. | No further alert algorithm change; deployment evidence remains pending. | confirmed | resolved |
| D-017 | Dashboard and AUQ follow-up | Increase the Phone recent-hour chart height and render isolated buckets as short rounded runs; show all seven AUQ responses as one full-width button per row; remove the selected/stored PPG preview from the patient dashboard and show live PPG plus EDA instead. PPG/EDA display traces reuse the existing 20-second fixed-grid interpolation and channel MinMax convention without changing uploaded or stored samples. | user | Direct follow-up after live device review; the preprocessing detail uses the nearest existing model/upload preprocessing path. | Phone UI and display-only signal shaping change; backend, DB, APIs, Watch UI, model input, and persistence remain unchanged. | confirmed | resolved |
| D-018 | Calendar future boundary | Disable `Next` when the selected day is today, selected week is the current Monday-through-Sunday week, or selected month is the current month. Reject future anchors and disable future dates in the picker. | user | Direct follow-up requesting that the day/week/month dashboard cannot move past the current date and shows a disabled button. | Phone calendar controls/state only; API and stored data remain unchanged. | confirmed | resolved |
| D-019 | Stage aggregate scale | Render calendar stage bars from raw counts instead of 100%-normalized proportions. Use 360 measurements/hour and 8,640 measurements/day as fixed 10-second-cadence maxima; leave no-data buckets blank. | user | The user explicitly requested count-scaled stacked bars and identified 360 as the hourly maximum. | Phone Canvas/detail copy only; API stage counts and stored data remain unchanged. | confirmed | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | Stage | Final label/copy | Affects all clients. | `위험` and “높게 감지됐어요”. | D-001 | answered | User selected recommendation. |
| Q-002 | Alert | Meaning of three results | Changes sensitivity and persistence. | Three consecutive danger-stage Watch results. | D-002 | answered | User selected recommendation. |
| Q-003 | Calendar | Which charts follow day/month | Changes API/UI scope. | All patient charts. | D-004 | answered | User selected recommendation. |
| Q-004 | AUQ | Official claim and result | Prevents misleading scale claims. | AUQ-informed research adaptation and neutral total. | D-005 | answered | User selected recommendation. |
| Q-005 | Wear | Dashboard scope and remote-start fallback | Changes Wear UI and permissions. | Three summaries plus confirmation fallback. | D-007 | answered | User selected both D-007 and D-008 recommendations. |
| Q-006 | Voice/rPPG | TTS scope and rPPG alert relay | Changes notification and playback behavior. | One matching TTS reply; Watch-only alert source. | D-009 | answered | User selected both D-003 and D-009 recommendations. |

## 5. Requirements and Acceptance Criteria

### Functional Requirements

- **FR-001:** Display thresholds remain `<.25`, `<.50`, `<.75`, and `>=.75`, with exact D-001 Korean labels/copy on Phone and Watch.
- **FR-002:** Recent-hour Phone and Watch charts render wearable sleep-stage-style categorical runs over real 10-second timestamps: thick rounded stage-colored horizontal segments, narrow vertical transition connectors, four labeled lanes, and disconnected missing buckets.
- **FR-003:** The calendar API returns complete local-hour buckets for a day, seven Monday-through-Sunday local-date buckets for a week, or local-date buckets for a month with stage counts, event presence/count, and AUQ 0–48 average/count.
- **FR-004:** Phone day/week/month selection moves all stage/event/AUQ charts together and distinguishes no prediction from a valid zero alert count.
- **FR-014:** The DGX deployment contract sets `ALERT_DANGER_THRESHOLD=0.75`, `ALERT_DANGER_STREAK=3`, `ALERT_MAX_GAP_SECONDS=20`, and `ALERT_COOLDOWN_SECONDS=900`, removes deprecated alert variables, and recreates the backend container before device retest.
- **FR-015:** The Phone recent-hour chart uses a dedicated larger height and renders every real 10-second bucket as a visible rounded horizontal stage run, including an isolated bucket; it retains four named lanes, vertical stage transitions, real gaps, and tap detail.
- **FR-016:** AUQ presents the seven sentence choices as seven full-width rows while retaining one item per screen, 48dp minimum targets, normal-font no-scroll intent, and large-font scroll fallback.
- **FR-017:** The dashboard signal section contains only live Watch PPG and EDA. It removes the stored-measurement selection/preview UI. Each fresh 20-second trace is interpolated on the existing upload grid and channel-wise MinMax normalized for display; absence/staleness remains an empty state rather than a zero trace.
- **FR-018:** Phone calendar navigation never enters a future local period. `Next` is disabled for today, the current Monday-through-Sunday week, and the current month; the date picker disables dates after today and state handlers ignore future anchors.
- **FR-019:** Calendar stage charts stack raw stage counts without percentage normalization. Day-view hourly bars use a fixed 0–360 axis; week/month daily bars use a fixed 0–8,640 axis. No-data buckets remain blank, and selection detail reports total and per-stage counts.
- **FR-005:** AUQ title/copy uses `자기설문`, shows the D-005 information dialog, and fits one question plus seven choices on normal supported Phone viewports.
- **FR-006:** Successful AUQ submission displays `총점 X/48` and neutral interpretation before explicit Chat continuation; skip routes directly to Chat.
- **FR-007:** Phone is the primary start/stop controller; Watch status ack gates Phone MonitoringService startup; Watch retains stop and one-tap start confirmation only.
- **FR-008:** Once Watch reports started and samples arrive, Phone automatically forms 20-second windows and uploads every 10 seconds using the existing authenticated endpoint.
- **FR-009:** Watch displays the three D-007 pages using only stage/summary data relayed by Phone and never holds backend credentials, probabilities, or raw history.
- **FR-010:** Only Watch predictions participate in the D-002 alert rule; one persisted alert ID is relayed to and deduplicated by both clients.
- **FR-011:** Successful STT creates and immediately dispatches one voice message without changing an existing typed draft.
- **FR-012:** The matching assistant reply to a voice message auto-plays once; text replies keep the existing auto-read setting.
- **FR-013:** rPPG prediction, persistence, AUQ/chat handoff, and latest-state display remain intact but its alert action and alert ID are always absent.

### Non-Functional Requirements

- **NFR-001:** Preserve existing architecture, encryption, authentication, Data Layer-only Wear boundary, and unrelated working-tree changes.
- **NFR-002:** Alert streak/cooldown must survive backend restart and be safe against duplicate/concurrent Watch window persistence using the existing PostgreSQL transaction and patient advisory lock.
- **NFR-003:** The calendar API must validate IANA timezone, view, and anchor and handle DST/calendar month length using timezone-aware boundaries.
- **NFR-004:** No new production dependency, generic chart framework, database migration, or shared abstraction.
- **NFR-005:** Wear background-start rejection and missing background sensor permission must become an explicit status/confirmation path, never a silent measuring state.

### Acceptance Criteria

- **AC-001:** Focused backend tests prove the 0.75 boundary, consecutive/gap reset, 900-second durable cooldown, duplicate/concurrent single alert, and rPPG alert exclusion.
- **AC-002:** Calendar tests prove day/week/month boundaries, Monday/Sunday rollover, timezone/DST, empty versus valid zero, and AUQ/event/stage bucket values.
- **AC-007:** API/deployment documentation gives a secret-safe `.env` patch and backend recreation/verification commands for the 15-minute DGX cooldown.
- **AC-008:** On a Phone-sized render, the recent-hour chart is materially taller than aggregate charts, isolated samples appear as bars rather than dots, AUQ has exactly one response button per row, and the signal card shows separate live PPG and EDA plots with no stored-record selection copy.
- **AC-009:** JVM tests prove PPG and EDA channel isolation, fixed-grid output sizes, per-channel MinMax range/constant handling, freshness, and no fabricated trace for a missing channel; App unit/lint/debug build passes.
- **AC-010:** JVM tests prove day, week, and month next-navigation boundaries and future-anchor rejection; the disabled `Next` control and date-picker maximum compile and the App unit/lint/debug build passes.
- **AC-011:** App compilation and unit tests pass with no percentage-based stage rendering path; chart copy exposes count axes and selected buckets expose raw total/per-stage counts.
- **AC-003:** Phone JVM/UI state tests prove exact labels, categorical gaps, calendar navigation, AUQ result/skip, STT immediate dispatch, TTS correlation, and retry idempotency; Phone/Wear builds and visual review confirm the sleep-stage-style recent-hour charts.
- **AC-004:** Wear JVM/build tests prove request/status control, confirmation fallback state, three summary pages, stage-only snapshot parsing, and alert-ID deduplication.
- **AC-005:** Backend full pytest, Phone/Core/Wear unit tests, lint, and both debug APK builds pass.
- **AC-006:** After the user reconnects devices, Phone start → Watch sensors → Phone upload → backend prediction → Phone/Watch stage and alert is observed without Watch-side direct backend access.

### Edge and Failure Cases

- Stage result below 0.75 or a prediction gap over 20 seconds → reset danger streak.
- Three danger predictions during cooldown → persist prediction but no alert/event ID.
- Duplicate sensor window → return its existing prediction/alert; do not advance streak or notify twice.
- Notification consent off → persist prediction without alert creation, preserving existing consent semantics.
- Watch start rejected or permission unavailable → status `confirmation_required` or `error`; Phone monitoring stays stopped.
- Watch disconnect during measurement → stop both sides and show the existing blocker.
- Empty/failed STT → do not send; keep typed draft and offer recording/text retry.
- Voice message 502 → keep one user bubble and same client ID for the existing one retry.
- AUQ save ambiguous/fails → do not show result; retain explicit retry or skip.

## 6. Implementation Strategy and Direction

### STRAT-1 — Extend Existing Owners

- **Direction:** `preserve`
- **Current approach:** Add narrow parameters/branches to the current repository/service/Compose/Data Layer owners and focused tests beside them.
- **Existing flow to reuse:** R-001 through R-008.
- **Why this is minimal:** Every requested behavior already has a direct owner; only the new calendar route and bounded Wear control/snapshot payloads add public interfaces.
- **Behavior-preserving limitations:** Internal `low|observe|caution|high` keys and existing APIs remain; Watch receives stage labels but no exact probability; AUQ cutoff categories remain absent.
- **Explicit exclusions:** No refactor of sensor scheduling, no new persistence table, no official AUQ wording replacement, no generic cross-device framework, and no unrelated UI redesign.
- **Compatibility and migration posture:** No schema/data migration. Backend, Phone, and Wear release together. Rollback uses the previous image/APKs; new alert rows remain valid historical records.
- **Direction approval:** None required.
- **Open-question sensitivity:** None.

### Material Alternatives Considered

| Strategy | Direction | Benefit | Additional code or risk | Decision |
| --- | --- | --- | --- | --- |
| New alert-state table | user-approved-divergence | Simple state lookup | Requires an unnecessary migration and a new persistence owner. | rejected; use prior predictions/alerts under existing transaction |
| Watch direct backend access | user-approved-divergence | Independent Watch dashboard | Duplicates auth/network ownership and exposes credentials. | rejected |
| Official Korean AUQ claim | user-approved-divergence | Stronger marketing language | Unsupported by current wording/source and potentially misleading. | rejected by D-005 |
| New chart dependency | user-approved-divergence | Faster drawing primitives | Existing Canvas charts already own rendering and gap semantics. | rejected |

## 7. Modification Map and Change Budget

### Modification Map

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | production | `apps/backend/app/ml/craving/pipeline.py` | `AlertConfig`, `AlertEvaluator` | edit | current rolling evaluator | Evaluate danger probability/gap/cooldown context without legacy 6/10 rules. | FR-010, NFR-002 | WS1 | preserve |
| CH-002 | production | `apps/backend/app/repositories/postgres.py` | `persist_sensor_prediction`, calendar rows | extend | existing prediction transaction/dashboard SQL | Lock patient, derive prior Watch context/latest alert, atomically persist one alert; add calendar aggregation. | FR-003, FR-010 | WS1 | preserve |
| CH-003 | production | `apps/backend/app/services/sensor.py` | `_ingest_locked` | extend | existing repository persistence/SSE | Pass notification policy and persist repository-enriched alert response. | FR-010 | WS1 | preserve |
| CH-004 | production | `apps/backend/app/services/rppg.py` | `RppgService._process` | edit | current alert_decider branch | Store camera prediction with `alertAction=none` and no alert row. | FR-013 | WS1 | preserve |
| CH-005 | production | `apps/backend/app/repositories/rppg.py` | `finish_success` | edit | current optional alert insert | Persist camera prediction without an alert row. | FR-013 | WS1 | preserve |
| CH-006 | production | `apps/backend/app/services/dashboard.py` | `DashboardService.craving_calendar` | extend | current craving dashboard service | Validate and return day/month stage/event/AUQ buckets. | FR-003 | WS1 | preserve |
| CH-017 | production | `apps/backend/app/api/v1/routes/dashboard.py` | `craving_calendar` | extend | current patient dashboard routes | Expose the additive authenticated calendar route. | FR-003 | WS1 | preserve |
| CH-018 | production | `apps/backend/app/schemas/dashboard.py` | calendar query types | extend | current range literal types | Type the `day` or `month` query contract. | FR-003 | WS1 | preserve |
| CH-041 | test | `apps/backend/tests/test_sse_payload.py` | `EXPECTED_PUBLIC_OPERATIONS` | extend | current exact endpoint inventory | Add the approved `/api/me/craving-calendar` operation. | AC-005 | WS1 | preserve |
| CH-019 | test | `apps/backend/tests/test_alerts.py` | danger streak/cooldown tests | extend | current alert evaluator cases | Prove alert boundary, gaps, and cooldown. | AC-001 | WS1 | preserve |
| CH-020 | test | `apps/backend/tests/test_sensor_routes_v25.py` | persistence alert cases | extend | current sensor repository fake | Prove duplicate and Watch alert persistence behavior. | AC-001 | WS1 | preserve |
| CH-021 | test | `apps/backend/tests/test_craving_bar_dashboard_v25.py` | calendar aggregation cases | extend | current dashboard fakes | Prove day/month/timezone/empty buckets. | AC-002 | WS1 | preserve |
| CH-022 | test | `apps/backend/tests/test_rppg_v25.py` | camera alert exclusion | extend | current rPPG service tests | Prove rPPG never creates an alert. | AC-001 | WS1 | preserve |
| CH-007 | production | `apps/mobile/core/src/main/kotlin/com/neurotruth/mobile/core/CravingStage.kt` | `CravingStage` display labels/messages | edit | R-001 | Apply D-001 exact display copy. | FR-001 | WS2 | preserve |
| CH-008 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/DashboardRepository.kt` | calendar DTO/parser/client | extend | current dashboard parser | Consume additive calendar API and keep recent hour series. | FR-002, FR-003, FR-004 | WS2 | preserve |
| CH-029 | production | `apps/mobile/core/src/main/kotlin/com/neurotruth/mobile/core/net/ApiEndpoints.kt` | `cravingCalendar` | extend | current dashboard endpoints | Build the encoded calendar query. | FR-003 | WS2 | preserve |
| CH-009 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt` | recent-hour/calendar UI | extend | existing Canvas sections | Draw stage step timeline and synchronized day/month navigation. | FR-002, FR-004 | WS2 | preserve |
| CH-030 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt` | calendar selection/state | extend | current dashboard state | Load selected day/month and keep all charts synchronized. | FR-004 | WS2 | preserve |
| CH-010 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/AuqScreen.kt` | copy/layout/result UI | extend | current one-item screen | Add info dialog, compact layout, and neutral result UI. | FR-005, FR-006 | WS2 | preserve |
| CH-031 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/AuqViewModel.kt` | result state | extend | current submission state | Expose a result only after confirmed persistence. | FR-006 | WS2 | preserve |
| CH-032 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/NeuroTruthNavHost.kt` | AUQ result navigation | extend | current AUQ route | Continue submitted result to Chat and skip directly. | FR-006 | WS2 | preserve |
| CH-011 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/chat/ChatViewModel.kt` | STT dispatch/TTS correlation | extend | R-005 | Immediate voice dispatch, preserve typed draft, and read matching reply once. | FR-011, FR-012 | WS2 | preserve |
| CH-033 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/chat/ChatScreen.kt` | voice progress/notices | edit | current microphone UI | Reflect immediate-send state without transcript-ready draft copy. | FR-011 | WS2 | preserve |
| CH-012 | test | `apps/mobile/core/src/test/kotlin/com/neurotruth/mobile/core/CravingStageTest.kt` | display boundaries/copy | extend | current stage tests | Prove exact four-stage copy. | AC-003 | WS2 | preserve |
| CH-023 | test | `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/data/DashboardParserTest.kt` | calendar/timeline parser cases | extend | current dashboard tests | Prove calendar parsing and sparse stage mapping. | AC-003 | WS2 | preserve |
| CH-024 | test | `apps/mobile/core/src/test/kotlin/com/neurotruth/mobile/core/AuqTest.kt` | result score cases | extend | current AUQ tests | Prove result total/labels remain 0–48. | AC-003 | WS2 | preserve |
| CH-025 | test | `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/ui/chat/ChatResponseParserTest.kt` | voice reply correlation cases | extend | current chat tests | Prove one voice dispatch/reply playback decision. | AC-003 | WS2 | preserve |
| CH-013 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/service/MonitoringService.kt` | measurement command/ack and relay | extend | existing MessageClient | Add explicit start/stop state, wait for Watch ack, and relay stage/alert ID/dashboard snapshot. | FR-007, FR-008, FR-009, FR-010 | WS3 | preserve |
| CH-034 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/service/PredictionPayloadParser.kt` | Watch payload | extend | R-008 | Relay stage code/copy and alert ID without probability. | FR-009, FR-010 | WS3 | preserve |
| CH-035 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/home/HomeScreen.kt` | Watch measurement control | extend | current Watch card | Add patient-visible start/stop/confirmation state. | FR-007 | WS3 | preserve |
| CH-036 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/home/HomeViewModel.kt` | measurement intent/ack state | extend | current connection reconciliation | Stop auto-start-on-connect and gate Phone monitoring on Watch ack. | FR-007, FR-008 | WS3 | preserve |
| CH-014 | production | `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/PredictionListenerService.kt` | control/prediction/snapshot listener | extend | R-008 | Handle request, status, stage-only snapshot, alert ID, and confirmation notification. | FR-007, FR-009, FR-010 | WS3 | preserve |
| CH-037 | production | `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/SensorTrackingService.kt` | start/stop status ack | extend | R-007 | Acknowledge actual service start/stop to Phone. | FR-007, FR-008 | WS3 | preserve |
| CH-038 | production | `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/MainActivity.kt` | three summary pages/local stop | extend | current Watch screen | Render the three pages and confirmation/local-stop actions. | FR-007, FR-009 | WS3 | preserve |
| CH-039 | production | `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/SensorState.kt` | display-safe companion state | extend | current display state | Hold stage timeline, today summaries, request, and alert-ID state. | FR-009, FR-010 | WS3 | preserve |
| CH-040 | production | `apps/mobile/wearos/src/main/AndroidManifest.xml` | Wear listener filters/permissions | edit | current Wear services | Receive control/dashboard paths and declare the existing background sensor prerequisite. | FR-007, NFR-005 | WS3 | preserve |
| CH-015 | test | `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/ui/home/MonitoringCommandTest.kt` | Phone control/ack cases | add | existing pure policy test style | Prove Phone state is gated by Watch ack. | AC-004 | WS3 | preserve |
| CH-042 | test | `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/service/SseFrameParserTest.kt` | Watch relay privacy contract | extend | current `toWatchPayload` test | Prove the Watch relay contains `stageCode` and alert metadata but no binary class, probability, or raw biosignal. | FR-009, AC-004 | WS3 | preserve |
| CH-026 | test | `apps/mobile/wearos/src/test/kotlin/com/neurotruth/mobile/wear/WearControlContractTest.kt` | control/snapshot/dedup cases | add | existing pure payload style | Prove Wear request/status and alert-ID decisions. | AC-004 | WS3 | preserve |
| CH-016 | docs | `apps/test_mobile_app/SERVER_API_SPEC.md` | calendar/alert/Data Layer/AUQ/voice contracts | edit | current server API spec | Document exact implemented public contracts. | FR-003, FR-007, FR-010, FR-011 | WS4 | preserve |
| CH-027 | docs | `docs/prd/PRD_neurotruth_mobile.ko.md` | patient and Watch flows | edit | current mobile PRD | Synchronize stages, AUQ, calendar, alert, and voice behavior. | FR-001, FR-005, FR-007, FR-010, FR-011, FR-013 | WS4 | preserve |
| CH-028 | docs | `docs/deployment/MOBILE_DEVICE_TEST_GUIDE.ko.md` | device acceptance flow | edit | current device guide | Add Phone-controlled Watch and dual-alert device checks. | AC-006 | WS4 | preserve |
| CH-043 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt` | `StageTimelineFrame` | edit | existing Canvas lane/gap/tap implementation | Replace thin lines with thick rounded stage runs and slim transition connectors while preserving tap selection and real gaps. | FR-002, AC-003 | WS5 | preserve |
| CH-044 | production | `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/MainActivity.kt` | `TimelinePage` | edit | existing fixed one-hour Canvas | Apply the same compact sleep-stage visual language and right-side lane labels without adding data or dependencies. | FR-002, AC-003 | WS5 | preserve |
| CH-045 | production | `apps/backend/app/schemas/dashboard.py` | `DashboardCalendarView` | edit | existing day-or-month literal | Add `week` to the authenticated query contract. | FR-003 | WS6 | preserve |
| CH-046 | production | `apps/backend/app/services/dashboard.py` | `DashboardService.craving_calendar` | edit | existing local-boundary and daily-bucket path | Normalize the anchor to local Monday, return seven day buckets, and retain timezone-aware UTC boundaries. | FR-003 | WS6 | preserve |
| CH-047 | test | `apps/backend/tests/test_craving_bar_dashboard_v25.py` | weekly calendar cases | extend | current day/month fake-repository tests | Prove Monday-to-Sunday, cross-month rollover, seven buckets, and invalid view behavior. | AC-002 | WS6 | preserve |
| CH-048 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/DashboardRepository.kt` | calendar client/constants | edit | existing day/month validation | Accept and request `week` without changing the response model. | FR-004 | WS7 | preserve |
| CH-049 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt` | calendar selection/navigation | edit | existing day/month anchor state | Navigate weekly anchors by seven days and load the same calendar endpoint. | FR-004 | WS7 | preserve |
| CH-050 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt` | calendar controls/copy | edit | existing range chips and calendar summary | Insert `주간` between `일간` and `월간` and show the selected week period. | FR-004 | WS7 | preserve |
| CH-051 | test | `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/data/DashboardParserTest.kt` | weekly client/parser cases | extend | existing day/month parser tests | Prove weekly request acceptance and seven daily bucket parsing. | AC-002 | WS7 | preserve |
| CH-052 | docs | `apps/test_mobile_app/SERVER_API_SPEC.md` | weekly API contract | edit | current calendar API section | Document day, week, and month requests and weekly bucket semantics. | FR-003, AC-002 | WS8 | preserve |
| CH-053 | docs | `docs/prd/PRD_neurotruth_mobile.ko.md` | weekly dashboard flow | edit | current day/month dashboard section | Add the weekly selector and synchronized chart behavior. | FR-004, AC-002 | WS8 | preserve |
| CH-054 | docs | `docs/deployment/DGX_RUNTIME_INVENTORY.ko.md` | DGX cooldown rollout | edit | current unresolved cooldown checklist | Add exact secret-safe environment verification, backend recreation, and device retest commands. | FR-014, AC-007 | WS8 | preserve |
| CH-055 | docs | `.env.example` | alert environment defaults | edit | current Watch-only alert block | Keep the 900-second cooldown contract and deprecated-variable removal visible. | FR-014, AC-007 | WS8 | preserve |
| CH-056 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt` | `StageTimelineFrame`, signal section | edit | existing Canvas chart and live signal card | Use a taller recent-hour Canvas, draw singleton buckets as short rounded runs, remove stored preview copy, and render separate live PPG/EDA plots. | FR-015, FR-017, AC-008 | WS9 | preserve |
| CH-057 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/AuqScreen.kt` | response layout | edit | current seven response buttons | Replace two-column chunking with one full-width response per row without changing scoring or navigation. | FR-016, AC-008 | WS9 | preserve |
| CH-058 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/LivePpgSource.kt` | live trace builder | edit | existing bounded 20-second Watch buffer | Add EDA and display-only fixed-grid/per-channel MinMax processing while preserving freshness and no-data semantics. | FR-017, AC-009 | WS9 | preserve |
| CH-059 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt` | dashboard signal state | edit | existing live polling | Remove selected-preview UI state/calls and expose only the live dual-channel trace. | FR-017, AC-008 | WS9 | preserve |
| CH-060 | test | `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/data/LivePpgWindowTest.kt` | live signal preprocessing tests | extend | existing pure JVM window tests | Cover PPG/EDA grids, MinMax, constant channels, missing channels, and freshness. | FR-017, AC-009 | WS9 | preserve |
| CH-061 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt` | calendar future-period guard | edit | existing day/week/month anchor navigation | Add deterministic local-period comparison, ignore future direct anchors, and prevent `onCalendarNext` from loading beyond today/current week/current month. | FR-018, AC-010 | WS9 | preserve |
| CH-062 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt` | next-button/date-picker limits | edit | existing `CalendarControls` | Disable `Next` at the current period and prevent the Material date picker from selecting dates after today. | FR-018, AC-010 | WS9 | preserve |
| CH-063 | test | `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardCalendarNavigationTest.kt` | calendar navigation boundary cases | add | existing pure JVM policy-test style | Prove past/current/future behavior for day, Monday-based week, month, and direct future anchors. | FR-018, AC-010 | WS9 | preserve |

### Change Budget

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 13 | 8 | 0 | 550 | None | None |
| WS2 | 16 | 11 | 2 | 1000 | None | None |
| WS3 | 16 | 10 | 1 | 930 | None | None |
| WS4 | 5 | 0 | 0 | 250 | None | None |
| WS5 | 2 | 2 | 0 | 140 | None | None |
| WS6 | 3 | 2 | 0 | 80 | None | None |
| WS7 | 4 | 3 | 0 | 100 | None | None |
| WS8 | 4 | 0 | 0 | 100 | None | None |
| WS9 | 6 | 4 | 0 | 250 | None | None |

## 8. Work Plan

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | Implement backend durable Watch-only alert policy and day/month calendar API. | None | P1 | CH-001, CH-002, CH-003, CH-004, CH-005, CH-006, CH-017, CH-018, CH-041, CH-019, CH-020, CH-021, CH-022 | `apps/backend/app/**`, `apps/backend/tests/**` | `apps/mobile/**`, `apps/backend/alembic/**`, existing unrelated changes outside mapped symbols | FR-003, FR-010, FR-013, NFR-001, NFR-002, NFR-003, NFR-004, AC-001, AC-002, AC-005 | focused pytest, then backend suite | verified |
| WS2 | Implement Phone stage/dashboard/AUQ/voice UX and tests. | None | P1 | CH-007, CH-008, CH-029, CH-009, CH-030, CH-010, CH-031, CH-032, CH-011, CH-033, CH-012, CH-023, CH-024, CH-025 | `apps/mobile/core/**`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/**`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/**`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/**`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/chat/**`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/NeuroTruthNavHost.kt`, `apps/mobile/app/src/test/**` | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/home/**`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/service/**`, `apps/mobile/wearos/**`, `apps/backend/**` | FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-011, FR-012, NFR-001, NFR-004, AC-003, AC-005 | Core/App unit tests and app lint/assemble | verified |
| WS3 | Implement Phone-controlled sensing and Watch companion/control/alert UI. | None | P1 | CH-013, CH-034, CH-035, CH-036, CH-014, CH-037, CH-038, CH-039, CH-040, CH-015, CH-042, CH-026 | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/service/**`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/home/**`, `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/ui/home/**`, `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/service/SseFrameParserTest.kt`, `apps/mobile/wearos/**` | `apps/backend/**`, Phone dashboard/AUQ/chat, Core stage owner | FR-007, FR-008, FR-009, FR-010, NFR-001, NFR-004, NFR-005, AC-004, AC-005, AC-006 | focused JVM tests, Wear/App unit/build | verified |
| WS4 | Synchronize developer/API/device-test Markdown with accepted implementation. | WS1, WS2, WS3 | Serial | CH-016, CH-027, CH-028 | `apps/test_mobile_app/SERVER_API_SPEC.md`, `docs/prd/PRD_neurotruth_mobile.ko.md`, `docs/deployment/MOBILE_DEVICE_TEST_GUIDE.ko.md` | PPTX, source code, unrelated docs | FR-001, FR-003, FR-005, FR-007, FR-010, FR-011, FR-013, AC-006 | API/term/path comparison | verified |
| WS5 | Restyle the Phone and Watch recent-hour charts to the approved wearable sleep-stage visual language. | WS2, WS3 | Serial | CH-043, CH-044 | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt`, `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/MainActivity.kt` | APIs, repositories, models, alerts, other screens, dependencies | FR-002, AC-003 | Phone/Wear unit tests, lint, debug builds, and rendered/device visual review when available | verified |
| WS6 | Extend the backend calendar contract with a Monday-through-Sunday weekly view. | None | P2 | CH-045, CH-046, CH-047 | `apps/backend/app/schemas/dashboard.py`, `apps/backend/app/services/dashboard.py`, `apps/backend/tests/test_craving_bar_dashboard_v25.py` | repositories, migrations, alert behavior, mobile | FR-003, AC-002 | focused backend pytest | verified |
| WS7 | Add the weekly selector and navigation to the Phone dashboard. | None | P2 | CH-048, CH-049, CH-050, CH-051 | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/DashboardRepository.kt`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt`, `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/data/DashboardParserTest.kt` | backend, Wear, Home, AUQ, Chat | FR-004, AC-002 | app unit tests, lint, debug build | verified |
| WS8 | Synchronize API/PRD/DGX environment instructions after runtime slices. | WS6, WS7 | Serial | CH-052, CH-053, CH-054, CH-055 | `apps/test_mobile_app/SERVER_API_SPEC.md`, `docs/prd/PRD_neurotruth_mobile.ko.md`, `docs/deployment/DGX_RUNTIME_INVENTORY.ko.md`, `.env.example` | source code, PPTX, secrets | FR-003, FR-004, FR-014, AC-002, AC-007 | term/path/secret scan | verified |
| WS9 | Apply the device-review dashboard, AUQ, live-signal, and future-calendar corrections without changing backend or persistence contracts. | WS5 | Serial | CH-056, CH-057, CH-058, CH-059, CH-060, CH-061, CH-062, CH-063 | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/AuqScreen.kt`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/LivePpgSource.kt`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt`, `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/data/LivePpgWindowTest.kt`, `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardCalendarNavigationTest.kt` | backend, DB, API contracts, model code, Watch UI, sensor upload/persistence, unrelated mobile screens | FR-015, FR-016, FR-017, FR-018, AC-008, AC-009, AC-010 | focused calendar/live-signal JVM tests, App unit test, lint, debug build, Phone screenshot review | in_progress |

### Parallelization Rationale

WS1 owns backend only. WS2 owns Phone/Core stage, dashboard, AUQ, and chat. WS3 owns Phone Home/service/parser and Wear. Their production write scopes are disjoint and the public contracts are fixed in this reviewed spec. WS4 runs after integration so it documents actual accepted behavior.

### Final Integration

Run focused tests after each slice; inspect complete diffs and budgets; run full backend pytest; run Core/App/Wear JVM tests, app/wear lint, and both debug builds; validate Compose/Docker health with fake external providers when available. Only then request Phone/Watch reconnection for installation and live pipeline validation.

## 9. Validation, Rollout, and Risk

### Validation Plan

- Backend: focused alert policy, persistence concurrency/duplicate, rPPG exclusion, and calendar timezone tests; full pytest.
- Phone: stage boundaries/copy, parser/state, Canvas gap mapping, AUQ result/skip, STT dispatch/TTS correlation, command state; app/core unit, lint, assemble.
- Wear: request/status parsing, fallback/dedup/state snapshot, three-page render state; Wear unit/lint/assemble.
- Docker: config/build and `/health`, `/ready`, OpenAPI; fake Bedrock/STT/DGX integration without real provider calls.
- Devices after reconnection: install both APKs, permissions/background sensor access, Phone start/stop, automatic upload, server prediction, stage snapshot, synchronized alert, AUQ result, STT/TTS, and disconnect recovery.

### Minimality and Style-Fidelity Review

- Every production path must map to CH-001–CH-016.
- Reject new dependencies, schema edits, generic cross-device/dashboard abstractions, broad refactors, or UI cleanup outside requested screens.
- Preserve existing user changes and compare each worker diff against its pre-slice path snapshot.
- Prefer parameterizing existing SQL, Canvas, navigation, scheduler, and MessageClient owners over adding files.

### Rollout and Rollback

1. Build and test backend/Phone/Wear together.
2. Back up DB before DGX deployment; no migration is run.
3. Deploy backend, Phone, and Wear as one contract set and verify readiness.
4. Roll back to prior image/APKs if needed. Existing new alert rows remain normal historical alerts; no data reversal is required.

### Risks and Mitigations

| Risk | Impact | Mitigation or Evidence |
| --- | --- | --- |
| Wear OS blocks remote health FGS | Phone appears to start but no samples arrive. | Ack-gated Phone state, explicit `confirmation_required`, Watch notification, and background sensor permission test. |
| Duplicate/late windows create alert storms | Excess events and notifications. | DB prior-history evaluation under patient advisory lock, 15-minute persisted cooldown, and alert-ID client dedup. |
| Calendar timezone/DST errors | Wrong day/month buckets. | Timezone-aware local boundaries and DST/month-length tests. |
| Compressed AUQ clips text | Inaccessible questionnaire. | Normal no-scroll adaptive layout and large-font scroll fallback. |
| Voice transcription is sent twice | Duplicate user/assistant messages. | One new client ID, existing idempotency/retry path, and correlated TTS test. |
| Existing dirty work is overwritten | Loss of user changes. | Branch preserves the current worktree; slices have disjoint scopes and main-agent diff review. |

## 10. Revision and Progress

### Design Revision History

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-25T00:00:00+09:00 | user-approved-plan-implementation | Created the complete reviewed implementation contract from the approved plan and six resolved decision groups. | D-001, D-002, D-003, D-004, D-005, D-006, D-007, D-008, D-009, D-010 | Q-001, Q-002, Q-003, Q-004, Q-005, Q-006 |
| 2 | 2026-07-25T02:15:00+09:00 | docker-full-pytest | Added the exact public-operation inventory test target required by the approved additive calendar endpoint. | D-011 | — |
| 3 | 2026-07-25T03:00:00+09:00 | ws2-final-diff-accounting | Counted the preserved pre-existing dirty Chat/NavHost work in the final WS2 branch patch budget. | D-012 | — |
| 4 | 2026-07-25T04:00:00+09:00 | ws3-stage-only-relay-verification | Added the exact existing payload test path required to verify the already approved stage-only Watch relay; production behavior and direction are unchanged. | D-010 | — |
| 5 | 2026-07-25T04:30:00+09:00 | ws3-final-recovery-accounting | Counted the minimal restart-ack and first-launch permission corrections required for the approved Phone-controlled Watch fallback. | D-013 | — |
| 6 | 2026-07-26T12:00:00+09:00 | user-sleep-stage-chart-reference | Reopened only the recent-hour chart visual domain and mapped a two-file Canvas restyle with no data/API changes. | D-014 | — |
| 7 | 2026-07-26T15:00:00+09:00 | user-weekly-dashboard-and-dgx-cooldown | Added the weekly calendar extension and documented that the 900-second cooldown still requires DGX `.env` rollout and device retest. | D-015, D-016 | — |
| 8 | 2026-07-26T16:00:00+09:00 | user-device-review-dashboard-auq-signals | Enlarged the recent-hour wearable chart contract, changed AUQ to one response per row, and replaced the stored-measurement preview with display-only preprocessed live PPG/EDA. The user's direct request confirms this narrow targeted revision. | D-017 | — |
| 9 | 2026-07-26T17:30:00+09:00 | user-calendar-future-boundary | Added a Phone-only current-period boundary for day/week/month navigation, disabled future picker dates, and mapped deterministic JVM coverage. The user's direct request confirms this narrow targeted revision. | D-018 | — |

### Implementation Progress Record

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-25T00:00:00+09:00 | 1 | — | ready | User supplied the complete plan and explicitly authorized implementation. Branch `feat/stage-watch-auq-voice-ux` created without discarding the existing dirty worktree. |
| 2026-07-25T01:00:00+09:00 | 1 | WS1 | implementing | Spec validation passed; backend slice started first because the validator found an Android test-scope overlap between WS2 and WS3. |
| 2026-07-25T02:00:00+09:00 | 1 | WS1 | completed | Initial scope/patch checks passed: 12 changed files, 8 production files, 410 production added lines, no new production files/dependencies/migrations; compileall and alert smoke passed. |
| 2026-07-25T02:01:00+09:00 | 1 | WS2 | implementing | Phone stage/dashboard/AUQ/voice slice started after WS1 diff review. |
| 2026-07-25T02:15:00+09:00 | 2 | WS1 | verification_followup | Docker focused pytest passed 43 tests. Full pytest exposed only the missing endpoint inventory entry plus two local schema bind-mount errors; CH-041 records the test-only follow-up. |
| 2026-07-25T03:00:00+09:00 | 3 | WS2 | patch_accounting | Final branch diff is 13 files, 10 production files, and +974 production lines including preserved pre-existing Chat/NavHost work; no new production files/dependencies/migrations. |
| 2026-07-25T03:01:00+09:00 | 3 | WS2 | verified | Scope/patch checks passed; Core/App tests, compile, lint, and debug APK build passed. |
| 2026-07-25T03:02:00+09:00 | 3 | WS3 | implementing | Phone Home/service and Wear control/companion slice started. |
| 2026-07-25T04:01:00+09:00 | 4 | WS3 | verification_followup | Main review removed the redundant legacy binary class from the Watch relay and added explicit stage-only/privacy assertions. Full Core/App/Wear tests, lint, and both debug APK builds passed. |
| 2026-07-25T04:10:00+09:00 | 4 | WS3 | verified | Scope and patch-budget checks passed: 12 changed files, 9 production files, 0 new production files, +888 production lines, no dependencies or shared abstractions. |
| 2026-07-25T04:11:00+09:00 | 4 | WS4 | implementing | Documentation synchronization started after all runtime slices were verified. |
| 2026-07-25T04:31:00+09:00 | 5 | WS3 | correction_verified | An already-running Watch now acknowledges a new Phone request without restarting trackers; first-launch sensor/background/notification permission setup was restored. Full Android tests, lint, and APK builds passed. |
| 2026-07-25T04:40:00+09:00 | 5 | WS4 | verified | Scope/patch checks passed for the three mapped documents; stale listener/start/STT/AUQ terms were removed and implemented routes/constants were cross-checked. |
| 2026-07-25T04:50:00+09:00 | 5 | — | complete | Backend Docker pytest passed 178 tests. Core/App/Wear tests, both lint tasks, and both APK builds passed. A disposable PostgreSQL smoke stack migrated to `20260717_0005`; `/health`, `/ready`, and OpenAPI calendar route checks passed and the temporary resources were removed. |
| 2026-07-26T11:00:00+09:00 | 5 | — | post_completion_audit | Final visual/runtime review corrected the zero-height Wear timeline, stage-aware Watch color, terminal control-ack race, and Phone chart-edge clipping. The repository-root schema authority files were restored without content changes after Docker exposed an accidental `docs/` relocation. Backend Docker pytest passed 178 tests; Core/App/Wear tests, both lint tasks, both APK builds, Compose config, and the schema bind mount passed. No ADB device was visible for a post-fix reinstall. |
| 2026-07-26T12:00:00+09:00 | 6 | WS5 | implementing | User approved a Galaxy/Apple sleep-stage-style visual restyle for the existing Phone and Watch recent-hour four-stage charts. |
| 2026-07-26T13:00:00+09:00 | 6 | WS5 | verified | Phone and Watch Canvas owners now use thick rounded stage runs, split-color transition connectors, stage-tinted guides, explicit gaps, and display-safe labels. Core/App/Wear tests, both lint tasks, and both debug APK builds passed. No ADB device was attached for an on-device screenshot review. |
| 2026-07-26T13:01:00+09:00 | 6 | — | complete | WS5 remained within the two-file production scope with no API, data, model, dependency, or migration change; the full Android validation completed successfully. |
| 2026-07-26T15:00:00+09:00 | 7 | — | ready | The user's explicit follow-up confirms the targeted weekly calendar and DGX cooldown rollout extension; WS6 and WS7 are dependency-disjoint and ready. |
| 2026-07-26T15:30:00+09:00 | 7 | WS6 | verified | Scope and patch checks passed at 3 files, 2 production files, +6 production lines, with no new dependency or abstraction. Python syntax compilation passed; focused pytest remains unavailable in the bundled host environment because project dependencies are absent. |
| 2026-07-26T15:31:00+09:00 | 7 | WS7 | verified | Scope and patch checks passed at 4 files, 3 production files, +35 production lines. App unit tests, lint, Kotlin compilation, and debug APK build passed. |
| 2026-07-26T15:45:00+09:00 | 7 | WS8 | verified | Scope and patch checks passed for the four mapped documentation/environment templates; weekly terms, DGX 900-second rollout commands, referenced paths, and example-only secret handling were verified. |
| 2026-07-26T15:46:00+09:00 | 7 | — | complete | Docker focused backend calendar tests passed 14 cases, Compose config passed, Android unit/lint/debug build had already passed, and all targeted slices were verified. DGX production rollout and the live 15-minute Phone/Watch observation remain operator acceptance steps rather than local implementation work. |
| 2026-07-26T16:00:00+09:00 | 8 | — | ready | Live device review identified three Phone-only presentation gaps. The user explicitly requested and authorized the narrow WS9 correction; no API, database, model, upload, persistence, Watch UI, dependency, or migration change is planned. |
| 2026-07-26T16:05:00+09:00 | 8 | WS9 | implementing | The validated targeted slice was delegated with a five-file write scope and no new production file, dependency, shared abstraction, backend, or persistence change. |
| 2026-07-26T17:10:00+09:00 | 8 | WS9 | correction_verified | Read-only review found two presentation risks: short AUQ viewports and singleton stage bars covering real gaps. The AUQ screen now scrolls only when its full-width seven-row content exceeds the viewport, and short stage runs preserve an explicit gap before adjacent measurements. |
| 2026-07-26T17:20:00+09:00 | 8 | WS9 | validation_pending | App unit tests, lint, debug APK build, diff check, and the five-file change budget passed. ADB currently exposes only the Watch, so Phone APK installation and screenshot review remain the sole acceptance step. |
| 2026-07-26T17:30:00+09:00 | 9 | WS9 | implementing | The user directly approved a narrow extension: disable future day/week/month navigation and picker dates. The same Phone-only slice resumes with six files, no new production file/dependency/abstraction, and no backend/API/DB/Watch change. |
| 2026-07-26T17:50:00+09:00 | 9 | WS9 | correction_verified | Read-only review found and the worker corrected current-week/current-month navigation that could retain a raw anchor after today. Returned anchors, direct anchor changes, DatePicker initial values, selectable dates, and confirmation now all enforce an anchor no later than local today; rollover coverage was added and the follow-up review found no remaining issue. |
| 2026-07-26T17:55:00+09:00 | 9 | WS9 | validation_pending | Spec/scope/patch checks passed at 6 files, 4 production files, 0 new production files, and 217/250 production additions. Full App unit tests, lint, debug APK build, and diff checks passed. ADB still exposes only the Watch, so Phone installation and screenshot acceptance remain pending. |
