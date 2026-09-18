# Alcohol_Test Batch Craving Inference - Living Implementation Specification

<!-- feature-planner-control
{
  "workflow": "feature-planner/v7",
  "state": "complete",
  "source_spec": "docs/specs/2026-07-21-neurotruth-alcohol-test-batch-inference-spec.md",
  "korean_mirror": "docs/specs/2026-07-21-neurotruth-alcohol-test-batch-inference-spec.ko.md",
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

> The English file is authoritative. The Korean file is the synchronized review mirror.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `complete`, revision 2, user-reviewed direction with repository-evidence count correction |
| Outcome | Import 100 Alcohol_Test subject-visits as 16,334 usable measurements; retain 16,339 theoretical windows and report five empty windows. |
| Recommended implementation | `STRAT-1` - add one maintenance CLI that reuses the current model, SensorService, encrypted storage, repository, and audit log. |
| Planned production targets | `apps/backend/app/maintenance/import_alcohol_test.py::main`; `apps/db/docker-compose.yml::alcohol-test-import` |
| Expected additions | New production files: `apps/backend/app/maintenance/import_alcohol_test.py`; dependencies: None; shared abstractions: None |
| Work plan | WS1 importer/tests and WS2 Docker/docs may proceed in parallel because write scopes are disjoint. |
| Open questions | None |
| Agent decisions to review | None |
| Last material change | Revision 2 - recorded the observed 68.7-second source gap and corrected executable totals without changing strategy. |

## 2. Outcome and Scope

### Outcome

Operators can validate or import only the G recordings in `Alcohol_Test/data`, run the current binary Conv1D craving model over 20-second windows at a 10-second stride, and inspect encrypted recordings, PostgreSQL predictions, and CSV/JSON summaries for 100 test accounts.

### In Scope

- Exact G-folder discovery, mixed-header TSV parsing, timestamp rebasing, prediction, encrypted persistence, resumable run metadata, reports, Docker profile, focused tests, and bilingual documentation.

### Out of Scope / Non-Goals

- Ground-truth accuracy/F1 evaluation, alert creation, Bedrock, public API/schema changes, database migrations, model retraining, and UI changes.

### Users and Primary Flow

1. An operator sets `ALCOHOL_TEST_ACCOUNT_PASSWORD` and starts PostgreSQL.
2. The operator validates the dataset, runs a three-window smoke import, then runs the full import.
3. The importer creates or reuses 100 accounts and persists encrypted windows and binary craving predictions while writing reports outside the repository.

### Current Assumptions and Constraints

- The source contains 100 subject-visit G CSV files and yields 16,339 theoretical windows. `1_2_006_V2` has five empty windows, leaving 16,334 importable windows.
- The Docker backend image supplies PyTorch; the current host `.venv` does not.
- Original sample timing is preserved relative to each file while database timestamps are rebased near the run anchor.

## 3. Repository Pattern Baseline

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Maintenance ownership | Operational commands live under `app.maintenance` and use explicit CLI entry points. | `apps/backend/app/maintenance/purge_legacy_predictions.py::main` | Keep this import as an operational backend command. |
| Sensor persistence | `SensorService.ingest` owns canonicalization, encrypted storage, idempotency, prediction persistence, and optional alerts. | `apps/backend/app/services/sensor.py::SensorService.ingest` | Reuse it with notification disabled. |
| Model ownership | The backend-owned Conv1D model consumes PPG and EDA samples and returns binary probabilities. | `apps/backend/app/ml/craving/model.py::CravingModel` | Load the supplied artifact once and preserve its preprocessing contract. |
| Deployment | Local services and persistent volumes are defined in the DB-owned Compose file. | `apps/db/docker-compose.yml` | Add an opt-in profile without altering normal startup. |
| Testing | Backend unit tests use pytest and focused fakes around repositories/services. | `apps/backend/tests/test_sensor_routes_v25.py` | Add focused parser/import tests without external services. |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | `SensorService` | `apps/backend/app/services/sensor.py::SensorService` | Persist every window through the production sensor path. |
| R-002 | `CravingModel` | `apps/backend/app/ml/craving/model.py::CravingModel` | Perform current binary craving inference. |
| R-003 | `EncryptedSensorStorage` | `apps/backend/app/storage/sensor.py::EncryptedSensorStorage` | Preserve gzip plus AES-GCM raw-window storage. |
| R-004 | `SqlAlchemyV25Repository` and `audit_logs` | `apps/backend/app/repositories/postgres.py::SqlAlchemyV25Repository.audit` | Create accounts/consents, query run manifests, and record lifecycle metadata. |
| R-005 | Current Compose backend build and volumes | `apps/db/docker-compose.yml::backend` | Reuse image, model, database environment, and encrypted sensor volume. |

## 4. Decisions and Questions

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | Accounts | Create 100 accounts, one per V1/V2 subject-visit. | user | Confirmed before implementation. | Each source file has an independently login-capable account. | confirmed | resolved |
| D-002 | Persistence | Use the full encrypted SensorService pipeline. | user | Confirmed before implementation. | Raw windows, recordings, and predictions are retained. | confirmed | resolved |
| D-003 | Evaluation | Produce prediction distributions without alerts or ground-truth scoring. | user | Confirmed before implementation. | `craving_alerts` remains unchanged. | confirmed | resolved |
| D-004 | Credentials | Use one required `ALCOHOL_TEST_ACCOUNT_PASSWORD` environment value and never write it to reports. | user | Confirmed before implementation. | Accounts can be used in the UI without credential artifacts. | confirmed | resolved |
| D-005 | Time | Rebase database times near the run anchor and retain original times in metadata. | user | Confirmed before implementation. | Imported measurements are visible in current dashboards and remain traceable. | confirmed | resolved |
| D-006 | Architecture | Preserve current backend service and database schema; use audit metadata for resumability. | repository | Existing service and JSON metadata columns already support the flow. | No migration, provider, or API change is needed. | not-required | resolved |
| D-007 | Source quality | Treat five empty windows in `1_2_006_V2` as recoverable source failures and do not interpolate them. | repository | Validation found an approximately 68.7-second timestamp gap in the selected G CSV. | Full import completes with a partial summary and 16,334 persisted windows. | not-required | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | Account granularity | Choose 50 subjects or 100 subject-visits. | Controls account identity and result grouping. | Use 100 accounts. | D-001 | answered | User selected 100 subject-visit accounts. |
| Q-002 | Persistence | Choose full encrypted persistence or predictions only. | Controls storage and production-path fidelity. | Use the full pipeline. | D-002 | answered | User selected full encrypted persistence. |
| Q-003 | Time | Preserve historical times or rebase near the current run. | Controls dashboard visibility and idempotency metadata. | Rebase and preserve source times in metadata. | D-005 | answered | User selected rebasing. |

## 5. Requirements and Acceptance Criteria

### Functional Requirements

- **FR-001:** Discover exactly one `<subject_visit>G_...` CSV under each `ECG_PPG_GSR` directory and exclude ECG and GT sources.
- **FR-002:** Parse tab-separated files by header suffix, skip the separator and unit rows, and support both observed five-column and fifteen-column layouts.
- **FR-003:** Produce complete 20-second windows at a 10-second stride and map PPG to `PPG_GREEN` and conductance to `EDA`.
- **FR-004:** Rebase sample/window timestamps near a stable run anchor while retaining source timestamps and offset in `device_info`.
- **FR-005:** Create or reuse 100 deterministic-email patient accounts with required biosignal/AI consent and notifications disabled.
- **FR-006:** Persist each window through `SensorService` using deterministic UUID5 client IDs and resumable audit-log run metadata.
- **FR-007:** Write account CSV, prediction CSV, and summary JSON outside the repository without credentials.
- **FR-008:** Provide `--validate-only`, `--subject-visit`, `--limit-windows`, `--run-id`, and `--new-run` controls.
- **FR-009:** Provide an opt-in Docker Compose profile and bilingual execution guide.

### Non-Functional Requirements

- **NFR-001:** Preserve all public APIs, the database schema, model artifact/behavior, and normal Docker startup.
- **NFR-002:** Rerunning the same run ID must not add duplicate sensor or prediction rows.
- **NFR-003:** Invalid files/windows must be reported without silently changing column selection or using another sensor folder.
- **NFR-004:** Introduce no dependency or shared abstraction and preserve unrelated working-tree changes.

### Acceptance Criteria

- **AC-001:** Dataset validation reports 100 G files, 16,339 theoretical windows, and the five exact empty-window failures in `1_2_006_V2`.
- **AC-002:** Focused tests prove exact folder selection, mixed-header parsing, window boundaries, rebasing, deterministic IDs, account naming, resume behavior, and notification suppression.
- **AC-003:** A three-window Docker smoke import produces matching encrypted recordings, DB predictions, and report rows; repeating the run does not increase counts.
- **AC-004:** With the current source, a full import produces 100 test accounts, 16,334 sensor recordings, 16,334 predictions, five reported skipped windows, and zero import-generated alerts.
- **AC-005:** Backend compileall, pytest, Compose config, and `git diff --check` pass, or environmental blockers are reported exactly.

### Edge and Failure Cases

- Missing/multiple matching G folders or CSVs -> record a validation failure and do not choose an alternative.
- Missing required header, non-finite value, or duration below 20 seconds -> report the source and skip its invalid work.
- Existing email outside the expected active patient/consent contract -> fail visibly rather than overwrite the account.
- Same run ID with changed canonical payload -> preserve SensorService conflict behavior and fail visibly.
- Completed run without `--new-run` -> report completion and avoid creating a duplicate run.

## 6. Implementation Strategy and Direction

### STRAT-1 - Existing-Service Batch Import

- **Direction:** `preserve`
- **Current approach:** A single maintenance CLI parses source files, establishes a stable audit-backed run anchor, creates/reuses accounts, and feeds deterministic canonical windows into a small async adapter around the existing model and `SensorService`.
- **Existing flow to reuse:** R-001 through R-005.
- **Why this is minimal:** Existing metadata JSON and audit storage provide provenance and resumability, so no API, migration, dependency, or parallel persistence path is required.
- **Behavior-preserving limitations:** Raw sample points remain in encrypted files while PostgreSQL stores recording/prediction metadata, matching production behavior.
- **Explicit exclusions:** No public route, model refactor, generic importer framework, alert logic, Bedrock use, or dashboard change.
- **Compatibility and migration posture:** Opt-in command/profile only; normal deployment is unchanged and rollback is removal of the new command/profile/docs.
- **Direction approval:** None required; this extends the nearest established owners.
- **Open-question sensitivity:** None.

### Material Alternatives Considered

| Strategy | Direction | Benefit | Additional code or risk | Decision |
| --- | --- | --- | --- | --- |
| Insert predictions directly with custom tables | user-approved-divergence | Potentially faster bulk loading | Bypasses encryption/idempotency and requires migration/persistence duplication | rejected |
| Upload every window through the public HTTP API | preserve | Exercises authentication/routes | Adds token/session/network overhead and loses internal source metadata without API changes | rejected |

## 7. Modification Map and Change Budget

### Modification Map

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | production | `apps/backend/app/maintenance/import_alcohol_test.py` | `main` | add | `apps/backend/app/maintenance/purge_legacy_predictions.py::main` | Add exact discovery, parsing, run/account management, service ingestion, reporting, and CLI controls. | Implements FR-001 through FR-008 and NFR-001 through NFR-004. | WS1 | preserve |
| CH-002 | test | `apps/backend/tests/test_import_alcohol_test.py` | focused parser/import tests | add | `apps/backend/tests/test_sensor_routes_v25.py` | Test folder/header/window/time/ID/account/resume/alert behavior with fakes. | Proves AC-001 through AC-003 and AC-005. | WS1 | preserve |
| CH-003 | config | `apps/db/docker-compose.yml` | `alcohol-test-import` service | extend | `apps/db/docker-compose.yml::backend` | Add an opt-in profile reusing the backend image, environment, volumes, and read/write dataset mounts. | Implements FR-009 and preserves NFR-001. | WS2 | preserve |
| CH-004 | config | `.env.example` | `ALCOHOL_TEST_ACCOUNT_PASSWORD` | extend | `.env.example` | Document the required test-account password variable. | Implements FR-005 and FR-009. | WS2 | preserve |
| CH-005 | docs | `docs/deployment/alcohol-test-batch-import.md` | execution guide | add | `docs/deployment` | Add English prerequisites, validation, smoke/full/resume commands, outputs, and DB checks. | Implements FR-009 and proves AC-003 through AC-005. | WS2 | preserve |
| CH-006 | docs | `docs/deployment/alcohol-test-batch-import.ko.md` | Korean execution guide | add | `docs/deployment` | Add the synchronized Korean guide. | Implements FR-009. | WS2 | preserve |

### Change Budget

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 2 | 1 | 1 | 520 | None | None |
| WS2 | 4 | 0 | 0 | 0 | None | None |

The production-line budget is an expansion alarm, not a compression target.

## 8. Work Plan

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | Add and verify the resumable Alcohol_Test importer. | None | P1 | CH-001, CH-002 | `apps/backend/app/maintenance/import_alcohol_test.py`, `apps/backend/tests/test_import_alcohol_test.py` | Existing services, schemas, migrations, model files, unrelated changes | FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-007, FR-008, NFR-001, NFR-002, NFR-003, NFR-004, AC-001, AC-002, AC-003, AC-004, AC-005 | `python -m pytest tests/test_import_alcohol_test.py` | verified |
| WS2 | Add the opt-in Compose runner, environment contract, and bilingual guide. | None | P1 | CH-003, CH-004, CH-005, CH-006 | `apps/db/docker-compose.yml`, `.env.example`, `docs/deployment/alcohol-test-batch-import.md`, `docs/deployment/alcohol-test-batch-import.ko.md` | Backend source/tests, existing services, unrelated docs | FR-005, FR-009, NFR-001, NFR-004, AC-003, AC-004, AC-005 | `docker compose -f apps/db/docker-compose.yml --profile alcohol-test config` | verified |

### Parallelization Rationale

WS1 and WS2 use disjoint targets. The command/module name, CLI flags, mounts, and output paths are fixed in this reviewed spec, so no unstable shared interface remains.

### Final Integration

Run compileall, the focused and full backend tests, Compose config, validation-only import, three-window Docker smoke plus rerun count checks, then the full import when Docker and the password variable are available.

## 9. Validation, Rollout, and Risk

### Validation Plan

- `apps/backend/.venv/Scripts/python.exe -m compileall app tests`
- `apps/backend/.venv/Scripts/python.exe -m pytest tests/test_import_alcohol_test.py`
- Full backend pytest after the focused suite.
- `docker compose -f apps/db/docker-compose.yml --profile alcohol-test config`
- Validate-only, three-window smoke/replay, DB/report comparison, and full 100-account import.
- `git diff --check`

### Minimality and Style-Fidelity Review

- Confirm the importer calls existing account/repository/model/storage/service assets and does not create a second persistence path.
- Confirm every changed path is mapped and no dependency, migration, route, model, or unrelated formatting change appears.

### Rollout and Rollback

The profile is opt-in. Remove the importer service/command/docs to roll back code; imported test data remains subject to existing administrative deletion procedures.

### Risks and Mitigations

| Risk | Impact | Mitigation or Evidence |
| --- | --- | --- |
| Docker Desktop or PyTorch is unavailable | Integration import cannot run | Use Docker backend image; report the exact environmental blocker. |
| A source file changes after a partial run | Idempotency conflict | Stable run metadata and canonical UUID/checksum conflict fail visibly. |
| Full import consumes time and encrypted storage | Long-running local operation | Validate, smoke test, stream one file at a time, and support resume. |
| Timestamp rebasing obscures source time | Research traceability loss | Store original window timestamps and offset in `device_info` and reports. |

## 10. Revision and Progress

### Design Revision History

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-21T11:32:44+09:00 | user-approved-plan | Recorded the complete implementation design, repository reuse, exact targets, budgets, and validation. | D-001, D-002, D-003, D-004, D-005, D-006 | Q-001, Q-002, Q-003 |
| 2 | 2026-07-21T12:04:26+09:00 | repository-evidence-correction | Recorded five empty source windows, the 16,334 importable total, and partial-completion behavior. | D-007 | None |

### Implementation Progress Record

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-21T11:32:44+09:00 | 1 | - | ready | User explicitly requested implementation of the reviewed plan. |
| 2026-07-21T11:36:00+09:00 | 1 | WS1, WS2 | implementing | Validated spec; started the two target-disjoint slices. |
| 2026-07-21T12:04:26+09:00 | 2 | WS1 | verified | 16 focused tests and 175-test backend regression suite passed; real-data validation matched 100/16,339/five-empty. |
| 2026-07-21T12:04:26+09:00 | 2 | WS2 | verified | Compose profile config, normal service isolation, scope, patch budget, and bilingual guide review passed. |
| 2026-07-21T12:04:26+09:00 | 2 | - | complete | Docker daemon was unavailable, so live DB/model smoke and full import remain operator-run validation documented in the guide. |
