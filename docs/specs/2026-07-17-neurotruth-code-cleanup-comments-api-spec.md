# NeuroTruth Code Cleanup, Contract Comments, and API Specification — Living Implementation Specification

<!-- feature-planner-control
{
  "workflow": "feature-planner/v7",
  "state": "complete",
  "source_spec": "docs/specs/2026-07-17-neurotruth-code-cleanup-comments-api-spec.md",
  "korean_mirror": "docs/specs/2026-07-17-neurotruth-code-cleanup-comments-api-spec.ko.md",
  "spec_revision": 3,
  "reviewed_revision": 3,
  "selected_strategy": "STRAT-1",
  "implementation_direction": "user-approved-divergence",
  "direction_decision_id": "D-006",
  "minimal_change_policy": "strict",
  "final_domain_gate": "confirmed_none",
  "open_question_ids": [],
  "active_slices": [],
  "next_action": "none"
}
-->

> This pair is a living design. The English file is authoritative; the Korean file is its synchronized review mirror.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `complete`, revision 3, user-reviewed |
| Outcome | Complete the approved cleanup and replace the hourly average craving bars with four-stage 100% stacked bars backed by exact server counts. |
| Recommended implementation | `STRAT-1` — preservation-first deletion plus one backward-compatible dashboard response extension |
| Planned production targets | `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt::duplicate upload/SSE helpers and state`; `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt::ProbabilityLineChart`, `AuqChart`; `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt::UserStatusRow and diagnostics label`; `apps/backend/app/main.py::unregistered legacy slot/handoff compatibility group`; `apps/backend/app/v25/repository.py::craving_dashboard_rows`; `apps/backend/app/v25/dashboard_service.py::craving_dashboard`; `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt::HourlyCravingBucket parser and HourlyCravingBarChart` |
| Expected additions | New production files: 0; dependencies: None; shared abstractions: None |
| Work plan | 4 slices; WS1/WS2/WS3 are independent, WS4 owns the stacked-chart contract end to end |
| Open questions | None |
| Agent decisions to review | None |
| Last material change | Revision 3 — added the user-requested four-stage stacked craving chart and required server aggregation. |

## 2. Outcome and Scope

### Outcome

The current v7 application removes duplicated/unreachable code, keeps the diagnostic workflow, documents the current API, and shows each local hour as a four-stage composition rather than a single average bar.

### In Scope

- Remove the unreachable Android uploader/SSE implementation duplicated by `PhoneMonitoringService`.
- Remove unused private UI composables that retain obsolete displays.
- Remove backend slot/handoff compatibility functions that are neither registered routes nor used by current `app.v25` runtime paths.
- Correct stale 10-second-window and structured-dialogue comments/documents.
- Update `apps/mobile/SERVER_API_SPEC.md` and current facts in `apps/backend/README.md`.
- Preserve the hidden Android diagnostics screen, hold gesture, simulation controls, and supporting reachable state.
- Extend each hourly dashboard bucket with exact counts for `low`, `observe`, `caution`, and `high` probability bands.
- Render a 100% stacked bar per hour with a four-color legend and selected-hour composition detail.

### Out of Scope / Non-Goals

- No schema, migration, model, security, consent, dependency, deployment, or stored-data change.
- No deletion of legacy records, migrations, read-only legacy sessions, or readable 10-second rPPG history.
- No generated OpenAPI snapshot, broad formatting rewrite, Git commit, push, or deployment.

### Users and Primary Flow

1. Android users continue using the same v7 flow.
2. Device testers can still unlock the diagnostics screen.
3. Developers use the updated Markdown contract and runtime `/openapi.json` without contradictory old behavior.
4. Patients inspect an hourly stacked bar to see the distribution of the four research UI stages; tapping it shows stage percentages and sample count.

### Current Assumptions and Constraints

- The uncommitted v7 implementation and user-owned `PULL_REQUEST_FREE_CHAT_BAR_DASHBOARD.md` must be preserved.
- Comments explain non-obvious ownership, timing, idempotency, encryption, and legacy/current boundaries only.

