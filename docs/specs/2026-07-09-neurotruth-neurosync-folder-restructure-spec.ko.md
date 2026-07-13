# NeuroTruth NeuroSync-Style Folder Restructure Spec

- **Language role:** Korean mirror spec
- **Spec status:** Finalized
- **Spec version:** 1.2
- **Last updated:** 2026-07-09
- **English source:** `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-neurosync-folder-restructure-spec.md`
- **Korean mirror:** `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-neurosync-folder-restructure-spec.ko.md`
- **Requester / owner:** NeuroTruth project requester
- **Implementation status:** Superseded historical intermediate structure

> 현재 상태 메모 (2026-07-09): 이 문서는 히스토리용이며 `docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.md` version 1.2가 현재 우선 스펙입니다. 아래의 `apps/api`, `apps/ai-server`, `infra/*` 언급은 중간 NeuroSync-style restructure 기록이며 현재 active layout이 아닙니다.

## 0. Codex Implementation Handoff

이 섹션은 planning/spec refinement에서 implementation으로 넘어갈 때 Feature Planner workflow를 보존합니다.

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

복사해서 사용할 구현 프롬프트:

```text
$feature-planner Implement `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-neurosync-folder-restructure-spec.md` using IMPLEMENTATION_ORCHESTRATION. Use the English source spec as authoritative, keep `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-neurosync-folder-restructure-spec.ko.md` synchronized, spawn worker sub-agents for source-code edits, and have the main agent verify minimal diffs and update progress records. If the spec has a gap, use TARGETED_REFINEMENT for only that gap before continuing.
```

## 1. Summary

NeuroTruth를 서비스 중심 NeuroSync repository 형태에 맞게 재구성합니다. Runtime behavior, API contracts, Bedrock alert/chat behavior, Android behavior, model inference behavior는 변경하지 않아야 합니다. 구현은 기존 service folders를 `apps/*`로 이동하고, database/deployment assets를 `infra/*`로 이동하며, old root service folders를 제거하고, Docker, CI, README, Android docs, specs의 path references를 갱신합니다. 기존 uncommitted feature changes는 각 owning folder와 함께 보존해서 이동해야 합니다.

## 2. Goals

- G1. NeuroTruth service folders를 NeuroSync-style `apps/` layout으로 이동합니다.
- G2. Database 및 deployment configuration을 `infra/`로 이동합니다.
- G3. Commands, Docker compose paths, GitHub workflow path filters, docs를 새 구조에 맞게 업데이트합니다.
- G4. 기존 runtime behavior와 public API contracts를 보존합니다.
- G5. Contents 이동 후 old root service folders를 제거합니다.

## 3. Non-Goals

- NG1. 이번 pass에서는 root `package.json`, `pnpm-workspace.yaml`, Turborepo, 또는 `packages/shared-contracts`를 추가하지 않습니다.
- NG2. Path change가 요구하지 않는 한 Python imports, package names, Android package names, Docker images, service names, ports를 refactor하지 않습니다.
- NG3. Bedrock, alert rule, Postgres memory, Android chat, model inference behavior를 변경하지 않습니다.
- NG4. Old root service locations에 README stubs나 compatibility copies를 만들지 않습니다.

## 4. Users and Use Cases

### 4.1 Target Users

- Repository를 탐색하는 NeuroTruth developers.
- Docker compose deployments를 실행하는 operators.
- HealthSensorApp을 build하는 Android developers.

### 4.2 Primary Use Cases

- UC1. Developer가 backend, LLM server, web, mobile surfaces를 `apps/*` 아래에서 찾습니다.
- UC2. Operator가 `infra/deploy` 아래의 Docker compose files를 실행합니다.
- UC3. Developer가 `apps/mobile`에서 Android app을 build합니다.

## 5. Final Decisions

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | Service layout | `backend/`는 `apps/api/`, `llm-server/`는 `apps/ai-server/`, `frontend/`는 `apps/web/`, `wearable_sensor/HealthSensorApp/` contents는 `apps/mobile/`로 이동합니다. | User decision |
| D2 | Infra layout | `db/init.sql`은 `infra/db/init.sql`로, 두 Docker compose files는 `infra/deploy/`로 이동합니다. | User decision |
| D3 | Legacy paths | Contents 이동 후 old root service folders를 제거합니다. Stubs나 copies는 남기지 않습니다. | User decision |
| D4 | Monorepo tooling | 이번 pass에서는 root pnpm/Turborepo/shared-contracts tooling을 추가하지 않습니다. | User decision |
| D5 | Behavior | Repository paths 외에는 기존 API, LLM, model, Android, Docker service names, ports, command behavior를 보존합니다. | User decision |

