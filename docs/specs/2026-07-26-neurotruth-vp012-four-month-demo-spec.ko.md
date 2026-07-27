# NeuroTruth VP-012 4개월 데모 — 구현 명세

<!-- feature-planner-control
{
  "workflow": "feature-planner/v7",
  "state": "complete",
  "source_spec": "docs/specs/2026-07-26-neurotruth-vp012-four-month-demo-spec.md",
  "korean_mirror": "docs/specs/2026-07-26-neurotruth-vp012-four-month-demo-spec.ko.md",
  "spec_revision": 9,
  "reviewed_revision": 9,
  "selected_strategy": "STRAT-2",
  "implementation_direction": "preserve",
  "direction_decision_id": null,
  "minimal_change_policy": "strict",
  "final_domain_gate": "confirmed_none",
  "open_question_ids": [],
  "active_slices": [],
  "next_action": "none"
}
-->

> 영문 파일이 구현 기준이며, 이 파일은 동기화된 한국어 검토본입니다.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `complete`, revision 9, 구현·검증 완료 |
| Outcome | 준비된 VP-012 계정이 정상 로그인 시 최신 120일 시나리오를 생성하고 해당 계정이 기존 앱 로그아웃을 실행하면 완전히 삭제되는 구조 |
| Recommended implementation | `STRAT-2` — 기존 seeder와 auth lifecycle을 확장하며 demo API·migration·scheduler·mobile-only 저장소는 추가하지 않음 |
| Planned production targets | `apps/backend/app/maintenance/seed_vp012_demo.py` — `Vp012DemoSeeder`, `run_cli`; `apps/backend/app/services/auth.py` — `AuthService.login`, `AuthService.logout`; `apps/backend/app/api/v1/routes/auth.py` — `_raise_service_error`, `login`, `logout`; `apps/backend/app/schemas/auth.py` — `LoginInput.email`; `apps/backend/app/core/runtime.py` — `initialize_runtime`; `apps/backend/app/core/config.py` — `SecuritySettings`; `apps/backend/app/repositories/postgres.py` — `refresh-token owner lookup / transaction boundary`; `apps/mobile/core/src/main/kotlin/com/neurotruth/mobile/core/net/AuthenticatedApiClient.kt` — `logout`; `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/settings/SettingsViewModel.kt` — `logout`, `tearDownAndSignOut` |
| Expected additions | New production files: `apps/backend/app/maintenance/seed_vp012_demo.py`; dependencies: None; shared abstractions: None |
| Work plan | WS1·WS2·WS3·WS4 verified |
| Open questions | 없음 |
| Agent decisions to review | None |
| Last material change | Revision 9 — retry-safe mobile logout과 전체 반복 촬영 lifecycle 검증 완료 |

## 2. Outcome and Scope

### Outcome

운영자는 과거 행이 없는 명확한 가상 VP-012 로그인 계정을 준비할 수 있습니다. 계정이 armed 상태일 때 최초 비밀번호 로그인이 성공하면 그 로그인 시각을 기준으로 시간적으로 일관된 Watch 갈망 예측, 상한이 있는 알림 이벤트, AUQ, 자유대화 세션, 암호화 메시지, 상태 요약, 보고서를 120일치 생성합니다. 기존 앱 로그아웃이 명시적인 데모 종료 동작이며 데모 계정과 자료 전체를 제거합니다. 다음 촬영 전 운영자가 다시 계정을 준비할 수 있습니다. 한국어 제작 패키지는 90–120초 촬영 순서, 내레이션, 자막, 앱 조작, 고지 문구를 제공해 더미 데이터를 연구 결과로 오인시키지 않으면서 실제 Phone/Watch 화면을 촬영할 수 있게 합니다.

### In Scope

- `--dry-run`, 확인형 provision, 확인형 seed fallback, status, 확인형 delete CLI.
- 기본 비활성 설정, reserved demo identity, armed audit 상태로 보호하는 login-triggered seed 생성.
- reserved armed/started demo identity에만 적용되는 logout-triggered 계정 전체 삭제.
- 고정 seed와 안정 UUID를 이용한 중복 없는 재실행.
- `Asia/Seoul` 기준 명시한 `--anchor-date` 또는 오늘까지 120일.
- 단조로운 치료효과 곡선이 아닌 식당 업무·저녁·주말 변동.
- 기존 `Alcohol_Test/1_1_010_V1` 데모 시나리오에서 값 순서를 보존해 가져온
  10초 간격 360개 최근 1시간 곡선.
- 현재 대시보드가 읽는 prediction, alert, AUQ, session, message, intervention, state-inference, report 행.
- 현재 keyring과 정확한 AAD를 이용한 민감 필드 AES-256-GCM 암호화.
- 과거 seed 세션은 모두 완료 상태로 만들어 실시간 데모 세션 시작을 방해하지 않음.
- Watch 중심 핵심 흐름과 짧은 선택형 rPPG 장면을 포함한 한국어 촬영 대본.

### Out of Scope / Non-Goals

- 4개월 연속 원시 PPG/EDA, 가짜 얼굴 영상, STT 음성, rPPG 파형 생성 제외.
- seed 중 Bedrock, STT, FactorizePhys, 갈망 모델 호출 제외.
- schema migration, dependency, 모델 설정, 런타임 alert 정책 변경 제외.
- 자동 SSH 배포, Git push, 비데모 사용자 파괴 작업 제외.
- 앱 강제 종료, process death, access-token 만료, refresh, 네트워크 단절을 데모 종료로 해석하지 않음.
- CBT·대화·앱이 갈망 감소를 유발했다는 주장, 진단, 회복, 치료 성공 주장 제외.
- 합성 화면을 실제 모델 추론처럼 제시하지 않으며 seed 후 실제 앱·기기 화면을 촬영함.

### Users and Primary Flow

