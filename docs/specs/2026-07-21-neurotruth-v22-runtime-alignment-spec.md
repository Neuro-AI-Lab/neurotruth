# NeuroTruth V22 Runtime Alignment — Living Implementation Specification

<!-- feature-planner-control
{
  "workflow": "feature-planner/v7",
  "state": "complete",
  "source_spec": "docs/specs/2026-07-21-neurotruth-v22-runtime-alignment-spec.md",
  "korean_mirror": "docs/specs/2026-07-21-neurotruth-v22-runtime-alignment-spec.ko.md",
  "spec_revision": 2,
  "reviewed_revision": 2,
  "selected_strategy": "STRAT-1",
  "implementation_direction": "preserve",
  "direction_decision_id": null,
  "minimal_change_policy": "strict",
  "final_domain_gate": "confirmed_none",
  "open_question_ids": [],
  "active_slices": [],
  "next_action": "none"
}
-->

> The English file is authoritative. The Korean file is the synchronized review mirror. Revision 1 records the user-approved implementation plan and the follow-up choices confirmed in planning.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `complete`, revision 2, user-reviewed |
| Outcome | Align the existing backend, Bedrock dialogue layer, and Kotlin test app with the renamed V22 handoff. |
| Recommended implementation | `STRAT-1` — narrowly extend the current dashboard, session, encrypted repository, and Compose owners. |
| Planned production targets | `apps/backend/app/services/dashboard.py::PROBABILITY_RANGES, craving_dashboard`, `apps/backend/app/repositories/postgres.py::craving_dashboard_rows`, `apps/backend/app/services/sensor.py::prediction response`, `apps/backend/app/api/v1/routes/session.py::assessment`, `apps/backend/app/maintenance/migrate_auq_zero_based.py::new CLI`, `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt::Home/navigation/AUQ/rPPG routing`, `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt::AUQ scoring and entry methods`, `apps/mobile/app/src/main/java/com/example/healthsensor/AuthenticatedSessionApi.kt::postAssessment`, `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt::series/parser/charts/screen`, `apps/mobile/app/src/main/java/com/example/healthsensor/ServerUploader.kt::CravingPrediction, parser`, `apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringState.kt::publishPrediction`, `apps/mobile/app/src/main/java/com/example/healthsensor/RppgModels.kt::toPrediction`, `apps/mobile/app/src/main/java/com/example/healthsensor/WatchConnectionMonitor.kt::WatchRppgPresentationPolicy`; exact composite symbols: PROBABILITY_RANGES`, `craving_dashboard and `CravingPrediction`, parser |
| Expected additions | New production files: 1 (`apps/backend/app/maintenance/migrate_auq_zero_based.py`); dependencies: None; shared abstractions: None |
| Work plan | WS1 backend and WS2 Android may run in parallel against the fixed contract; WS3 synchronizes docs after both. |
| Open questions | None |
| Agent decisions to review | None |
| Last material change | Revision 2 — corrected the test-file Modification Map to match the accepted minimal patch. |

## 2. Outcome and Scope

### Outcome

The existing authenticated NeuroTruth runtime and Kotlin test app match the V22 visual handoff: four user-facing craving stages, a one-hour probability graph, zero-based AUQ, Watch-aware camera gating, and retry-safe AUQ-to-free-dialogue routing.

### In Scope

- Add the `1h` probability-series range and Watch prediction source metadata.
- Enforce new AUQ `0..6` item and `0..48` total contracts and convert encrypted legacy AUQ rows with a confirmed maintenance command.
- Preserve the existing Bedrock/STT/DGX architecture and free-dialogue prompt behavior.
- Align the Kotlin Phone home, dashboard, AUQ, rPPG completion, and latest-source behavior.
- Update API and frontend handoff Markdown to the implemented contract.

### Out of Scope / Non-Goals

- No new AI service, dependency, database column, Alembic revision, administrator-web redesign, Wear OS UI redesign, PPT edit, Git push, or production deployment.
- No replacement of Bedrock, STT, FactorizePhys, binary craving model, encryption, session, or notification architecture.

### Users and Primary Flow

