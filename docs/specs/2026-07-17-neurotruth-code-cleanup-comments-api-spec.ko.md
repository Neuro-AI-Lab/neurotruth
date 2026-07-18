# NeuroTruth 코드 정리·계약 주석·API 명세 — 구현 명세

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

> 이 문서는 살아있는 설계다. 영문이 구현 기준이며 이 문서는 사용자 검토용 동기화본이다.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `complete`, revision 3, 사용자 검토 완료 |
| Outcome | 승인된 코드 정리와 함께 시간별 평균 막대를 네 단계 구성비 100% 중첩 막대로 바꾼다. |
| Recommended implementation | `STRAT-1` — 보존 우선 삭제와 하나의 하위 호환 dashboard 응답 확장 |
| Planned production targets | `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt::duplicate upload/SSE helpers and state`; `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt::ProbabilityLineChart`, `AuqChart`; `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt::UserStatusRow and diagnostics label`; `apps/backend/app/main.py::unregistered legacy slot/handoff compatibility group`; `apps/backend/app/v25/repository.py::craving_dashboard_rows`; `apps/backend/app/v25/dashboard_service.py::craving_dashboard`; `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt::HourlyCravingBucket parser and HourlyCravingBarChart` |
| Expected additions | New production files: 0; dependencies: None; shared abstractions: None |
| Work plan | 4개 slice; WS1/WS2/WS3은 독립적이고 WS4가 stacked-chart 계약 양쪽을 소유한다. |
| Open questions | None |
| Agent decisions to review | None |
| Last material change | Revision 3 — 사용자 요청 네 단계 stacked craving chart와 필요한 server aggregation 추가 |

## 2. Outcome and Scope

### Outcome

현재 v7 앱의 중복·미사용 코드를 제거하고 진단 흐름을 보존하며 API를 문서화하는 동시에, 각 local hour를 하나의 평균이 아닌 네 단계 구성비로 보여준다.

### In Scope

- `PhoneMonitoringService`와 중복된 Android uploader/SSE 구현 제거
- 오래된 표시를 포함한 미사용 private UI composable 제거
- route 등록도 현재 `app.v25` runtime 사용도 없는 backend slot/handoff compatibility 함수 제거
- 오래된 10초 window와 구조화 대화 주석·문서 교정
- `apps/mobile/SERVER_API_SPEC.md`와 `apps/backend/README.md` 최신화
- 숨은 Android 진단 화면, hold gesture, simulation control과 필요한 상태 보존
- 시간별 bucket에 `low`, `observe`, `caution`, `high`의 정확한 개수 추가
- 시간마다 100% stacked bar, 네 색 legend, 선택한 시간의 구성비 상세 표시

### Out of Scope / Non-Goals

- schema, migration, model, 보안, 동의, dependency, 배포, 저장 데이터 변경 없음
- legacy record, migration, 읽기 전용 legacy session, 10초 rPPG 이력 삭제 없음
- generated OpenAPI snapshot, 대규모 formatting, Git commit/push/deploy 없음

### Users and Primary Flow

1. Android 사용자는 동일한 v7 흐름을 사용한다.
2. 실제 기기 테스트 담당자는 숨은 진단 화면을 계속 열 수 있다.
3. 개발자는 구형 설명 없이 Markdown 계약과 runtime `/openapi.json`을 사용한다.
4. 환자는 시간별 stacked bar를 눌러 네 연구용 UI 단계의 비율과 sample count를 확인한다.

### Current Assumptions and Constraints

- 커밋되지 않은 v7 구현과 사용자 소유 `PULL_REQUEST_FREE_CHAT_BAR_DASHBOARD.md`를 보존한다.
- 주석은 ownership, timing, idempotency, encryption, legacy/current 경계만 설명한다.