1. 운영자가 `DEMO_SCENARIO_ENABLED`를 활성화하고 `DEMO_PATIENT_PASSWORD`를 설정한 뒤 backend container에서 dry-run과 정확한 provision 명령을 실행합니다.
2. Provision은 안정된 가상 로그인, 암호화된 `"정우식"` profile, consent, armed audit record만 만들고 과거 시나리오 행은 만들지 않습니다.
3. 정확한 reserved account의 비밀번호 로그인이 성공하면 token 발급 전에 현재 `Asia/Seoul` 로그인 시각을 기준으로 120일 시나리오를 원자적으로 생성합니다.
4. 촬영자는 Watch 측정, 알림, AUQ, 대화, rPPG, 대시보드 장면을 촬영합니다.
5. 사용자가 기존 앱 로그아웃을 누르면 backend가 계정과 연계 자료 전체를 삭제한 뒤에만 로그아웃 성공을 반환합니다. 삭제 실패는 재시도 가능한 오류를 반환하고 다음 로그아웃 시도를 위해 계정을 남깁니다.
6. 다음 촬영 전에 운영자가 provision 명령을 다시 실행합니다. 확인형 delete CLI는 운영 복구 경로로 유지합니다.

### Current Assumptions and Constraints

- 페르소나는 가상 NeuroSync `VP-012`이며 음주 갈망 데모 행동에만 적용합니다.
- 기존 미커밋 mobile/spec 변경은 관련 없는 사용자 작업으로 그대로 보존합니다.
- seed 전 활성 binary `craving_model` 행이 존재해야 하며 CLI는 활성 모델 소유권을 변경하지 않습니다.
- 비밀번호는 `DEMO_PATIENT_PASSWORD`에서만 읽고 명령 인자나 문서에 저장하지 않습니다.
- `DEMO_SCENARIO_ENABLED` 기본값은 `false`이며 일반 사용자와 설정 비활성 상태의 demo identity는 정상 인증 흐름을 유지합니다.
- reserved profile 표시 이름은 demo suffix가 없는 `"정우식"`이며 가상/demo 고지는 seeded metadata와 제작 runbook에 유지합니다.
- 최근 1시간 곡선은 VP-012 참여자의 측정값이 아닌 Alcohol_Test 데모용
  모델 출력이며, 각 최근 prediction에 출처를 보존합니다.

## 3. Repository Pattern Baseline

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Maintenance 안전 | 변환·삭제 CLI는 dry-run, 정확한 확인값, transaction, advisory lock, 정제된 출력, audit을 사용 | `apps/backend/app/maintenance/migrate_auq_zero_based.py::AuqZeroBasedMigrator` | 동일한 CLI·transaction 방식을 사용 |
| 암호화 | 민감 값은 `AesGcmKeyring`과 `aad_for(table,column,patient,record)` 사용 | `apps/backend/app/core/security/crypto.py`; `apps/backend/app/services/session.py::_encrypt_json` | 평문 fallback 금지, nonce 재사용 금지 |
| 대시보드 원천 | prediction, alert, AUQ, session, intervention, inference, report를 집계 | `apps/backend/app/repositories/postgres.py::craving_dashboard_rows`; `craving_calendar_rows` | demo 전용 API/schema 대신 기존 table을 seed |
| 인증 | 환자 비밀번호는 Argon2id, profile/consent는 현재 계약 사용 | `apps/backend/app/core/security/passwords.py::hash_password`; `SqlAlchemyV25Repository.create_patient` | 정상 로그인 가능한 환자 계정 생성 |
| 페르소나 | VP-012는 식당·저녁 스트레스가 있는 가상 SI-negative 음주 페르소나 | `../neurosync/docs/ai/personas/VP-012_first_visit_alcohol.md` | 영상에서 가상·비진단임을 유지 |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | Maintenance CLI transaction/confirmation pattern | `apps/backend/app/maintenance/migrate_auq_zero_based.py` | entrypoint, 환경 파싱, advisory lock, report 출력 재사용 |
| R-002 | AES-GCM keyring and AAD helpers | `apps/backend/app/core/security/crypto.py` | 이름, AUQ, session state, message, intervention, inference, report 암호화 |
| R-003 | Argon2id password helper | `apps/backend/app/core/security/passwords.py::hash_password` | 운영자가 제공한 데모 비밀번호 해시 |
| R-004 | Existing dashboard tables and aggregation | `apps/backend/app/repositories/postgres.py` | 현재 API가 소비하는 행만 삽입 |
| R-005 | VP-012 persona facts and dialogue style | `../neurosync/docs/ai/personas/VP-012_first_visit_alcohol.md` | 재현 가능한 대사와 시간 변동 구성 |
| R-006 | 지속 반등형 최근 1시간 모델 곡선 | `../Alcohol_Test/demo_scenarios/1_1_010_V1.json`; `../Alcohol_Test/docs/ai/personas/NT-DP-002_1_1_010_V1_rebound.ko.md` | 기존 360개 값 순서와 60분 시간축 정규화 보존 |