1. An authenticated patient sees profile, latest four-stage craving state, and Watch/camera availability on Home.
2. Alert, manual Chat, or completed rPPG creates or reuses one session; a new session offers AUQ completion or skip before free dialogue.
3. Dashboard shows the last hour, today's stage composition, alert events, AUQ 0–48 history, and PPG previews.

### Current Assumptions and Constraints

- The V22 files are the V21 artifacts renamed by the user; explicit user choices supersede stale handoff wording.
- Backend and Phone deploy together because legacy AUQ submissions are rejected after the switch.
- Existing unrelated working-tree changes and untracked files must be preserved.

## 3. Repository Pattern Baseline

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Dashboard | `DashboardService` validates ranges and delegates SQL aggregation to `V25Repository`. | `apps/backend/app/services/dashboard.py::DashboardService`; `apps/backend/app/repositories/postgres.py::craving_probability_rows` | Extend the existing range table and payloads; no new dashboard layer. |
| Sessions/AUQ | FastAPI route validates input and `SessionService` encrypts/persists through the repository. | `apps/backend/app/api/v1/routes/session.py::assessment`; `apps/backend/app/services/session.py::assessment` | Keep validation at the route/service boundary and AES-GCM ownership in backend. |
| Prediction source | Sensor and rPPG services persist and return display-safe prediction dictionaries. | `apps/backend/app/services/sensor.py::SensorService`; `apps/backend/app/services/rppg.py::RppgService` | Add metadata without a new response family. |
| Mobile state | Compose screens consume `StateFlow` from existing ViewModels and parsers. | `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt`; `PatientDashboard.kt` | Reuse three tabs, full-screen branches, and existing API client. |
| rPPG | `RppgViewModel` polls persistent jobs and claims completed routing once. | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgViewModel.kt::markResultRouted` | Extend the completion orchestration without changing camera/DGX flow. |
| Tests | Backend uses focused pytest fakes; Android uses JVM unit tests plus Gradle lint/build. | `apps/backend/tests/test_craving_bar_dashboard_v25.py`; `apps/mobile/app/src/test/...` | Add focused cases beside the current owners. |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | Probability bucketing | `DashboardService.craving_probability_series` | Add `1h=(60m,10s,360)` to the existing table. |
| R-002 | Normalized AUQ aggregation | `SqlAlchemyV25Repository.craving_dashboard_rows` | Return 0–48 averages and retain normalized compatibility. |
| R-003 | Versioned AES-GCM keyring and AAD | `app/core/security/crypto.py` | Decrypt and re-encrypt legacy answers safely. |
| R-004 | Conversation session manager | `ConversationSessionManager.start/ensure` | Route all new entry paths through optional AUQ; resume active sessions directly. |
| R-005 | rPPG route-once store | `RppgViewModel.markResultRouted` | Prevent duplicate prediction/session/navigation effects. |
| R-006 | Probability series gap segmentation | `CravingProbabilitySeriesState.segments` | Render the fixed one-hour chart with missing gaps. |

## 4. Decisions and Questions

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | Source | Treat V22 PPT/Markdown as the renamed V21 source, with later explicit choices authoritative. | user | User clarified that V21 was renamed to V22. | Fixes the implementation source. | confirmed | resolved |
| D-002 | Mobile | Modify the existing Kotlin Phone test app; preserve Wear UI. | user | User selected the existing Kotlin app. | Limits mobile scope. | confirmed | resolved |
| D-003 | Session | Every newly created alert/manual/rPPG session offers AUQ completion or skip before free dialogue; active sessions resume directly. | user | User selected AUQ for every new session. | Unifies entry orchestration. | confirmed | resolved |
| D-004 | Camera | Disable camera measurement while Watch is connected. | user | User selected connected-state disablement. | Changes Home CTA gating only. | confirmed | resolved |
| D-005 | AUQ | New and historical AUQ use item values `0..6` and totals `0..48`; historical encrypted rows are converted. | user | User selected full conversion and DB migration. | Introduces a confirmed maintenance operation. | confirmed | resolved |
| D-006 | AI architecture | Keep Bedrock dialogue in backend, STT service, and DGX rPPG adapter. | user | User selected the current architecture. | No new service or dependency. | confirmed | resolved |
| D-007 | Craving labels | Use `안전`, `관찰`, `주의`, `심각` with the exact approved Korean sentences. | user | Explicit user copy. | Replaces stale UI labels only; wire keys remain stable. | confirmed | resolved |
| D-008 | Migration implementation | Use a dry-run/confirmed transactional maintenance CLI rather than an Alembic data migration. | repository | No schema change is needed and encrypted rows require application keyring/AAD. | Preserves Alembic head and authenticated encryption. | not-required | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | Source | Which artifact is authoritative? | Changes UI target. | Use renamed V22. | D-001 | answered | V22 is renamed V21. |
| Q-002 | Mobile | Existing Kotlin or new React Native? | Changes implementation surface. | Existing Kotlin. | D-002 | answered | Existing Kotlin test app. |
| Q-003 | Session | Where is AUQ shown? | Changes navigation. | Every new session. | D-003 | answered | Every new session, active sessions resume. |
| Q-004 | Camera | Camera availability with connected Watch? | Changes measurement policy. | Disable it. | D-004 | answered | Disabled when connected. |
| Q-005 | AUQ | Preserve, convert, or hide legacy scores? | Changes persistence and rollout. | Convert encrypted rows. | D-005 | answered | Full zero-based conversion. |

## 5. Requirements and Acceptance Criteria

### Functional Requirements

- **FR-001:** `range=1h` returns up to 360 ten-second probability buckets and omits missing buckets.
- **FR-002:** Watch predictions expose `source=watch_sensor`; rPPG remains `source=camera_rppg`.
- **FR-003:** New AUQ accepts exactly eight `0..6` responses/scored items and a consistent `0..48` total under version `2.0`.
- **FR-004:** The confirmed CLI atomically converts every standard legacy `8..56` AUQ row, including encrypted answers, metadata, key version, and audit evidence.
- **FR-005:** New alert/manual/rPPG sessions offer AUQ or skip before free dialogue; active sessions resume without another AUQ.
- **FR-006:** Home shows profile/settings, exact four-stage copy, source/time, Watch status, and camera gating without PPG/server/developer controls.
- **FR-007:** A stale rPPG completion cannot replace a newer Watch result, create duplicate sessions, or navigate twice.
- **FR-008:** Dashboard shows fixed one-hour probability, 24-hour stacked stages, alert events, AUQ 0–48, and PPG while hiding state/report cards.
- **FR-009:** Bedrock free-dialogue prompt, bounded history, repeat repair, 502 retry, STT, and local TTS behavior remain intact.

### Non-Functional Requirements

- **NFR-001:** Preserve current architecture, encryption/AAD rules, dependencies, and unrelated user changes.
- **NFR-002:** Backend and Phone must deploy together; the maintenance CLI must fail closed and roll back all rows on malformed or tampered ciphertext.
- **NFR-003:** Camera predictions remain Phone-only and are never relayed to Wear.

### Acceptance Criteria

- **AC-001:** Backend focused and full tests pass for range/source/AUQ/migration/session behavior.
- **AC-002:** Android unit tests, lint, and debug APK build pass for labels, gating, routing, ordering, and charts.
- **AC-003:** Local Compose reaches healthy/ready state and publishes updated OpenAPI without exposing secrets.
- **AC-004:** A Phone/Watch/DGX smoke flow completes Watch and rPPG entry through AUQ and free dialogue when the devices/services are available.

### Edge and Failure Cases

- Invalid or inconsistent AUQ arrays/totals → `422 invalid_auq_scale`; no row written.
- Tampered or unexpected legacy AUQ row → CLI aborts and rolls back every conversion.
- Missing one-hour buckets → line segments remain disconnected.
- Watch connects while Home is open → camera action immediately disables; in-progress accepted rPPG job continues.
- rPPG completion repeated or older than current result → history remains stored but no duplicate/latest-state overwrite.
- Bedrock/STT/rPPG unavailable → only the affected feature reports its existing error; authentication and usable features remain.

## 6. Implementation Strategy and Direction

### STRAT-1 — Extend Existing Owners

- **Direction:** `preserve`
- **Current approach:** Add contract branches and UI state to the current service/repository/ViewModel/Compose owners, plus one maintenance CLI for encrypted data conversion.
- **Existing flow to reuse:** R-001 through R-006.
- **Why this is minimal:** Every requested behavior has a current owner and test pattern; only encrypted data conversion needs a new executable module.
- **Behavior-preserving limitations:** Backend retains `averageNormalizedScore` and existing range values for compatibility; wire stage keys remain `low/observe/caution/high` while display copy changes.
- **Explicit exclusions:** No refactor, generic chart library, navigation framework, dependency, schema column, AI service, or PPT edit.
- **Compatibility and migration posture:** Deploy backend first to reject legacy writes, stop legacy Phone use, run dry-run and confirmed CLI, then deploy the new Phone app. Roll back code with the previous image; converted AUQ values stay 0–48.
- **Direction approval:** None required.
- **Open-question sensitivity:** None.

### Material Alternatives Considered

| Strategy | Direction | Benefit | Additional code or risk | Decision |
| --- | --- | --- | --- | --- |
| Alembic ciphertext rewrite | user-approved-divergence | Runs during startup | Migration layer would need application encryption configuration and is unsafe for runtime readiness. | rejected |
| Separate AI server | user-approved-divergence | Independent scaling | New service/API/deployment path with no V22 requirement. | rejected by D-006 |
| React Native implementation | user-approved-divergence | Matches future greenfield app | Duplicates the existing tested Phone app. | rejected by D-002 |

## 7. Modification Map and Change Budget

### Modification Map

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | production | `apps/backend/app/services/dashboard.py` | `PROBABILITY_RANGES`, `craving_dashboard` | extend | R-001/R-002 | Add 1h and AUQ 0–48 field. | FR-001, FR-008 | WS1 | preserve |
| CH-002 | production | `apps/backend/app/repositories/postgres.py` | `craving_dashboard_rows` | extend | R-002 | Return raw 0–48 AUQ average beside normalized value. | FR-008 | WS1 | preserve |
| CH-003 | production | `apps/backend/app/services/sensor.py` | prediction response | extend | current public prediction dictionary | Add `source=watch_sensor`. | FR-002 | WS1 | preserve |
| CH-004 | production | `apps/backend/app/api/v1/routes/session.py` | `assessment` | extend | current score range validation | Validate AUQ v2 arrays and totals. | FR-003 | WS1 | preserve |
| CH-005 | production | `apps/backend/app/maintenance/migrate_auq_zero_based.py` | new CLI | add | R-003 and existing maintenance commands | Add atomic dry-run/confirm conversion. | FR-004, NFR-002 | WS1 | preserve |
| CH-006 | test | `apps/backend/tests/test_craving_bar_dashboard_v25.py` | dashboard cases | extend | existing dashboard fakes | Prove 1h and AUQ fields. | AC-001 | WS1 | preserve |
| CH-007 | test | `apps/backend/tests/test_sessions_v25.py` | AUQ route/service cases | extend | current session tests | Prove v2 validation. | AC-001 | WS1 | preserve |
| CH-008 | test | `apps/backend/tests/test_auq_zero_based_migration.py` | new migration tests | add | maintenance test patterns | Prove conversion, rollback, audit, idempotency. | AC-001 | WS1 | preserve |
| CH-009 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt` | Home/navigation/AUQ/rPPG routing | extend | current three-tab Compose branch | Align Home and route new sessions through AUQ. | FR-005, FR-006, FR-007 | WS2 | preserve |
| CH-010 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt` | AUQ scoring and entry methods | extend | current optional-AUQ state | Switch to 0..6 and expose one entry orchestration. | FR-003, FR-005 | WS2 | preserve |
| CH-011 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/AuthenticatedSessionApi.kt` | `postAssessment` | extend | current AUQ request | Send v2 0–48 payload. | FR-003 | WS2 | preserve |
| CH-012 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt` | series/parser/charts/screen | extend | R-006 and existing charts | Add fixed 1h chart, new labels/AUQ, remove state/report cards. | FR-008 | WS2 | preserve |
| CH-013 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/ServerUploader.kt` | `CravingPrediction`, parser | extend | current binary parser | Preserve source metadata. | FR-002, FR-006 | WS2 | preserve |
| CH-014 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringState.kt` | `publishPrediction` | extend | latest prediction state | Reject stale latest-state replacement. | FR-007 | WS2 | preserve |
| CH-015 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgModels.kt` | `toPrediction` | extend | current camera conversion | Set camera source explicitly. | FR-002, FR-007 | WS2 | preserve |
| CH-016 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/WatchConnectionMonitor.kt` | `WatchRppgPresentationPolicy` | extend | current CTA policy | Disable camera when connected. | FR-006 | WS2 | preserve |
| CH-017 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/AuthenticatedSessionApiContractTest.kt` | session API contract tests | extend | existing session API tests | Prove AUQ v2 request behavior. | AC-002 | WS2 | preserve |
| CH-018 | docs | `apps/mobile/SERVER_API_SPEC.md` | prediction/AUQ/dashboard sections | edit | current API contract | Document implemented wire changes. | FR-001–FR-005 | WS3 | preserve |
| CH-019 | docs | `docs/neurotruth_frontend_handoff_v22.md` | V22 behavior | edit | renamed V21 handoff | Resolve labels, links, AUQ and routing contradictions. | FR-005–FR-008 | WS3 | preserve |
| CH-020 | test | `apps/backend/tests/test_sensor_routes_v25.py` | Watch prediction source assertions | extend | current sensor route regression tests | Prove Watch source propagation. | AC-001 | WS1 | preserve |
| CH-021 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/PatientDashboardParserTest.kt` | dashboard parser/state tests | extend | existing dashboard tests | Prove one-hour gaps, stages, and AUQ 0–48. | AC-002 | WS2 | preserve |
| CH-022 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt` | rPPG policy and latest-source tests | extend | existing rPPG tests | Prove Watch camera gating and stale-source rejection. | AC-002 | WS2 | preserve |
| CH-023 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/StateCheckScoringTest.kt` | zero-based AUQ scoring tests | extend | existing AUQ scoring tests | Prove item 0–6 and total 0–48. | AC-002 | WS2 | preserve |

### Change Budget

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 9 | 5 | 1 | 360 | None | None |
| WS2 | 12 | 8 | 0 | 500 | None | None |
| WS3 | 2 | 0 | 0 | 120 | None | None |

## 8. Work Plan

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | Implement backend contracts and encrypted AUQ conversion. | None | P1 | CH-001, CH-002, CH-003, CH-004, CH-005, CH-006, CH-007, CH-008, CH-020 | `apps/backend/app/**`, `apps/backend/tests/**` | `apps/mobile/**`, `apps/web/**`, `apps/backend/alembic/**`, `apps/backend/app/maintenance/import_alcohol_test.py`, `apps/backend/tests/test_import_alcohol_test.py` | FR-001, FR-002, FR-003, FR-004, FR-008, NFR-001, NFR-002, AC-001, AC-003 | backend focused pytest and local backend Compose checks | verified |
| WS2 | Align Kotlin Phone UI/state/routing and tests. | None | P1 | CH-009, CH-010, CH-011, CH-012, CH-013, CH-014, CH-015, CH-016, CH-017, CH-021, CH-022, CH-023 | `apps/mobile/app/src/main/java/com/example/healthsensor/*.kt`, `apps/mobile/app/src/test/java/com/example/healthsensor/**` | `apps/backend/**`, `apps/mobile/wearos/**`, `apps/mobile/**/build/**` | FR-002, FR-003, FR-005, FR-006, FR-007, FR-008, FR-009, NFR-001, NFR-003, AC-002, AC-004 | Gradle unit/lint/assemble and available device smoke | verified |
| WS3 | Synchronize API and frontend Markdown. | WS1, WS2 | Serial | CH-018, CH-019 | `apps/mobile/SERVER_API_SPEC.md`, `docs/neurotruth_frontend_handoff_v22.md` | `docs/handoff/*.pptx`, `docs/specs/**` | FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-007, FR-008 | rg/manual contract comparison | verified |

### Parallelization Rationale

WS1 and WS2 have disjoint write scopes and a fixed reviewed JSON contract. WS3 runs after both so documentation reflects actual accepted patches.

### Final Integration

Run backend full tests, Android Phone/Wear unit tests, lint and builds, Compose config/health checks, then available device/DGX smoke tests. Inspect the complete diff and exclude unrelated existing work.

## 9. Validation, Rollout, and Risk

### Validation Plan

- Backend: focused dashboard/session/migration tests, then complete pytest.
- Android: focused JVM tests, `:app:testDebugUnitTest`, `:wearos:testDebugUnitTest`, lint, and debug APK builds.
- Docker: CPU Compose config/build/up, `/health`, `/ready`, `/openapi.json`, STT/rPPG status when Docker Desktop is available.
- Device: Phone install and Watch/rPPG/STT/TTS/Bedrock smoke when connected services and hardware are available.

### Minimality and Style-Fidelity Review

- Every changed path must map to CH-001–CH-019.
- Reject new dependencies, shared abstractions, schema edits, generic refactors, and generated-file changes.
- Keep old API ranges and normalized AUQ field only where existing consumers need compatibility.

### Rollout and Rollback

1. Back up the database and stop old Phone writes.
2. Deploy backend validation/API, run CLI dry-run, then confirmed conversion.
3. Deploy the matching Phone APK and verify health/flows.
4. Roll back code with the previous images/APK if needed; do not reverse converted AUQ rows automatically.

### Risks and Mitigations

| Risk | Impact | Mitigation or Evidence |
| --- | --- | --- |
| Encrypted legacy row is malformed | Conversion could partially corrupt data. | Single transaction, AAD validation, fail-closed rollback, dry-run. |
| Old Phone posts 1..7 after backend switch | Requests fail. | Coordinated deployment and explicit 422. |
| Delayed rPPG overwrites Watch | Incorrect Home latest state. | Timestamp/source ordering plus route-once tests. |
| Existing handoff contradictions | UI differs from approved copy. | Latest explicit decisions and synchronized docs are authoritative. |
| Docker/devices unavailable locally | Integration evidence incomplete. | Record exact limitation after network-free and build validation. |

## 10. Revision and Progress

### Design Revision History

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-21T16:00:00+09:00 | approved-plan-implementation | Created the reviewed implementation-ready bilingual contract from the approved V22 plan and answers. | D-001, D-002, D-003, D-004, D-005, D-006, D-007, D-008 | Q-001, Q-002, Q-003, Q-004, Q-005 |
| 2 | 2026-07-21T17:25:00+09:00 | implementation-map-correction | Mapped the concrete Watch source and Phone test files exercised by the accepted patch; no feature direction changed. | None | None |

### Implementation Progress Record

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-21T16:00:00+09:00 | 1 | — | ready | User supplied the complete plan and explicitly authorized implementation. |
| 2026-07-21T16:20:00+09:00 | 1 | WS1 | in_progress | Backend slice delegated against the reviewed contract. |
| 2026-07-21T16:20:00+09:00 | 1 | WS2 | in_progress | Kotlin Phone slice delegated against the reviewed contract. |
| 2026-07-21T17:10:00+09:00 | 1 | WS1 | verified | Focused backend tests passed; diff, scope, rollback behavior, and mixed-scale AUQ aggregation reviewed. |
| 2026-07-21T17:10:00+09:00 | 1 | WS2 | verified | Phone unit, lint, and debug build passed; diff and budget reviewed. |
| 2026-07-21T17:10:00+09:00 | 1 | WS3 | in_progress | Documentation synchronization delegated after accepted backend and Phone contracts. |
| 2026-07-21T17:35:00+09:00 | 2 | WS3 | verified | API and V22 frontend handoff Markdown matched the implemented contract; stale labels and links were removed. |
| 2026-07-21T17:40:00+09:00 | 2 | — | complete | Full backend suite passed (184 passed, 1 skipped); Phone/Wear unit, lint and debug builds passed; local Docker health/ready passed. AUQ dry-run correctly failed closed on pre-existing nonstandard local rows, and no ADB device was connected for hardware smoke. |
| 2026-07-21T17:32:36+09:00 | 2 | — | complete | Backed up the local PostgreSQL database, removed only the confirmed automated AUQ test sessions with audit records, and reran the zero-based CLI successfully in dry-run and confirmed modes with zero remaining rows. Backend full tests again passed (184 passed, 1 skipped); Phone/Wear unit, lint and debug builds passed; Docker health/ready and the updated OpenAPI paths were verified. |