## 3. Repository Pattern Baseline

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Android transport | foreground service가 upload와 SSE lifecycle을 소유한다. | `apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringService.kt` | 하나의 background transport owner와 기존 retry/idempotency 동작 |
| Session runtime | 인증 route가 v25 service/agent에 위임한다. | `apps/backend/app/v25/routes_sessions.py`, `session_service.py` | 현재 free-dialogue route 계약 |
| Machine API | FastAPI가 live OpenAPI를 생성한다. | `apps/backend/app/main.py::app` | `/openapi.json`은 등록 route를 반영한다. |
| Human API | root 문서가 mobile handoff spec을 연결한다. | `apps/mobile/SERVER_API_SPEC.md` | 경쟁 문서 추가 없이 기존 문서 최신화 |
| Dashboard aggregation | server가 binary class-1 probability를 local-hour bucket으로 집계한다. | `apps/backend/app/v25/repository.py::craving_dashboard_rows` | timezone, active-model, quality-gate, no-data filter 유지 |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | `PhoneMonitoringService` transport | `PhoneMonitoringService.kt` | 유일한 sensor/SSE owner로 유지 |
| R-002 | v25 routers and schemas | `apps/backend/app/v25/routes_*.py` | 현재 API 문서의 근거이자 runtime 동작으로 보존 |
| R-003 | Live OpenAPI | `GET /openapi.json` | route inventory와 machine contract 검증 |
| R-004 | Existing API handoff | `apps/mobile/SERVER_API_SPEC.md` | 오래된 내용을 제자리에서 교체 |
| R-005 | Existing selectable Canvas bars | `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt::DailyEventBarChart` | hit testing, selection border, no-data color, detail pattern 재사용 |

## 4. Decisions and Questions

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | Android | `PhoneMonitoringService`를 실제 transport owner로 둔다. | repository | 중복 `SensorViewModel` loop에는 호출 지점이 없다. | 중복을 안전하게 제거할 수 있다. | not-required | resolved |
| D-002 | Compatibility | 접근 가능한 legacy reader와 migration을 유지한다. | repository | 기존 record와 10초 rPPG history는 명시적 호환 경로다. | 파괴적 정리를 방지한다. | not-required | resolved |
| D-003 | Documentation | `apps/mobile/SERVER_API_SPEC.md`를 단일 사람용 API 명세로 유지한다. | agent | 이미 연결된 앱 소비자 최인접 문서다. | 병렬 계약 문서를 만들지 않는다. | confirmed | resolved |
| D-004 | Documentation | live `/openapi.json`을 사용하고 generated snapshot은 커밋하지 않는다. | agent | 별도 schema는 route code와 drift할 수 있다. | 생성 파일과 workflow를 추가하지 않는다. | confirmed | resolved |
| D-005 | Android | 숨은 diagnostics 화면과 접근 가능한 control을 유지한다. | user | 사용자가 실제 기기 테스트를 위해 유지를 선택했다. | 오래된 문구와 미사용 내부만 정리한다. | confirmed | resolved |
| D-006 | Dashboard | 하나의 시간별 갈망 막대를 단계 구성 중첩 막대로 교체한다. | user | 사용자가 제공한 stacked-bar 참고 그림과 같은 차트를 요청했다. | 유일하게 승인된 관찰 동작/API 확장이다. | confirmed | resolved |
| D-007 | Dashboard | 네 구간을 100%로 정규화하고 backend가 정확한 단계별 count를 반환한다. | agent | 과거 평균값만으로 구성비를 복원할 수 없고 참고 그림은 100% stacked chart다. | hourly bucket에 하위 호환 `stageCounts`와 선택 막대 비율을 추가한다. | confirmed | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | Android | 숨은 diagnostics 화면 유지 또는 제거 | 제거하면 실제 기기 테스트 흐름이 바뀐다. | 유지하고 미사용 내부만 제거 | D-005 | answered | 사용자가 실제 기기 테스트용 유지를 선택했다. |

## 5. Requirements and Acceptance Criteria

### Functional Requirements

- **FR-001:** Android는 service 소유 20초 window/10초 cadence transport 하나와 접근 가능한 diagnostics 흐름을 유지해야 한다.
- **FR-002:** Backend는 현재 runtime AI/session 경로와 필요한 legacy data 호환만 유지해야 한다.
- **FR-003:** 사람용 API spec은 등록된 현재 route 전체, 인증/동의, 20초 sensor/rPPG, STT, free dialogue, AUQ 8–56, dashboard, 오류, 연구 한계를 설명해야 한다.
- **FR-004:** 계약 주석과 diagnostics 문구는 20초 input window와 10초 cadence를 구분해야 한다.
- **FR-005:** 데이터가 있는 시간별 craving bucket은 `low` (`p<0.25`), `observe` (`0.25≤p<0.50`), `caution` (`0.50≤p<0.75`), `high` (`p≥0.75`) 비율을 제공하고 표시해야 한다.
- **FR-006:** 빈 시간은 데이터가 있는 시간과 구분하고, 선택한 시간에는 네 비율과 전체 sample count를 표시해야 한다.

### Non-Functional Requirements

