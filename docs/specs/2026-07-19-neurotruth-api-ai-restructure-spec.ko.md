# NeuroTruth 계층형 백엔드 재구성 — Living Implementation Specification

<!-- feature-planner-control
{
  "workflow": "feature-planner/v7",
  "state": "complete",
  "source_spec": "docs/specs/2026-07-19-neurotruth-api-ai-restructure-spec.md",
  "korean_mirror": "docs/specs/2026-07-19-neurotruth-api-ai-restructure-spec.ko.md",
  "spec_revision": 9,
  "reviewed_revision": 9,
  "selected_strategy": "STRAT-1",
  "implementation_direction": "user-approved-divergence",
  "direction_decision_id": "D-002",
  "minimal_change_policy": "strict",
  "final_domain_gate": "confirmed_none",
  "open_question_ids": [],
  "active_slices": [],
  "next_action": "none"
}
-->

> 사용자가 전체 구현 계획을 직접 제공하고 구현을 명시적으로 승인했다. 영문 문서가 구현 기준이며 이 문서는 동기화된 한국어 검토본이다.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `complete`, revision 9, 구조 개편 후 correctness/cleanup/API contract/active documentation 검증 완료 |
| Outcome | 공개 API, 저장 구조, 모바일/웹 동작, 배포 경계를 유지하면서 단일 FastAPI 백엔드를 NeuroSync식 계층으로 재구성한다. |
| Recommended implementation | `STRAT-1` — 기존 책임을 명시적 계층으로 이동하고 세 AI 책임을 분리하며 상태 요약/보고서 AI를 기본 비활성화한다. |
| Planned production targets | `apps/backend/app/ai/__init__.py::layered module relocation`; `apps/backend/app/ai/bedrock_agents.py::layered module relocation`; `apps/backend/app/alerts.py::layered module relocation`; `apps/backend/app/inference.py::layered module relocation`; `apps/backend/app/inference_time.py::layered module relocation`; `apps/backend/app/main.py::layered module relocation`; `apps/backend/app/memory.py::layered module relocation`; `apps/backend/app/security/__init__.py::layered module relocation`; `apps/backend/app/security/crypto.py::layered module relocation`; `apps/backend/app/security/passwords.py::layered module relocation`; `apps/backend/app/security/tokens.py::layered module relocation`; `apps/backend/app/settings.py::layered module relocation`; `apps/backend/app/v25/__init__.py::layered module relocation`; `apps/backend/app/v25/admin_service.py::layered module relocation`; `apps/backend/app/v25/auth_service.py::layered module relocation`; `apps/backend/app/v25/dashboard_service.py::layered module relocation`; `apps/backend/app/v25/dependencies.py::layered module relocation`; `apps/backend/app/v25/models.py::layered module relocation`; `apps/backend/app/v25/repository.py::layered module relocation`; `apps/backend/app/v25/routes_admin.py::layered module relocation`; `apps/backend/app/v25/routes_auth.py::layered module relocation`; `apps/backend/app/v25/routes_dashboard.py::layered module relocation`; `apps/backend/app/v25/routes_rppg.py::layered module relocation`; `apps/backend/app/v25/routes_sensor.py::layered module relocation`; `apps/backend/app/v25/routes_sessions.py::layered module relocation`; `apps/backend/app/v25/routes_stt.py::layered module relocation`; `apps/backend/app/v25/rppg_dgx.py::layered module relocation`; `apps/backend/app/v25/rppg_media.py::layered module relocation`; `apps/backend/app/v25/rppg_repository.py::layered module relocation`; `apps/backend/app/v25/rppg_service.py::layered module relocation`; `apps/backend/app/v25/rppg_storage.py::layered module relocation`; `apps/backend/app/v25/runtime.py::layered module relocation`; `apps/backend/app/v25/sensor_service.py::layered module relocation`; `apps/backend/app/v25/sensor_storage.py::layered module relocation`; `apps/backend/app/v25/session_agents.py::layered module relocation`; `apps/backend/app/v25/session_service.py::layered module relocation`; `apps/backend/app/v25/stt_client.py::layered module relocation`; `apps/backend/app/adapters/rppg_dgx.py::layered module relocation`; `apps/backend/app/adapters/rppg_media.py::layered module relocation`; `apps/backend/app/adapters/stt_client.py::layered module relocation`; `apps/backend/app/agents/bedrock.py::layered module relocation`; `apps/backend/app/agents/intervention.py::layered module relocation`; `apps/backend/app/agents/report.py::layered module relocation`; `apps/backend/app/agents/state_summary.py::layered module relocation`; `apps/backend/app/api/v1/dependencies.py::layered module relocation`; `apps/backend/app/api/v1/router.py::layered module relocation`; `apps/backend/app/api/v1/routes/admin.py::layered module relocation`; `apps/backend/app/api/v1/routes/auth.py::layered module relocation`; `apps/backend/app/api/v1/routes/dashboard.py::layered module relocation`; `apps/backend/app/api/v1/routes/rppg.py::layered module relocation`; `apps/backend/app/api/v1/routes/sensor.py::layered module relocation`; `apps/backend/app/api/v1/routes/session.py::layered module relocation`; `apps/backend/app/api/v1/routes/stt.py::layered module relocation`; `apps/backend/app/api/v1/routes/system.py::layered module relocation`; `apps/backend/app/core/config.py::layered module relocation`; `apps/backend/app/core/runtime.py::layered module relocation`; `apps/backend/app/core/security/__init__.py::layered module relocation`; `apps/backend/app/core/security/crypto.py::layered module relocation`; `apps/backend/app/core/security/passwords.py::layered module relocation`; `apps/backend/app/core/security/tokens.py::layered module relocation`; `apps/backend/app/ml/craving/latency.py::layered module relocation`; `apps/backend/app/ml/craving/model.py::layered module relocation`; `apps/backend/app/ml/craving/pipeline.py::layered module relocation`; `apps/backend/app/models/records.py::layered module relocation`; `apps/backend/app/prompts/intervention.py::layered module relocation`; `apps/backend/app/prompts/report.py::layered module relocation`; `apps/backend/app/prompts/state_summary.py::layered module relocation`; `apps/backend/app/repositories/legacy_memory.py::layered module relocation`; `apps/backend/app/repositories/postgres.py::layered module relocation`; `apps/backend/app/repositories/rppg.py::layered module relocation`; `apps/backend/app/schemas/admin.py::layered module relocation`; `apps/backend/app/schemas/auth.py::layered module relocation`; `apps/backend/app/schemas/dashboard.py::layered module relocation`; `apps/backend/app/schemas/rppg.py::layered module relocation`; `apps/backend/app/schemas/sensor.py::layered module relocation`; `apps/backend/app/schemas/session.py::layered module relocation`; `apps/backend/app/schemas/stt.py::layered module relocation`; `apps/backend/app/services/admin.py::layered module relocation`; `apps/backend/app/services/auth.py::layered module relocation`; `apps/backend/app/services/dashboard.py::layered module relocation`; `apps/backend/app/services/prediction.py::layered module relocation`; `apps/backend/app/services/rppg.py::layered module relocation`; `apps/backend/app/services/sensor.py::layered module relocation`; `apps/backend/app/services/session.py::layered module relocation`; `apps/backend/app/services/stt.py::layered module relocation`; `apps/backend/app/storage/rppg.py::layered module relocation`; `apps/backend/app/storage/sensor.py::layered module relocation`; `apps/backend/app/inference_time.txt::latency artifact ownership`; `apps/backend/app/ml/craving/inference_time.txt::latency artifact ownership`; `apps/backend/app/core/config.py::AI feature flags`; `apps/backend/app/services/session.py::state/report call sites` |
| Expected additions | New production files: apps/backend/app/adapters/rppg_dgx.py, apps/backend/app/adapters/rppg_media.py, apps/backend/app/adapters/stt_client.py, apps/backend/app/agents/bedrock.py, apps/backend/app/agents/intervention.py, apps/backend/app/agents/report.py, apps/backend/app/agents/state_summary.py, apps/backend/app/api/v1/dependencies.py, apps/backend/app/api/v1/router.py, apps/backend/app/api/v1/routes/admin.py, apps/backend/app/api/v1/routes/auth.py, apps/backend/app/api/v1/routes/dashboard.py, apps/backend/app/api/v1/routes/rppg.py, apps/backend/app/api/v1/routes/sensor.py, apps/backend/app/api/v1/routes/session.py, apps/backend/app/api/v1/routes/stt.py, apps/backend/app/api/v1/routes/system.py, apps/backend/app/core/config.py, apps/backend/app/core/runtime.py, apps/backend/app/core/security/__init__.py, apps/backend/app/core/security/crypto.py, apps/backend/app/core/security/passwords.py, apps/backend/app/core/security/tokens.py, apps/backend/app/ml/craving/latency.py, apps/backend/app/ml/craving/model.py, apps/backend/app/ml/craving/pipeline.py, apps/backend/app/models/records.py, apps/backend/app/prompts/intervention.py, apps/backend/app/prompts/report.py, apps/backend/app/prompts/state_summary.py, apps/backend/app/repositories/legacy_memory.py, apps/backend/app/repositories/postgres.py, apps/backend/app/repositories/rppg.py, apps/backend/app/schemas/admin.py, apps/backend/app/schemas/auth.py, apps/backend/app/schemas/dashboard.py, apps/backend/app/schemas/rppg.py, apps/backend/app/schemas/sensor.py, apps/backend/app/schemas/session.py, apps/backend/app/schemas/stt.py, apps/backend/app/services/admin.py, apps/backend/app/services/auth.py, apps/backend/app/services/dashboard.py, apps/backend/app/services/prediction.py, apps/backend/app/services/rppg.py, apps/backend/app/services/sensor.py, apps/backend/app/services/session.py, apps/backend/app/services/stt.py, apps/backend/app/storage/rppg.py, apps/backend/app/storage/sensor.py, apps/backend/app/ml/craving/inference_time.txt; dependencies: None; shared abstractions: User-approved layered module layout only |
| Work plan | `WS1`, `WS2`, `WS3`, `WS4`, `WS5`, `WS6` 검증 완료; serial `WS7` sensor contract/idempotency, `WS8` lifecycle/state masking, `WS9` dead-code/name cleanup, `WS10` active API/documentation refresh |
| Open questions | None |
| Agent decisions to review | None; 서비스 경계, AI 범위, 폴더 방식, 호환성, 비활성 동작을 모두 사용자가 선택했다. |
| Last material change | Revision 9 — 사용자가 correctness/cleanliness/API-doc audit을 요청했고 repository review에서 bounded runtime contract/lifecycle defect 5개와 stale active documentation을 확인했다. public route set, database, dependency, deployment direction은 유지한다. |

