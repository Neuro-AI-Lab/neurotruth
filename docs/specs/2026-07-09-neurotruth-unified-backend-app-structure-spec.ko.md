# NeuroTruth 통합 Backend 앱 구조 스펙

- **Language role:** Korean mirror spec
- **Spec status:** Finalized
- **Spec version:** 1.2
- **Last updated:** 2026-07-09
- **English source:** `docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.md`
- **Korean mirror:** `docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.ko.md`
- **Requester / owner:** NeuroTruth project owner
- **Implementation status:** Implemented and locally verified

## 0. Codex Implementation Handoff

이 섹션은 계획/스펙 정리에서 구현으로 넘어갈 때 Feature Planner 워크플로를 보존합니다.

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

복사해서 사용할 구현 프롬프트:

```text
$feature-planner Implement `docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.md` using IMPLEMENTATION_ORCHESTRATION. Use the English source spec as authoritative, keep `docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.ko.md` synchronized, spawn worker sub-agents for source-code edits, and have the main agent verify minimal diffs and update progress records. If the spec has a gap, use TARGETED_REFINEMENT for only that gap before continuing.
```

## 1. Summary

NeuroTruth는 임시 NeuroSync식 분리 구조에서 더 단순한 3/4-service 앱 구조인 `apps/backend`, `apps/web`, `apps/mobile`, `apps/db`로 통합되어야 합니다. Bedrock은 외부 AWS runtime이고 로컬 GPU나 독립 서비스 경계가 필요하지 않으므로 별도 `apps/ai-server` 서비스는 제거합니다. Bedrock agent 코드는 backend 내부 모듈로 이동하고, 로컬 craving targeting RandomForest 모델은 `apps/backend/model/weights` 아래 backend 소유로 유지합니다. Android-facing APIs와 RF inference behavior는 호환성을 유지해야 합니다.

## 2. Goals

- G1. `backend`, `web`, `mobile`, `db` 앱 단위로 명확한 최종 repository structure를 제공합니다.
- G2. Bedrock-powered intervention behavior를 유지하면서 불필요한 AI/GPU service deployment를 제거합니다.
- G3. 로컬 craving classifier와 model artifact path를 backend 소유로 유지합니다.
- G4. Docker, CI, docs, tests, environment references를 통합 구조로 갱신합니다.

## 3. Non-Goals

- NG1. RandomForest craving inference model을 재학습, 교체, 또는 동작 변경하지 않습니다.
- NG2. Android public API paths 또는 mobile package IDs를 변경하지 않습니다.
- NG3. microphone, STT, local LLM, GPU, 또는 새 model-provider behavior를 추가하지 않습니다.
- NG4. Private repo에서 팀이 의도적으로 포함하기로 한 model binaries, local config, Samsung SDK AAR/JAR files는 추적할 수 있습니다. Public mirror 전에는 별도 검토가 필요합니다.

## 4. Users and Use Cases

### 4.1 Target Users

- GitHub 업로드와 로컬 배포를 준비하는 project developers.
- backend, web, mobile, Postgres를 로컬 또는 서버에서 실행하는 demo operators.

### 4.2 Primary Use Cases

- UC1. 개발자는 repo를 `apps/backend`, `apps/web`, `apps/mobile`, `apps/db`로 이해할 수 있습니다.
- UC2. backend operator는 prediction, alerting, Bedrock intervention, handoff behavior를 하나의 backend process에서 실행할 수 있습니다.
- UC3. Android는 코드 변경 없이 기존 sensor, SSE, chat, slot, handoff endpoints를 계속 사용할 수 있습니다.

## 5. Final Decisions

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | Service layout | `apps/backend`, `apps/web`, `apps/mobile`, `apps/db`를 사용하고 `apps/ai-server`를 제거합니다. | User decision |
| D2 | DB location | Postgres schema와 Docker Compose deployment assets는 `apps/db` 아래에 둡니다. | User decision |
| D3 | AI integration | Bedrock agent code는 HTTP microservice가 아니라 backend 내부에서 실행됩니다. | User decision |
| D4 | Model ownership | 로컬 craving targeting model은 `apps/backend/model/weights/rf_dependent.joblib`에 backend 소유로 남습니다. | User decision |
| D5 | Compatibility | 기존 Android-facing API paths와 response semantics를 유지합니다. | User decision |
| D6 | Historical docs | 기존 historical specs는 old paths를 유지할 수 있지만 historical 또는 superseded임이 명확해야 합니다. | Agent default |