- **NFR-001:** 공개 동작을 보존하고 production file, dependency, shared abstraction, migration 또는 관계없는 formatting을 추가하지 않는다.
- **NFR-002:** 암호화, credential masking, 인증 ownership, idempotency, legacy read-only 보장을 유지한다.

### Acceptance Criteria

- **AC-001:** 저장소 검색에서 제거한 Android/backend symbol 참조가 남지 않는다.
- **AC-002:** backend test/compile, Android test/build/lint, Docker health/readiness가 통과한다.
- **AC-003:** API route table이 live OpenAPI 39개 path와 일치하고 voice-disabled, current 10초, structured-dialogue 구형 주장이 없다.
- **AC-004:** 숨은 diagnostics가 접근 가능하고 20초 window 문구가 정확하다.
- **AC-005:** diff에 관계없는 사용자 변경, secret, 환자 데이터, 신규 dependency가 없다.
- **AC-006:** 데이터가 있는 bucket에서 네 count 합이 `sampleCount`이고 segment 높이 합은 전체 bar이며 `0.25`, `0.50`, `0.75` 경계가 의도한 단계에 들어간다.
- **AC-007:** `stageCounts`를 무시하는 기존 client와 호환되고 average/minimum/maximum field를 유지한다.

### Edge and Failure Cases

- Legacy 10초 rPPG record → 그대로 읽고 재작성하지 않는다.
- STT/rPPG disabled → 준비된 것처럼 표현하지 않고 availability/status를 문서화한다.
- Model/storage invalid → 기존 `503`을 변경하지 않는다.
- 예측 0개 시간 → 회색 no-data bar이며 인위적 0% 구성비를 만들지 않는다.

## 6. Implementation Strategy and Direction

### STRAT-1 — 보존 우선 정리와 additive stacked-dashboard 계약

- **Direction:** `user-approved-divergence` via D-006.
- **Current approach:** 확인된 중복만 삭제하고 기존 문서를 고치며, 기존 시간별 SQL/result에 네 filtered count를 추가한다. Android는 count를 100% Canvas stack으로 정규화한다.
- **Existing flow to reuse:** R-001부터 R-005.
- **Why this is minimal:** 정확한 과거 구성비에는 server count가 필요하며 기존 query/bucket 확장이 신규 route/table/client 재구성보다 작고 안전하다.
- **Behavior-preserving limitations:** 일반 환자가 쓰지 않는 접근 가능한 개발 도구와 역사적 호환 코드는 유지한다.
- **Explicit exclusions:** 신규 route, schema, model, UI navigation, migration, deployment, data cleanup 없음.
- **Compatibility and migration posture:** source-only cleanup이며 rollback은 이전 source revision이다.
- **Direction approval:** D-006은 시간별 stacked-chart/API field 변경만 승인한다.
- **Open-question sensitivity:** None.

### Material Alternatives Considered

| Strategy | Direction | Benefit | Additional code or risk | Decision |
| --- | --- | --- | --- | --- |
| Generated OpenAPI snapshot 추가 | preserve | 커밋된 machine artifact | drift와 generator 관리 | rejected |
| 모든 developer tooling 제거 | user-approved-divergence | 더 작은 APK/UI source | device-test workflow 손상 | rejected by D-005 |
| 광범위 architecture rewrite | user-approved-divergence | 장기 통합 가능성 | 큰 regression surface | rejected |

## 7. Modification Map and Change Budget