## 2. Outcome and Scope

### Outcome

백엔드 개발자가 HTTP 라우터, 스키마, 서비스, AI 에이전트, 프롬프트, 모델 추론, 저장소, 어댑터, 암호화 저장 책임을 쉽게 찾을 수 있다. 실행 구조는 `apps/backend` 단일 FastAPI 배포와 기존 STT worker를 유지한다.

### In Scope

- `app/v25`와 혼합된 root 모듈을 승인된 계층 패키지로 교체한다.
- 모든 공개 경로와 요청/응답 계약을 유지한다.
- text와 voice-derived text에 같은 중재 대화를 사용한다.
- STT를 `route -> service -> adapter -> apps/stt-service` 구조로 유지한다.
- binary craving inference는 `ml/craving`, 실행 오케스트레이션은 `services/prediction.py`로 둔다.
- rPPG 동작을 유지하면서 client/service/repository/storage 소유자를 이동한다.
- 상태 요약/보고서 AI를 환경 플래그로 기본 비활성화하고 나중에 활성화할 코드는 보존한다.
- 테스트, import, OpenAPI 구성, backend/mobile API 문서, Compose/env 예시, living spec pair를 갱신한다.
- 이미 문서화된 20초 sensor-window contract를 강제하고 concurrent retry를 직렬화하며 disabled state summary를 dashboard read에서도 마스킹하고 partial startup/shutdown을 안전하게 만든다.
- public compatibility key는 유지하면서 production-only dead legacy Bedrock helper와 일부 stale internal `v25` name을 제거한다.
- live 42-operation route inventory를 기준으로 active API/operator Markdown를 갱신하되 historical specification은 역사 기록으로 유지한다.

### Out of Scope / Non-Goals

- `apps/ai-server`, 내부 HTTP AI API, DB migration, dependency 추가, model 변경, prompt 동작 재설계, mobile/web UI 변경은 하지 않는다.
- 인증, 동의, 암호화, 세션 멱등성, STT 제한, rPPG 품질 규칙, 갈망 threshold, Alembic revision은 바꾸지 않는다.
- 내부 import 전환 후 `app/v25` 또는 `app.inference` compatibility shim을 남기지 않는다.
- 사용자의 unrelated handoff 문서 삭제나 새 presentation artifact를 건드리지 않는다.

### Users and Primary Flow

1. 모바일/웹 client는 기존 `/api/...` endpoint를 그대로 호출한다.
2. 얇은 route module이 요청을 검증하고 typed service owner를 호출한다.
3. session text와 확정 STT text는 같은 intervention agent로, sensor window는 같은 binary craving model로 간다.
4. persistence와 공개 response는 호환되며 비활성 상태/보고서 AI는 Bedrock을 호출하지 않는다.

### Current Assumptions and Constraints

- `apps/backend`는 deployment 및 Docker build context로 유지한다 (`D-001`).
- 계층형 구조는 명시적 사용자 승인 direction change이며 해당 backend module로만 제한한다 (`D-002`).
- cleanup 전 audit baseline은 `162 passed, 1 skipped`였다. production-dead helper test 9개를 제거하고 새 boundary test를 추가한 final behavioral suite는 `159 passed, 1 skipped`이며 compileall 성공과 live OpenAPI 42 method/path 및 active mobile inventory exact equality를 확인했다(`D-014`).

## 3. Repository Pattern Baseline

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| 배포 | 하나의 FastAPI backend가 DB, Bedrock, craving inference, provider proxy를 소유한다. | `apps/backend/app/main.py::lifespan`; `apps/db/docker-compose.yml` | backend 단일 process와 기존 STT container를 유지한다. |
| 공개 API | router는 version 없는 `/api/...` URL을 노출한다. | `apps/backend/app/v25/routes_*.py`; `apps/mobile/SERVER_API_SPEC.md` | 내부 `api/v1`가 URL에 `/v1`을 추가하면 안 된다. |
| 대화 | `SessionService.message`가 user message 저장, agent 호출/검증, 단일 reply 저장을 수행한다. | `apps/backend/app/v25/session_service.py::SessionService.message` | consent, lock, retry/idempotency, modality metadata, encryption, error를 유지한다. |
| 상태/보고서 | deterministic inference는 optional LLM text와 독립 저장되며 report는 async job이다. | `SessionService._create_inference`; `SessionService._run_report` | AI 비활성화가 deterministic state와 기존 report read를 바꾸지 않는다. |
| STT | backend가 audio를 검증하고 tmpfs에 복사한 뒤 별도 STT worker를 호출한다. | `routes_stt.py::transcribe`; `stt_client.py::SttClient` | auth, voice consent, type/limit/cleanup/error code를 유지한다. |
| Craving inference | process-wide model을 한 번 load하고 `SensorService`가 사용한다. | `app/inference.py::RealtimePredictionService`; `main.py::RuntimePredictorAdapter` | checksum/device fallback, output schema, persistence, alert, SSE를 유지한다. |
| 테스트 | test가 production symbol을 직접 import하고 fake repository/provider를 사용한다. | `apps/backend/tests/test_*_v25.py` | import만 갱신하고 behavior assertion을 유지한다. |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | Existing public routers and request validation | `apps/backend/app/v25/routes_*.py` | decorator/error mapping을 바꾸지 않고 이동 및 thin route화한다. |
| R-002 | Existing services and repository protocol | `apps/backend/app/v25/*_service.py`; `repository.py` | transaction을 재작성하지 않고 ownership을 유지해 이동한다. |
| R-003 | Existing Bedrock adapter and output validators | `app/ai/bedrock_agents.py`; `v25/session_agents.py` | agent/prompt 책임별로 나누고 같은 provider를 호출한다. |
| R-004 | Existing craving model and worker | `app/inference.py` | 수학/artifact를 바꾸지 않고 model/pipeline과 orchestration을 분리한다. |
| R-005 | Existing STT client and tests | `v25/stt_client.py`; `tests/test_stt_routes_v25.py` | 새 service layer 뒤에서 재사용한다. |
| R-006 | Existing backend test suite | `apps/backend/tests` | 152 pass/1 skip을 regression floor로 사용한다. |