## 3. Repository Pattern Baseline

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Android transport | Foreground service owns upload and SSE lifecycle. | `apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringService.kt` | One background transport owner and existing retry/idempotency behavior. |
| Session runtime | Authenticated routes delegate to v25 services/agents. | `apps/backend/app/v25/routes_sessions.py`, `session_service.py` | Current free-dialogue route contract. |
| Machine API | FastAPI produces live OpenAPI. | `apps/backend/app/main.py::app` | `/openapi.json` reflects registered routes. |
| Human API | Root documentation links the mobile handoff spec. | `apps/mobile/SERVER_API_SPEC.md` | Update the existing document rather than add a competing contract. |
| Dashboard aggregation | Server derives local-hour buckets from binary class-1 probabilities. | `apps/backend/app/v25/repository.py::craving_dashboard_rows` | Keep timezone, active-model, quality-gate, and no-data filters. |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | `PhoneMonitoringService` transport | `PhoneMonitoringService.kt` | Retain as sole sensor/SSE owner. |
| R-002 | v25 routers and schemas | `apps/backend/app/v25/routes_*.py` | Derive the current API document and preserve runtime behavior. |
| R-003 | Live OpenAPI | `GET /openapi.json` | Verify route inventory and machine-readable contract. |
| R-004 | Existing API handoff | `apps/mobile/SERVER_API_SPEC.md` | Replace stale content in place. |
| R-005 | Existing selectable Canvas bars | `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt::DailyEventBarChart` | Reuse hit testing, selection border, no-data color, and details pattern. |

## 4. Decisions and Questions

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | Android | `PhoneMonitoringService` is the active transport owner. | repository | No call sites exist for the duplicate `SensorViewModel` loops. | Allows safe duplicate removal. | not-required | resolved |
| D-002 | Compatibility | Reachable legacy data readers and migrations remain. | repository | Existing records and 10-second rPPG history are explicit compatibility paths. | Prevents destructive cleanup. | not-required | resolved |
| D-003 | Documentation | Keep `apps/mobile/SERVER_API_SPEC.md` as the single human API specification. | agent | It is already linked and closest to app consumers. | Avoids parallel documents. | confirmed | resolved |
| D-004 | Documentation | Use live `/openapi.json`; do not commit a generated snapshot. | agent | A checked-in generated schema can drift from route code. | No generated file or workflow. | confirmed | resolved |
| D-005 | Android | Preserve the hidden diagnostics screen and its reachable controls. | user | User selected retention for real-device testing. | Only stale labels and dead duplicate internals are cleaned. | confirmed | resolved |
| D-006 | Dashboard | Replace the single hourly craving bar with a stacked stage-composition bar. | user | User requested a chart matching the supplied stacked-bar reference. | This is the only approved observable behavior/API extension. | confirmed | resolved |
| D-007 | Dashboard | Use four 100%-normalized segments and return exact stage counts from the backend. | agent | Historical averages cannot reconstruct stage composition; the reference is a 100% stacked chart. | Adds backward-compatible `stageCounts` to hourly buckets and selected-bar percentages. | confirmed | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | Android | Keep or remove the hidden diagnostics screen. | Removal changes real-device testing workflow. | Keep it and remove only unreachable internals. | D-005 | answered | User chose to retain it for real-device testing. |

## 5. Requirements and Acceptance Criteria

### Functional Requirements

- **FR-001:** Android must retain one service-owned 20-second-window/10-second-cadence transport implementation and the reachable diagnostics workflow.
- **FR-002:** Backend must retain only current runtime AI/session paths plus required legacy data compatibility.
- **FR-003:** The human API spec must describe all current registered route groups, authentication/consent, 20-second sensor/rPPG, STT, free dialogue, AUQ 8–56, dashboards, errors, and research boundaries.
- **FR-004:** Contract comments and visible diagnostics labels must distinguish 20-second input windows from 10-second cadence.
- **FR-005:** Each populated hourly craving bucket must expose and display the proportions of `low` (`p<0.25`), `observe` (`0.25≤p<0.50`), `caution` (`0.50≤p<0.75`), and `high` (`p≥0.75`).
- **FR-006:** Empty hours must remain visually distinct from a populated hour, and selected-hour detail must show all four percentages plus total sample count.

### Non-Functional Requirements

