# Alcohol_Test 갈망 배치 추론 - 구현 명세

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

> 영어 파일이 구현의 기준이며 이 파일은 동기화된 한국어 검토본이다.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `complete`, revision 2, 사용자 검토 방향과 repository 근거 기반 개수 수정 완료 |
| Outcome | Alcohol_Test의 100개 subject-visit에서 사용 가능한 측정 16,334개를 적재하고 이론적 16,339개와 빈 window 5개를 보고한다. |
| Recommended implementation | `STRAT-1` - 현재 모델, SensorService, 암호화 저장소, repository, audit log를 재사용하는 maintenance CLI 하나를 추가한다. |
| Planned production targets | `apps/backend/app/maintenance/import_alcohol_test.py::main`; `apps/db/docker-compose.yml::alcohol-test-import` |
| Expected additions | New production files: `apps/backend/app/maintenance/import_alcohol_test.py`; dependencies: None; shared abstractions: None |
| Work plan | 쓰기 범위가 겹치지 않는 WS1 importer/tests와 WS2 Docker/docs를 병렬 진행할 수 있다. |
| Open questions | None |
| Agent decisions to review | None |
| Last material change | Revision 2 - 전략 변경 없이 관찰된 68.7초 source gap과 실행 가능 총계를 기록했다. |

## 2. Outcome and Scope

### Outcome

운영자는 `Alcohol_Test/data`의 G 기록만 검증하거나 적재하고, 20초 window/10초 stride로 현재 binary Conv1D 갈망 모델을 실행한 뒤 100개 테스트 계정의 암호화 recording, PostgreSQL prediction, CSV/JSON 요약을 확인할 수 있다.

### In Scope

- 정확한 G 폴더 탐색, 혼합 header TSV 파싱, 시간 재배치, 예측, 암호화 저장, 재개 가능한 실행 metadata, 보고서, Docker profile, 집중 테스트와 이중 언어 문서.

### Out of Scope / Non-Goals

- 정답 기반 정확도/F1 평가, alert 생성, Bedrock, 공개 API/schema 변경, DB migration, 모델 재학습, UI 변경.

### Users and Primary Flow

1. 운영자가 `ALCOHOL_TEST_ACCOUNT_PASSWORD`를 설정하고 PostgreSQL을 시작한다.
2. 데이터 검증과 3-window smoke import 후 전체 import를 실행한다.
3. importer가 100개 계정을 생성/재사용하고 암호화 window와 binary craving prediction을 저장하며 repository 밖에 결과를 작성한다.

### Current Assumptions and Constraints

- 원본에는 100개 subject-visit G CSV와 이론적 window 16,339개가 있다. `1_2_006_V2`의 빈 window 5개를 제외하면 16,334개를 적재할 수 있다.
- Docker backend image가 PyTorch를 제공하며 현재 host `.venv`에는 PyTorch가 없다.
- 파일 내부 상대 시각은 유지하고 DB 시각은 실행 anchor 근처로 재배치한다.