## 6. Functional Requirements

- FR1. `apps/api`를 `apps/backend`로 이동하고 모든 active path references를 갱신해야 합니다.
- FR2. `apps/ai-server/app/bedrock_agents.py`를 backend의 `app/ai/bedrock_agents.py`로 이동해야 합니다.
- FR3. standalone AI server app, Dockerfile, requirements, README, tests, GPU compose, GPU deployment workflow를 삭제해야 합니다.
- FR4. Backend intervention routes는 `LLM_SERVER_URL` 호출 대신 internal Bedrock helper functions를 직접 호출해야 합니다.
- FR5. `/api/llm/chat`는 backend-internal Bedrock chat behavior를 호출하는 compatibility wrapper로 유지해야 합니다.
- FR6. `infra/db/init.sql`을 `apps/db/init.sql`로 이동하고 `apps/db/docker-compose.yml`을 생성해야 합니다.
- FR7. 새 compose file은 `db`, `backend`, `web` services를 정의하고 build contexts는 `../backend`, `../web`, Postgres init mount는 `./init.sql`이어야 합니다.
- FR8. `boto3`를 backend runtime dependencies에 추가해야 합니다.
- FR9. internal backend AI calls를 위한 active `LLM_SERVER_URL`와 `LLM_API_KEY` usage를 제거해야 합니다.

## 7. User Experience / UI Requirements

- UI1. Android endpoint contract는 변경하지 않습니다.
- UI2. Phone과 watch intervention behavior는 기존 backend endpoints와 호환되어야 하며, mobile UI는 이후 working `watch_test` 앱 환경을 기반으로 재구성되었습니다.
- UI3. Web UI behavior는 이 restructure로 변경하지 않습니다.

## 8. API / Data / State Requirements

- API1. `POST /sensor-window`와 `GET /prediction-stream`을 보존해야 합니다.
- API2. `POST /api/intervention/chat`, `POST /api/intervention/slots`, `POST /api/intervention/handoff`를 보존해야 합니다.
- API3. `/api/llm/chat` compatibility를 보존하되 외부 LLM server 없이 구현해야 합니다.
- DATA1. 기존 Postgres tables와 idempotent schema creation을 보존해야 합니다.
- DATA2. 현재 craving slot keys와 handoff report persistence를 보존해야 합니다.
- STATE1. 기존 backend memory/session behavior를 보존해야 합니다.

## 9. Permissions, Security, Privacy, and Audit

- SEC1. Bedrock credentials는 계속 `AWS_BEARER_TOKEN_BEDROCK`, IAM role, AWS profile, 또는 standard AWS environment variables에서 가져와야 합니다.
- SEC2. 현재 프로젝트는 private GitHub 사용을 기준으로 준비되어 있으므로 `.env`, local model weights, Android `local.properties`, Samsung SDK AAR/JAR files는 팀 검토 후 추적할 수 있습니다. Public repository로 mirror할 때는 별도 검토 없이 포함하지 않습니다.
- PRIV1. 기존 session, prediction, alert, turn, slot, handoff records 외의 stored personal data를 확장하지 않습니다.
- AUDIT1. 새 audit log surface는 필요하지 않습니다.

## 10. Error, Edge-Case, and Concurrency Behavior

- ERR1. Internal backend AI calls에서 Bedrock failure가 발생하면 현재 LLM failures와 같은 broad client-facing failure class를 유지하고 request/provider failures에는 502를 사용해야 합니다.
- ERR2. Prediction model unavailable behavior는 변경하지 않습니다.
- EDGE1. Slot extraction이 invalid JSON 또는 unsupported keys를 반환해도 기존 filtering과 missing-slot behavior가 계속 적용되어야 합니다.
- CONC1. 새 concurrency model은 도입하지 않으며 backend는 계속 하나의 prediction service와 async route handlers를 소유합니다.