## 4. Decisions and Questions

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | 배포 | `apps/backend` 단일 FastAPI process를 유지하고 `apps/ai-server`를 만들지 않는다. | user | 사용자가 folder-only separation을 선택했다. | 새 service/network hop/shared-contract package가 없다. | confirmed | resolved |
| D-002 | 아키텍처 | 최신 NeuroSync식 layered internal folder structure를 적용한다. | user | 사용자가 NeuroSync layered option과 exact target tree를 제공했다. | `app/v25` 제거와 명시적 계층 이동을 승인한다. | confirmed | resolved |
| D-003 | 호환성 | 모든 공개 `/api/...` URL과 mobile/web contract를 유지한다. | user | 사용자가 public API preservation을 선택했다. | internal import/ownership과 OpenAPI grouping만 변경한다. | confirmed | resolved |
| D-004 | AI 범위 | 중재 대화, STT, craving prediction은 활성화하고 state-summary/report AI는 기본 비활성화한다. | user | 명시적 구현 요청이다. | typed flag 두 개를 추가하고 agent를 분리한다. | confirmed | resolved |
| D-005 | 비활성 동작 | disabled AI를 조용히 생략한다: unavailable/null summary, automatic report 없음, manual 202/not_started, historical read 보존. | user | 사용자가 “flag + quietly skip”을 선택했다. | DB status/schema 추가가 필요 없다. | confirmed | resolved |
| D-006 | 저장 구조 | DB와 Alembic을 변경하지 않는다. | user | public/persistence compatibility가 요구된다. | 기존 revision `20260717_0005`를 유지한다. | confirmed | resolved |
| D-007 | 기준선 | Backend test는 현재 `152 passed, 1 skipped`이다. | repository | `.venv\\Scripts\\python.exe -m pytest -q --basetemp=.pytest-plan-basetemp` | acceptance floor를 정한다. | not-required | resolved |
| D-008 | 변경 예산 | content-preserving relocation path를 literal하게 계산한다: rename detection 전 110 changed paths, 87 production paths, 50 new production Python paths. | repository | `git status --short --untracked-files=all -- apps/backend/app apps/backend/tests` | 승인된 tree, behavior, direction을 바꾸지 않고 WS2 expansion alarm만 교정한다. | not-required | resolved |
| D-009 | 변경 예산 | +148 net line delta가 아니라 split/relocated file 전체의 872 literal production additions를 계산한다. | repository | Temporary-index `git diff --cached --find-renames=40% --numstat -- apps/backend/app` | WS2 expansion alarm만 900으로 높이며 behavior와 target tree는 바뀌지 않는다. | not-required | resolved |
| D-010 | 소유권 | tracked `app/inference_time.txt` artifact를 `ml/craving/latency.py` 옆으로 이동한다. | repository | Main/read-only review에서 새 default path는 `ml/craving/inference_time.txt`인데 tracked file은 root에 남은 것을 확인했다. | stale mixed-root artifact를 제거하고 tracked latency record를 실제 default 위치에 보존한다. | not-required | resolved |
| D-011 | Runtime composition | typed state-summary/report flag를 `core/config.py`에서 `core/runtime.py`를 거쳐 `SessionService`로 전달한다. | repository | `initialize_runtime`이 현재 세 agent와 `SessionService`를 생성하는 composition root다. | service-local environment read 없이 environment parsing을 typed로 유지하고 zero-call branch를 명시한다. | not-required | resolved |
| D-012 | Prediction ownership | `RealtimePredictionService`를 authenticated sensor ingestion이 사용하는 단일 process-wide model queue/patient SSE-hub owner로 만들고 inactive legacy persistence/broadcast path를 제거한다. | repository | final read-only review에서 `RuntimePredictorAdapter.predict`가 model을 직접 호출하고 `SensorService`가 second hub를 소유해 prediction queue/latency/hub가 `/api/sensor-windows`와 분리된 것을 확인했다. | model math, DB persistence, route/payload 변경 없이 user-approved `services/prediction.py` loading/queue/SSE 책임을 복구한다. | not-required | resolved |
| D-013 | Prediction lifecycle | stopping 중 신규 work를 거부하고 queued future를 실패 처리하며 shared prediction worker보다 rPPG를 먼저 종료하고 rPPG persistence 전에 worker-only timing key를 제거한다. | repository | WS5 correction review에서 worker 종료 후 future가 대기할 수 있고 shared adapter가 rPPG result에 `_lat`/`_readyPerf`를 추가함을 확인했다. | inference math/schema 변경 없이 bounded shutdown과 기존 rPPG DB/public payload contract를 보존한다. | not-required | resolved |
| D-014 | Audit baseline | original `152 passed, 1 skipped` acceptance floor를 유지하고 pre-cleanup `162 passed, 1 skipped` 및 final behavioral suite와 live OpenAPI 42 method/path/active mobile inventory exact equality를 요구한다. | repository | backend virtual environment와 live `app.openapi()`를 사용한 fresh audit/final validation 결과이며 final `159 passed, 1 skipped`는 dead-helper test 9개 제거와 새 boundary coverage를 반영한다. | deleted code test를 세지 않으면서 meaningful behavior coverage를 명시한다. | not-required | resolved |
| D-015 | Correctness scope | sensor duration/error/idempotency, disabled-summary dashboard masking, partial-startup cleanup, in-flight prediction shutdown을 완료한 뒤 restructure를 clean으로 선언한다. | user | 사용자가 오류 검증과 code cleanup을 명시적으로 요청했고 audit이 approved backend scope 내부 gap을 재현했다. | valid public call, DB schema, model math, topology 변경 없이 bounded fix/test를 추가한다. | confirmed | resolved |
| D-016 | API authority | response model을 별도 reviewed scope에서 완성하기 전까지 `SERVER_API_SPEC.md`가 detailed response payload를, `/openapi.json`이 route/method/request schema를 담당한다고 정확히 문서화한다. | repository | 42 route는 정확하지만 current JSON success schema는 generic object이며 35 operation response validation 추가는 이번 correction 범위를 크게 확장한다. | generated contract를 과장하지 않으면서 runtime payload를 보존한다. | not-required | resolved |
| D-017 | Cleanup | production-unreferenced Bedrock slot/handoff/repetition helper와 좁은 internal name만 제거하고 public `health.v25`, `v25_unavailable`, repository protocol/class name, historical spec은 유지한다. | repository | import scan으로 dead helper/internal label과 compatibility-visible field/broad symbol을 구분했다. | broad rename이나 historical-doc rewrite 없이 dead code를 줄인다. | not-required | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | 배포 | service를 나눌지 folder만 나눌지 선택한다. | runtime topology가 달라진다. | backend 하나를 유지한다. | D-001 | answered | 사용자가 folder-only separation을 선택했다. |
| Q-002 | AI | 어느 AI capability를 활성화할지 선택한다. | provider call과 product behavior가 달라진다. | intervention, STT, craving만 활성화한다. | D-004 | answered | 사용자가 세 capability를 선택했다. |
| Q-003 | 구조 | feature-first와 NeuroSync layered folder 중 선택한다. | module ownership을 결정한다. | NeuroSync layered structure를 사용한다. | D-002 | answered | 사용자가 layered folder를 선택했다. |
| Q-004 | 비활성 동작 | state/report AI를 error 처리할지 조용히 생략할지 선택한다. | flag off 시 API behavior가 달라진다. | not_started/unavailable로 조용히 생략한다. | D-005 | answered | 사용자가 quiet skipping을 선택했다. |

## 5. Requirements and Acceptance Criteria

### Functional Requirements

- **FR-001:** Production code는 승인된 `api/v1`, `core`, `schemas`, `models`, `services`, `agents`, `prompts`, `ml/craving`, `repositories`, `adapters`, `storage` owner를 사용하고 `app/v25` package를 남기지 않는다.
- **FR-002:** 기존 모든 공개 route, HTTP method, 인증/동의 gate, compatible response field를 같은 URL에서 제공한다.
- **FR-003:** Text와 voice-derived message는 같은 intervention dialogue workflow를 사용하고 `inputModality`, encryption, lock, idempotency를 보존한다.
- **FR-004:** STT separate container와 backend `route -> service -> adapter` flow, limit, cleanup, error code를 유지한다.
- **FR-005:** Craving model/preprocessing은 `ml/craving`, process-wide orchestration은 `services/prediction.py`에 두고 prediction/alert/persistence/SSE를 유지한다.
- **FR-006:** `STATE_SUMMARY_AI_ENABLED=false`이면 deterministic inference를 `summaryStatus=unavailable`, `summary=null`로 저장하고 provider/model-registration call을 0회 수행한다.
- **FR-007:** `REPORT_AI_ENABLED=false`이면 automatic report를 생략하고 manual request는 HTTP 202, `reportId/version=null`, `status=not_started`를 반환하며 historical GET을 유지한다.
- **FR-008:** 어느 flag든 활성화하면 추가 code 변경 없이 기존 Bedrock behavior를 복구한다.
- **FR-009:** rPPG 기능과 provider isolation을 이동 후 그대로 유지한다.
- **FR-010:** active docs와 environment example에 새 구조 및 default flag를 기록한다.
- **FR-011:** sensor upload는 internally consistent 20-second window만 받아야 하고 invalid timing은 422여야 하며 동일 `(patient, clientWindowId)` concurrent retry에서 inference/alert/persistence/SSE가 한 번만 실행되어야 한다.
- **FR-012:** state-summary AI가 disabled이면 session/dashboard read 모두 stored prose를 decrypt하지 않고 unavailable/null을 반환하며 flag enable 시 기존 patient behavior가 복구되어야 한다.
- **FR-013:** startup failure와 prediction shutdown은 partial runtime resource를 닫고 이전 synchronous model inference가 restart와 겹치지 않게 해야 한다.
- **FR-014:** active API/architecture/AI/database/mobile/deployment/development Markdown는 current path/default, migration head `20260717_0005`, 20-second/1,024-point signal, implemented feature boundary를 반영해야 한다.
- **FR-015:** active production module에 production caller가 없는 pre-free-dialogue Bedrock slot/handoff/repetition helper나 stale ownership documentation이 남지 않아야 한다.

### Non-Functional Requirements

- **NFR-001:** dependency, DB migration, internal HTTP service, unrelated abstraction을 추가하지 않는다.
- **NFR-002:** encryption AAD, fail-closed security, sanitized provider error, retry/concurrency, model checksum/device fallback을 유지한다.
- **NFR-003:** content-preserving move를 우선하고 business logic edit는 routing ownership, schema extraction, flag, 승인된 quiet-skip behavior로 제한한다.
- **NFR-004:** unrelated working-tree change를 그대로 보존한다.
- **NFR-005:** audited edge correction 중 dependency, migration, public route/response field, model transformation을 추가하지 않는다.

### Acceptance Criteria