## 3. Repository Pattern Baseline

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Maintenance ownership | 운영 command는 `app.maintenance` 아래의 명시적 CLI entry point로 둔다. | `apps/backend/app/maintenance/purge_legacy_predictions.py::main` | import를 backend 운영 command로 유지한다. |
| Sensor persistence | `SensorService.ingest`가 canonicalization, 암호화 저장, idempotency, prediction 저장, 선택적 alert를 소유한다. | `apps/backend/app/services/sensor.py::SensorService.ingest` | notification을 끄고 그대로 재사용한다. |
| Model ownership | backend Conv1D 모델이 PPG와 EDA를 받아 binary probability를 반환한다. | `apps/backend/app/ml/craving/model.py::CravingModel` | artifact를 한 번 load하고 preprocessing contract를 유지한다. |
| Deployment | DB 소유 Compose 파일이 local service와 persistent volume을 정의한다. | `apps/db/docker-compose.yml` | 일반 시작에 영향을 주지 않는 opt-in profile을 추가한다. |
| Testing | backend unit test는 pytest와 repository/service 주변 focused fake를 사용한다. | `apps/backend/tests/test_sensor_routes_v25.py` | 외부 service 없는 focused parser/import test를 추가한다. |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | `SensorService` | `apps/backend/app/services/sensor.py::SensorService` | 모든 window를 production sensor path로 저장한다. |
| R-002 | `CravingModel` | `apps/backend/app/ml/craving/model.py::CravingModel` | 현재 binary craving inference를 수행한다. |
| R-003 | `EncryptedSensorStorage` | `apps/backend/app/storage/sensor.py::EncryptedSensorStorage` | gzip + AES-GCM raw-window 저장을 유지한다. |
| R-004 | `SqlAlchemyV25Repository` and `audit_logs` | `apps/backend/app/repositories/postgres.py::SqlAlchemyV25Repository.audit` | 계정/동의 생성, run manifest 조회, lifecycle metadata 기록에 사용한다. |
| R-005 | Current Compose backend build and volumes | `apps/db/docker-compose.yml::backend` | image, model, DB 환경과 암호화 sensor volume을 재사용한다. |

## 4. Decisions and Questions

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | Accounts | V1/V2 subject-visit별로 총 100개 계정을 만든다. | user | 구현 전에 확인됨. | source file마다 독립적으로 로그인 가능한 계정이 생긴다. | confirmed | resolved |
| D-002 | Persistence | 전체 암호화 SensorService pipeline을 사용한다. | user | 구현 전에 확인됨. | raw window, recording, prediction을 보존한다. | confirmed | resolved |
| D-003 | Evaluation | alert나 ground-truth scoring 없이 prediction 분포를 만든다. | user | 구현 전에 확인됨. | `craving_alerts`는 변경되지 않는다. | confirmed | resolved |
| D-004 | Credentials | 필수 `ALCOHOL_TEST_ACCOUNT_PASSWORD` 환경변수 하나를 사용하고 report에는 기록하지 않는다. | user | 구현 전에 확인됨. | 자격증명 artifact 없이 UI에서 계정을 쓸 수 있다. | confirmed | resolved |
| D-005 | Time | DB 시각은 run anchor 근처로 재배치하고 원본 시각을 metadata에 보존한다. | user | 구현 전에 확인됨. | 현재 dashboard에서 보이면서 추적 가능하다. | confirmed | resolved |
| D-006 | Architecture | 기존 backend service와 DB schema를 유지하고 audit metadata로 resume을 지원한다. | repository | 기존 service와 JSON metadata column이 flow를 지원한다. | migration, provider, API 변경이 없다. | not-required | resolved |
| D-007 | Source quality | `1_2_006_V2`의 빈 window 5개를 복구 가능한 source failure로 처리하고 보간하지 않는다. | repository | 선택한 G CSV에서 약 68.7초 timestamp gap이 검출됐다. | full import는 partial summary와 16,334개 저장으로 완료된다. | not-required | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | Account granularity | 50 subjects 또는 100 subject-visits를 선택한다. | 계정 identity와 결과 grouping을 결정한다. | 100개 계정을 사용한다. | D-001 | answered | 사용자가 100 subject-visit 계정을 선택했다. |
| Q-002 | Persistence | 전체 암호화 저장 또는 prediction만 저장할지 선택한다. | storage와 production path fidelity를 결정한다. | 전체 pipeline을 사용한다. | D-002 | answered | 사용자가 전체 암호화 저장을 선택했다. |
| Q-003 | Time | 과거 시각을 유지하거나 현재 run 근처로 재배치한다. | dashboard visibility와 idempotency metadata에 영향을 준다. | 재배치하고 metadata에 원본 시각을 남긴다. | D-005 | answered | 사용자가 재배치를 선택했다. |

## 5. Requirements and Acceptance Criteria

### Functional Requirements