## 6. Functional Requirements

- FR1. Repository root는 `apps/api`, `apps/ai-server`, `apps/web`, `apps/mobile`, `infra/db`, `infra/deploy`를 포함해야 합니다.
- FR2. Old root folders `backend`, `llm-server`, `frontend`, `wearable_sensor`, `db`는 move 후 남아 있으면 안 됩니다.
- FR3. Backend는 `apps/api`에서 계속 `uvicorn app.main:app`로 실행되어야 합니다.
- FR4. LLM server는 `apps/ai-server`에서 계속 `uvicorn app.main:app`로 실행되어야 합니다.
- FR5. Android Gradle root는 `apps/mobile`이어야 하며, `settings.gradle`, `gradlew.bat`, `app/`, `wearos/`를 포함해야 합니다.
- FR6. Docker compose files는 `infra/deploy` 기준의 올바른 relative paths로 moved services를 build해야 합니다.
- FR7. GitHub workflow path filters는 new paths 기준으로 trigger되어야 합니다.
- FR8. Documentation 및 existing specs는 active commands와 file locations에 대해 new paths를 참조해야 합니다.

## 7. User Experience / UI Requirements

- UI1. Not applicable; 이 변경은 product UI 변경이 없는 repository structure change입니다.

## 8. API / Data / State Requirements

- API1. Public API endpoint, payload, SSE event, Android sensor upload contract는 변경하면 안 됩니다.
- DATA1. Database schema behavior는 변경하면 안 됩니다. `init.sql` location만 변경합니다.
- STATE1. Application state, persisted data format, runtime session behavior는 변경하면 안 됩니다.

## 9. Permissions, Security, Privacy, and Audit

- SEC1. Credentials 또는 secrets를 repository에 추가하면 안 됩니다.
- PRIV1. Privacy-relevant logging 또는 persisted data behavior는 변경하면 안 됩니다.
- AUDIT1. Not applicable; audit behavior changes는 없습니다.

## 10. Error, Edge-Case, and Concurrency Behavior

- ERR1. Validation이 local `.env`, Android SDK, pytest, Docker credentials 누락 때문에만 실패하면 exact blocker를 기록하고 implementation을 보존합니다.
- EDGE1. Path searches는 revision history/spec progress notes의 historical references를 찾을 수 있습니다. Active commands와 current docs는 new paths를 사용해야 합니다.
- CONC1. Not applicable; 이 변경은 runtime concurrency를 변경하지 않습니다.

## 11. Dependencies and Configuration

- DEP1. New dependencies는 없습니다.
- DEP2. Compose `env_file` entries는 `infra/deploy`에서 root `.env`를 `../../.env`로 가리켜야 합니다.
- DEP3. Compose build contexts는 `infra/deploy`에서 moved service folders를 가리켜야 합니다.

## 12. Migration, Rollout, and Rollback

- MIG1. File move migration only; database 또는 runtime data migration은 없습니다.
- ROLL1. Merge 후 operators는 `docker compose -f infra/deploy/docker-compose.backend.yml ...`와 `docker compose -f infra/deploy/docker-compose.gpu.yml ...`를 사용해야 합니다.
- BACK1. Folder move commit을 revert해서 rollback합니다.

## 13. Implementation Boundaries

### 13.1 Expected Change Areas

- Repository layout under `apps/` and `infra/`.
- Docker compose files 및 GitHub workflow path filters.
- README, backend README, Android docs/API spec, active file paths 또는 commands를 언급하는 existing specs.

### 13.2 Forbidden Changes

- Runtime behavior, public APIs, model inference, Android package IDs, Docker service names, ports, dependencies, generated files를 변경하지 않습니다.
- `apps/`와 `infra/` 외의 root monorepo tooling을 추가하지 않습니다.
- 이전 NeuroTruth implementation의 existing uncommitted changes를 revert하거나 discard하지 않습니다.
- Unrelated refactors, formatting churn, dependency upgrades, broad rewrites, generated-file churn은 금지합니다.