## 11. Dependencies and Configuration

- DEP1. `boto3==1.34.113` 또는 기존 AI server의 pinned boto3 version을 backend requirements에 추가합니다.
- DEP2. `AWS_BEARER_TOKEN_BEDROCK`, `BEDROCK_MODEL_ID`, `AWS_REGION`, `AWS_DEFAULT_REGION`는 backend environment variables로 유지합니다.
- DEP3. `.env.example`, compose, docs에서 active `LLM_SERVER_URL`를 제거합니다.
- DEP4. `LLM_API_KEY`는 backend-to-AI internal calls에 더 이상 필요하지 않으므로 obsolete로 명시된 historical note가 아닌 active examples에서는 제거합니다.
- DEP5. `MODEL_PATH`는 optional로 유지하고 default는 backend container path `/app/model/weights/rf_dependent.joblib`입니다.

## 12. Migration, Rollout, and Rollback

- MIG1. `init.sql` 이동 외의 database data migration은 필요하지 않으며 schema contents는 호환성을 유지합니다.
- ROLL1. Operators는 이 변경 후 `apps/db/docker-compose.yml`에서 Docker를 실행해야 합니다.
- ROLL2. GitHub deployment는 `apps/backend/**`, `apps/web/**`, `apps/db/**`에서 trigger되어야 합니다.
- BACK1. Rollback은 이 consolidation commit을 git revert하고 이전 compose/workflow paths를 복원하는 것입니다.

## 13. Implementation Boundaries

### 13.1 Expected Change Areas

- Repository layout: `apps/backend`, `apps/db`, `apps/ai-server`와 `infra` 제거.
- Backend: route integration, AI helper module import, requirements, tests.
- Deployment: DB-centered compose와 backend workflow path filters.
- Documentation: README, GitHub upload notes, PRD/PLAN, AI docs, backend/web/db docs, superseding spec notes.

### 13.2 Forbidden Changes

- RandomForest feature extraction, model prediction math, class labels, SSE prediction semantics를 변경하지 않습니다.
- Documentation path reference에 필요한 경우를 제외하고 Android source behavior를 변경하지 않습니다.
- Public vendoring을 요구하지 않습니다. Private repo에서는 팀이 의도적으로 검토한 경우 model binaries 또는 Samsung SDK binaries를 추적할 수 있습니다.
- Non-historical docs에 active `apps/api`, `apps/ai-server`, `infra/deploy`, GPU deployment commands를 남기지 않습니다.
- unrelated refactors, formatting churn, required `boto3` 외 dependency upgrades, broad rewrites, generated-file churn을 하지 않습니다.

### 13.3 Minimal-Change Guidance

- 가장 작은 import-path adjustment로 `bedrock_agents.py`를 재사용합니다.
- 가능한 기존 backend request/response models와 helper names를 유지합니다.
- Public endpoints를 backward-compatible하게 유지하고 internal call boundary에 맞게 tests를 조정합니다.

## 14. Acceptance Criteria

- AC1. Repository root 기준 `apps/backend`, `apps/web`, `apps/mobile`, `apps/db`가 존재하고 `apps/api`, `apps/ai-server`, `infra`는 active service folders로 남지 않습니다.
- AC2. Backend dependencies가 설치되어 있을 때 `apps/backend`에서 `python -m compileall app tests`가 성공합니다.
- AC3. Backend tests를 실행할 수 있을 때 intervention tests는 external `_post_llm` HTTP proxy 대신 internal Bedrock helpers/adapters를 mock합니다.
- AC4. Docker가 사용 가능할 때 `docker compose -f apps/db/docker-compose.yml config --no-env-resolution`이 성공합니다.
- AC5. Root `.env`가 있을 때 compose file은 새 paths로 `db`, `backend`, `web`을 resolve합니다.
- AC6. Android가 기존 endpoints를 호출할 때 endpoint path changes는 필요하지 않습니다.
- AC7. `apps/api`, `apps/ai-server`, `llm-server`, `LLM_SERVER_URL`, `docker-compose.gpu`, `deploy-gpu`, `infra/db`, `infra/deploy`에 대한 path scan에서 historical/superseded docs만 남습니다.
- AC8. `git diff --check`에서 whitespace errors가 보고되지 않습니다.