- **FR-001:** 각 `ECG_PPG_GSR` 아래에서 정확히 하나의 `<subject_visit>G_...` CSV를 찾고 ECG와 GT를 제외한다.
- **FR-002:** header suffix로 tab-separated file을 파싱하고 separator/unit 행을 건너뛰며 관찰된 5열/15열 형식을 지원한다.
- **FR-003:** 10초 stride로 완전한 20초 window를 만들고 PPG를 `PPG_GREEN`, conductance를 `EDA`로 매핑한다.
- **FR-004:** 안정된 run anchor 근처로 sample/window 시각을 재배치하고 원본 시각과 offset을 `device_info`에 보존한다.
- **FR-005:** deterministic email의 patient account 100개를 생성/재사용하고 biosignal/AI 동의를 켜며 notification을 끈다.
- **FR-006:** deterministic UUID5 client ID와 audit-log run metadata를 사용해 각 window를 `SensorService`로 저장하고 resume을 지원한다.
- **FR-007:** 자격증명 없이 account CSV, prediction CSV, summary JSON을 repository 밖에 작성한다.
- **FR-008:** `--validate-only`, `--subject-visit`, `--limit-windows`, `--run-id`, `--new-run`을 지원한다.
- **FR-009:** opt-in Docker Compose profile과 이중 언어 실행 guide를 제공한다.

### Non-Functional Requirements

- **NFR-001:** 모든 public API, DB schema, model artifact/behavior, 일반 Docker startup을 유지한다.
- **NFR-002:** 동일 run ID 재실행은 sensor/prediction row를 중복 추가하지 않는다.
- **NFR-003:** 잘못된 file/window는 column 선택을 바꾸거나 다른 sensor folder를 쓰지 않고 보고한다.
- **NFR-004:** dependency/shared abstraction을 추가하지 않고 기존 unrelated working-tree 변경을 보존한다.

### Acceptance Criteria

- **AC-001:** dataset validation이 G file 100개, 이론적 window 16,339개, `1_2_006_V2`의 정확한 빈 window failure 5개를 보고한다.
- **AC-002:** focused test가 정확한 folder 선택, mixed-header parsing, window 경계, rebasing, deterministic ID, account naming, resume, notification suppression을 증명한다.
- **AC-003:** 3-window Docker smoke import가 일치하는 encrypted recording, DB prediction, report row를 만들고 같은 run 반복 시 count가 증가하지 않는다.
- **AC-004:** 현재 source의 full import가 100 test account, 16,334 sensor recording, 16,334 prediction, skip failure 5개, import-generated alert 0개를 만든다.
- **AC-005:** backend compileall, pytest, Compose config, `git diff --check`가 통과하거나 환경 blocker를 정확히 보고한다.

### Edge and Failure Cases

- matching G folder/CSV 누락 또는 복수 -> validation failure를 기록하고 대체 파일을 고르지 않는다.
- 필수 header 누락, non-finite value, 20초 미만 -> source를 보고하고 invalid work를 건너뛴다.
- 기존 email이 예상 active patient/consent contract와 다름 -> 덮어쓰지 않고 실패한다.
- 같은 run ID의 canonical payload가 변경됨 -> SensorService conflict를 유지하고 실패한다.
- `--new-run` 없는 completed run -> 완료 상태를 보고하고 duplicate run을 만들지 않는다.

## 6. Implementation Strategy and Direction

### STRAT-1 - 기존 Service 기반 Batch Import