- **AC-001:** `rg`가 production/test에서 `app.v25`, `app.inference`, `app.settings`, `app.security`, `app.ai` import를 찾지 못하고 tracked `apps/backend/app/v25` file이 없다.
- **AC-002:** OpenAPI의 기존 path/method set이 같고 internal `api/v1`가 `/v1` URL prefix를 추가하지 않는다.
- **AC-003:** backend compile이 성공하고 pytest가 최소 152 tests를 pass하며 기존 skip 이하를 유지한다.
- **AC-004:** dialogue test가 text/voice의 단일 intervention agent와 retry/idempotency 보존을 증명한다.
- **AC-005:** flag test가 disabled provider call 0회와 exact unavailable/not_started contract를 증명한다.
- **AC-006:** 기존 STT, craving, rPPG, auth, dashboard, schema, encryption, repository test가 통과한다.
- **AC-007:** Compose config가 유효하고 Docker 사용 가능 시 rebuilt status endpoint, authenticated dialogue, sensor prediction이 성공한다.
- **AC-008:** Alembic revision, SQL schema, dependency lock, mobile production code, web production code 변경이 없다.
- **AC-009:** sensor focused test에서 10-second, 1 ms, reversed, mismatched, overflow timestamp가 storage/inference 전에 422이고 concurrent identical retry가 predictor/alert/persistence/SSE를 각각 한 번만 실행함을 증명한다.
- **AC-010:** dashboard focused test에서 disabled stored summary가 patient read에 unavailable/null이고 admin metadata도 unavailable이며 decrypt하지 않고, enabled patient read는 historical prose를 유지함을 증명한다.
- **AC-011:** lifecycle test에서 rPPG/runtime startup failure가 partial resource를 정확히 한 번 닫고 `stop()`이 model thread와 다음 `start()` overlap을 허용하지 않음을 증명한다.
- **AC-012:** active docs에 removed `app/v25`/`app/ai` ownership path, old current-head `0004`, current 10-second signal, default-pending report example이 없고 42-operation inventory가 exact하게 유지된다.

### Edge and Failure Cases

- Disabled state AI -> deterministic state는 commit되고 prose 부재를 provider failure로 audit하지 않는다.
- Disabled report AI -> job row를 만들지 않고 manual POST는 successful no-op이며 기존 row는 GET으로 조회한다.
- STT unavailable -> typed chat은 계속 사용하고 현재 sanitized STT error를 반환한다.
- Craving model unavailable -> auth/chat은 계속 사용하고 sensor prediction은 현재 503 behavior를 유지한다.
- Provider/rPPG failure -> 현재 timeout, cleanup, sanitized error behavior를 유지한다.
- Invalid sensor timing -> encrypted storage/model access 전에 FastAPI 422, consent failure만 403으로 매핑한다.
- Simultaneous identical sensor retry -> keyed critical section 하나와 published prediction 하나를 만들며 conflicting content는 계속 409다.
- Synchronous model inference 중 shutdown -> request future는 unavailable로 실패하지만 restart 전에 thread boundary 완료를 기다린다.

## 6. Implementation Strategy and Direction

### STRAT-1 — Content-Preserving Layered Relocation

- **Direction:** `user-approved-divergence`
- **Current approach:** 기존 owner를 승인된 계층으로 이동하고 import/test를 갱신하며 mixed agent/model module을 현재 responsibility boundary에서 분리한 뒤 composition/service call site에 두 flag를 추가한다.
- **Existing flow to reuse:** R-001 through R-006.
- **Why this is minimal:** 사용자가 새 layered direction을 명시적으로 요청했다. content-preserving move와 두 narrow flag로 새 service/dependency/DB state/compatibility shim/business rewrite를 피한다.
- **Behavior-preserving limitations:** `api/v1`은 internal package name이며 STT worker는 separate deployment를 유지하고 state/report code는 남지만 default disabled다.
- **Explicit exclusions:** endpoint family redesign, shared-contract package, model/prompt redesign, DB migration, client rewrite, adjacent cleanup은 제외한다.
- **Compatibility and migration posture:** data migration 없는 단일 deployable refactor다. source move/config commit을 revert할 수 있고 flag로 optional AI behavior를 독립 복구한다.
- **Direction approval:** D-002는 `approved-divergence`로 표시된 module-layer relocation row만 승인한다.
- **Open-question sensitivity:** None.

### Material Alternatives Considered

| Strategy | Direction | Benefit | Additional code or risk | Decision |
| --- | --- | --- | --- | --- |
| Separate `apps/ai-server` | user-approved-divergence | 독립 AI deployment | network contract, operation, latency, new service | D-001로 rejected |
| Feature-first vertical slices | user-approved-divergence | domain별 self-contained | 선택된 latest NeuroSync style과 다름 | D-002로 rejected |
| Keep `app/v25` wrappers | preserve | 즉시 import churn 감소 | 제거 요청한 duplicate/confusing structure가 남음 | rejected |

## 7. Modification Map and Change Budget