## 4. Decisions and Questions

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | Persona | 가상 VP-012 한 명을 데모 사용자로 사용한다. | user | 사용자가 추천 기본안을 승인했다. | NeuroTruth 음주 갈망 흐름과 일치한다. | confirmed | resolved |
| D-002 | Duration | 설정 가능한 anchor date까지 120일을 생성한다. | user | 사용자가 4개월 기본안을 승인했다. | 일·주·월 대시보드 이력을 확보한다. | confirmed | resolved |
| D-003 | Narrative | 식당·저녁·주말 변동을 사용하고 단조로운 호전을 만들지 않는다. | user | 승인안에서 즉시 치료효과 주장을 제외했다. | 영상이 인과가 아닌 기록 묘사에 머문다. | confirmed | resolved |
| D-004 | Video | 90–120초 Watch 중심 실제 기기 대본에 짧은 선택형 rPPG 장면을 둔다. | user | 사용자가 추천 영상 묶음을 승인했다. | 핵심 제품 흐름을 우선 촬영한다. | confirmed | resolved |
| D-005 | Safety | 안정된 가상 계정만 seed하며 생성·삭제에 정확한 확인값을 요구한다. | repository | R-001과 기존 audit/security 관례. | 연구 기록과 다른 계정을 건드리지 않는다. | not-required | resolved |
| D-006 | Raw signals | 4개월 원시 센서 파일은 만들지 않고 대시보드 행만 생성한다. | user | 승인안이 대표 실기기 신호만 촬영하도록 제한했다. | 데이터 크기와 가짜 생리신호 오인을 줄인다. | confirmed | resolved |
| D-007 | Change budget | 파일·동작·dependency·abstraction·direction 변경 없이 WS1 production-line 확장 경보를 650에서 850으로 높인다. | repository | 9개 기존 schema record group과 필드별 정확한 AES-GCM AAD를 명시한 최소 CH-001이 778줄로 측정됐다. | 가독성·보안 압축을 피하면서 승인 설계를 유지한다. | not-required | resolved |
| D-008 | Profile | 가상 계정 표시 이름은 demo suffix 없이 `"정우식"`으로 사용한다. | user | 사용자가 “가상 데모” suffix 제거를 명시했다. | 앱 profile은 자연스럽게 보이고 seeded metadata와 runbook에는 가상 고지를 유지한다. | confirmed | resolved |
| D-009 | Live alert isolation | 최근 1시간 seed의 마지막 세 prediction을 위험 미만으로 둔다. | user | 실제 Watch 촬영이 자체적으로 3회 연속 위험을 만들어야 한다. | 과거 seed가 실시간 위험 streak를 즉시 유발하거나 오염하지 않는다. | confirmed | resolved |
| D-010 | Demo start | 사전 provision되어 armed 상태인 reserved demo account의 비밀번호 로그인 성공 시 120일 시나리오를 시작한다. | user | 사용자가 demo account 연결 순간에 scenario가 시작되길 원했다. | 과거 timestamp가 실제 촬영 시작에 맞춰진다. | confirmed | resolved |
| D-011 | Repeat takes | 각 촬영 완료 후 demo account와 scenario 전체를 제거하고 다음 촬영 전 다시 provision한다. | user | 실수한 촬영을 이전 자료 누적 없이 반복해야 한다. | 매 촬영이 깨끗한 identity와 dataset에서 시작한다. | confirmed | resolved |
| D-012 | Demo end | 기존 앱 logout을 정확한 demo-end boundary로 사용한다. 전체 demo 삭제가 commit된 뒤에만 logout success를 반환하고 삭제 실패는 재시도 가능하게 한다. | user | 사용자가 “로그아웃 버튼으로 보고 싶다.”라고 답했다. | 신규 mobile button/API route가 없고 force-close·disconnect는 삭제를 일으키지 않는다. | confirmed | resolved |
| D-013 | Error mapping | 정제된 retryable demo lifecycle `503` 응답을 위해 기존 auth route error mapper를 확장한다. | repository | `routes/auth.py`는 현재 authentication/authorization/conflict 오류만 mapping하고 logout에는 service-error mapping이 없다. | 신규 route 없이 FR-011·FR-013을 충족한다. | not-required | resolved |
| D-014 | Login validation | 일반 사용자처럼 보이는 가상 로그인 `woosik.jeong@neurotruth.kr`를 사용하고 `LoginInput.email`은 strict `EmailStr`로 유지한다. | user | reserved `.invalid` 로그인이 촬영 화면에서 데모 계정처럼 보였다. | 일반 이메일 검증을 유지하면서 가상 프로필을 데모 화면에 일관되게 표시한다. | not-required | resolved |
| D-015 | Mobile logout | Backend logout 성공 후에만 local credential과 per-user cache를 지우고 transport/non-2xx 실패 시 session을 유지하며 retryable error를 표시한다. | repository | 현재 `AuthenticatedApiClient.logout`은 `finally`에서 지우고 `SettingsViewModel`은 결과를 무시해 D-012 retry 의미와 충돌한다. | 신규 screen/API 없이 기존 logout button이 신뢰 가능한 demo-end boundary가 된다. | not-required | resolved |
| D-016 | 최근 1시간 출처 | 임의 7구간 파형을 기존 `Alcohol_Test/1_1_010_V1` 360개 곡선으로 교체하고, MA10이 있으면 MA10을, 초기 warm-up에서는 원본 softmax를 사용한다. | user | VP-012에 수치형 갈망 시계열이 없어 사용자가 없을 경우 Alcohol_Test 데이터를 사용하라고 지시했다. | 명시적 데모 출처를 유지하면서 기존 지속 반등형 데모 곡선을 사용한다. | confirmed | resolved |
| D-017 | 실시간 위험 촬영 | 데모 flag가 켜진 경우 fresh login 뒤 reserved 환자의 첫 다섯 Watch upload에만 `0.38, 0.62, 0.82, 0.86, 0.89`를 overlay하고 production alert transaction으로 저장·판정한다. | user | 과거 seed 행은 SSE로 재생되지 않으므로 실제 모델만 기다려서는 위험→챗봇 장면을 보장할 수 없었다. | 공개 demo endpoint나 일반 사용자 prediction 변경 없이 약 60초에 세 번째 위험 결과가 정상 Phone/Watch 알림 하나를 생성한다. | confirmed | resolved |
| D-018 | 과거 대시보드 일관성 | 결정론적인 아침·점심·저녁 Watch 착용 구간에만 가중 15분 행을 만들고 나머지 시간은 미측정으로 둔다. 120일에 과거 이벤트 날짜 59개를 분산하고 모든 이벤트에 실제 위험 3회 연속 근거를 둔다. | user | 사용자가 매일 24시간 연속 측정처럼 보이는 달력을 거부하고 갈망 이벤트 이력을 더 잘 보이게 해 달라고 두 차례 요청했다. | 측정된 시간은 10초 샘플 360개를 유지하지만 일간 합계는 최대 8,640보다 작고 미측정 시간은 빈 구간으로 남는다. | superseded | D-020 |
| D-019 | 로그인 당일 표시 | 데모 로그인 성공 시 최근 1시간 곡선 앞에 오늘 현재 시각 이전의 Watch 착용 구간을 만들고, 오늘 이벤트 1건, 이벤트 연결 AUQ, 완료 대화 세션, 상태 추론, 리포트를 생성한다. AUQ는 유효한 0..48 범위 안에서 화면에 잘 보이는 39..41점으로 둔다. | user | 당일 중첩 이력이 보이지 않았고 이벤트·AUQ 기록도 촬영 화면에서 여전히 너무 적었다. | 미래 행이나 임상 해석을 만들지 않고 로그인 현재 시각에 맞춰 당일 중첩 막대·이벤트·AUQ를 즉시 채운다. | superseded | D-020 |
| D-020 | 이벤트 밀도·AUQ 연결·수면 착용·안정 단계 균형 | 이벤트 날짜당 1건 패턴을 완료된 날짜마다 재현 가능한 무작위 2~10건으로 교체하고 운영의 15분 쿨다운과 하루 10건 상한을 적용한다. 모든 이벤트에는 AUQ·세션·리포트 하나를 연결한다. 120일 중 6일에는 수면 중 Watch 착용 구간을 넣되 수면 구간 합성 알림은 만들지 않고, 비이벤트 주간 baseline을 조정해 `low`/안정이 충분히 보이게 한다. | user | 15분 후 재알림 가능한 정책에 비해 하루 1건은 부자연스러웠고, 사용자는 이에 맞는 AUQ 이력, 드문 수면 착용, 더 많은 안정 단계도 요청했다. | 이벤트·AUQ 막대는 다양하지만 상한이 있고 어느 날도 10건을 넘지 않으며 seed는 재현 가능하다. 수면 착용은 예외적으로만 나타나고 안정 단계는 자동 검증에서 전체 측정 표시 가중치의 20~30%를 차지한다. | confirmed | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | Demo defaults | VP-012, 120일, 90–120초, Watch 중심, 짧은 rPPG를 확정할지 결정. | 페르소나·기간·촬영 범위를 결정한다. | 제안 묶음을 사용한다. | D-001 | answered | 사용자가 “엉 부탁할게”라고 답하여 D-001, D-002, D-004를 승인함. |
| Q-002 | Demo end boundary | 데모 완료를 앱 logout, 운영자 CLI, 신규 명시적 종료 중 무엇으로 볼지 결정. | 계정 전체 삭제는 파괴적이므로 모호하지 않은 trigger 하나가 필요하다. | 기존 앱 logout을 사용하고 force-close·timeout은 사용하지 않는다. | D-012 | answered | 사용자가 로그아웃 버튼을 선택함. |