- **Direction:** `preserve`
- **Current approach:** maintenance CLI 하나가 source를 파싱하고 audit-backed run anchor를 고정하며 계정을 생성/재사용한 뒤 기존 model과 `SensorService`의 작은 async adapter에 deterministic canonical window를 전달한다.
- **Existing flow to reuse:** R-001부터 R-005.
- **Why this is minimal:** 기존 metadata JSON과 audit 저장이 provenance/resume을 지원하므로 API, migration, dependency, 별도 persistence path가 필요 없다.
- **Behavior-preserving limitations:** production과 동일하게 raw sample point는 암호화 file에 있고 PostgreSQL에는 recording/prediction metadata가 저장된다.
- **Explicit exclusions:** public route, model refactor, generic importer framework, alert logic, Bedrock, dashboard 변경 없음.
- **Compatibility and migration posture:** opt-in command/profile이며 일반 deployment는 그대로다. rollback은 새 command/profile/docs 제거다.
- **Direction approval:** 필요 없음. 가장 가까운 기존 owner를 확장한다.
- **Open-question sensitivity:** 없음.

### Material Alternatives Considered

| Strategy | Direction | Benefit | Additional code or risk | Decision |
| --- | --- | --- | --- | --- |
| custom table에 prediction 직접 삽입 | user-approved-divergence | 더 빠른 bulk loading 가능 | encryption/idempotency 우회와 migration/persistence 중복 필요 | rejected |
| public HTTP API로 모든 window upload | preserve | authentication/route까지 시험 | token/session/network overhead가 생기고 API 변경 없이는 source metadata를 잃음 | rejected |

## 7. Modification Map and Change Budget

### Modification Map

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | production | `apps/backend/app/maintenance/import_alcohol_test.py` | `main` | add | `apps/backend/app/maintenance/purge_legacy_predictions.py::main` | 정확한 discovery, parsing, run/account 관리, service ingest, reporting, CLI control을 추가한다. | Implements FR-001 through FR-008 and NFR-001 through NFR-004. | WS1 | preserve |
| CH-002 | test | `apps/backend/tests/test_import_alcohol_test.py` | focused parser/import tests | add | `apps/backend/tests/test_sensor_routes_v25.py` | fake로 folder/header/window/time/ID/account/resume/alert behavior를 시험한다. | Proves AC-001 through AC-003 and AC-005. | WS1 | preserve |
| CH-003 | config | `apps/db/docker-compose.yml` | `alcohol-test-import` service | extend | `apps/db/docker-compose.yml::backend` | backend image/environment/volume과 dataset mount를 재사용하는 opt-in profile을 추가한다. | Implements FR-009 and preserves NFR-001. | WS2 | preserve |
| CH-004 | config | `.env.example` | `ALCOHOL_TEST_ACCOUNT_PASSWORD` | extend | `.env.example` | 필수 test-account password variable을 문서화한다. | Implements FR-005 and FR-009. | WS2 | preserve |
| CH-005 | docs | `docs/deployment/alcohol-test-batch-import.md` | execution guide | add | `docs/deployment` | 영어 prerequisite, validation, smoke/full/resume command, output, DB check를 추가한다. | Implements FR-009 and proves AC-003 through AC-005. | WS2 | preserve |
| CH-006 | docs | `docs/deployment/alcohol-test-batch-import.ko.md` | Korean execution guide | add | `docs/deployment` | 동기화된 한국어 guide를 추가한다. | Implements FR-009. | WS2 | preserve |

### Change Budget

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 2 | 1 | 1 | 520 | None | None |
| WS2 | 4 | 0 | 0 | 0 | None | None |

production line budget은 코드 압축 목표가 아니라 범위 확장 경보다.

## 8. Work Plan

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | 재개 가능한 Alcohol_Test importer를 추가하고 검증한다. | None | P1 | CH-001, CH-002 | `apps/backend/app/maintenance/import_alcohol_test.py`, `apps/backend/tests/test_import_alcohol_test.py` | Existing services, schemas, migrations, model files, unrelated changes | FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-007, FR-008, NFR-001, NFR-002, NFR-003, NFR-004, AC-001, AC-002, AC-003, AC-004, AC-005 | `python -m pytest tests/test_import_alcohol_test.py` | verified |
| WS2 | opt-in Compose runner, 환경 contract, 이중 언어 guide를 추가한다. | None | P1 | CH-003, CH-004, CH-005, CH-006 | `apps/db/docker-compose.yml`, `.env.example`, `docs/deployment/alcohol-test-batch-import.md`, `docs/deployment/alcohol-test-batch-import.ko.md` | Backend source/tests, existing services, unrelated docs | FR-005, FR-009, NFR-001, NFR-004, AC-003, AC-004, AC-005 | `docker compose -f apps/db/docker-compose.yml --profile alcohol-test config` | verified |