### Modification Map

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt` | duplicate upload/SSE helpers and state | remove | `PhoneMonitoringService` | 미사용 transport 중복과 stale import를 제거하고 계약 주석을 교정한다. | FR-001, FR-004, AC-001 | WS1 | preserve |
| CH-002 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt` | `ProbabilityLineChart`, `AuqChart` | remove | current bar dashboard | 미사용 obsolete chart와 필요 시 stale preview 문구를 제거한다. | FR-001, AC-001 | WS1 | preserve |
| CH-003 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt` | UserStatusRow and diagnostics label | edit | `HealthMonitorScreen` | 미사용 row를 제거하고 diagnostics를 보존하며 20초 label을 고친다. | FR-001, FR-004, AC-004 | WS1 | preserve |
| CH-004 | production | `apps/backend/app/main.py` | unregistered legacy slot/handoff compatibility group | remove | v25 session routes | 격리된 request model/helper/import를 제거한다. | FR-002, AC-001 | WS2 | preserve |
| CH-005 | test | `apps/backend/tests/test_intervention_routes.py` | obsolete direct helper tests | remove | v25 session tests | CH-004로 삭제되는 non-route test를 제거한다. | FR-002, AC-002 | WS2 | preserve |
| CH-006 | test | `apps/backend/tests/test_bedrock_agents.py` | obsolete helper-only cases | edit | v25 agent tests | 삭제된 `app.main` helper 전용 case만 제거한다. | FR-002, AC-002 | WS2 | preserve |
| CH-007 | docs | `apps/mobile/SERVER_API_SPEC.md` | full document | edit | live OpenAPI and v25 routes | 현재 API 계약과 동기화한다. | FR-003, AC-003 | WS3 | preserve |
| CH-008 | docs | `apps/backend/README.md` | current facts | edit | current source/migrations/tests | model, STT/rPPG, migration, validation을 교정한다. | FR-003, AC-003 | WS3 | preserve |
| CH-009 | docs | `README.md` | API link context | edit | existing link | 주변 설명이 오래된 경우에만 수정한다. | FR-003, AC-003 | WS3 | preserve |
| CH-010 | production | `apps/backend/app/v25/repository.py` | `craving_dashboard_rows` | extend | existing hourly SQL | 동일 filter/timezone으로 네 probability band를 센다. | FR-005, AC-006, AC-007 | WS4 | approved-divergence |
| CH-011 | production | `apps/backend/app/v25/dashboard_service.py` | `craving_dashboard` | extend | existing hourly bucket serializer | average/min/max/sample을 유지하며 `stageCounts`를 추가한다. | FR-005, FR-006, AC-006, AC-007 | WS4 | approved-divergence |
| CH-012 | test | `apps/backend/tests/test_craving_bar_dashboard_v25.py` | hourly stage-distribution tests | extend | existing bucket tests | threshold, sum, no-data, compatibility field를 검증한다. | AC-006, AC-007 | WS4 | approved-divergence |
| CH-013 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt` | HourlyCravingBucket parser and HourlyCravingBarChart | extend | `DailyEventBarChart` | count를 parse하고 네 색 100% stack/legend/detail을 그린다. | FR-005, FR-006, AC-006 | WS4 | approved-divergence |
| CH-014 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/PatientDashboardParserTest.kt` | stacked bucket parsing/boundary tests | extend | existing dashboard parser tests | count, total, no-data, label을 검증한다. | AC-006, AC-007 | WS4 | approved-divergence |

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
| WS1 | Android 중복과 stale private UI를 제거하고 diagnostics를 보존한다. | None | PG-1 | CH-001, CH-002, CH-003 | `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt`, `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt`, `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt` | backend, specs, Gradle/dependencies | FR-001, FR-004, NFR-001, AC-001, AC-004 | Android unit tests and debug compile | verified |
| WS2 | Backend route 미등록 compatibility helper와 격리 test를 제거한다. | None | PG-1 | CH-004, CH-005, CH-006 | `apps/backend/app/main.py`, `apps/backend/tests/test_intervention_routes.py`, `apps/backend/tests/test_bedrock_agents.py` | v25 runtime/routes, migrations, model/STT/rPPG | FR-002, NFR-001, NFR-002, AC-001, AC-002 | backend full pytest and compileall | verified |
| WS3 | 사람용 API와 backend 운영 문서를 동기화한다. | None | PG-1 | CH-007, CH-008, CH-009 | `apps/mobile/SERVER_API_SPEC.md`, `apps/backend/README.md`, `README.md` | source, migrations, config, specs | FR-003, NFR-001, AC-003, AC-005 | OpenAPI inventory comparison and link check | verified |
| WS4 | 정확한 네 단계 시간별 집계와 mobile 100% stacked chart를 추가한다. | None | Serial | CH-010, CH-011, CH-012, CH-013, CH-014 | `apps/backend/app/v25/repository.py`, `apps/backend/app/v25/dashboard_service.py`, `apps/backend/tests/test_craving_bar_dashboard_v25.py`, `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt`, `apps/mobile/app/src/test/java/com/example/healthsensor/PatientDashboardParserTest.kt` | migrations, models, routes, admin web, Wear OS | FR-005, FR-006, NFR-001, NFR-002, AC-006, AC-007 | focused backend dashboard tests and Android parser/build tests | verified |

### Parallelization Rationale

PG-1은 target이 분리된다. WS4는 additive dashboard contract 양쪽을 소유하고 WS1의 `PatientDashboard.kt`와 겹치므로 WS1 뒤에 실행한다.

### Final Integration

Backend pytest/compileall, Android unit/build/lint, Docker status endpoint, OpenAPI inventory, `git diff --check`, deletion-oriented diff review를 수행한다.

## 9. Validation, Rollout, and Risk

### Validation Plan

- Backend full tests와 `python -m compileall -q app tests`
- Android `testDebugUnitTest`, `assembleDebug`, `lintDebug`
- Docker `/health`, `/ready`, `/openapi.json`, `/api/stt/status`, `/api/rppg/status`
- 제거 symbol 검색과 live path/API 문서 route inventory 대조
- 결정적 fixture로 SQL threshold 경계와 stacked segment 합 검증

### Minimality and Style-Fidelity Review

- 모든 변경 path를 CH-001~CH-009에 대응시킨다.
- 동작 변경, 신규 abstraction/dependency, formatting churn, 접근 가능한 history/tooling 삭제를 거부한다.

### Rollout and Rollback

Migration이나 특별 rollout은 없다. 기존 v7 통합 검증 뒤 backend/Android를 배포하며 이전 Git/Docker/APK revision으로 rollback한다.

### Risks and Mitigations

| Risk | Impact | Mitigation or Evidence |
| --- | --- | --- |
| 정적 참조가 reflection 사용을 놓침 | runtime regression | private/unregistered symbol만 제거하고 full test/build/OpenAPI를 확인한다. |
| 문서와 runtime drift | client integration error | 현재 source와 live OpenAPI에서 route/model을 도출한다. |
| 기존 미커밋 작업 덮어쓰기 | 사용자 작업 손실 | 좁은 write scope와 최종 status/diff 검토를 적용한다. |
| 100% stack이 sample 양을 감춤 | 적은 표본 시간도 크게 보일 수 있음 | no-data 회색과 선택 시간 전체 sample count를 유지한다. |

## 10. Revision and Progress

### Design Revision History

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-17T15:10:00+09:00 | initial-repository-design | dead-code 근거, 보존 경계, API 문서 전략을 기록했다. | D-001, D-002, D-003, D-004 | Q-001 |
| 2 | 2026-07-17T15:25:00+09:00 | user-answer | 접근 가능한 숨은 diagnostics workflow를 보존하고 slice/budget을 확정했다. | D-005 | Q-001 |
| 3 | 2026-07-18T10:00:00+09:00 | user-change-request | 승인된 네 단계 stacked chart와 정확한 additive server aggregation을 추가했다. | D-006, D-007 | None |

### Implementation Progress Record

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-17T15:10:00+09:00 | 1 | — | refining | 첫 질문 전에 living pair를 생성했다. |
| 2026-07-17T15:25:00+09:00 | 2 | — | refining | 개발자 diagnostics 유지 확정; 최종 domain review 대기 중 |
| 2026-07-18T10:00:00+09:00 | 3 | — | refining | stacked-chart 방향 기록; normalization/API detail 검토 대기 중 |
| 2026-07-18T10:15:00+09:00 | 3 | — | ready | 사용자가 남은 우려 없음으로 확정하여 revision 3 구현 준비 완료 |
| 2026-07-18T10:40:00+09:00 | 3 | WS1 | verified | scope/patch check, Android compile/unit test 통과; diagnostics 접근 유지 |
| 2026-07-18T10:45:00+09:00 | 3 | WS3 | verified | 기존 API spec을 39 path/42 operation과 동기화; 문서 검증 통과 |
| 2026-07-18T10:50:00+09:00 | 3 | WS4 | in_progress | WS1 검증 완료 후 stacked dashboard 계약 구현 시작 |
| 2026-07-18T10:51:00+09:00 | 3 | WS4 | pending | WS2 완료 전 serial scheduling 준수를 위해 편집 전에 일시 중지 |
| 2026-07-18T10:55:00+09:00 | 3 | WS2 | verified | scope/patch check, compileall, backend 전체 149 tests 통과 |
| 2026-07-18T10:56:00+09:00 | 3 | WS4 | in_progress | WS2 검증 후 serial stacked-dashboard slice 재개 |
| 2026-07-18T11:30:00+09:00 | 3 | WS4 | verified | 네 단계 count, 100% stacked Canvas, 2×2 legend, focused test와 APK build 통과 |
| 2026-07-18T11:45:00+09:00 | 3 | — | complete | Backend 149/1, Android unit/APK/lint, Docker rebuild, health/readiness, OpenAPI, diff 검증 통과 |