## 5. Requirements and Acceptance Criteria

### Functional Requirements

- **FR-001:** CLI는 `--dry-run`, `--confirm SEED-VP012-120D-DEMO`, `--status`, `--delete-confirm DELETE-VP012-DEMO`를 지원해야 합니다.
- **FR-002:** seed는 현재 consent를 가진 정상 로그인 가능 가상 환자 하나만 생성하고 비데모 계정은 바꾸지 않아야 합니다.
- **FR-003:** 120일 동안 미래 시각이 없는 대시보드 호환 prediction을
  결정론적으로 생성하고 네 단계 변동을 포함해야 합니다. 최근 1시간은
  정확한 `Alcohol_Test/1_1_010_V1` 360개 값을 10초 간격으로 사용하며,
  출처를 기록하고 별도 보간이나 임의 단계 구간을 만들지 않아야 합니다.
  과거 prediction은 명시한 Watch 착용 구간에만 존재하고 미측정 시간은
  빈 구간으로 남겨야 합니다.
- **FR-004:** alert는 위험 prediction에 연결되고 15분 이상 간격이며 demo로 표시되어야 합니다. 완료된 과거 날짜마다 결정론적 무작위 2~10건을 포함하고 어느 날도 10건을 넘지 않아야 하며, 로그인 당일 행은 미래 시각을 만들지 않아야 합니다.
- **FR-005:** AUQ는 version `2.0`, 8개 `0..6` 응답, 합계 `0..48`을 사용해야 합니다. 로그인 당일과 달력 화면이 채워지도록 모든 seed 이벤트에 AUQ 하나를 연결해야 합니다.
- **FR-006:** 완료된 자유대화 세션은 암호화된 user/assistant turn, 선택형 delivered intervention, state inference, ready report metadata를 가져야 합니다.
- **FR-007:** 확인형 seed를 다시 실행해도 같은 계정·record ID를 사용하고 중복 생성하지 않아야 합니다.
- **FR-008:** 확인형 삭제는 안정된 demo user와 cascade record만 삭제하고 다른 사용자는 건드리지 않아야 합니다.
- **FR-009:** 제작 패키지는 시간별 shot, 실제 기기 조작, 한국어 내레이션·자막, persona 응답, 데이터 고지, rPPG 포함, export checklist를 제공해야 합니다.
- **FR-010:** `--provision-confirm PROVISION-VP012-DEMO`는 reserved login, 암호화된 `"정우식"` profile, consent snapshot, armed audit state만 만들고 과거 시나리오 행은 만들지 않아야 합니다.
- **FR-011:** `DEMO_SCENARIO_ENABLED=true`일 때 정확한 reserved armed identity의 비밀번호 로그인이 성공하면 token 발급 전에 현재 서울 로그인 시각 기준 120일 시나리오를 advisory-locked transaction 하나에서 seed해야 합니다.
- **FR-012:** Refresh, armed 상태 소비 후 반복 login, 일반 사용자, `DEMO_SCENARIO_ENABLED=false` 상태의 모든 인증은 demo data를 seed하거나 삭제하지 않아야 합니다.
- **FR-013:** 시작된 reserved demo account가 기존 앱 logout을 실행하면 auth session, profile, consent, prediction, alert, AUQ, session, message, intervention, state inference, report, sensor recording/file, rPPG capture/job을 성공 반환 전에 원자적으로 제거해야 하며 정제된 system audit만 남길 수 있습니다.
- **FR-014:** 새 촬영 전 운영자가 reserved account를 다시 provision해야 하며 자동 background 복원이나 scheduled seed를 만들지 않습니다.
- **FR-015:** Mobile logout은 backend logout 실패 시 credential, active-session state, cache, signed-in UI를 유지하고 retryable error를 표시하며 성공한 backend response 뒤에만 local teardown해야 합니다.

### Non-Functional Requirements