## 15. Validation Plan

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Backend compile | `python -m compileall app tests` from `apps/backend` | PASS |
| Backend tests | `python -m pytest` from `apps/backend` if pytest is available | PASS 또는 unavailable reason recorded |
| Docker config | `docker compose -f apps/db/docker-compose.yml config --no-env-resolution` | PASS |
| Android build | `apps/mobile/gradlew.bat assembleDebug` | PASS 또는 SDK/AAR missing reason recorded |
| Path hygiene | `rg -n "apps/api|apps/ai-server|llm-server|LLM_SERVER_URL|docker-compose.gpu|deploy-gpu|infra/db|infra/deploy"` | Only historical/superseded references remain |
| Diff hygiene | `git diff --check` | PASS |

## 16. Risks and Open Notes

- RISK1. 큰 untracked service folders 이동은 staging 전까지 delete/add로 보일 수 있으므로 status만 보지 말고 content를 검증해야 합니다.
- RISK2. Local Bedrock calls는 `AWS_BEARER_TOKEN_BEDROCK` 또는 AWS credentials 없이는 완전한 smoke-test가 어렵기 때문에 mocked tests와 compile checks가 필요합니다.
- RISK3. Android build는 이전에 local SDK/JDK/AAR setup 때문에 block되었지만, 현재 로컬 환경에서는 build와 install이 성공했습니다.

## 17. Implementation Checklist / Progress Record

이 섹션은 구현 중 main orchestrating agent가 업데이트합니다.

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Finalize spec pair | Main | Complete | `docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.md`, `docs/specs/2026-07-09-neurotruth-unified-backend-app-structure-spec.ko.md` | Pending validation | 구현 준비 완료 spec pair를 생성했습니다. |
| P2 | Source restructure and backend AI integration | Main after worker limit | Complete | `apps/backend/**`, removed `apps/ai-server/**` | `python -m compileall app tests`: PASS; `python -m pytest`: PASS | Backend가 이제 `app/ai` 아래 Bedrock helpers를 소유합니다. Intervention routes는 internal helpers를 호출하며 Bedrock은 `AWS_BEARER_TOKEN_BEDROCK`이 제공되면 Runtime `converse` API에 bearer header를 사용합니다. |
| P3 | Documentation, compose, CI, and path hygiene | Main after worker limit | Complete | `apps/db/**`, `.github/workflows/deploy-backend.yml`, `.env.example`, `README.md`, active docs | `docker compose -f apps/db/docker-compose.yml config`: PASS; `--no-env-resolution`: PASS | DB compose가 `db`, `backend`, `web`을 소유하며 active docs는 unified layout과 bearer-token Bedrock test 설정을 문서화합니다. |
| P4 | Main verification and progress update | Main | Complete | Spec progress records | `git diff --check`: PASS; Android build: PASS; Android unit tests: PASS; phone/watch install: PASS | Worker sub-agent가 usage limit로 실패해서 main이 직접 구현을 마무리하고 deviation을 기록했습니다. |
| P5 | Mobile reconstruction from `watch_test` | Main | Complete | `apps/mobile/**`, mobile docs | `apps/mobile/gradlew.bat assembleDebug`: PASS; `apps/mobile/gradlew.bat testDebugUnitTest`: PASS | Phone user dashboard, developer mode, state-check, background monitoring, notifications, watch UI, vibration/notification behavior를 복원하고 NeuroTruth alert/chat/handoff integration을 유지했습니다. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.2 | 2026-07-09 | Codex | Implementation status, Android verification, private-repo artifact policy, mobile reconstruction progress를 업데이트했습니다. |
| 1.1 | 2026-07-09 | Codex | AWS Bedrock bearer-token configuration과 backend `converse` API note를 추가했습니다. |
| 1.0 | 2026-07-09 | Feature Planner | Initial finalized spec pair. |