- **NFR-001:** Preserve public behavior and introduce no production file, dependency, shared abstraction, migration, or unrelated formatting churn.
- **NFR-002:** Preserve encryption, credential masking, authentication ownership, idempotency, and legacy read-only guarantees.

### Acceptance Criteria

- **AC-001:** Repository search shows no remaining calls/references to removed Android and backend symbols.
- **AC-002:** Backend tests/compile, Android tests/build/lint, and Docker health/readiness checks pass.
- **AC-003:** API route table matches all 39 live OpenAPI paths and contains no stale voice-disabled, 10-second-current, or structured-dialogue claims.
- **AC-004:** Hidden developer diagnostics remains reachable and its 20-second-window label is correct.
- **AC-005:** Diff review shows no unrelated user change, secret, patient data, or new dependency.
- **AC-006:** For every populated bucket, the four stage counts sum to `sampleCount`, rendered segment heights sum to the full bar, and boundary values `0.25`, `0.50`, and `0.75` enter the intended stage.
- **AC-007:** Existing clients that ignore `stageCounts` remain compatible and existing average/minimum/maximum fields remain present.

### Edge and Failure Cases

- Legacy 10-second rPPG record → remains readable and is not rewritten.
- STT/rPPG disabled → API specification documents availability/status behavior without implying readiness.
- Empty or invalid model/storage state → existing `503` behavior remains unchanged.
- Hour with zero predictions → grey no-data bar and no artificial 0% distribution.

## 6. Implementation Strategy and Direction

### STRAT-1 — Preservation-first cleanup with an additive stacked-dashboard contract

- **Direction:** `user-approved-divergence` via D-006.
- **Current approach:** Delete only reachability-proven duplicates, update existing docs, and add four filtered counts to the existing hourly SQL/result. Android normalizes those counts into a 100% Canvas stack.
- **Existing flow to reuse:** R-001 through R-005.
- **Why this is minimal:** Exact historical composition requires server counts; extending the existing query/bucket is smaller and safer than a new route, table, or client reconstruction.
- **Behavior-preserving limitations:** Reachable developer tools and historical compatibility code remain even if not used by ordinary patients.
- **Explicit exclusions:** No new route, schema, model, UI navigation, migration, deployment, or data cleanup.
- **Compatibility and migration posture:** Source-only cleanup; rollback is the previous source revision; no data conversion.
- **Direction approval:** D-006 approves only the hourly stacked-chart/API-field change.
- **Open-question sensitivity:** None.

### Material Alternatives Considered

| Strategy | Direction | Benefit | Additional code or risk | Decision |
| --- | --- | --- | --- | --- |
| Add a generated OpenAPI snapshot | preserve | Machine-readable committed artifact | Drift and generator maintenance | rejected |
| Remove all developer tooling | user-approved-divergence | Smaller APK/UI source | Breaks device-test workflow | rejected by D-005 |
| Broad architectural rewrite | user-approved-divergence | Potential long-term consolidation | Large regression surface | rejected |

## 7. Modification Map and Change Budget

