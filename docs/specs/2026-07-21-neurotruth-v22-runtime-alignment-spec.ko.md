# NeuroTruth V22 런타임 정렬 — Living Implementation Specification

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

> 영문 문서가 구현 기준이며 이 문서는 사용자 검토용 동기화 번역본이다. Revision 1은 사용자가 승인한 계획과 계획 단계에서 확정한 선택을 기록한다.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `complete`, revision 2, 사용자 검토 완료 |
| Outcome | 기존 백엔드, Bedrock 대화 계층, Kotlin 테스트 앱을 이름이 변경된 V22 인계 자료와 일치시킨다. |
| Recommended implementation | `STRAT-1` — 현재 dashboard, session, 암호화 repository, Compose 소유자를 좁게 확장한다. |
| Planned production targets | `apps/backend/app/services/dashboard.py::PROBABILITY_RANGES, craving_dashboard`, `apps/backend/app/repositories/postgres.py::craving_dashboard_rows`, `apps/backend/app/services/sensor.py::prediction response`, `apps/backend/app/api/v1/routes/session.py::assessment`, `apps/backend/app/maintenance/migrate_auq_zero_based.py::new CLI`, `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt::Home/navigation/AUQ/rPPG routing`, `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt::AUQ scoring and entry methods`, `apps/mobile/app/src/main/java/com/example/healthsensor/AuthenticatedSessionApi.kt::postAssessment`, `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt::series/parser/charts/screen`, `apps/mobile/app/src/main/java/com/example/healthsensor/ServerUploader.kt::CravingPrediction, parser`, `apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringState.kt::publishPrediction`, `apps/mobile/app/src/main/java/com/example/healthsensor/RppgModels.kt::toPrediction`, `apps/mobile/app/src/main/java/com/example/healthsensor/WatchConnectionMonitor.kt::WatchRppgPresentationPolicy`; exact composite symbols: PROBABILITY_RANGES`, `craving_dashboard and `CravingPrediction`, parser |
| Expected additions | 새 production 파일: 1개 (`apps/backend/app/maintenance/migrate_auq_zero_based.py`); dependencies: None; shared abstractions: None |
| Work plan | WS1 backend와 WS2 Android를 고정 계약으로 병렬 진행할 수 있고 완료 뒤 WS3 docs를 동기화한다. |
| Open questions | None |
| Agent decisions to review | None |
| Last material change | Revision 2 — 승인된 최소 patch와 일치하도록 test-file Modification Map을 보정했다. |

## 2. Outcome and Scope

### Outcome

인증된 NeuroTruth 런타임과 Kotlin 테스트 앱이 V22 화면 인계와 일치한다. 사용자용 4단계 갈망 표시, 최근 1시간 그래프, zero-based AUQ, Watch 기반 카메라 제한, AUQ 후 자유대화 흐름을 제공한다.

### In Scope

- `1h` probability series와 Watch prediction source metadata 추가.
- 신규 AUQ `0..6`/`0..48` 검증 및 확인형 명령으로 암호화된 기존 AUQ 변환.
- 기존 Bedrock/STT/DGX 구조와 자유대화 프롬프트 유지.
- Kotlin Phone의 홈, 대시보드, AUQ, rPPG 완료, 최신 source 동작 정렬.
- API 및 frontend handoff Markdown 동기화.

### Out of Scope / Non-Goals

- 새 AI service, dependency, DB column, Alembic revision, 관리자 web 재설계, Wear OS UI 재설계, PPT 수정, Git push, 운영 배포는 제외한다.
- Bedrock, STT, FactorizePhys, binary craving model, encryption, session, notification 구조를 교체하지 않는다.

### Users and Primary Flow

1. 인증된 환자는 홈에서 프로필, 4단계 최신 갈망 상태, Watch/camera 가용성을 확인한다.
2. Alert, 수동 Chat, 완료된 rPPG가 하나의 session을 생성하거나 재사용한다. 새 session은 AUQ 작성 또는 건너뛰기 후 자유대화로 이동한다.
3. Dashboard는 최근 1시간, 오늘 단계 구성, alert event, AUQ 0–48 이력과 PPG를 표시한다.