### Modification Map

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | docs | `docs/specs/2026-07-19-neurotruth-api-ai-restructure-spec.md` | living spec | add | approved user plan | 검토된 design/progress를 기록한다. | Implements FR-010 and NFR-004. | WS1 | preserve |
| CH-002 | docs | `docs/specs/2026-07-19-neurotruth-api-ai-restructure-spec.ko.md` | Korean mirror | add | approved user plan | 한국어 mirror를 동기화한다. | Implements FR-010 and NFR-004. | WS1 | preserve |
| CH-100 | production | `apps/backend/app/ai/__init__.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-101 | production | `apps/backend/app/ai/bedrock_agents.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-102 | production | `apps/backend/app/alerts.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-103 | production | `apps/backend/app/inference.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-104 | production | `apps/backend/app/inference_time.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-105 | production | `apps/backend/app/main.py` | layered module relocation | edit | current app composition | layered owner에 맞춰 central composition을 갱신한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-106 | production | `apps/backend/app/memory.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-107 | production | `apps/backend/app/security/__init__.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-108 | production | `apps/backend/app/security/crypto.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-109 | production | `apps/backend/app/security/passwords.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-110 | production | `apps/backend/app/security/tokens.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-111 | production | `apps/backend/app/settings.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-112 | production | `apps/backend/app/v25/__init__.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-113 | production | `apps/backend/app/v25/admin_service.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-114 | production | `apps/backend/app/v25/auth_service.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-115 | production | `apps/backend/app/v25/dashboard_service.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-116 | production | `apps/backend/app/v25/dependencies.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-117 | production | `apps/backend/app/v25/models.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-118 | production | `apps/backend/app/v25/repository.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-119 | production | `apps/backend/app/v25/routes_admin.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-120 | production | `apps/backend/app/v25/routes_auth.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-121 | production | `apps/backend/app/v25/routes_dashboard.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-122 | production | `apps/backend/app/v25/routes_rppg.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-123 | production | `apps/backend/app/v25/routes_sensor.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-124 | production | `apps/backend/app/v25/routes_sessions.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-125 | production | `apps/backend/app/v25/routes_stt.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-126 | production | `apps/backend/app/v25/rppg_dgx.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-127 | production | `apps/backend/app/v25/rppg_media.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-128 | production | `apps/backend/app/v25/rppg_repository.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-129 | production | `apps/backend/app/v25/rppg_service.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-130 | production | `apps/backend/app/v25/rppg_storage.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-131 | production | `apps/backend/app/v25/runtime.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-132 | production | `apps/backend/app/v25/sensor_service.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-133 | production | `apps/backend/app/v25/sensor_storage.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-134 | production | `apps/backend/app/v25/session_agents.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-135 | production | `apps/backend/app/v25/session_service.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-136 | production | `apps/backend/app/v25/stt_client.py` | layered module relocation | remove | approved relocation destination | owner 이동 후 legacy path를 제거한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-137 | test | `apps/backend/tests/test_admin_routes_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-138 | test | `apps/backend/tests/test_alerts.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-139 | test | `apps/backend/tests/test_auth_routes_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-140 | test | `apps/backend/tests/test_auth_service_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-141 | test | `apps/backend/tests/test_bedrock_agents.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-142 | test | `apps/backend/tests/test_binary_craving_model.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-143 | test | `apps/backend/tests/test_craving_bar_dashboard_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-144 | test | `apps/backend/tests/test_inference_alert_sessions.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-145 | test | `apps/backend/tests/test_intervention_dashboard_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-146 | test | `apps/backend/tests/test_intervention_schema_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-147 | test | `apps/backend/tests/test_memory.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-148 | test | `apps/backend/tests/test_probability_series_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-149 | test | `apps/backend/tests/test_repository_model_version_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-150 | test | `apps/backend/tests/test_rppg_routes_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-151 | test | `apps/backend/tests/test_rppg_runtime_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-152 | test | `apps/backend/tests/test_rppg_schema_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-153 | test | `apps/backend/tests/test_rppg_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-154 | test | `apps/backend/tests/test_security_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-155 | test | `apps/backend/tests/test_sensor_routes_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-156 | test | `apps/backend/tests/test_sensor_storage_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-157 | test | `apps/backend/tests/test_sessions_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-158 | test | `apps/backend/tests/test_sse_payload.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-159 | test | `apps/backend/tests/test_stt_routes_v25.py` | layered import/contract coverage | edit | existing backend test | import를 갱신하고 mapped regression assertion을 유지하거나 확장한다. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-160 | production | `apps/backend/app/adapters/rppg_dgx.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-161 | production | `apps/backend/app/adapters/rppg_media.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-162 | production | `apps/backend/app/adapters/stt_client.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-163 | production | `apps/backend/app/agents/bedrock.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-164 | production | `apps/backend/app/agents/intervention.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-165 | production | `apps/backend/app/agents/report.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-166 | production | `apps/backend/app/agents/state_summary.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-167 | production | `apps/backend/app/api/v1/dependencies.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-168 | production | `apps/backend/app/api/v1/router.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-169 | production | `apps/backend/app/api/v1/routes/admin.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-170 | production | `apps/backend/app/api/v1/routes/auth.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-171 | production | `apps/backend/app/api/v1/routes/dashboard.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-172 | production | `apps/backend/app/api/v1/routes/rppg.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-173 | production | `apps/backend/app/api/v1/routes/sensor.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-174 | production | `apps/backend/app/api/v1/routes/session.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-175 | production | `apps/backend/app/api/v1/routes/stt.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-176 | production | `apps/backend/app/api/v1/routes/system.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-177 | production | `apps/backend/app/core/config.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-178 | production | `apps/backend/app/core/runtime.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-179 | production | `apps/backend/app/core/security/__init__.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-180 | production | `apps/backend/app/core/security/crypto.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-181 | production | `apps/backend/app/core/security/passwords.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-182 | production | `apps/backend/app/core/security/tokens.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-183 | production | `apps/backend/app/ml/craving/latency.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-184 | production | `apps/backend/app/ml/craving/model.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-185 | production | `apps/backend/app/ml/craving/pipeline.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-186 | production | `apps/backend/app/models/records.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-187 | production | `apps/backend/app/prompts/intervention.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-188 | production | `apps/backend/app/prompts/report.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-189 | production | `apps/backend/app/prompts/state_summary.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-190 | production | `apps/backend/app/repositories/legacy_memory.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-191 | production | `apps/backend/app/repositories/postgres.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-192 | production | `apps/backend/app/repositories/rppg.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-193 | production | `apps/backend/app/schemas/admin.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-194 | production | `apps/backend/app/schemas/auth.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-195 | production | `apps/backend/app/schemas/dashboard.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-196 | production | `apps/backend/app/schemas/rppg.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-197 | production | `apps/backend/app/schemas/sensor.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-198 | production | `apps/backend/app/schemas/session.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-199 | production | `apps/backend/app/schemas/stt.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-200 | production | `apps/backend/app/services/admin.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-201 | production | `apps/backend/app/services/auth.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-202 | production | `apps/backend/app/services/dashboard.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-203 | production | `apps/backend/app/services/prediction.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-204 | production | `apps/backend/app/services/rppg.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-205 | production | `apps/backend/app/services/sensor.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-206 | production | `apps/backend/app/services/session.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-207 | production | `apps/backend/app/services/stt.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-208 | production | `apps/backend/app/storage/rppg.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-209 | production | `apps/backend/app/storage/sensor.py` | layered module relocation | add | approved legacy owner | relocated/split content를 승인된 layered destination에 추가한다. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-210 | production | `apps/backend/app/inference_time.txt` | latency artifact ownership | remove | `apps/backend/app/ml/craving/latency.py` | relocation 후 stale root artifact를 제거한다. | Implements FR-001, FR-005, and AC-001. | WS2 | approved-divergence |
| CH-211 | production | `apps/backend/app/ml/craving/inference_time.txt` | latency artifact ownership | add | `apps/backend/app/inference_time.txt` | tracked latency record를 content 변경 없이 writer 옆으로 이동한다. | Implements FR-001, FR-005, and AC-001. | WS2 | approved-divergence |
| CH-005 | config | `apps/backend/app/core/config.py` | AI feature flags | extend | current environment-backed settings | typed default-false state-summary/report setting을 추가한다. | Implements FR-006 through FR-008 and AC-005. | WS3 | preserve |
| CH-013 | production | `apps/backend/app/core/runtime.py` | layered module relocation | extend | current `initialize_runtime` composition root | intervention은 활성 상태로 두고 두 typed flag를 `SessionService`에 전달한다. | Implements FR-006 through FR-008 and AC-005. | WS3 | preserve |
| CH-006 | production | `apps/backend/app/services/session.py` | state/report call sites | extend | current `_create_inference`, `_queue_report`, `_run_report` | enabled behavior를 유지하면서 exact quiet-skip branch를 추가한다. | Implements FR-006 through FR-008 and AC-005. | WS3 | preserve |
| CH-007 | test | `apps/backend/tests/test_sessions.py` | disabled/enabled AI behavior tests | add | relocated current session tests | disabled provider call 0회와 enabled behavior 보존을 증명한다. | Proves AC-003 through AC-006. | WS3 | preserve |
| CH-008 | test | `apps/backend/tests/test_api_structure.py` | public route and layer-boundary tests | add | current app/OpenAPI and import paths | public path/method 유지와 legacy import 부재를 증명한다. | Proves AC-001, AC-002, and AC-005. | WS3 | preserve |
| CH-009 | config | `.env.example` | AI feature flag defaults | edit | existing environment example | 두 default-off setting을 문서화한다. | Implements FR-006, FR-007, FR-010. | WS4 | preserve |
| CH-010 | config | `apps/db/docker-compose.yml` | backend environment | edit | existing backend environment block | service 변경 없이 두 default-off setting을 전달한다. | Implements FR-006, FR-007, FR-010. | WS4 | preserve |
| CH-011 | docs | `apps/backend/README.md` | active backend architecture | edit | current backend README | layer, active AI, disabled state/report behavior를 설명한다. | Implements FR-002 and FR-010. | WS4 | preserve |
| CH-012 | docs | `apps/mobile/SERVER_API_SPEC.md` | report API contract | edit | current mobile API contract | disabled 상태의 successful no-op report creation을 문서화한다. | Implements FR-002, FR-007, FR-010. | WS4 | preserve |
| CH-212 | production | `apps/backend/app/services/prediction.py` | layered module relocation | extend | current queue/model adapter plus inactive legacy worker path | authenticated prediction request를 single request/response queue와 patient-scoped hub로 전달하고 inactive legacy persistence/broadcast ownership을 제거한다. | Implements FR-001, FR-005, NFR-004, AC-003, and AC-006. | WS5 | preserve |
| CH-213 | production | `apps/backend/app/services/sensor.py` | layered module relocation | extend | current locally constructed patient hub | prediction service hub를 주입/사용하고 internal timing은 SSE에만 publish하면서 public/DB response를 유지한다. | Implements FR-005, NFR-001, and AC-003. | WS5 | preserve |
| CH-214 | production | `apps/backend/app/main.py` | layered module relocation | extend | current lifespan composition | legacy-memory disable workaround를 제거하고 prediction-owned hub를 `SensorService`에 주입한다. | Implements FR-001 and FR-005. | WS5 | preserve |
| CH-215 | production | `apps/backend/app/api/v1/routes/sensor.py` | layered module relocation | extend | current authenticated SSE generator | latency recording을 active authenticated SSE path에 연결하고 worker-only key를 모두 제거한다. | Implements FR-002, FR-005, and AC-002. | WS5 | preserve |
| CH-216 | test | `apps/backend/tests/test_inference_alert_sessions.py` | prediction orchestration tests | edit | obsolete direct/legacy orchestration assertions | adapter queue routing, patient hub isolation, direct model bypass 부재를 증명한다. | Proves AC-003 and AC-006. | WS5 | preserve |
| CH-217 | test | `apps/backend/tests/test_sensor_routes_v25.py` | sensor SSE ownership tests | edit | current sensor service/SSE tests | injected hub publication의 patient isolation과 public response timing-key 미노출을 증명한다. | Proves AC-002, AC-003, and AC-006. | WS5 | preserve |
| CH-218 | docs | `apps/backend/README.md` | active backend architecture | edit | current prediction-flow and validation wording | consolidated queue/SSE flow를 설명하고 stale Docker rerun text를 final acceptance evidence로 교체한다. | Implements FR-005 and FR-010. | WS5 | preserve |
| CH-219 | production | `apps/backend/app/services/prediction.py` | layered module relocation | extend | current request/response worker queue | running/stopping state를 추적하고 active worker가 없으면 work를 거부하며 shutdown 중 queued/in-flight future를 실패 처리한다. | Implements FR-005, NFR-002, AC-003, and AC-006. | WS6 | preserve |
| CH-220 | production | `apps/backend/app/main.py` | layered module relocation | edit | current prediction-before-runtime shutdown | shared prediction worker를 중지하기 전에 rPPG/runtime consumer를 먼저 중지한다. | Implements FR-005, FR-009, and NFR-002. | WS6 | preserve |
| CH-221 | production | `apps/backend/app/services/rppg.py` | layered module relocation | edit | current merged predictor result | rPPG success persistence 전에 underscore-prefixed worker-only timing field를 제거한다. | Implements FR-005, FR-009, NFR-002, and AC-006. | WS6 | preserve |
| CH-222 | test | `apps/backend/tests/test_inference_alert_sessions.py` | prediction shutdown tests | edit | current queue routing assertions | stopping이 신규 work를 거부하고 queued future를 hang 없이 실패 처리함을 증명한다. | Proves AC-003 and AC-006. | WS6 | preserve |
| CH-223 | test | `apps/backend/tests/test_rppg_v25.py` | rPPG persistence tests | edit | current successful prediction assertions | shared-adapter result에서 worker-only timing field를 제거한 뒤 rPPG persistence가 수행됨을 증명한다. | Proves AC-006 and FR-009. | WS6 | preserve |
| CH-224 | production | `apps/backend/app/schemas/sensor.py` | layered module relocation | edit | current permissive timing fields | route 실행 전 documented 20-second duration과 timestamp consistency를 강제한다. | Implements FR-011 and AC-009. | WS7 | preserve |
| CH-225 | production | `apps/backend/app/services/sensor.py` | layered module relocation | edit | current lookup/predict/persist flow | patient/window retry를 직렬화하고 overflow 포함 invalid timestamp를 consent와 분리한다. | Implements FR-011, NFR-002, and AC-009. | WS7 | preserve |
| CH-226 | production | `apps/backend/app/api/v1/routes/sensor.py` | layered module relocation | edit | current broad `ValueError` mapping and stale internal handler name | authorization은 403, sensor payload는 422로 매핑하고 public route 변경 없이 private handler를 rename한다. | Implements FR-011. | WS7 | preserve |
| CH-227 | test | `apps/backend/tests/test_sensor_routes_v25.py` | sensor validation/concurrency tests | edit | sequential 10-second fixture | current 20-second contract와 invalid timing/concurrent idempotency boundary를 증명한다. | Proves AC-009. | WS7 | preserve |
| CH-228 | production | `apps/backend/app/services/dashboard.py` | layered module relocation | edit | current unconditional stored-summary status/decryption | typed flag를 주입하고 disabled dashboard summary를 decrypt하지 않고 mask한다. | Implements FR-012 and AC-010. | WS8 | preserve |
| CH-229 | production | `apps/backend/app/core/runtime.py` | layered module relocation | edit | current partial cleanup and dashboard composition | dashboard flag를 compose하고 partial initialization failure를 common cleanup으로 보낸다. | Implements FR-012, FR-013, AC-010, and AC-011. | WS8 | preserve |
| CH-230 | production | `apps/backend/app/main.py` | layered module relocation | edit | current post-setup `try/finally` | startup action 전 cleanup boundary를 만들어 setup failure에서도 resource를 닫는다. | Implements FR-013 and AC-011. | WS8 | preserve |
| CH-231 | production | `apps/backend/app/services/prediction.py` | layered module relocation | edit | current cancellable wrapper around a continuing `to_thread` call | stop/restart 전에 in-flight model thread boundary를 추적하고 join한다. | Implements FR-013 and AC-011. | WS8 | preserve |
| CH-232 | test | `apps/backend/tests/test_intervention_dashboard_v25.py` | state-summary dashboard tests | edit | current always-enabled fixture expectation | disabled masking/decryption skip과 enabled compatibility를 증명한다. | Proves AC-010. | WS8 | preserve |
| CH-233 | test | `apps/backend/tests/test_inference_alert_sessions.py` | prediction restart lifecycle tests | edit | current stop test releases the thread after stop | stop이 thread boundary를 기다리고 restart가 overlap하지 않음을 증명한다. | Proves AC-011. | WS8 | preserve |
| CH-234 | test | `apps/backend/tests/test_rppg_runtime_v25.py` | partial runtime cleanup tests | edit | current successful initialization/shutdown coverage | startup failure를 강제하고 partial resource close exactly once를 검증한다. | Proves AC-011. | WS8 | preserve |
| CH-235 | test | `apps/backend/tests/test_api_structure.py` | runtime flag composition assertion | edit | session-only forwarding assertion | typed state-summary flag가 `DashboardService`에도 전달됨을 증명한다. | Proves AC-010. | WS8 | preserve |
| CH-236 | production | `apps/backend/app/agents/bedrock.py` | layered module relocation | edit | production-unreferenced slot/handoff/repetition utilities | active agent가 쓰는 provider adapter/JSON parser만 유지하고 pre-free-dialogue helper를 제거한다. | Implements FR-015. | WS9 | preserve |
| CH-237 | test | `apps/backend/tests/test_bedrock_agents.py` | active Bedrock adapter coverage | edit | tests for production-unreferenced helpers | active provider/parser behavior coverage만 유지한다. | Proves D-017 and AC-003. | WS9 | preserve |
| CH-238 | production | `apps/backend/app/ml/craving/latency.py` | layered module relocation | edit | stale `app/main.py` generator ownership text | active sensor route ownership으로 수정한다. | Implements FR-015. | WS9 | preserve |
| CH-239 | docs | `README.md` | active project overview | edit | current restructure/config/API summary | layered backend, default-off flag, endpoint, validation command를 갱신한다. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-240 | docs | `apps/backend/README.md` | backend architecture/runbook | edit | current mostly-correct layered guide | omitted layer, venv command, STT engine, report default, validation evidence를 추가한다. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-241 | docs | `apps/mobile/SERVER_API_SPEC.md` | human API contract | edit | current generic OpenAPI-authority wording and stale response/consent/readiness details | authority, 20-second validation/idempotency, `not_started`, consent/readiness를 정확히 한다. | Implements FR-011, FR-012, FR-014, and AC-012. | WS10 | preserve |
| CH-242 | docs | `apps/mobile/README.md` | mobile integration overview | edit | stale 10-second/default-report wording | 20-second capture, unified STT/text, default-off summary/report를 설명한다. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-243 | docs | `apps/mobile/docs/PHONE_APP.md` | phone behavior guide | edit | stale 10-second capture wording | camera/sensor timing과 current server boundary를 맞춘다. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-244 | docs | `apps/db/README.md` | database/Compose runbook | edit | stale `0004` head and pending evidence | `20260717_0005`, STT service, exact table, rerun evidence를 기록한다. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-245 | docs | `docs/dev-environment.md` | development environment | edit | stale flags, limits, 10-second signal, and deferred-feature text | current flag, 40 MiB, head, free dialogue, 20-second/1,024, STT/local TTS를 반영한다. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-246 | docs | `docs/ai/README.md` | active AI architecture | edit | deleted module paths and old prompt/default behavior | layered owner와 active/default-off behavior를 기록한다. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-247 | docs | `docs/ai/README.ko.md` | Korean AI architecture mirror | edit | deleted module paths and old prompt/default behavior | CH-246을 한국어로 동기화한다. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-248 | docs | `docs/ai/agents/README.md` | active agent overview | edit | old deterministic/deferred boundaries | current LLM-only dialogue safety와 feature-gated STT/rPPG를 설명한다. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-249 | docs | `docs/ai/agents/README.ko.md` | Korean agent overview mirror | edit | old deterministic/deferred boundaries | CH-248을 한국어로 동기화한다. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-250 | docs | `docs/ai/agents/03_handoff_agent.md` | report-agent behavior | edit | default-active automatic report wording | exact default-off 202/no-op/history behavior와 enabled behavior를 구분한다. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-251 | docs | `docs/ai/agents/03_handoff_agent.ko.md` | Korean report-agent mirror | edit | default-active automatic report wording | CH-250을 한국어로 동기화한다. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-252 | docs | `docs/deployment/DGX_STT_TTS_SETUP.ko.md` | DGX STT deployment paths | edit | deleted `app/v25` STT paths | layered route/service/adapter owner를 가리킨다. | Implements FR-014 and AC-012. | WS10 | preserve |

