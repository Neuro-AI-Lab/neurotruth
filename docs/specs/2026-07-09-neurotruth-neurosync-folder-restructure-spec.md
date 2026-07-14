# NeuroTruth NeuroSync-Style Folder Restructure Spec

- **Language role:** English source spec
- **Spec status:** Finalized
- **Spec version:** 1.2
- **Last updated:** 2026-07-09
- **English source:** `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-neurosync-folder-restructure-spec.md`
- **Korean mirror:** `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-neurosync-folder-restructure-spec.ko.md`
- **Requester / owner:** NeuroTruth project requester
- **Implementation status:** Superseded historical intermediate structure

> Current status note (2026-07-09): This spec is historical and has been superseded by `docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.md` version 1.2. Old `apps/api`, `apps/ai-server`, and `infra/*` references below describe the intermediate NeuroSync-style restructure, not the current active layout.

## 0. Codex Implementation Handoff

This section preserves the Feature Planner workflow when moving from planning/spec refinement to implementation.

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-neurosync-folder-restructure-spec.md
korean_mirror_spec: C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-neurosync-folder-restructure-spec.ko.md
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
$feature-planner Implement `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-neurosync-folder-restructure-spec.md` using IMPLEMENTATION_ORCHESTRATION. Use the English source spec as authoritative, keep `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-neurosync-folder-restructure-spec.ko.md` synchronized, spawn worker sub-agents for source-code edits, and have the main agent verify minimal diffs and update progress records. If the spec has a gap, use TARGETED_REFINEMENT for only that gap before continuing.
```

## 1. Summary

Reorganize NeuroTruth to match the service-centered NeuroSync repository shape. Runtime behavior, API contracts, Bedrock alert/chat behavior, Android behavior, and model inference behavior must remain unchanged. The implementation moves existing service folders into `apps/*`, moves database/deployment assets into `infra/*`, removes the old root service folders, and updates path references in Docker, CI, README, Android docs, and specs. Existing uncommitted feature changes must be preserved and moved with their owning folders.

## 2. Goals

- G1. Move NeuroTruth service folders into a NeuroSync-style `apps/` layout.
- G2. Move database and deployment configuration into `infra/`.
- G3. Update commands, Docker compose paths, GitHub workflow path filters, and docs so the repository works from the new structure.
- G4. Preserve all existing runtime behavior and public API contracts.
- G5. Remove old root service folders after moving their contents.

## 3. Non-Goals

- NG1. Do not add root `package.json`, `pnpm-workspace.yaml`, Turborepo, or `packages/shared-contracts` in this pass.
- NG2. Do not refactor Python imports, package names, Android package names, Docker images, service names, or ports unless a path change requires it.
- NG3. Do not change Bedrock, alert rule, Postgres memory, Android chat, or model inference behavior.
- NG4. Do not create README stubs or compatibility copies in old root service locations.

## 4. Users and Use Cases

### 4.1 Target Users

- NeuroTruth developers navigating the repository.
- Operators running Docker compose deployments.
- Android developers building the HealthSensorApp.

### 4.2 Primary Use Cases

- UC1. A developer finds backend, LLM server, web, and mobile surfaces under `apps/*`.
- UC2. An operator runs Docker compose files under `infra/deploy`.
- UC3. A developer builds the Android app from `apps/mobile`.

## 5. Final Decisions

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | Service layout | Move `backend/` to `apps/api/`, `llm-server/` to `apps/ai-server/`, `frontend/` to `apps/web/`, and `wearable_sensor/HealthSensorApp/` contents to `apps/mobile/`. | User decision |
| D2 | Infra layout | Move `db/init.sql` to `infra/db/init.sql` and both Docker compose files to `infra/deploy/`. | User decision |
| D3 | Legacy paths | Remove old root service folders after moving contents; do not leave stubs or copies. | User decision |
| D4 | Monorepo tooling | Do not add root pnpm/Turborepo/shared-contracts tooling in this pass. | User decision |
| D5 | Behavior | Preserve existing API, LLM, model, Android, Docker service names, ports, and command behavior except for repository paths. | User decision |

## 6. Functional Requirements

- FR1. The repository root must contain `apps/api`, `apps/ai-server`, `apps/web`, `apps/mobile`, `infra/db`, and `infra/deploy`.
- FR2. The old root folders `backend`, `llm-server`, `frontend`, `wearable_sensor`, and `db` must not remain after the move.
- FR3. Backend must still run as `uvicorn app.main:app` from `apps/api`.
- FR4. LLM server must still run as `uvicorn app.main:app` from `apps/ai-server`.
- FR5. Android Gradle root must be `apps/mobile`, containing `settings.gradle`, `gradlew.bat`, `app/`, and `wearos/`.
- FR6. Docker compose files must build the moved services using correct relative paths from `infra/deploy`.
- FR7. GitHub workflow path filters must trigger on the new paths.
- FR8. Documentation and existing specs must reference the new paths for active commands and file locations.

## 7. User Experience / UI Requirements

- UI1. Not applicable; this is a repository structure change with no product UI change.

## 8. API / Data / State Requirements

- API1. No public API endpoint, payload, SSE event, or Android sensor upload contract may change.
- DATA1. No database schema behavior may change; only `init.sql` location changes.
- STATE1. No application state, persisted data format, or runtime session behavior may change.

## 9. Permissions, Security, Privacy, and Audit

- SEC1. No credentials or secrets may be added to the repository.
- PRIV1. No privacy-relevant logging or persisted data behavior may change.
- AUDIT1. Not applicable; no audit behavior changes.

## 10. Error, Edge-Case, and Concurrency Behavior

- ERR1. If validation fails only because `.env`, Android SDK, pytest, or Docker credentials are missing locally, record the exact blocker and preserve the implementation.
- EDGE1. Path searches may still find historical references in revision history/spec progress notes; active commands and current docs must use new paths.
- CONC1. Not applicable; this change does not alter runtime concurrency.

## 11. Dependencies and Configuration

- DEP1. No new dependencies.
- DEP2. Compose `env_file` entries must point from `infra/deploy` to root `.env` as `../../.env`.
- DEP3. Compose build contexts must point from `infra/deploy` to moved service folders.

## 12. Migration, Rollout, and Rollback

- MIG1. File move migration only; no database or runtime data migration.
- ROLL1. After merge, operators must use `docker compose -f infra/deploy/docker-compose.backend.yml ...` and `docker compose -f infra/deploy/docker-compose.gpu.yml ...`.
- BACK1. Roll back by reverting the folder move commit.

## 13. Implementation Boundaries

### 13.1 Expected Change Areas

- Repository layout under `apps/` and `infra/`.
- Docker compose files and GitHub workflow path filters.
- README, backend README, Android docs/API spec, and existing specs that mention active file paths or commands.

### 13.2 Forbidden Changes

- Do not change runtime behavior, public APIs, model inference, Android package IDs, Docker service names, ports, dependencies, or generated files.
- Do not add root monorepo tooling beyond `apps/` and `infra/`.
- Do not revert or discard existing uncommitted changes from the prior NeuroTruth implementation.
- No unrelated refactors, formatting churn, dependency upgrades, broad rewrites, or generated-file churn.

### 13.3 Minimal-Change Guidance

- Prefer `git mv` or equivalent moves that preserve content.
- Update only path references required by the new layout.
- Keep old path mentions only when they are clearly historical and not active instructions.

## 14. Acceptance Criteria

- AC1. Given the repository root, when listing top-level folders, then `apps` and `infra` exist and old root service folders are absent.
- AC2. Given `apps/api`, when running Python compile validation, backend modules compile without import-path changes.
- AC3. Given `apps/ai-server`, when running Python compile validation, LLM server modules compile without import-path changes.
- AC4. Given `apps/mobile`, Android build validation is now tracked in the unified backend/web/mobile/db spec and passes in the latest local environment.
- AC5. Given Docker compose config from `infra/deploy`, when `.env` exists, compose resolves build contexts and init SQL paths to the moved locations.
- AC6. Given active documentation and workflow files, when searching for old paths, then active commands use new paths.

## 15. Validation Plan

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Spec pair | `python C:\Users\NeuroAI-Laptop\.codex\skills\feature-planner\scripts\validate_spec_pair.py docs\specs\2026-07-09-neurotruth-neurosync-folder-restructure-spec.md` | PASS |
| Backend compile | `python -m compileall app tests` from `apps/api` | PASS |
| LLM compile | `python -m compileall app tests` from `apps/ai-server` | PASS |
| Python tests | `python -m pytest` from `apps/api` and `apps/ai-server` when pytest is available | PASS or unavailable reason recorded |
| Android build | `.\gradlew.bat assembleDebug` from `apps/mobile` | PASS in latest unified validation |
| Docker config | `docker compose -f infra/deploy/docker-compose.backend.yml config` and `docker compose -f infra/deploy/docker-compose.gpu.yml config` | PASS or missing `.env` reason recorded |
| Path scan | `rg -n "backend|llm-server|frontend|wearable_sensor|db/init.sql|docker-compose.backend.yml|docker-compose.gpu.yml"` | Only historical references or intentionally documented command names remain |
| Git hygiene | `git diff --check` and top-level folder check | PASS |

## 16. Risks and Open Notes

- RISK1. This repository already has uncommitted feature changes; the move must preserve those edits.
- RISK2. Current Android/Docker/pytest validation is now tracked in the unified backend/web/mobile/db spec.
- RISK3. Path scans may include historical references in spec progress records; review them manually.

## 17. Implementation Checklist / Progress Record

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Create and validate restructure spec pair | Main | Complete | `docs/specs/...neurosync-folder-restructure...` | `validate_spec_pair.py`: PASS | Spec pair created before source moves. |
| P2 | Move folders and update active path references | Worker | Complete | `apps/*`, `infra/*`, `.github/workflows/*`, `.gitignore`, `README.md`, existing specs/docs | Worker checks PASS; Android/pytest/exact Docker config environment-limited | Old root service folders removed; moved tracked latency file restored as trackable. |
| P3 | Main verification and progress sync | Main | Complete | `docs/specs/...neurosync-folder-restructure...` | Historical check recorded; current validation moved to unified spec | This intermediate layout was superseded by the backend/web/mobile/db consolidation. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.0 | 2026-07-09 | Feature Planner | Initial finalized spec pair for NeuroSync-style folder restructure. |
| 1.1 | 2026-07-09 | Feature Planner | Recorded implementation completion and environment-limited verification results. |
| 1.2 | 2026-07-09 | Codex | Marked as superseded and redirected current validation to the unified backend/web/mobile/db spec. |
