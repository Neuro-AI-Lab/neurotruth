# NeuroTruth Unified Backend App Structure Spec

- **Language role:** English source spec
- **Spec status:** Finalized
- **Spec version:** 1.2
- **Last updated:** 2026-07-09
- **English source:** `docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.md`
- **Korean mirror:** `docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.ko.md`
- **Requester / owner:** NeuroTruth project owner
- **Implementation status:** Implemented and locally verified

## 0. Codex Implementation Handoff

This section preserves the Feature Planner workflow when moving from planning/spec refinement to implementation.

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.md
korean_mirror_spec: docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.ko.md
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
$feature-planner Implement `docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.md` using IMPLEMENTATION_ORCHESTRATION. Use the English source spec as authoritative, keep `docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.ko.md` synchronized, spawn worker sub-agents for source-code edits, and have the main agent verify minimal diffs and update progress records. If the spec has a gap, use TARGETED_REFINEMENT for only that gap before continuing.
```

## 1. Summary

NeuroTruth must be consolidated from the temporary NeuroSync-like split into a simpler 3/4-service application layout: `apps/backend`, `apps/web`, `apps/mobile`, and `apps/db`. The separate `apps/ai-server` service is removed because Bedrock is an external AWS runtime and does not require a local GPU or independent service boundary. Bedrock agent code moves into the backend as an internal module, while the local craving targeting RandomForest model remains owned by backend under `apps/backend/model/weights`. Android-facing APIs and RF inference behavior must remain compatible.

## 2. Goals

- G1. Expose a clear final repository structure with `backend`, `web`, `mobile`, and `db` app units.
- G2. Remove unnecessary AI/GPU service deployment while preserving Bedrock-powered intervention behavior.
- G3. Keep the local craving classifier and model artifact path under backend ownership.
- G4. Update Docker, CI, docs, tests, and environment references to the unified structure.

## 3. Non-Goals

- NG1. Do not retrain, replace, or behaviorally modify the RandomForest craving inference model.
- NG2. Do not change Android public API paths or mobile package IDs.
- NG3. Do not add microphone, STT, local LLM, GPU, or new model-provider behavior.
- NG4. Private-repo tracking of model binaries, local config, and Samsung SDK AAR/JAR files is allowed when the team intentionally includes them; review before any public mirror.

## 4. Users and Use Cases

### 4.1 Target Users

- Project developers preparing NeuroTruth for GitHub upload and local deployment.
- Demo operators running backend, web, mobile, and Postgres locally or on a server.

### 4.2 Primary Use Cases

- UC1. A developer can understand the repo as `apps/backend`, `apps/web`, `apps/mobile`, and `apps/db`.
- UC2. A backend operator can run prediction, alerting, Bedrock intervention, and handoff behavior from one backend process.
- UC3. Android can keep using existing sensor, SSE, chat, slot, and handoff endpoints without code changes.

## 5. Final Decisions

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | Service layout | Use `apps/backend`, `apps/web`, `apps/mobile`, and `apps/db`; remove `apps/ai-server`. | User decision |
| D2 | DB location | Store Postgres schema and Docker Compose deployment assets under `apps/db`. | User decision |
| D3 | AI integration | Bedrock agent code runs inside backend, not as an HTTP microservice. | User decision |
| D4 | Model ownership | The local craving targeting model remains under backend at `apps/backend/model/weights/rf_dependent.joblib`. | User decision |
| D5 | Compatibility | Keep existing Android-facing API paths and response semantics. | User decision |
| D6 | Historical docs | Existing historical specs may keep old paths if clearly historical or superseded. | Agent default |

## 6. Functional Requirements

- FR1. Move `apps/api` to `apps/backend` and update all active path references accordingly.
- FR2. Move `apps/ai-server/app/bedrock_agents.py` into backend as `app/ai/bedrock_agents.py`.
- FR3. Delete the standalone AI server app, Dockerfile, requirements, README, tests, GPU compose, and GPU deployment workflow.
- FR4. Backend intervention routes must call internal Bedrock helper functions directly instead of calling `LLM_SERVER_URL`.
- FR5. Keep `/api/llm/chat` as a compatibility wrapper that calls backend-internal Bedrock chat behavior.
- FR6. Move `infra/db/init.sql` to `apps/db/init.sql` and create `apps/db/docker-compose.yml`.
- FR7. The new compose file must define `db`, `backend`, and `web` services with build contexts `../backend` and `../web`, and Postgres init mount `./init.sql`.
- FR8. Add `boto3` to backend runtime dependencies.
- FR9. Remove active `LLM_SERVER_URL` and `LLM_API_KEY` usage for internal backend AI calls.

## 7. User Experience / UI Requirements

- UI1. Android endpoint contracts must not change.
- UI2. Phone and watch intervention behavior must remain compatible with existing backend endpoints; the mobile UI has since been reconstructed from the working `watch_test` app environment.
- UI3. Web UI behavior is not changed by this restructure.

## 8. API / Data / State Requirements

- API1. Preserve `POST /sensor-window` and `GET /prediction-stream`.
- API2. Preserve `POST /api/intervention/chat`, `POST /api/intervention/slots`, and `POST /api/intervention/handoff`.
- API3. Preserve `/api/llm/chat` compatibility, but implement it without an external LLM server.
- DATA1. Preserve existing Postgres tables and idempotent schema creation.
- DATA2. Preserve current craving slot keys and handoff report persistence.
- STATE1. Preserve existing backend memory/session behavior.

## 9. Permissions, Security, Privacy, and Audit

- SEC1. Bedrock credentials must continue to come from `AWS_BEARER_TOKEN_BEDROCK`, IAM role, AWS profile, or standard AWS environment variables.
- SEC2. This project is currently prepared for private GitHub use, so `.env`, local model weights, Android `local.properties`, and Samsung SDK AAR/JAR files may be tracked if intentionally reviewed by the team. Do not mirror them to a public repository without a separate review.
- PRIV1. Do not expand stored personal data beyond the existing session, prediction, alert, turn, slot, and handoff records.
- AUDIT1. No new audit log surface is required.

## 10. Error, Edge-Case, and Concurrency Behavior

- ERR1. Bedrock failures from internal backend AI calls should return the same broad client-facing failure class currently used for LLM failures, using 502 for request/provider failures.
- ERR2. Prediction model unavailable behavior must remain unchanged.
- EDGE1. If slot extraction returns invalid JSON or unsupported keys, existing filtering and missing-slot behavior must continue to apply.
- CONC1. No new concurrency model is introduced; backend continues to own one prediction service and async route handlers.

## 11. Dependencies and Configuration

- DEP1. Add `boto3==1.34.113` or the existing AI server's pinned boto3 version to backend requirements.
- DEP2. Keep `AWS_BEARER_TOKEN_BEDROCK`, `BEDROCK_MODEL_ID`, `AWS_REGION`, and `AWS_DEFAULT_REGION` as backend environment variables.
- DEP3. Remove active `LLM_SERVER_URL` from `.env.example`, compose, and docs.
- DEP4. `LLM_API_KEY` is no longer required for backend-to-AI internal calls; remove active examples unless a historical note explicitly marks it obsolete.
- DEP5. Keep `MODEL_PATH` optional; default remains backend container path `/app/model/weights/rf_dependent.joblib`.

## 12. Migration, Rollout, and Rollback

- MIG1. No database data migration is required beyond moving `init.sql`; schema contents remain compatible.
- ROLL1. Operators should run Docker from `apps/db/docker-compose.yml` after this change.
- ROLL2. GitHub deployment should trigger from `apps/backend/**`, `apps/web/**`, and `apps/db/**`.
- BACK1. Rollback is a git revert of this consolidation commit plus restoring the previous compose/workflow paths.

## 13. Implementation Boundaries

### 13.1 Expected Change Areas

- Repository layout: `apps/backend`, `apps/db`, removal of `apps/ai-server` and `infra`.
- Backend: route integration, AI helper module import, requirements, tests.
- Deployment: DB-centered compose and backend workflow path filters.
- Documentation: README, GitHub upload notes, PRD/PLAN, AI docs, backend/web/db docs, superseding spec notes.

### 13.2 Forbidden Changes

- Do not change RandomForest feature extraction, model prediction math, class labels, or SSE prediction semantics.
- Do not change Android source behavior unless a path reference in documentation requires it.
- Do not require public vendoring of model binaries or Samsung SDK binaries; private-repo tracking is allowed when intentionally reviewed.
- Do not keep active `apps/api`, `apps/ai-server`, `infra/deploy`, or GPU deployment commands in non-historical docs.
- No unrelated refactors, formatting churn, dependency upgrades beyond required `boto3`, broad rewrites, or generated-file churn.

### 13.3 Minimal-Change Guidance

- Reuse `bedrock_agents.py` with the smallest import-path adjustment.
- Keep existing backend request/response models and helper names where practical.
- Keep public endpoints backward-compatible and adjust tests around internal call boundaries.

## 14. Acceptance Criteria

- AC1. Given the repository root, `apps/backend`, `apps/web`, `apps/mobile`, and `apps/db` exist, and `apps/api`, `apps/ai-server`, and `infra` do not remain as active service folders.
- AC2. Given backend dependencies are installed, `python -m compileall app tests` from `apps/backend` succeeds.
- AC3. Given backend tests can run, intervention tests mock internal Bedrock helpers/adapters instead of an external `_post_llm` HTTP proxy.
- AC4. Given Docker is available, `docker compose -f apps/db/docker-compose.yml config --no-env-resolution` succeeds.
- AC5. Given root `.env` exists, the compose file resolves `db`, `backend`, and `web` using the new paths.
- AC6. Given Android calls existing endpoints, no endpoint path changes are required.
- AC7. Given a path scan for active references to `apps/api`, `apps/ai-server`, `llm-server`, `LLM_SERVER_URL`, `docker-compose.gpu`, `deploy-gpu`, `infra/db`, and `infra/deploy`, only historical/superseded docs may remain.
- AC8. Given `git diff --check`, no whitespace errors are reported.

## 15. Validation Plan

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Backend compile | `python -m compileall app tests` from `apps/backend` | PASS |
| Backend tests | `python -m pytest` from `apps/backend` if pytest is available | PASS or unavailable reason recorded |
| Docker config | `docker compose -f apps/db/docker-compose.yml config --no-env-resolution` | PASS |
| Android build | `apps/mobile/gradlew.bat assembleDebug` | PASS or SDK/AAR missing reason recorded |
| Path hygiene | `rg -n "apps/api|apps/ai-server|llm-server|LLM_SERVER_URL|docker-compose.gpu|deploy-gpu|infra/db|infra/deploy"` | Only historical/superseded references remain |
| Diff hygiene | `git diff --check` | PASS |

## 16. Risks and Open Notes

- RISK1. Moving large untracked service folders may appear as delete/add until staged with rename detection; verify content rather than relying on status alone.
- RISK2. Local Bedrock calls cannot be fully smoke-tested without `AWS_BEARER_TOKEN_BEDROCK` or AWS credentials; mocked tests and compile checks are required.
- RISK3. Android build was previously blocked by local SDK/JDK/AAR setup; the current local environment now builds and installs successfully.

## 17. Implementation Checklist / Progress Record

This section is updated by the main orchestrating agent during implementation.

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Finalize spec pair | Main | Complete | `docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.md`, `docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.ko.md` | Pending validation | Created implementation-ready spec pair. |
| P2 | Source restructure and backend AI integration | Main after worker limit | Complete | `apps/backend/**`, removed `apps/ai-server/**` | `python -m compileall app tests`: PASS; `python -m pytest`: PASS | Backend now owns Bedrock helpers under `app/ai`; intervention routes call internal helpers; Bedrock uses the Runtime `converse` API with a bearer header when `AWS_BEARER_TOKEN_BEDROCK` is provided. |
| P3 | Documentation, compose, CI, and path hygiene | Main after worker limit | Complete | `apps/db/**`, `.github/workflows/deploy-backend.yml`, `.env.example`, `README.md`, active docs | `docker compose -f apps/db/docker-compose.yml config`: PASS; `--no-env-resolution`: PASS | DB compose owns `db`, `backend`, `web`; active docs use unified layout and document bearer-token Bedrock testing. |
| P4 | Main verification and progress update | Main | Complete | Spec progress records | `git diff --check`: PASS; Android build: PASS; Android unit tests: PASS; phone/watch install: PASS | Worker sub-agent failed due usage limit, so main completed implementation directly and recorded the deviation. |
| P5 | Mobile reconstruction from `watch_test` | Main | Complete | `apps/mobile/**`, mobile docs | `apps/mobile/gradlew.bat assembleDebug`: PASS; `apps/mobile/gradlew.bat testDebugUnitTest`: PASS | Restored phone user dashboard, developer mode, state-check, background monitoring, notifications, watch UI, vibration/notification behavior, and kept NeuroTruth alert/chat/handoff integration. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.2 | 2026-07-09 | Codex | Updated implementation status, Android verification, private-repo artifact policy, and mobile reconstruction progress. |
| 1.1 | 2026-07-09 | Codex | Added AWS Bedrock bearer-token configuration and backend `converse` API note. |
| 1.0 | 2026-07-09 | Feature Planner | Initial finalized spec pair. |