### Change Budget

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 2 | 0 | 0 | 0 | None | None |
| WS2 | 115 | 89 | 51 | 900 | None | User-approved layered module layout only |
| WS3 | 8 | 3 | 0 | 120 | None | None |
| WS4 | 4 | 0 | 0 | 0 | None | None |
| WS5 | 7 | 4 | 0 | 140 | None | None |
| WS6 | 5 | 3 | 0 | 100 | None | None |
| WS7 | 4 | 3 | 0 | 140 | None | None |
| WS8 | 8 | 4 | 0 | 180 | None | None |
| WS9 | 3 | 2 | 0 | 20 | None | None |
| WS10 | 14 | 0 | 0 | 0 | None | None |

WS2 budget는 relocated target file을 new path로, old/new rename side를 separate changed path로 계산한다. review에서는 content-preserving move와 실제 새 logic을 구분한다.

## 8. Work Plan

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | 검토 완료 bilingual implementation contract를 만든다. | None | Serial | CH-001, CH-002 | `docs/specs/2026-07-19-neurotruth-api-ai-restructure-spec*.md` | All production/test files | FR-010, NFR-004, AC-008 | `specctl.py validate` | verified |
| WS2 | backend code를 승인된 layer로 이동하고 current owner를 분리하며 import/test를 갱신하고 old module을 제거하되 behavior를 유지한다. | WS1 | Serial | CH-100, CH-101, CH-102, CH-103, CH-104, CH-105, CH-106, CH-107, CH-108, CH-109, CH-110, CH-111, CH-112, CH-113, CH-114, CH-115, CH-116, CH-117, CH-118, CH-119, CH-120, CH-121, CH-122, CH-123, CH-124, CH-125, CH-126, CH-127, CH-128, CH-129, CH-130, CH-131, CH-132, CH-133, CH-134, CH-135, CH-136, CH-137, CH-138, CH-139, CH-140, CH-141, CH-142, CH-143, CH-144, CH-145, CH-146, CH-147, CH-148, CH-149, CH-150, CH-151, CH-152, CH-153, CH-154, CH-155, CH-156, CH-157, CH-158, CH-159, CH-160, CH-161, CH-162, CH-163, CH-164, CH-165, CH-166, CH-167, CH-168, CH-169, CH-170, CH-171, CH-172, CH-173, CH-174, CH-175, CH-176, CH-177, CH-178, CH-179, CH-180, CH-181, CH-182, CH-183, CH-184, CH-185, CH-186, CH-187, CH-188, CH-189, CH-190, CH-191, CH-192, CH-193, CH-194, CH-195, CH-196, CH-197, CH-198, CH-199, CH-200, CH-201, CH-202, CH-203, CH-204, CH-205, CH-206, CH-207, CH-208, CH-209, CH-210, CH-211 | `apps/backend/app/**`, `apps/backend/tests/**` | DB/Alembic, mobile/web production code, handoff docs | FR-001, FR-002, FR-003, FR-004, FR-005, FR-008, FR-009, NFR-001, NFR-002, NFR-003, NFR-004, AC-001, AC-002, AC-003, AC-004, AC-006, AC-008 | compileall; full backend pytest | verified |
| WS3 | default-off state-summary/report flag와 exact quiet-skip contract를 추가한다. | WS2 | Serial | CH-005, CH-013, CH-006, CH-007, CH-008 | `apps/backend/app/**`, `apps/backend/tests/**` | Public URLs, DB schema, intervention/STT/craving behavior | FR-006, FR-007, FR-008, NFR-001, NFR-002, NFR-003, AC-001, AC-002, AC-003, AC-004, AC-005, AC-006 | focused session/API tests; full backend pytest | verified |
| WS4 | active docs/config를 갱신하고 integration acceptance를 실행한다. | WS3 | Serial | CH-009, CH-010, CH-011, CH-012 | `.env.example`, `apps/db/docker-compose.yml`, `apps/backend/README.md`, `apps/mobile/SERVER_API_SPEC.md` | User handoff docs, mobile/web source, DB schema | FR-002, FR-006, FR-007, FR-010, NFR-004, AC-002, AC-007, AC-008 | spec validation; compose config; full backend pytest; status/smoke when available | verified |
| WS5 | authenticated craving prediction을 planned queue/SSE owner로 통합하고 final validation doc를 교정한다. | WS4 | Serial | CH-212, CH-213, CH-214, CH-215, CH-216, CH-217, CH-218 | `apps/backend/app/services/prediction.py`, `apps/backend/app/services/sensor.py`, `apps/backend/app/main.py`, `apps/backend/app/api/v1/routes/sensor.py`, `apps/backend/tests/test_inference_alert_sessions.py`, `apps/backend/tests/test_sensor_routes_v25.py`, `apps/backend/README.md` | Public URLs/payloads, model math, PostgreSQL/Alembic, other docs/tests | FR-001, FR-002, FR-005, FR-010, NFR-001, NFR-004, AC-002, AC-003, AC-006, AC-008 | focused prediction/sensor tests; compileall; full pytest; Docker sensor/SSE smoke | verified |
| WS6 | correction review에서 확인한 shared prediction shutdown 및 rPPG serialization regression을 닫는다. | WS5 | Serial | CH-219, CH-220, CH-221, CH-222, CH-223 | `apps/backend/app/services/prediction.py`, `apps/backend/app/main.py`, `apps/backend/app/services/rppg.py`, `apps/backend/tests/test_inference_alert_sessions.py`, `apps/backend/tests/test_rppg_v25.py` | Public URLs/payloads, model math, PostgreSQL/Alembic, other files | FR-005, FR-009, NFR-001, NFR-002, AC-003, AC-006, AC-008 | focused prediction/rPPG tests; compileall; full pytest; Docker shutdown and sensor/SSE smoke | verified |
| WS7 | current 20-second sensor contract, error mapping, concurrent retry idempotency를 교정한다. | WS6 | Serial | CH-224, CH-225, CH-226, CH-227 | `apps/backend/app/schemas/sensor.py`, `apps/backend/app/services/sensor.py`, `apps/backend/app/api/v1/routes/sensor.py`, `apps/backend/tests/test_sensor_routes_v25.py` | Model math, DB/Alembic, other routes/tests | FR-011, NFR-002, NFR-005, AC-009 | focused sensor route/service tests; compileall; full pytest | verified |
| WS8 | disabled-summary read leakage와 partial-startup/in-flight-thread lifecycle gap을 닫는다. | WS7 | Serial | CH-228, CH-229, CH-230, CH-231, CH-232, CH-233, CH-234, CH-235 | `apps/backend/app/services/dashboard.py`, `apps/backend/app/core/runtime.py`, `apps/backend/app/main.py`, `apps/backend/app/services/prediction.py`, `apps/backend/tests/test_intervention_dashboard_v25.py`, `apps/backend/tests/test_inference_alert_sessions.py`, `apps/backend/tests/test_rppg_runtime_v25.py`, `apps/backend/tests/test_api_structure.py` | Public routes/payloads, model math, DB/Alembic | FR-012, FR-013, NFR-002, NFR-005, AC-010, AC-011 | focused dashboard/runtime/prediction tests; compileall; full pytest | verified |
| WS9 | broad compatibility rename 없이 audited dead Bedrock helper와 stale ownership text를 제거한다. | WS8 | Serial | CH-236, CH-237, CH-238 | `apps/backend/app/agents/bedrock.py`, `apps/backend/app/ml/craving/latency.py`, `apps/backend/tests/test_bedrock_agents.py` | Provider adapter/parser, prompts, public compatibility names, historical specs | FR-015, NFR-005, AC-003 | focused agent tests; dead-symbol import scan; compileall; full pytest | verified |
| WS10 | mapped active API/architecture/AI/DB/mobile/deployment Markdown를 verified runtime behavior와 맞춘다. | WS9 | Serial | CH-239, CH-240, CH-241, CH-242, CH-243, CH-244, CH-245, CH-246, CH-247, CH-248, CH-249, CH-250, CH-251, CH-252 | `README.md`, `apps/backend/README.md`, `apps/mobile/README.md`, `apps/mobile/SERVER_API_SPEC.md`, `apps/mobile/docs/PHONE_APP.md`, `apps/db/README.md`, `docs/dev-environment.md`, `docs/ai/README.md`, `docs/ai/README.ko.md`, `docs/ai/agents/README.md`, `docs/ai/agents/README.ko.md`, `docs/ai/agents/03_handoff_agent.md`, `docs/ai/agents/03_handoff_agent.ko.md`, `docs/deployment/DGX_STT_TTS_SETUP.ko.md` | Production code, historical specs/handoff artifacts, DB/Alembic | FR-011, FR-012, FR-014, NFR-004, AC-012 | stale-path/default scan; 42-operation parity; Markdown diff review; full validation evidence update | verified |