### Modification Map

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt` | duplicate upload/SSE helpers and state | remove | `PhoneMonitoringService` | Remove unreachable transport duplicate and stale imports; correct contract comment. | FR-001, FR-004, AC-001 | WS1 | preserve |
| CH-002 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt` | `ProbabilityLineChart`, `AuqChart` | remove | current bar dashboard | Remove unreachable obsolete charts and stale preview wording if needed. | FR-001, AC-001 | WS1 | preserve |
| CH-003 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt` | UserStatusRow and diagnostics label | edit | `HealthMonitorScreen` | Remove unreachable row and fix 20-second label while preserving diagnostics. | FR-001, FR-004, AC-004 | WS1 | preserve |
| CH-004 | production | `apps/backend/app/main.py` | unregistered legacy slot/handoff compatibility group | remove | v25 session routes | Remove isolated request models/helpers/imports. | FR-002, AC-001 | WS2 | preserve |
| CH-005 | test | `apps/backend/tests/test_intervention_routes.py` | obsolete direct helper tests | remove | v25 session tests | Remove tests for non-routes deleted by CH-004. | FR-002, AC-002 | WS2 | preserve |
| CH-006 | test | `apps/backend/tests/test_bedrock_agents.py` | obsolete helper-only cases | edit | v25 agent tests | Remove only cases bound to deleted `app.main` helpers. | FR-002, AC-002 | WS2 | preserve |
| CH-007 | docs | `apps/mobile/SERVER_API_SPEC.md` | full document | edit | live OpenAPI and v25 routes | Synchronize current API contract in place. | FR-003, AC-003 | WS3 | preserve |
| CH-008 | docs | `apps/backend/README.md` | current facts | edit | current source/migrations/tests | Correct model, STT/rPPG, migration and validation statements. | FR-003, AC-003 | WS3 | preserve |
| CH-009 | docs | `README.md` | API link context | edit | existing link | Edit only if surrounding description is stale. | FR-003, AC-003 | WS3 | preserve |
| CH-010 | production | `apps/backend/app/v25/repository.py` | `craving_dashboard_rows` | extend | existing hourly SQL | Count the four probability bands with the same filters/timezone. | FR-005, AC-006, AC-007 | WS4 | approved-divergence |
| CH-011 | production | `apps/backend/app/v25/dashboard_service.py` | `craving_dashboard` | extend | existing hourly bucket serializer | Add `stageCounts` while preserving average/min/max/sample fields. | FR-005, FR-006, AC-006, AC-007 | WS4 | approved-divergence |
| CH-012 | test | `apps/backend/tests/test_craving_bar_dashboard_v25.py` | hourly stage-distribution tests | extend | existing bucket tests | Prove thresholds, sums, no-data, and compatibility fields. | AC-006, AC-007 | WS4 | approved-divergence |
| CH-013 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt` | HourlyCravingBucket parser and HourlyCravingBarChart | extend | `DailyEventBarChart` | Parse counts and draw/select four-color 100% stacks with legend/detail. | FR-005, FR-006, AC-006 | WS4 | approved-divergence |
| CH-014 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/PatientDashboardParserTest.kt` | stacked bucket parsing/boundary tests | extend | existing dashboard parser tests | Prove counts, totals, no-data, and labels. | AC-006, AC-007 | WS4 | approved-divergence |

### Change Budget

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 3 | 3 | 0 | 20 | None | None |
| WS2 | 3 | 1 | 0 | 10 | None | None |
| WS3 | 3 | 0 | 0 | 420 | None | None |
| WS4 | 5 | 3 | 0 | 180 | None | None |

## 8. Work Plan

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | Remove Android duplicates and stale private UI while preserving diagnostics. | None | PG-1 | CH-001, CH-002, CH-003 | `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt`, `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt`, `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt` | backend, specs, Gradle/dependencies | FR-001, FR-004, NFR-001, AC-001, AC-004 | Android unit tests and debug compile | verified |
| WS2 | Remove unregistered backend compatibility helpers and their isolated tests. | None | PG-1 | CH-004, CH-005, CH-006 | `apps/backend/app/main.py`, `apps/backend/tests/test_intervention_routes.py`, `apps/backend/tests/test_bedrock_agents.py` | v25 runtime/routes, migrations, model/STT/rPPG | FR-002, NFR-001, NFR-002, AC-001, AC-002 | backend full pytest and compileall | verified |
| WS3 | Synchronize human API and backend operational documentation. | None | PG-1 | CH-007, CH-008, CH-009 | `apps/mobile/SERVER_API_SPEC.md`, `apps/backend/README.md`, `README.md` | source, migrations, config, specs | FR-003, NFR-001, AC-003, AC-005 | OpenAPI inventory comparison and link check | verified |
| WS4 | Add exact four-stage hourly aggregation and the mobile 100% stacked chart. | None | Serial | CH-010, CH-011, CH-012, CH-013, CH-014 | `apps/backend/app/v25/repository.py`, `apps/backend/app/v25/dashboard_service.py`, `apps/backend/tests/test_craving_bar_dashboard_v25.py`, `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt`, `apps/mobile/app/src/test/java/com/example/healthsensor/PatientDashboardParserTest.kt` | migrations, models, routes, admin web, Wear OS | FR-005, FR-006, NFR-001, NFR-002, AC-006, AC-007 | focused backend dashboard tests and Android parser/build tests | verified |

### Parallelization Rationale

PG-1 slices have disjoint targets. WS4 is serial because it owns both ends of the additive dashboard contract and overlaps WS1's `PatientDashboard.kt`; schedule WS4 after WS1.

### Final Integration

Run backend pytest/compileall, Android unit/build/lint, Docker status endpoints, OpenAPI inventory comparison, `git diff --check`, and a deletion-oriented diff review.

## 9. Validation, Rollout, and Risk

### Validation Plan

- Backend full tests and `python -m compileall -q app tests`.
- Android `testDebugUnitTest`, `assembleDebug`, and `lintDebug`.
- Docker `/health`, `/ready`, `/openapi.json`, `/api/stt/status`, `/api/rppg/status`.
- Search removed symbols and compare all live paths to the Markdown route inventory.
- Verify SQL threshold boundaries and stacked-segment sums with deterministic fixtures.

### Minimality and Style-Fidelity Review

- Classify every changed path against CH-001 through CH-009.
- Reject behavior changes, new abstractions, dependencies, formatting churn, and deletion of reachable history/tooling.

### Rollout and Rollback

No migration or special rollout. Deploy backend and Android only after their existing v7 integration verification; rollback uses the previous Git/Docker/APK revision.

### Risks and Mitigations

| Risk | Impact | Mitigation or Evidence |
| --- | --- | --- |
| Static reference misses reflective usage | Runtime regression | Remove only private/unregistered symbols and run full tests/build/OpenAPI checks. |
| Documentation drifts from runtime | Client integration error | Derive routes and models from current source plus live OpenAPI. |
| Existing uncommitted work is overwritten | User data loss | Narrow write scopes and final status/diff review. |
| A 100% stack hides sample volume | A sparse hour may look visually dominant | Keep no-data grey and show total sample count in selected-hour detail. |

## 10. Revision and Progress

### Design Revision History

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-17T15:10:00+09:00 | initial-repository-design | Recorded dead-code evidence, preservation boundaries, and API-document strategy. | D-001, D-002, D-003, D-004 | Q-001 |
| 2 | 2026-07-17T15:25:00+09:00 | user-answer | Preserved the reachable hidden diagnostics workflow and finalized exact slices/budgets. | D-005 | Q-001 |
| 3 | 2026-07-18T10:00:00+09:00 | user-change-request | Added the approved four-stage stacked chart and exact additive server aggregation. | D-006, D-007 | None |

### Implementation Progress Record

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-17T15:10:00+09:00 | 1 | — | refining | Living pair created before the first clarification question. |
| 2026-07-17T15:25:00+09:00 | 2 | — | refining | Developer diagnostics retention confirmed; awaiting final domain review. |
| 2026-07-18T10:00:00+09:00 | 3 | — | refining | Stacked-chart direction recorded; awaiting review of the normalization/API detail. |
| 2026-07-18T10:15:00+09:00 | 3 | — | ready | User confirmed no remaining concerns; revision 3 is implementation-ready. |
| 2026-07-18T10:40:00+09:00 | 3 | WS1 | verified | Scope/patch checks passed; Android compile and unit tests succeeded; diagnostics remain reachable. |
| 2026-07-18T10:45:00+09:00 | 3 | WS3 | verified | Existing API spec synchronized with 39 paths/42 operations; documentation checks passed. |
| 2026-07-18T10:50:00+09:00 | 3 | WS4 | in_progress | WS1 verified; end-to-end stacked dashboard contract implementation started. |
| 2026-07-18T10:51:00+09:00 | 3 | WS4 | pending | Paused before edits to honor serial slice scheduling until WS2 completes. |
| 2026-07-18T10:55:00+09:00 | 3 | WS2 | verified | Scope/patch checks, compileall, and full backend suite passed (149 tests). |
| 2026-07-18T10:56:00+09:00 | 3 | WS4 | in_progress | Serial stacked-dashboard slice resumed after WS2 verification. |
| 2026-07-18T11:30:00+09:00 | 3 | WS4 | verified | Four-stage counts, 100% stacked Canvas, 2×2 legend, focused tests, and APK build passed. |
| 2026-07-18T11:45:00+09:00 | 3 | — | complete | Full backend 149/1, Android unit/APK/lint, Docker rebuild, health/readiness, OpenAPI, and diff checks passed. |