- **NFR-001:** 기존 구조를 보존하고 schema migration, dependency, shared abstraction, runtime 변경을 만들지 않습니다.
- **NFR-002:** seed는 advisory lock 아래 하나의 PostgreSQL transaction으로 실행하며 실패 시 전체 rollback합니다.
- **NFR-003:** 민감 내용은 현재 key와 정확한 AAD로 AES-256-GCM 암호화하고 출력에는 비밀번호·키를 노출하지 않습니다.
- **NFR-004:** 난수는 고정 seed, record ID는 안정 namespace에서 파생합니다.
- **NFR-005:** 사용자 문구에 진단, 임상 중증도, 치료효과, 즉시 감소, 인과적 호전을 주장하지 않습니다.
- **NFR-006:** 관련 없는 dirty-worktree 변경을 모두 보존합니다.
- **NFR-007:** migration, public demo API, mobile-only branch, scheduler, dependency, shared abstraction을 추가하지 않습니다.
- **NFR-008:** demo lifecycle은 기본 비활성이며 정확한 reserved user ID/email과 예상 audit state가 모두 맞아야 동작합니다.

### Acceptance Criteria

- **AC-001:** network-free test가 날짜 120일, 안정 ID/건수, 미래 시각 없음, AUQ 유효성, alert 간격, 비단조 stage 분포를 증명합니다.
- **AC-002:** 올바른 AAD 복호화와 잘못된 AAD 거부를 증명합니다.
- **AC-003:** dry-run 무쓰기, 잘못된 확인값의 DB 미접속, seed 재실행 idempotency, demo 전용 delete 범위를 증명합니다.
- **AC-004:** status가 평문 민감 정보 없이 patient ID, 기간, table count를 보고합니다.
- **AC-005:** 한국어 제작 패키지만으로 login→Watch→alert→AUQ→dialogue→dashboard→선택형 rPPG→export를 실제 존재 화면에서 촬영할 수 있습니다.
- **AC-006:** 집중 backend test와 spec pair validation이 통과하고 구현 diff에 관련 없는 mobile edit가 없습니다.
- **AC-007:** Provision test가 계정 인증 가능, 과거 demo row 0개, armed audit state 1개를 증명합니다.
- **AC-008:** Login test가 token 발급 전 scenario commit, login-relative timestamp, provision당 1회 실행, seed 실패 시 token 미발급과 login 거부를 증명합니다.
- **AC-009:** Logout test가 demo account 전체 삭제, 정제된 system audit 유지, 삭제 실패 전체 rollback 및 retry 성공, 일반 사용자 무영향을 증명합니다.
- **AC-010:** End-to-end test가 provision → login seed → logout delete → reprovision → second login seed를 stale row 없이 반복할 수 있음을 증명합니다.
- **AC-011:** Mobile core test가 non-2xx/transport logout 시 refresh token과 user 유지, 204 시 제거를 증명하고 Android compile이 Settings logout의 성공 후 signed-out 전환을 증명합니다.

### Edge and Failure Cases

- `DEMO_PATIENT_PASSWORD` 누락 또는 약한 값 → DB 접속 전 실패.
- 활성 binary craving model 없음 → `demo_model_unavailable`로 rollback.
- reserved email 또는 안정 ID가 비데모 사용 중 → `demo_identity_conflict`로 rollback.
- 기존 demo의 anchor/seed contract 불일치 → 부분 수정하지 않고 삭제 후 재생성 요구.
- anchor date가 서울 기준 미래 → 거부.
- 암호화·constraint·transaction 오류 → seed 전체 rollback.
- Demo login seed 실패 → 정제된 `503 demo_seed_failed`, access/refresh token 미발급, provisioned account armed 상태 유지.
- Demo logout 삭제 실패 → 재시도 가능한 오류 반환, logout success 미반환, 전체 삭제 rollback 후 사용자가 logout을 다시 누를 수 있음.
- 앱 force-close, process death, network loss, access-token expiry, refresh → demo account와 scenario 유지.
- 삭제 commit 이후 반복 logout/delete → 다른 account를 건드리지 않고 기존 unauthenticated/idempotent 결과 반환.
- Mobile이 demo logout `503` 또는 transport failure 수신 → 현재 session 유지와 오류 표시로 동일 logout button 재시도 가능.

## 6. Implementation Strategy and Direction

### STRAT-2 — Armed Account Auth-Lifecycle Demo Seed

- **Direction:** `preserve`
- **Current approach:** 기존 maintenance seeder에 identity-only provision command와 재사용 가능한 transaction method를 추가합니다. 기존 runtime construction을 통해 optional auth lifecycle hook을 연결합니다. Armed demo login은 token 발급 전에 seed하고 demo logout은 성공 반환 전에 계정 전체를 삭제합니다. 안정 UUID5, audit state, 정확한 reserved identity 검사, advisory lock 하나로 retry의 결정성과 격리를 보장합니다.
- **Existing flow to reuse:** R-001부터 R-005.
- **Why this is minimal:** 현재 dashboard와 login/logout endpoint가 필요한 경계를 이미 제공합니다. 신규 API route, mobile action, migration, scheduler, replay service, 연속 raw-signal generator가 필요하지 않습니다.
- **Behavior-preserving limitations:** 과거 raw PPG/EDA preview는 합성하지 않습니다. Live Watch와 rPPG 장면은 실제 기기·서비스에서 촬영합니다.
- **Explicit exclusions:** Mobile UI, runtime alert, model/provider call, schema, Compose topology, 관련 없는 문서 정리.
- **Compatibility and migration posture:** 기본 비활성입니다. Rollback은 `DEMO_SCENARIO_ENABLED=false` 설정 후 확인형 delete CLI로 남은 demo account를 제거하고 이전 image로 원래 auth lifecycle을 복원합니다.
- **Direction approval:** None.
- **Open-question sensitivity:** None.

### Material Alternatives Considered