### Parallelization Rationale

sensor contract가 lifecycle test에 영향을 주고 documentation은 final corrected behavior를 설명해야 하며 dirty worktree에 사용자 restructure/handoff change가 있으므로 serial을 유지한다. unstable interface나 active doc를 worker가 동시에 수정하지 않게 한다.

### Final Integration

strict spec validation, import/path scan, Python compile, full backend pytest, OpenAPI path comparison, Compose config, 가능한 Docker/mobile contract check를 실행한다. final diff에서 unrelated change와 과도한 logic rewrite를 검토한다.

## 9. Validation, Rollout, and Risk

### Validation Plan

- `& '.\\.venv\\Scripts\\python.exe' -m compileall app tests`
- `& '.\\.venv\\Scripts\\python.exe' -m pytest -q --basetemp=.pytest-restructure`
- `rg -n "app\\.(v25|inference|settings|security|ai)" apps/backend/app apps/backend/tests`
- pre/post OpenAPI path/method set을 비교하고 message, STT, prediction, state, report payload를 검증한다.
- focused STT, craving, rPPG, session, auth/security, repository/schema, dashboard, SSE test를 실행한다.
- `docker compose config`를 실행하고 Docker 사용 가능 시 rebuild/status/authenticated flow를 smoke한다.
- Android toolchain 사용 가능 시 기존 mobile session API contract test를 실행한다.
- invalid/concurrent sensor window, disabled-summary dashboard read, partial-startup cleanup, blocked model call 중 stop/restart를 검증한다.
- historical spec을 제외한 active Markdown만 removed path, stale migration/default, current 10-second signal claim으로 scan한다.

### Minimality and Style-Fidelity Review

- 모든 production line을 move, import/composition update, schema extraction, approved flag branch, required testability adjustment로 분류한다.
- compatibility shim, generic registry, new HTTP boundary, dependency, business rewrite, unrelated formatting을 거부한다.
- final tree에서 이동된 각 책임의 owner가 하나이고 duplicate old module이 없는지 확인한다.

### Rollout and Rollback

data migration은 없다. 두 flag가 false인 rebuilt backend/STT image를 배포한다. source/config 변경을 revert할 수 있고 historical DB row는 호환된다. flag를 켜면 보존한 provider-backed behavior를 복구한다.