### Current Assumptions and Constraints

- V22 파일은 사용자가 V21에서 이름을 변경한 자료이며, 이후 명시적 사용자 결정이 오래된 문구보다 우선한다.
- Legacy AUQ 요청을 차단하므로 Backend와 Phone을 함께 배포한다.
- 기존의 관련 없는 working-tree 변경과 untracked 파일을 보존한다.

## 3. Repository Pattern Baseline

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Dashboard | `DashboardService`가 range를 검증하고 SQL 집계를 `V25Repository`에 위임한다. | `apps/backend/app/services/dashboard.py::DashboardService`; `apps/backend/app/repositories/postgres.py::craving_probability_rows` | 새 계층 없이 기존 range table과 payload를 확장한다. |
| Sessions/AUQ | FastAPI route가 입력을 검증하고 `SessionService`가 repository를 통해 암호화·저장한다. | `apps/backend/app/api/v1/routes/session.py::assessment`; `apps/backend/app/services/session.py::assessment` | route/service 경계 검증과 backend AES-GCM 소유권을 유지한다. |
| Prediction source | Sensor와 rPPG service가 표시 안전한 prediction dictionary를 저장·반환한다. | `apps/backend/app/services/sensor.py::SensorService`; `apps/backend/app/services/rppg.py::RppgService` | 새 response family 없이 metadata만 추가한다. |
| Mobile state | Compose 화면은 기존 ViewModel과 parser의 `StateFlow`를 소비한다. | `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt`; `PatientDashboard.kt` | 3탭, 전체화면 분기, 기존 API client를 재사용한다. |
| rPPG | `RppgViewModel`이 영속 job을 polling하고 완료 routing을 한 번만 claim한다. | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgViewModel.kt::markResultRouted` | camera/DGX 흐름을 바꾸지 않고 완료 orchestration만 확장한다. |
| Tests | Backend는 pytest fake, Android는 JVM unit test와 Gradle lint/build를 사용한다. | `apps/backend/tests/test_craving_bar_dashboard_v25.py`; `apps/mobile/app/src/test/...` | 현재 소유자 옆에 focused case를 추가한다. |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | Probability bucketing | `DashboardService.craving_probability_series` | 기존 table에 `1h=(60m,10s,360)`을 추가한다. |
| R-002 | Normalized AUQ aggregation | `SqlAlchemyV25Repository.craving_dashboard_rows` | 0–48 average와 normalized 호환값을 함께 반환한다. |
| R-003 | Versioned AES-GCM keyring and AAD | `app/core/security/crypto.py` | 기존 answers를 안전하게 복호화·재암호화한다. |
| R-004 | Conversation session manager | `ConversationSessionManager.start/ensure` | 모든 신규 진입은 선택형 AUQ, 활성 session은 직접 재개한다. |
| R-005 | rPPG route-once store | `RppgViewModel.markResultRouted` | prediction/session/navigation 중복을 막는다. |
| R-006 | Probability series gap segmentation | `CravingProbabilitySeriesState.segments` | 누락 구간이 끊긴 고정 1시간 그래프를 그린다. |

## 4. Decisions and Questions

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | Source | V22 PPT/Markdown을 이름이 변경된 V21 기준으로 사용하고 이후 명시적 선택을 우선한다. | user | 사용자가 V21 이름을 V22로 바꿨다고 확인했다. | 구현 자료를 확정한다. | confirmed | resolved |
| D-002 | Mobile | 기존 Kotlin Phone 테스트 앱을 수정하고 Wear UI는 보존한다. | user | 사용자가 기존 Kotlin 앱을 선택했다. | 모바일 범위를 제한한다. | confirmed | resolved |
| D-003 | Session | 새 alert/manual/rPPG session은 AUQ 작성·건너뛰기 후 자유대화로 이동하고 활성 session은 직접 재개한다. | user | 사용자가 모든 신규 session의 AUQ를 선택했다. | 진입 orchestration을 통일한다. | confirmed | resolved |
| D-004 | Camera | Watch 연결 중 camera measurement를 비활성화한다. | user | 사용자가 연결 시 비활성을 선택했다. | Home CTA만 변경한다. | confirmed | resolved |
| D-005 | AUQ | 신규·기존 AUQ를 item `0..6`, total `0..48`로 통일하고 암호화 이력을 변환한다. | user | 사용자가 전체 전환과 DB 변환을 선택했다. | 확인형 data operation을 추가한다. | confirmed | resolved |
| D-006 | AI architecture | Bedrock dialogue는 backend, STT service, DGX rPPG adapter 구조를 유지한다. | user | 사용자가 현재 구조 유지를 선택했다. | 새 service/dependency가 없다. | confirmed | resolved |
| D-007 | Craving labels | 정확한 한국어 문구와 `안전`, `관찰`, `주의`, `심각`을 사용한다. | user | 사용자가 직접 제공한 문구다. | wire key는 유지하고 UI label만 변경한다. | confirmed | resolved |
| D-008 | Migration implementation | Alembic data migration 대신 dry-run/confirm transactional maintenance CLI를 사용한다. | repository | schema 변경이 없고 암호문에는 application keyring/AAD가 필요하다. | Alembic head와 인증 암호화를 보존한다. | not-required | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | Source | 어떤 artifact가 기준인가? | UI target이 달라진다. | 이름이 변경된 V22 사용. | D-001 | answered | V22는 이름이 변경된 V21이다. |
| Q-002 | Mobile | 기존 Kotlin인가 신규 React Native인가? | 구현 범위가 달라진다. | 기존 Kotlin. | D-002 | answered | 기존 Kotlin 테스트 앱. |
| Q-003 | Session | AUQ는 어디에서 표시하는가? | navigation이 달라진다. | 모든 신규 session. | D-003 | answered | 모든 신규 session, 활성 session 재개. |
| Q-004 | Camera | Watch 연결 시 camera 가용성은? | 측정 정책이 달라진다. | 비활성. | D-004 | answered | 연결 중 비활성. |
| Q-005 | AUQ | 기존 점수를 보존·변환·숨김 중 무엇으로 처리하는가? | persistence와 rollout이 달라진다. | 암호화 행 변환. | D-005 | answered | Zero-based 전체 변환. |

## 5. Requirements and Acceptance Criteria

### Functional Requirements

- **FR-001:** `range=1h`는 최대 360개의 10초 probability bucket을 반환하고 빈 bucket은 생략한다.
- **FR-002:** Watch prediction은 `source=watch_sensor`, rPPG는 `source=camera_rppg`를 제공한다.
- **FR-003:** 신규 AUQ는 version `2.0`, 정확히 8개의 `0..6` response/scored item과 일치하는 `0..48` total만 허용한다.
- **FR-004:** 확인형 CLI는 표준 기존 `8..56` AUQ 행 전체를 encrypted answers, metadata, key version, audit evidence와 함께 원자적으로 변환한다.
- **FR-005:** 신규 alert/manual/rPPG session은 AUQ 또는 skip 후 자유대화로 이동하고 활성 session은 AUQ 없이 재개한다.
- **FR-006:** Home은 PPG/server/developer control 없이 profile/settings, 정확한 4단계 copy, source/time, Watch status, camera gating을 표시한다.
- **FR-007:** 오래된 rPPG 완료가 최신 Watch 결과를 덮거나 session/navigation을 중복 생성하지 않는다.
- **FR-008:** Dashboard는 고정 1시간 probability, 24시간 stacked stage, alert event, AUQ 0–48, PPG를 표시하고 state/report card는 숨긴다.
- **FR-009:** Bedrock free-dialogue prompt, bounded history, repeat repair, 502 retry, STT, local TTS를 유지한다.

### Non-Functional Requirements

- **NFR-001:** 현재 architecture, encryption/AAD, dependencies, 관련 없는 사용자 변경을 보존한다.
- **NFR-002:** Backend와 Phone을 함께 배포하고 maintenance CLI는 malformed/tampered ciphertext에서 전체 rollback한다.
- **NFR-003:** Camera prediction은 Phone-only이며 Wear로 전달하지 않는다.

### Acceptance Criteria

- **AC-001:** range/source/AUQ/migration/session 관련 backend focused/full test가 통과한다.
- **AC-002:** label, gating, routing, ordering, chart 관련 Android unit test, lint, debug APK build가 통과한다.
- **AC-003:** Local Compose가 healthy/ready가 되고 secret 없이 updated OpenAPI를 제공한다.
- **AC-004:** 장치·service 사용 가능 시 Phone/Watch/DGX smoke flow가 Watch/rPPG 진입에서 AUQ와 자유대화까지 완주한다.

### Edge and Failure Cases

- 불일치 AUQ 배열/총점 → `422 invalid_auq_scale`, 저장 없음.
- 변조·비표준 기존 AUQ → CLI 전체 rollback.
- 누락된 1시간 bucket → line segment 단절.
- Home 중 Watch 연결 → camera action 즉시 비활성; 이미 accepted된 rPPG job은 계속 처리.
- rPPG completion 중복 또는 현재 결과보다 오래됨 → history는 유지하되 최신 상태·session·navigation 중복 없음.
- Bedrock/STT/rPPG unavailable → 해당 기능만 기존 오류를 표시하고 인증과 사용 가능한 기능 유지.

## 6. Implementation Strategy and Direction

### STRAT-1 — 기존 소유자 확장

- **Direction:** `preserve`
- **Current approach:** 현재 service/repository/ViewModel/Compose 소유자에 좁은 contract branch와 UI state를 추가하고, encrypted data conversion에만 maintenance CLI 하나를 추가한다.
- **Existing flow to reuse:** R-001부터 R-006.
- **Why this is minimal:** 모든 요구사항에 현재 소유자와 test pattern이 있으며 encrypted data conversion만 새 실행 module이 필요하다.
- **Behavior-preserving limitations:** 기존 consumer 호환을 위해 `averageNormalizedScore`와 기존 range를 유지하고, wire stage key는 `low/observe/caution/high`를 유지한다.
- **Explicit exclusions:** refactor, generic chart library, navigation framework, dependency, schema column, AI service, PPT edit를 추가하지 않는다.
- **Compatibility and migration posture:** Backend를 먼저 배포해 legacy write를 거부하고 구 Phone 사용을 멈춘 뒤 dry-run/confirm CLI를 실행하고 새 Phone 앱을 배포한다. Rollback은 이전 image로 하며 변환된 AUQ는 자동 역변환하지 않는다.
- **Direction approval:** None.
- **Open-question sensitivity:** None.

### Material Alternatives Considered

| Strategy | Direction | Benefit | Additional code or risk | Decision |
| --- | --- | --- | --- | --- |
| Alembic ciphertext rewrite | user-approved-divergence | startup 시 실행 | migration layer가 application encryption 설정을 요구해 readiness에 위험하다. | rejected |
| Separate AI server | user-approved-divergence | 독립 확장 | V22 요구 없이 새 service/API/deployment가 필요하다. | rejected by D-006 |
| React Native implementation | user-approved-divergence | 미래 신규 앱과 일치 | 기존 테스트된 Phone app을 중복한다. | rejected by D-002 |

## 7. Modification Map and Change Budget

### Modification Map

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | production | `apps/backend/app/services/dashboard.py` | `PROBABILITY_RANGES`, `craving_dashboard` | extend | R-001/R-002 | 1h와 AUQ 0–48 field 추가. | FR-001, FR-008 | WS1 | preserve |
| CH-002 | production | `apps/backend/app/repositories/postgres.py` | `craving_dashboard_rows` | extend | R-002 | normalized와 함께 raw 0–48 AUQ average 반환. | FR-008 | WS1 | preserve |
| CH-003 | production | `apps/backend/app/services/sensor.py` | prediction response | extend | current public prediction dictionary | `source=watch_sensor` 추가. | FR-002 | WS1 | preserve |
| CH-004 | production | `apps/backend/app/api/v1/routes/session.py` | `assessment` | extend | current score range validation | AUQ v2 배열·총점 검증. | FR-003 | WS1 | preserve |
| CH-005 | production | `apps/backend/app/maintenance/migrate_auq_zero_based.py` | new CLI | add | R-003 and existing maintenance commands | atomic dry-run/confirm conversion 추가. | FR-004, NFR-002 | WS1 | preserve |
| CH-006 | test | `apps/backend/tests/test_craving_bar_dashboard_v25.py` | dashboard cases | extend | existing dashboard fakes | 1h와 AUQ field 검증. | AC-001 | WS1 | preserve |
| CH-007 | test | `apps/backend/tests/test_sessions_v25.py` | AUQ route/service cases | extend | current session tests | v2 validation 검증. | AC-001 | WS1 | preserve |
| CH-008 | test | `apps/backend/tests/test_auq_zero_based_migration.py` | new migration tests | add | maintenance test patterns | conversion, rollback, audit, idempotency 검증. | AC-001 | WS1 | preserve |
| CH-009 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt` | Home/navigation/AUQ/rPPG routing | extend | current three-tab Compose branch | Home 정렬과 신규 session AUQ routing. | FR-005, FR-006, FR-007 | WS2 | preserve |
| CH-010 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt` | AUQ scoring and entry methods | extend | current optional-AUQ state | 0..6 전환과 단일 entry orchestration. | FR-003, FR-005 | WS2 | preserve |
| CH-011 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/AuthenticatedSessionApi.kt` | `postAssessment` | extend | current AUQ request | v2 0–48 payload 전송. | FR-003 | WS2 | preserve |
| CH-012 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt` | series/parser/charts/screen | extend | R-006 and existing charts | 고정 1h chart, label/AUQ, state/report card 제거. | FR-008 | WS2 | preserve |
| CH-013 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/ServerUploader.kt` | `CravingPrediction`, parser | extend | current binary parser | source metadata 보존. | FR-002, FR-006 | WS2 | preserve |
| CH-014 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringState.kt` | `publishPrediction` | extend | latest prediction state | 오래된 최신 상태 교체 거부. | FR-007 | WS2 | preserve |
| CH-015 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgModels.kt` | `toPrediction` | extend | current camera conversion | camera source 명시. | FR-002, FR-007 | WS2 | preserve |
| CH-016 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/WatchConnectionMonitor.kt` | `WatchRppgPresentationPolicy` | extend | current CTA policy | Watch 연결 중 camera 비활성. | FR-006 | WS2 | preserve |
| CH-017 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/AuthenticatedSessionApiContractTest.kt` | session API contract tests | extend | existing session API tests | AUQ v2 request 동작 검증. | AC-002 | WS2 | preserve |
| CH-018 | docs | `apps/mobile/SERVER_API_SPEC.md` | prediction/AUQ/dashboard sections | edit | current API contract | 구현된 wire change 문서화. | FR-001–FR-005 | WS3 | preserve |
| CH-019 | docs | `docs/neurotruth_frontend_handoff_v22.md` | V22 behavior | edit | renamed V21 handoff | label, link, AUQ, routing 충돌 해소. | FR-005–FR-008 | WS3 | preserve |
| CH-020 | test | `apps/backend/tests/test_sensor_routes_v25.py` | Watch prediction source assertions | extend | current sensor route regression tests | Watch source 전파 검증. | AC-001 | WS1 | preserve |
| CH-021 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/PatientDashboardParserTest.kt` | dashboard parser/state tests | extend | existing dashboard tests | 1시간 gap, stage, AUQ 0–48 검증. | AC-002 | WS2 | preserve |
| CH-022 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt` | rPPG policy and latest-source tests | extend | existing rPPG tests | Watch camera gating과 stale-source rejection 검증. | AC-002 | WS2 | preserve |
| CH-023 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/StateCheckScoringTest.kt` | zero-based AUQ scoring tests | extend | existing AUQ scoring tests | item 0–6, total 0–48 검증. | AC-002 | WS2 | preserve |

### Change Budget

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 9 | 5 | 1 | 360 | None | None |
| WS2 | 12 | 8 | 0 | 500 | None | None |
| WS3 | 2 | 0 | 0 | 120 | None | None |

## 8. Work Plan

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | Backend contract와 encrypted AUQ conversion 구현. | None | P1 | CH-001, CH-002, CH-003, CH-004, CH-005, CH-006, CH-007, CH-008, CH-020 | `apps/backend/app/**`, `apps/backend/tests/**` | `apps/mobile/**`, `apps/web/**`, `apps/backend/alembic/**`, `apps/backend/app/maintenance/import_alcohol_test.py`, `apps/backend/tests/test_import_alcohol_test.py` | FR-001, FR-002, FR-003, FR-004, FR-008, NFR-001, NFR-002, AC-001, AC-003 | backend focused pytest and local backend Compose checks | verified |
| WS2 | Kotlin Phone UI/state/routing과 test 정렬. | None | P1 | CH-009, CH-010, CH-011, CH-012, CH-013, CH-014, CH-015, CH-016, CH-017, CH-021, CH-022, CH-023 | `apps/mobile/app/src/main/java/com/example/healthsensor/*.kt`, `apps/mobile/app/src/test/java/com/example/healthsensor/**` | `apps/backend/**`, `apps/mobile/wearos/**`, `apps/mobile/**/build/**` | FR-002, FR-003, FR-005, FR-006, FR-007, FR-008, FR-009, NFR-001, NFR-003, AC-002, AC-004 | Gradle unit/lint/assemble and available device smoke | verified |
| WS3 | API와 frontend Markdown 동기화. | WS1, WS2 | Serial | CH-018, CH-019 | `apps/mobile/SERVER_API_SPEC.md`, `docs/neurotruth_frontend_handoff_v22.md` | `docs/handoff/*.pptx`, `docs/specs/**` | FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-007, FR-008 | rg/manual contract comparison | verified |

### Parallelization Rationale

WS1과 WS2는 write scope가 분리되고 검토된 JSON contract가 고정돼 있다. WS3는 두 patch가 완료된 뒤 실제 구현을 문서에 반영한다.

### Final Integration

Backend 전체 test, Android Phone/Wear unit test, lint/build, Compose config/health를 실행하고 가능한 device/DGX smoke test를 수행한다. 전체 diff에서 관련 없는 기존 작업을 제외한다.

## 9. Validation, Rollout, and Risk

### Validation Plan

- Backend: focused dashboard/session/migration test 후 전체 pytest.
- Android: focused JVM test, `:app:testDebugUnitTest`, `:wearos:testDebugUnitTest`, lint, debug APK build.
- Docker: Docker Desktop 사용 가능 시 CPU Compose config/build/up, `/health`, `/ready`, `/openapi.json`, STT/rPPG status.
- Device: service/hardware 사용 가능 시 Phone install과 Watch/rPPG/STT/TTS/Bedrock smoke.

### Minimality and Style-Fidelity Review

- 모든 변경 경로는 CH-001–CH-019에 매핑돼야 한다.
- 새 dependency, shared abstraction, schema edit, generic refactor, generated file change를 거부한다.
- 기존 consumer에 필요한 기존 API range와 normalized AUQ field만 호환용으로 유지한다.

### Rollout and Rollback

1. DB를 백업하고 구 Phone write를 중지한다.
2. Backend validation/API를 배포하고 CLI dry-run과 confirmed conversion을 실행한다.
3. 일치하는 Phone APK를 배포하고 health/flow를 검증한다.
4. 필요하면 이전 image/APK로 code를 rollback하되 변환된 AUQ row는 자동 역변환하지 않는다.

### Risks and Mitigations

| Risk | Impact | Mitigation or Evidence |
| --- | --- | --- |
| 암호화 기존 행이 malformed | 부분 변환 위험. | 단일 transaction, AAD 검증, fail-closed rollback, dry-run. |
| 구 Phone이 전환 뒤 1..7 제출 | 요청 실패. | 동시 배포와 명시적 422. |
| 지연된 rPPG가 Watch를 덮음 | Home 최신 상태 오류. | timestamp/source ordering과 route-once test. |
| 기존 handoff 충돌 | 승인 문구와 UI 불일치. | 최신 명시적 결정과 동기화 문서를 기준으로 사용. |
| Docker/device unavailable | 통합 evidence 부족. | network-free/build 검증 뒤 정확한 제한 기록. |

## 10. Revision and Progress

### Design Revision History

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-21T16:00:00+09:00 | approved-plan-implementation | 승인된 V22 계획과 답변으로 검토 완료된 bilingual 구현 contract를 생성했다. | D-001, D-002, D-003, D-004, D-005, D-006, D-007, D-008 | Q-001, Q-002, Q-003, Q-004, Q-005 |
| 2 | 2026-07-21T17:25:00+09:00 | implementation-map-correction | 승인된 patch의 concrete Watch source/Phone test 파일을 매핑했다. 기능 방향은 변경하지 않았다. | None | None |

### Implementation Progress Record

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-21T16:00:00+09:00 | 1 | — | ready | 사용자가 전체 계획을 제공하고 구현을 명시적으로 승인했다. |
| 2026-07-21T16:20:00+09:00 | 1 | WS1 | in_progress | 검토된 계약에 따라 Backend slice를 위임했다. |
| 2026-07-21T16:20:00+09:00 | 1 | WS2 | in_progress | 검토된 계약에 따라 Kotlin Phone slice를 위임했다. |
| 2026-07-21T17:10:00+09:00 | 1 | WS1 | verified | Backend focused test 통과; diff, scope, rollback, mixed-scale AUQ 집계를 검토했다. |
| 2026-07-21T17:10:00+09:00 | 1 | WS2 | verified | Phone unit/lint/debug build 통과; diff와 budget을 검토했다. |
| 2026-07-21T17:10:00+09:00 | 1 | WS3 | in_progress | 승인된 Backend/Phone 계약을 기준으로 문서 동기화를 위임했다. |
| 2026-07-21T17:35:00+09:00 | 2 | WS3 | verified | API와 V22 frontend handoff Markdown을 구현 계약과 맞추고 stale label/link를 제거했다. |
| 2026-07-21T17:40:00+09:00 | 2 | — | complete | Backend 전체 184 passed/1 skipped, Phone/Wear unit·lint·debug build, local Docker health/ready가 통과했다. 기존 local 비표준 AUQ row에서 dry-run은 설계대로 fail-closed했고 연결된 ADB device가 없어 hardware smoke는 수행하지 못했다. |
| 2026-07-21T17:32:36+09:00 | 2 | — | complete | 로컬 PostgreSQL을 백업하고 감사 로그를 남기며 확인된 자동 AUQ 테스트 세션만 제거했다. 이후 zero-based CLI의 dry-run과 confirmed 실행이 남은 대상 0건으로 통과했다. Backend 전체 테스트는 다시 184 passed/1 skipped였고 Phone/Wear unit·lint·debug build, Docker health/ready 및 갱신된 OpenAPI 경로도 검증했다. |