| Strategy | Direction | Benefit | Additional code or risk | Decision |
| --- | --- | --- | --- | --- |
| 120일 연속 raw 20초 sensor file seed | preserve | 과거 PPG preview 가능 | 수백만 sample, 암호화 storage 비용, 가짜 생리 데이터 오인 | rejected |
| Demo replay API/service 또는 명시적 demo-end button 추가 | user-approved-divergence | 자동화된 live형 데모 | 신규 public/runtime 계약과 mobile 변경, demo 동작 배포 위험 | rejected |
| 일회성 direct SQL | preserve | 초기 구현이 짧음 | 안전한 암호화, AAD, password hash, test가 어려움 | rejected |
| 매 login마다 delete/reseed | preserve | Provision 단계가 없음 | reconnect나 login retry가 live take data를 지울 수 있음 | rejected |
| 앱 force-close를 demo end로 처리 | preserve | 명시적 logout 불필요 | Process death를 신뢰성 있게 관찰할 수 없고 복구 가능한 take를 파괴할 수 있음 | rejected |
| Operator CLI만 종료 경계로 유지 | preserve | 우발 삭제 위험이 가장 낮음 | 사용자가 요청한 앱 기반 반복 촬영 흐름을 충족하지 못함 | rejected |

## 7. Modification Map and Change Budget