### Risks and Mitigations

| Risk | Impact | Mitigation or Evidence |
| --- | --- | --- |
| 큰 이동 중 import 누락 | startup/test collection failure | slice마다 compile, import scan, full pytest를 수행한다. |
| schema extraction의 OpenAPI drift | mobile/web breakage | decorator/alias를 유지하고 exact path/method와 representative payload를 비교한다. |
| disabled report의 terminal flow 변경 | unexpected pending report UI | exact 202/not_started와 empty GET test, no DB job을 검증한다. |
| split 중 model behavior 변경 | prediction regression | math를 그대로 이동하고 binary model/sensor/SSE test를 유지한다. |
| unrelated dirty file overwrite | user work loss | handoff path를 제외하고 scoped diff를 검토한다. |
| stricter sensor validation이 obsolete client를 거부 | upload 422 | contract/current Android/model metadata가 이미 20초이므로 19.5–20.5초 tolerance를 명시하고 test한다. |
| synchronous model call join으로 shutdown 지연 | controlled shutdown이 느려짐 | 신규 work는 즉시 거부하고 request future를 실패 처리한 뒤 existing inference boundary만 join하며 restart overlap 부재를 증명한다. |
| generated OpenAPI authority 과장 | client confidence 오류 | OpenAPI는 route/method/request schema, human contract는 detailed response를 담당한다고 명시한다. |

## 10. Revision and Progress

### Design Revision History

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-19T00:00:00+09:00 | user-authorized-plan | 사용자 final plan과 기존 answer로 complete reviewed implementation contract를 만들었다. | D-001, D-002, D-003, D-004, D-005, D-006, D-007 | Q-001, Q-002, Q-003, Q-004 |
| 2 | 2026-07-19T00:10:00+09:00 | repository-budget-evidence | exact approved target tree에 필요한 literal Git path count로 WS2 changed/production/new-path budget을 교정했으며 behavior/direction은 바꾸지 않았다. | D-008 | None |
| 3 | 2026-07-19T00:15:00+09:00 | repository-budget-evidence | 승인된 file split으로 생긴 872 literal addition에 맞춰 WS2 production-added-line budget을 net delta에서 교정했으며 behavior/direction은 바꾸지 않았다. | D-009 | None |
| 4 | 2026-07-19T00:20:00+09:00 | exact-scope-evidence | aggregate WS2 row를 scope validation에 필요한 exact old/new/test path로 확장했으며 behavior/direction은 바꾸지 않았다. | D-008, D-009 | None |
| 5 | 2026-07-19T00:25:00+09:00 | readonly-review-finding | missing tracked latency artifact relocation을 추가하고 literal production/new-path budget을 교정했다. | D-010 | None |
| 6 | 2026-07-19T00:30:00+09:00 | repository-composition-evidence | service 내부 environment read 대신 existing composition root를 거쳐 typed feature flag가 전달되도록 `core/runtime.py`를 WS3에 추가했다. | D-011 | None |
| 7 | 2026-07-19T00:50:00+09:00 | final-readonly-review | authenticated sensor inference를 planned process-wide prediction queue/SSE owner에 연결하고 stale final validation wording을 교정하는 bounded slice를 추가했다. | D-012 | None |
| 8 | 2026-07-19T01:10:00+09:00 | correction-readonly-review | shutdown 중 stranded prediction future와 rPPG persistence의 worker-only timing field를 확인해 bounded lifecycle/serialization correction을 추가했다. | D-013 | None |
| 9 | 2026-07-19T01:35:00+09:00 | user-requested-post-refactor-audit | fresh compile/test/OpenAPI/read-only audit 후 bounded sensor/state-read/startup-thread/dead-code/API-contract/active-doc correction으로 complete plan을 다시 열었다. | D-014, D-015, D-016, D-017 | None |

### Implementation Progress Record

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-19T00:29:00+09:00 | 5 | WS2 | verified | content-preserving latency artifact relocation 완료 후 compile, 153 passed/1 skipped, OpenAPI 42/no-v1, legacy scan, scope/patch check를 독립 검증했다. |
| 2026-07-19T00:30:00+09:00 | 6 | WS3 | in_progress | default-off AI worker 배정 전에 runtime composition target을 동기화했다. |
| 2026-07-19T00:40:00+09:00 | 6 | WS3 | verified | focused 7 passed, full 160 passed/1 skipped, scope/patch check 통과 후 read-only review에서 actionable issue가 없었다. |
| 2026-07-19T00:41:00+09:00 | 6 | WS4 | in_progress | WS3 검증 후 documentation/Compose configuration slice를 시작했다. |
| 2026-07-19T00:48:00+09:00 | 6 | WS4 | verified | four-file scope/patch check, Compose config, Docker rebuild/live authenticated smoke, mobile session contract test를 통과했다. |
| 2026-07-19T00:50:00+09:00 | 7 | WS5 | in_progress | final read-only review에서 authenticated model call이 planned prediction queue/SSE owner를 우회함을 확인해 acceptance 전 correction slice를 시작했다. |
| 2026-07-19T01:05:00+09:00 | 7 | WS5 | verified | queue/SSE ownership, patient isolation, public timing-key filtering, README evidence, focused 16 tests, compileall, 161 passed/1 skipped, WS5 scope/patch check가 통과했다. |
| 2026-07-19T01:10:00+09:00 | 8 | WS6 | in_progress | correction review에서 shutdown-future 및 rPPG timing-key persistence regression을 확인해 Docker acceptance 전 bounded lifecycle/serialization slice를 시작했다. |
| 2026-07-19T01:20:00+09:00 | 8 | WS6 | verified | scope/patch check, read-only 재검토, focused 12 tests, compileall, 162 passed/1 skipped, Compose, rebuilt Docker readiness, authenticated sensor/SSE identity/filtering, graceful restart가 통과했다. |
| 2026-07-19T01:35:00+09:00 | 9 | WS7 | in_progress | 사용자가 correctness/cleanliness/API-doc refresh를 요청했고 fresh baseline은 162 passed/1 skipped와 exact 42-operation parity를 유지했으며 read-only audit이 uncovered edge case를 확인했다. |
| 2026-07-19T01:48:00+09:00 | 9 | WS7 | verified | scope/patch budget, focused 13 tests, compileall 통과 후 independent review가 non-finite value와 pre-storage overflow P2를 발견하고 correction 재검토에서 모두 해결됨을 확인했다. |
| 2026-07-19T01:49:00+09:00 | 9 | WS8 | in_progress | sensor contract/idempotency correction 검증 후 lifecycle 및 disabled-summary masking slice를 시작했다. |
| 2026-07-19T02:02:00+09:00 | 9 | WS8 | verified | scope/patch budget, focused 17 tests, full 168 passed/1 skipped, compileall, cancellation correction 재검토가 통과했고 shared shielded cleanup task로 P1/P2 2건을 닫았다. |
| 2026-07-19T02:03:00+09:00 | 9 | WS9 | in_progress | lifecycle/state masking 검증 후 bounded dead-helper/stale ownership-text cleanup을 시작했다. |
| 2026-07-19T02:10:00+09:00 | 9 | WS9 | verified | scope/patch budget, dead-symbol scan, focused 22-test review, compileall, full 159 passed/1 skipped가 no finding으로 끝났고 dead production 302줄을 제거했다. |
| 2026-07-19T02:11:00+09:00 | 9 | WS10 | in_progress | code correctness/cleanup slice 검증 후 active API/Markdown refresh를 시작했다. |
| 2026-07-19T02:50:00+09:00 | 9 | WS10 | verified | 14-file scope/patch check, stale scan, 42/42 OpenAPI parity, independent review/correction, diff check가 통과했고 rebuilt Docker가 head `0005`에서 health/ready/model success 및 graceful restart readiness를 보였다. |
| 2026-07-19T02:51:00+09:00 | 9 | integration | verified | final compileall/backend `159 passed, 1 skipped`, Compose config, rebuilt service status, live STT-auth 401 contract, spec validation, forbidden-import scan, temporary-artifact cleanup이 통과했다. Android contract test는 시도했으나 local Android SDK path 미설정으로 시작되지 못했다. |
| 2026-07-19T00:00:00+09:00 | 1 | WS1 | verified | 사용자가 reviewed plan을 제공하고 implementation을 명시적으로 요청해 source edit 전에 bilingual pair를 만들었다. |
| 2026-07-19T00:05:00+09:00 | 1 | WS2 | in_progress | strict spec validation과 ready-slice 확인 후 structural relocation worker를 배정했다. |
| 2026-07-19T00:10:00+09:00 | 2 | WS2 | in_progress | repository evidence로 move-oriented path budget을 115/87/50으로 교정했으며 implementation direction과 scope는 유지했다. |
| 2026-07-19T00:15:00+09:00 | 3 | WS2 | in_progress | main review가 temporary Git index와 rename-aware numstat로 production additions를 872/900으로 교정했다. |
| 2026-07-19T00:20:00+09:00 | 4 | WS2 | in_progress | scope/patch check 재실행 전에 exact Git status path를 Modification Map에 동기화했다. |
| 2026-07-19T00:25:00+09:00 | 5 | WS2 | in_progress | main/read-only review가 `inference_time.txt` 한 건의 P2 ownership correction에 동의했다. |