### Parallelization Rationale

WS1과 WS2는 target이 겹치지 않는다. command/module name, CLI flag, mount, output path가 이 reviewed spec에 고정되어 불안정한 shared interface가 없다.

### Final Integration

compileall, focused/full backend test, Compose config, validate-only import, 3-window Docker smoke와 replay count check를 실행하고 Docker/password variable이 준비되면 전체 import를 수행한다.

## 9. Validation, Rollout, and Risk

### Validation Plan

- `apps/backend/.venv/Scripts/python.exe -m compileall app tests`
- `apps/backend/.venv/Scripts/python.exe -m pytest tests/test_import_alcohol_test.py`
- focused suite 이후 full backend pytest.
- `docker compose -f apps/db/docker-compose.yml --profile alcohol-test config`
- validate-only, 3-window smoke/replay, DB/report 비교, full 100-account import.
- `git diff --check`

### Minimality and Style-Fidelity Review

- importer가 기존 account/repository/model/storage/service를 호출하고 두 번째 persistence path를 만들지 않는지 확인한다.
- 모든 changed path가 map에 있고 dependency, migration, route, model, unrelated formatting 변경이 없는지 확인한다.

### Rollout and Rollback

profile은 opt-in이다. code rollback은 importer service/command/docs를 제거한다. 이미 적재된 test data는 기존 administrative deletion 절차를 따른다.

### Risks and Mitigations

| Risk | Impact | Mitigation or Evidence |
| --- | --- | --- |
| Docker Desktop 또는 PyTorch 미사용 | integration import 실행 불가 | Docker backend image를 사용하고 환경 blocker를 정확히 보고한다. |
| partial run 후 source file 변경 | idempotency conflict | stable run metadata와 canonical UUID/checksum conflict로 명시적으로 실패한다. |
| full import의 시간/storage 사용 | 긴 local operation | validate/smoke 후 file 단위 streaming과 resume을 사용한다. |
| timestamp rebasing으로 source time이 가려짐 | 연구 traceability 손실 | 원본 window timestamp와 offset을 `device_info` 및 report에 저장한다. |

## 10. Revision and Progress

### Design Revision History

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-21T11:32:44+09:00 | user-approved-plan | complete implementation design, repository reuse, exact target, budget, validation을 기록했다. | D-001, D-002, D-003, D-004, D-005, D-006 | Q-001, Q-002, Q-003 |
| 2 | 2026-07-21T12:04:26+09:00 | repository-evidence-correction | 빈 source window 5개, 적재 가능 16,334개, partial completion behavior를 기록했다. | D-007 | None |

### Implementation Progress Record

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-21T11:32:44+09:00 | 1 | - | ready | 사용자가 reviewed plan의 구현을 명시적으로 요청했다. |
| 2026-07-21T11:36:00+09:00 | 1 | WS1, WS2 | implementing | spec 검증 후 target이 분리된 두 slice를 시작했다. |
| 2026-07-21T12:04:26+09:00 | 2 | WS1 | verified | focused test 16개와 backend regression 175개가 통과했고 실데이터 검증이 100/16,339/빈 window 5개와 일치했다. |
| 2026-07-21T12:04:26+09:00 | 2 | WS2 | verified | Compose profile config, normal service 분리, scope, patch budget, 이중 언어 guide 검토가 통과했다. |
| 2026-07-21T12:04:26+09:00 | 2 | - | complete | Docker daemon을 사용할 수 없어 live DB/model smoke와 full import는 guide에 기록된 운영자 검증으로 남겼다. |