### Modification Map

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | production | `apps/backend/app/maintenance/seed_vp012_demo.py` | `Vp012DemoSeeder`, `run_cli` | add | `apps/backend/app/maintenance/migrate_auq_zero_based.py` | 결정론적 prepare/validate/seed/status/delete 추가 | Implements FR-001 through FR-008 and NFR-001 through NFR-004. | WS1 | preserve |
| CH-002 | test | `apps/backend/tests/test_seed_vp012_demo.py` | file-level | add | `apps/backend/tests/test_auq_zero_based_migration.py` | pure-data, encryption, CLI, idempotency, delete-scope test 추가 | Proves AC-001 through AC-004 and AC-006. | WS1 | preserve |
| CH-003 | docs | `apps/backend/README.md` | Maintenance commands | extend | Existing maintenance command documentation | container command, 환경, status, seed, delete 예제 추가 | Supports FR-001, FR-002, and AC-005. | WS2 | preserve |
| CH-004 | docs | `docs/demo/VP-012_120day_demo_video.ko.md` | file-level | add | `docs/deployment/MOBILE_DEVICE_TEST_GUIDE.ko.md` | persona 고지, data narrative, shot list, narration, caption, export checklist 추가 | Implements FR-009 and NFR-005. | WS2 | preserve |
| CH-005 | production | `apps/backend/app/maintenance/seed_vp012_demo.py` | `Vp012DemoSeeder`, `run_cli` | extend | Existing WS1 seeder | identity-only provision, armed/started audit-state 검사, login-relative seed entry, complete delete 재사용 추가 | Implements FR-010, FR-011, FR-013, and FR-014. | WS3 | preserve |
| CH-006 | production | `apps/backend/app/services/auth.py` | `AuthService.login`, `AuthService.logout` | extend | Existing login/refresh/logout flow | token 발급 전과 logout success 전 guarded demo lifecycle 실행 | Implements FR-011 through FR-013. | WS3 | preserve |
| CH-007 | production | `apps/backend/app/core/config.py` | `SecuritySettings` | extend | Existing security environment parsing | 기본 비활성 `DEMO_SCENARIO_ENABLED` 추가 | Implements NFR-008. | WS3 | preserve |
| CH-008 | production | `apps/backend/app/core/runtime.py` | `initialize_runtime` | extend | Existing service wiring | global/new service layer 없이 optional demo lifecycle을 `AuthService`에 주입 | Supports CH-006 and NFR-007. | WS3 | preserve |
| CH-009 | production | `apps/backend/app/repositories/postgres.py` | refresh-token owner lookup / transaction boundary | extend | Existing refresh revoke and user deletion helpers | logout token owner를 확인하고 transaction 하나에서 demo-only delete 실행; 현재 primitive로 충분하면 edit 생략 | Supports FR-013 with the smallest repository change. | WS3 | preserve |
| CH-010 | test | `apps/backend/tests/test_seed_vp012_demo.py` | focused seeder lifecycle tests | extend | Existing seeder tests | provision, login-relative seed, complete delete, repeat take 증명 | Proves AC-007 and AC-010. | WS3 | preserve |
| CH-011 | docs | `.env.example` | demo configuration | extend | Existing environment example | secret value 없이 disabled flag와 demo password 입력 문서화 | Supports FR-010 through FR-014. | WS3 | preserve |
| CH-012 | docs | `apps/backend/README.md` | demo lifecycle commands | extend | Existing maintenance documentation | provision → login → logout-delete → reprovision 명령 문서화 | Supports FR-010 through FR-014. | WS3 | preserve |
| CH-013 | docs | `docs/demo/VP-012_120day_demo_video.ko.md` | demo operator lifecycle | extend | Existing VP-012 production runbook | 반복 촬영 단계와 logout 삭제 경고 정렬 | Supports FR-010 through FR-014. | WS3 | preserve |
| CH-014 | test | `apps/backend/tests/test_auth_service_v25.py` | auth lifecycle unit tests | extend | Existing authentication service tests | guarded login seed, logout delete/rollback, ordinary-user 격리, retry 증명 | Proves AC-008 and AC-009. | WS3 | preserve |
| CH-015 | test | `apps/backend/tests/test_auth_routes_v25.py` | auth route lifecycle tests | extend | Existing authentication route tests | 정제된 login/logout failure와 public route contract 무변경 증명 | Proves AC-008 and AC-009. | WS3 | preserve |
| CH-016 | production | `apps/backend/app/api/v1/routes/auth.py` | `_raise_service_error`, `login`, `logout` | extend | Existing authentication route error mapping | 신규 route 없이 정제된 retryable demo lifecycle failure를 HTTP 503으로 mapping | Implements FR-011 and FR-013. | WS3 | preserve |
| CH-017 | production | `apps/backend/app/schemas/auth.py` | `LoginInput.email` | extend | Existing strict login input schema | `EmailStr` 또는 정확한 reserved demo literal만 허용하고 patient/admin signup은 변경하지 않음 | Implements FR-011 without broadening signup validation. | WS3 | preserve |
| CH-018 | production | `apps/mobile/core/src/main/kotlin/com/neurotruth/mobile/core/net/AuthenticatedApiClient.kt` | `logout` | extend | Existing authenticated logout client | Backend response를 검증하고 성공 후에만 credential 제거 | Implements FR-015. | WS4 | preserve |
| CH-019 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/settings/SettingsViewModel.kt` | `logout`, `tearDownAndSignOut` | extend | Existing settings logout flow | Backend 실패 시 monitoring/session/cache를 유지하고 retryable error 노출 | Implements FR-015. | WS4 | preserve |
| CH-020 | test | `apps/mobile/core/src/test/kotlin/com/neurotruth/mobile/core/net/AuthenticatedApiClientTest.kt` | logout tests | extend | Existing logout credential test | 실패 시 보존과 성공 시 cleanup 증명 | Proves AC-011. | WS4 | preserve |

### Change Budget

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 2 | 1 | 1 | 850 | None | None |
| WS2 | 2 | 0 | 0 | 0 | None | None |
| WS3 | 13 | 7 | 0 | 290 | None | None |
| WS4 | 3 | 2 | 0 | 35 | None | None |

Production-line budget은 확장 경보이며 코드 압축 목표가 아닙니다.

## 8. Work Plan

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | Add reversible deterministic VP-012 120-day seed tooling | None | DemoParallel | CH-001, CH-002 | `apps/backend/app/maintenance/seed_vp012_demo.py`, `apps/backend/tests/test_seed_vp012_demo.py` | `apps/mobile/**`, migrations, schema, Compose, existing maintenance files | FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-007, FR-008, NFR-001, NFR-002, NFR-003, NFR-004, NFR-006, AC-001, AC-002, AC-003, AC-004, AC-006 | `pytest -q apps/backend/tests/test_seed_vp012_demo.py` from repository root with backend path configured | verified |
| WS2 | Add operator commands and Korean demo-video production pack | None | DemoParallel | CH-003, CH-004 | `apps/backend/README.md`, `docs/demo/VP-012_120day_demo_video.ko.md` | `apps/mobile/**`, code, schema, existing design handoff files | FR-001, FR-002, FR-009, NFR-005, NFR-006, AC-005 | Manual link/command/copy review and forbidden-claim search | verified |
| WS3 | Add repeatable provision → login seed → logout delete lifecycle | WS1 | SerialAuthLifecycle | CH-005, CH-006, CH-007, CH-008, CH-009, CH-010, CH-011, CH-012, CH-013, CH-014, CH-015, CH-016, CH-017 | `apps/backend/app/maintenance/seed_vp012_demo.py`, `apps/backend/app/services/auth.py`, `apps/backend/app/api/v1/routes/auth.py`, `apps/backend/app/schemas/auth.py`, `apps/backend/app/core/config.py`, `apps/backend/app/core/runtime.py`, `apps/backend/app/repositories/postgres.py`, `apps/backend/tests/**`, `.env.example`, `apps/backend/README.md`, `docs/demo/**` | `apps/mobile/**`, migrations, schemas, model/STT/rPPG code, public route additions | FR-010, FR-011, FR-012, FR-013, FR-014, NFR-002, NFR-003, NFR-006, NFR-007, NFR-008, AC-007, AC-008, AC-009, AC-010 | Focused seeder/auth tests, backend regression, disposable PostgreSQL lifecycle, strict spec/scope/patch checks | verified |
| WS4 | Make the existing mobile logout button retry-safe | WS3 | SerialMobileLogout | CH-018, CH-019, CH-020 | `apps/mobile/core/src/main/kotlin/com/neurotruth/mobile/core/net/AuthenticatedApiClient.kt`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/settings/SettingsViewModel.kt`, `apps/mobile/core/src/test/kotlin/com/neurotruth/mobile/core/net/AuthenticatedApiClientTest.kt` | Backend, Wear OS, other Phone screens, navigation, new UI | FR-015, NFR-006, NFR-007, AC-011 | Core unit test plus app unit/compile check and scope/patch/spec validation | verified |

### Parallelization Rationale

WS1은 검증된 backend maintenance 기반을 소유하고 WS2는 검증된 문서를 소유합니다. WS3는 auth token 발급과 파괴적 logout이 하나의 lifecycle boundary를 공유하므로 원자적 동작 하나로 직렬 검토합니다.

### Final Integration

- 집중 seed test와 관련 maintenance CLI test를 실행합니다.
- `specctl.py check-scope`, `check-patch`, strict spec validation을 실행합니다.
- dirty-worktree 기준 complete diff를 검사합니다.
- Docker 사용 가능 시 disposable 또는 명시 승인 DB에만 dry-run/status를 실행하며 확인 명령 없이 기존 DB를 seed하지 않습니다.
- 운영자가 대상 demo DB를 seed한 뒤에만 실제 Phone/Watch 영상을 촬영합니다.
- Disposable DB에서 disabled flag의 일반 login/logout 무영향을 확인한 뒤 provision → login → account-data count → logout → zero-account count → reprovision을 실행합니다.

## 9. Validation, Rollout, and Risk

### Validation Plan

- Pure dataset: 120일, 최근 1시간, 네 단계 경계, 미래값 없음, 월별 비단조성.
- Persistence: transaction, advisory lock, reserved identity, idempotency manifest, exact delete scope.
- Security: password env 전용, AES-GCM AAD round trip, serialized DB parameter 내 민감 평문 부재.
- UI 호환: 현재 dashboard query가 schema/API 변경 없이 seed 행을 소비.
- Video: `안정·관찰·주의·위험`, AUQ 0–48, 위험 3회/15분, 연구용 고지 일치.
- Auth lifecycle: disabled flag와 일반 사용자는 무영향, provision은 history 0개, login은 token 전에 history 생성, logout은 성공 전에 전체 삭제 commit, 전체 cycle 반복 가능.

### Minimality and Style-Fidelity Review

- migration, public API route, 신규 runtime service layer, dependency, raw-signal generator, mobile edit는 거부합니다.
- CLI parsing, transaction, 정제 오류, test를 R-001과 비교합니다.
- 승인 데모에 표시되지 않거나 필요 없는 table seed는 제거합니다.

### Rollout and Rollback

Auth lifecycle은 `DEMO_SCENARIO_ENABLED=false`로 기본 비활성 배포합니다. 촬영 시 운영자가 이를 활성화하고 password env를 설정한 뒤 dry-run과 exact provision을 실행하고 로그인합니다. Login이 scenario를 생성하고 기존 앱 logout이 account와 scenario를 삭제합니다. Exact delete CLI는 복구 경로로 유지합니다. 다음 촬영 전 identity를 다시 provision합니다. Flag를 `false`로 되돌리면 기존 record를 삭제하지 않고 자동 start/end 동작만 즉시 비활성화합니다.

### Risks and Mitigations

| Risk | Impact | Mitigation or Evidence |
| --- | --- | --- |
| 더미 데이터가 연구 결과로 오인 | 잘못된 해석 | `.invalid` 계정, `DEMO/FICTIONAL` metadata, 영상 고지 |
| 치료효과 서사 | 제품 주장 왜곡 | 비단조 패턴과 금지 문구 검토 |
| seed가 실제 record 영향 | 무결성·개인정보 위험 | 안정 reserved identity, lock, transaction, exact confirmation, delete test |
| 4개월 데이터로 dashboard 지연 | 데모 성능 저하 | 연속 raw 신호 대신 희소 대표 measurement session |
| 최종 촬영 자동화 제한 | 영상 미완성 | 촬영 가능한 대본 제공, 운영자 로그인 후 실제 기기 화면 사용 |
| 우발적 account 삭제 | demo take 손실 | 정확한 reserved identity, started audit state, 명시적 앱 logout이 모두 맞을 때만 삭제하며 force-close·refresh·disconnect는 제외 |
| Logout 삭제 부분 실패 | cleanup 불일치 | Transaction 하나, rollback, retryable error, logout success 미반환 |
| Login seed 지연·실패 | 촬영 시작 불가 | Token 전에 seed, 정제된 503, armed state retry 유지, 부분 인증 session 금지 |

## 10. Revision and Progress

### Design Revision History

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-26T15:00:00+09:00 | user-confirmation | 승인된 기본안으로 VP-012 120일 seed와 영상 제작 설계를 생성함. | D-001, D-002, D-003, D-004, D-005, D-006 | Q-001 |
| 2 | 2026-07-26T15:45:00+09:00 | implementation-evidence | 동일 방향 최소 patch가 778줄로 측정되어 WS1 line-budget 경보만 650에서 850으로 보정함. 동작·대상·dependency·abstraction·strategy는 바뀌지 않음. | D-007 | None |
| 3 | 2026-07-26T17:10:00+09:00 | user-refinement | 반복 촬영 lifecycle을 identity-only provision, reserved-account login 성공 시 scenario 생성, 명시적 종료 경계에서 account 전체 제거로 변경함. | D-008, D-009, D-010, D-011 | Q-002 |
| 4 | 2026-07-26T17:25:00+09:00 | user-confirmation | 기존 앱 logout을 demo-end boundary로 확정하고 삭제 commit 후 logout success, 실패 시 retry를 요구함. | D-012 | Q-002 |
| 5 | 2026-07-26T17:35:00+09:00 | repository-evidence | 현재 route가 지정된 정제형 retryable 503 lifecycle failure를 반환할 수 없어 기존 auth route error mapper를 최소 target에 추가함. | D-013 | None |
| 6 | 2026-07-26T17:45:00+09:00 | test-evidence | 기존 EmailStr validator가 AuthService 전에 정확한 reserved `.invalid` demo address를 거부하여 `LoginInput.email`만 추가함. Signup은 strict를 유지함. | D-014 | None |
| 7 | 2026-07-26T18:05:00+09:00 | implementation-evidence | 예산 안에서 guarded auth lifecycle, 정확한 reserved login validation, 반복 가능한 account 전체 cleanup, configuration, tests, operator documentation 구현·검증을 완료함. | D-010, D-011, D-012, D-013, D-014 | None |
| 8 | 2026-07-26T18:15:00+09:00 | deletion-path-review | 현재 mobile logout client와 SettingsViewModel이 backend demo deletion 실패에도 credential을 폐기하고 signed-out 처리하는 것을 확인해 최소 target에 추가함. | D-015 | None |
| 9 | 2026-07-26T18:30:00+09:00 | implementation-evidence | Server-success-only local logout, failure retention/retry, password-change local teardown, Android core tests, app compile, WS4 scope/budget을 검증함. | D-015 | None |

### Implementation Progress Record

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-26T15:00:00+09:00 | 1 | — | ready | 사용자가 구현 전에 구체적인 기본 묶음을 승인했으며 pair를 생성해 worker orchestration 준비를 완료함. |
| 2026-07-26T15:35:00+09:00 | 1 | WS2 | verified | Scope·patch budget 검사가 통과했고, 명령 문서와 한국어 110초 실기기 제작팩을 현재 화면·금지 주장 기준으로 검토함. |
| 2026-07-26T15:45:00+09:00 | 2 | WS1 | pending | Repository 근거로 확장 경보를 850으로 보정했고, 승인 전 최근 1시간 전체 coverage와 실제 연속 위험 3회 근거 보정을 요청함. |
| 2026-07-26T16:35:00+09:00 | 2 | WS1 | verified | 신규 및 유지보수 회귀 테스트 17개 통과. 일회성 PostgreSQL head migration, dry-run, 3,216개 prediction seed, 재실행 중복 방지, status, 정확 범위 delete, 삭제 후 0건, seed/delete 감사 기록 보존을 모두 확인함. |
| 2026-07-26T17:25:00+09:00 | 4 | WS3 | pending | 사용자가 기존 앱 logout을 파괴적 종료 경계로 확정했으며 strict spec validation 후 구현 가능함. |
| 2026-07-26T18:05:00+09:00 | 7 | WS3 | verified | Focused 26개와 전체 backend 189개 test 통과(1 skipped). Disposable PostgreSQL에서 migration 0001→0005, history 0 provision, login 3,216 prediction seed, logout 전체 삭제, history 0 reprovision, recovery delete를 증명함. Scope/patch/spec/compile 검사 통과. |
| 2026-07-26T18:30:00+09:00 | 9 | WS4 | verified | Android core test 강제 재실행과 app debug Kotlin compile 통과. Scope/patch 3/3 files, production 33/35 lines 통과. |