### 13.3 Minimal-Change Guidance

- Content 보존을 위해 `git mv` 또는 equivalent moves를 선호합니다.
- New layout에 필요한 path references만 업데이트합니다.
- Old path mentions는 historical하고 active instructions가 아닌 경우에만 남길 수 있습니다.

## 14. Acceptance Criteria

- AC1. Repository root에서 top-level folders를 listing하면 `apps`와 `infra`가 존재하고 old root service folders가 없습니다.
- AC2. `apps/api`에서 Python compile validation을 실행하면 backend modules가 import-path changes 없이 compile됩니다.
- AC3. `apps/ai-server`에서 Python compile validation을 실행하면 LLM server modules가 import-path changes 없이 compile됩니다.
- AC4. `apps/mobile` Android build validation은 현재 unified backend/web/mobile/db spec에 기록하며 최신 로컬 환경에서 PASS입니다.
- AC5. `.env`가 있을 때 `infra/deploy`의 Docker compose config가 build contexts와 init SQL paths를 moved locations로 resolve합니다.
- AC6. Active documentation 및 workflow files를 old paths로 검색하면 active commands는 new paths를 사용합니다.

## 15. Validation Plan

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Spec pair | `python C:\Users\NeuroAI-Laptop\.codex\skills\feature-planner\scripts\validate_spec_pair.py docs\specs\2026-07-09-neurotruth-neurosync-folder-restructure-spec.md` | PASS |
| Backend compile | `python -m compileall app tests` from `apps/api` | PASS |
| LLM compile | `python -m compileall app tests` from `apps/ai-server` | PASS |
| Python tests | `python -m pytest` from `apps/api` and `apps/ai-server` when pytest is available | PASS 또는 unavailable reason recorded |
| Android build | `.\gradlew.bat assembleDebug` from `apps/mobile` | Latest unified validation에서 PASS |
| Docker config | `docker compose -f infra/deploy/docker-compose.backend.yml config` and `docker compose -f infra/deploy/docker-compose.gpu.yml config` | PASS 또는 missing `.env` reason recorded |
| Path scan | `rg -n "backend|llm-server|frontend|wearable_sensor|db/init.sql|docker-compose.backend.yml|docker-compose.gpu.yml"` | Only historical references or intentionally documented command names remain |
| Git hygiene | `git diff --check` and top-level folder check | PASS |

## 16. Risks and Open Notes

- RISK1. 이 repository에는 이미 uncommitted feature changes가 있습니다. Move는 이 edits를 보존해야 합니다.
- RISK2. 현재 Android/Docker/pytest validation은 unified backend/web/mobile/db spec에 기록합니다.
- RISK3. Path scans는 spec progress records의 historical references를 포함할 수 있습니다. 수동 review가 필요합니다.

## 17. Implementation Checklist / Progress Record

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Create and validate restructure spec pair | Main | Complete | `docs/specs/...neurosync-folder-restructure...` | `validate_spec_pair.py`: PASS | Source moves 전에 spec pair를 생성했습니다. |
| P2 | Move folders and update active path references | Worker | Complete | `apps/*`, `infra/*`, `.github/workflows/*`, `.gitignore`, `README.md`, existing specs/docs | Worker checks PASS; Android/pytest/exact Docker config environment-limited | Old root service folders를 제거했고 moved tracked latency file을 trackable 상태로 복원했습니다. |
| P3 | Main verification and progress sync | Main | Complete | `docs/specs/...neurosync-folder-restructure...` | Historical check recorded; current validation moved to unified spec | 이 intermediate layout은 backend/web/mobile/db consolidation으로 superseded되었습니다. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.0 | 2026-07-09 | Feature Planner | NeuroSync-style folder restructure를 위한 initial finalized spec pair. |
| 1.1 | 2026-07-09 | Feature Planner | 구현 완료 및 environment-limited verification 결과를 기록했습니다. |
| 1.2 | 2026-07-09 | Codex | Superseded 상태를 명시하고 current validation을 unified backend/web/mobile/db spec으로 이동했습니다. |
