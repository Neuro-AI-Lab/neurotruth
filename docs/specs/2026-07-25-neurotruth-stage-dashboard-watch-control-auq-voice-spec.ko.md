# NeuroTruth 갈망 단계·Watch 제어·AUQ·음성 UX — 구현 명세

<!-- feature-planner-control
{
  "workflow": "feature-planner/v7",
  "state": "implementing",
  "source_spec": "docs/specs/2026-07-25-neurotruth-stage-dashboard-watch-control-auq-voice-spec.md",
  "korean_mirror": "docs/specs/2026-07-25-neurotruth-stage-dashboard-watch-control-auq-voice-spec.ko.md",
  "spec_revision": 9,
  "reviewed_revision": 9,
  "selected_strategy": "STRAT-1",
  "implementation_direction": "preserve",
  "direction_decision_id": null,
  "minimal_change_policy": "strict",
  "final_domain_gate": "confirmed_none",
  "open_question_ids": [],
  "active_slices": ["WS9"],
  "next_action": "implement"
}
-->

> 영문 파일이 구현의 원본이고 이 문서는 사용자 검토용 동기화 미러다. 사용자가 최종 단계 문구, 3회 알림 규칙, 일·월 범위, AUQ 표기, Watch 화면/제어 fallback, rPPG 알림 범위, 음성 TTS 동작까지 모두 확정하고 구현을 승인했다.

## 1. 검토 스냅샷

| Review item | Current value |
| --- | --- |
| Lifecycle | `implementing`, revision 9, 사용자가 확인한 달력 미래 경계 후속 수정 |
| Outcome | 검증된 런타임을 유지하면서 최근 1시간 웨어러블 단계 차트를 키우고, AUQ 응답을 한 줄에 하나씩 배치하며, 불명확한 저장 측정 preview를 실시간 전처리 PPG·EDA로 교체하고, 일·주·월 이동이 현재 현지 기간을 넘지 않게 한다. |
| Recommended implementation | `STRAT-1` — 기존 dashboard, prediction transaction, Compose navigation/state, MonitoringService, Wear Data Layer 소유자를 좁게 확장한다. |
| Planned production targets | `apps/backend/app/ml/craving/pipeline.py::AlertConfig`, `apps/backend/app/ml/craving/pipeline.py::AlertEvaluator`, `apps/backend/app/repositories/postgres.py::persist_sensor_prediction`, `apps/backend/app/repositories/postgres.py::calendar rows`, `apps/backend/app/services/sensor.py::_ingest_locked`, `apps/backend/app/services/rppg.py::RppgService._process`, `apps/backend/app/repositories/rppg.py::finish_success`, `apps/backend/app/services/dashboard.py::DashboardService.craving_calendar`, `apps/backend/app/api/v1/routes/dashboard.py::craving_calendar`, `apps/backend/app/schemas/dashboard.py::calendar query types`, `apps/backend/app/schemas/dashboard.py::DashboardCalendarView`, `apps/mobile/core/src/main/kotlin/com/neurotruth/mobile/core/CravingStage.kt::CravingStage display labels/messages`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/DashboardRepository.kt::calendar DTO/parser/client`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/DashboardRepository.kt::calendar client/constants`, `apps/mobile/core/src/main/kotlin/com/neurotruth/mobile/core/net/ApiEndpoints.kt::cravingCalendar`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt::recent-hour/calendar UI`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt::calendar controls/copy`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt::StageTimelineFrame`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt::calendar selection/state`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt::calendar selection/navigation`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/AuqScreen.kt::copy/layout/result UI`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/AuqViewModel.kt::result state`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/NeuroTruthNavHost.kt::AUQ result navigation`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/chat/ChatViewModel.kt::STT dispatch/TTS correlation`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/chat/ChatScreen.kt::voice progress/notices`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/service/MonitoringService.kt::measurement command/ack and relay`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/service/PredictionPayloadParser.kt::Watch payload`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/home/HomeScreen.kt::Watch measurement control`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/home/HomeViewModel.kt::measurement intent/ack state`, `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/PredictionListenerService.kt::control/prediction/snapshot listener`, `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/SensorTrackingService.kt::start/stop status ack`, `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/MainActivity.kt::three summary pages/local stop`, `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/MainActivity.kt::TimelinePage`, `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/SensorState.kt::display-safe companion state`, `apps/mobile/wearos/src/main/AndroidManifest.xml::Wear listener filters/permissions`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt::StageTimelineFrame`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt::signal section`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/AuqScreen.kt::response layout`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/LivePpgSource.kt::live trace builder`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt::dashboard signal state`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt::calendar future-period guard`, and `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt::next-button/date-picker limits`. Combined symbols: `AlertConfig`, `AlertEvaluator`; `persist_sensor_prediction`, calendar rows; `CravingStage` display labels/messages; `StageTimelineFrame`, signal section; calendar future-period guard; next-button/date-picker limits. |
| Expected additions | New production files: 0; dependencies: None; shared abstractions: None; migrations: None. |
| Work plan | WS1, WS2, WS3, WS4, WS5, WS6, WS7, WS8은 검증 완료다. WS9 구현, 보정 검수, 단위 테스트, lint, APK 빌드는 완료됐고 현재 ADB에 Watch만 보여 Phone 설치와 화면 캡처 검토가 남았다. |
| Open questions | None |
| Agent decisions to review | None |
| Last material change | Revision 9 — 사용자가 일·주·월 제어가 현재 현지 기간에서 다음으로 이동하지 못하고 미래 날짜로 진입하지 않도록 요청했다. |

## 2. 결과와 범위

### 결과

인증된 환자는 Phone에서 Galaxy Watch 측정을 시작한다. Watch가 측정 시작을 확인한 뒤 Phone은 20초 신호 창을 10초마다 자동 전송한다. 두 기기는 `안정·관찰·주의·위험`을 표시하며 Watch 결과가 위험으로 3회 연속이면 하나의 동일 알림을 받고 15분 동안 추가 알림을 억제한다. AUQ를 완료하면 중립적인 결과 화면을 확인한 뒤 챗봇으로 이동하고, 음성은 STT 후 즉시 전송되며 해당 AI 답변만 한 번 자동 재생된다.

### 포함

- DB/wire key를 유지한 사용자 단계명·문구 변경
- 최근 1시간 4단계 step timeline과 일·월 calendar 집계
- 자기설문 정보·한 화면 문항·결과 화면
- Phone 주도 Watch 시작/중지, ack, Watch 확인 알림 fallback
- Watch 요약 3화면과 동일 alertId
- Watch `p>=0.75` 3회 연속 및 900초 cooldown
- STT 즉시 전송과 해당 답변 TTS
- API·PRD/handoff·기기 테스트 문서 동기화

### 제외

- DB schema/Alembic 변경, 신규 dependency
- 관리자 웹, 모델 재학습, Bedrock prompt, STT 서버, DGX/FactorizePhys, rPPG capture 변경
- 공식 검증 한국어 AUQ 주장이나 임의 점수 구간
- rPPG 기반 알림
- 로컬 검증 전 Git push·운영 배포·기기 설치

### 사용자 흐름

1. 로그인·생체신호 동의·Watch 연결 후 Phone의 `측정 시작`을 누른다.
2. Phone이 requestId를 보내고 Watch가 자동 시작하거나 OS 제한 시 한 번의 Watch 확인을 요청한다.
3. Watch가 `started`를 응답하면 Phone MonitoringService와 자동 20초/10초 전송이 시작된다.
4. Phone/Watch가 4단계를 표시하고 위험 3회 연속 시 동일 알림을 표시한다.
5. 신규 챗봇 세션은 AUQ 작성 또는 건너뛰기를 제공하고, 작성 시 결과 화면 뒤 대화로 이동한다.
6. 음성은 인식 즉시 전송되고 해당 AI 응답만 자동으로 읽는다.

### 제약

- `feat/stage-watch-auq-voice-ux`에는 `Master`에서 이어진 의도된 미커밋 작업이 있으므로 보존한다.
- Backend·Phone·Wear는 계약 변경 때문에 함께 배포한다.
- target SDK 35의 health FGS가 거부되면 Watch 확인 경로를 사용한다.
- 내부 단계 key `low|observe|caution|high`는 유지한다.

## 3. 저장소 기준 패턴

### 현재 패턴

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Dashboard | `DashboardService` validates time/range input and delegates SQL aggregation to `V25Repository`; Phone parses JSON into local DTOs and draws Canvas charts. | `apps/backend/app/services/dashboard.py::DashboardService`; `apps/mobile/app/.../data/DashboardRepository.kt`; `ui/dashboard/DashboardScreen.kt` | 기존 service/repository/parser/Canvas 흐름을 확장하고 chart library를 추가하지 않는다. |
| Prediction persistence | `SensorService` predicts, repository transaction inserts prediction and optional alert, then the service publishes the persisted response to SSE. | `apps/backend/app/services/sensor.py::SensorService._ingest_locked`; `repositories/postgres.py::persist_sensor_prediction` | DB transaction은 repository, SSE는 service에 유지한다. |
| rPPG | `RppgService` stores camera predictions through `RppgRepository.finish_success`. | `apps/backend/app/services/rppg.py`; `repositories/rppg.py` | 저장 흐름은 유지하고 camera alert만 제거한다. |
| AUQ | `Auq` owns 8 items, seven labels, and 0–48 scoring; `AuqViewModel` posts; `NeuroTruthNavHost` owns routing. | `apps/mobile/core/.../Auq.kt`; Phone `ui/auq`; `NeuroTruthNavHost.kt` | scoring/assessment endpoint를 재사용하고 결과 state/route만 추가한다. |
| Voice | `ChatViewModel` records, calls `TranscriptionRepository`, dispatches messages with idempotent client IDs, and owns local TTS. | `apps/mobile/app/.../ui/chat/ChatViewModel.kt` | 기존 dispatch/retry/TTS를 재사용한다. |
| Monitoring | Phone `MonitoringService` owns Watch MessageClient samples, 20-second scheduler, SSE, notifications, and Watch prediction relay. | `apps/mobile/app/.../service/MonitoringService.kt` | Watch ack 뒤 시작하도록만 확장한다. |
| Wear | Wear service owns Samsung sensor collection; Wear listener consumes display-safe Phone messages; Watch has no Internet permission. | `apps/mobile/wearos/.../SensorTrackingService.kt`; `PredictionListenerService.kt`; Wear manifest | Data Layer-only 경계를 유지한다. |
| Tests | Backend uses focused pytest repository fakes; Android uses JVM tests, lint, and debug builds. | `apps/backend/tests`; `apps/mobile/app/src/test`; `apps/mobile/core/src/test` | 기존 fixture와 위치를 유지한다. |

### 재사용 목록

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | Four-stage threshold owner | `apps/mobile/core/.../CravingStage.kt` | threshold/key 유지, 표시만 수정 |
| R-002 | One-hour sparse probability series | `DashboardService.craving_probability_series`; `CravingSeries.segments` | 실제 측정과 gap 유지 |
| R-003 | Existing stage-count SQL | `SqlAlchemyV25Repository.craving_dashboard_rows` | day/month boundary와 bucket만 확장 |
| R-004 | Existing AUQ result builder | `Auq.buildResult` | 결과 화면 점수 재사용 |
| R-005 | Existing chat idempotency and retry | `ChatViewModel.dispatch`; `ChatRetryPolicy` | voice client ID·retry 유지 |
| R-006 | Existing Phone prediction/alert ledger | `PredictionLedger`; `CravingAlertNotifier` | alertId 중복 제거 |
| R-007 | Existing Wear sensor FGS | `SensorTrackingService.start/stop` | Phone command와 local stop에서 호출 |
| R-008 | Existing Watch display-safe relay | `PredictionPayloadParser.toWatchPayload`; `PredictionListenerService` | 확률 없이 stage/alertId 추가 |
| R-009 | Existing live sensor buffer and fixed-grid interpolation | `SensorSampleRepository`; `FixedGridResampler`; backend `CravingModel.preprocess` | 최근 20초 버퍼, 고정격자 보간, 채널별 MinMax 관례를 화면용 PPG·EDA에 재사용 |

## 4. 결정과 질문

### 결정 기록

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | 단계 | `안정·관찰·주의·위험`, 위험 문구는 `갈망이 높게 감지됐어요. 챗봇과 대화를 시작할까요?` | user | 사용자 직접 선택 | Phone/Watch 표시 변경 | confirmed | resolved |
| D-002 | 알림 | Watch `p>=.75` 3회 연속, 간격 최대 20초, 이후 15분 patient cooldown | user | 사용자 직접 선택 | 기존 6/10·downtrend·30초 규칙 대체 | confirmed | resolved |
| D-003 | 알림 출처 | rPPG는 알림을 만들지 않고 Watch 알림만 양쪽에 표시 | user | `Watch 신호만 양쪽` 선택 | rPPG 저장은 유지 | confirmed | resolved |
| D-004 | Calendar | day/month 선택을 stage/event/AUQ 전체에 적용 | user | 전체 차트 연동 선택 | 신규 additive API | confirmed | resolved |
| D-005 | AUQ | 공식 한국어판이 아닌 AUQ 참고 연구용 한국어 자기설문으로 안내 | user | 원척도+연구용 표기 선택 | 정직한 정보 dialog·결과 문구 | confirmed | resolved |
| D-006 | AUQ 배치 | 한 문항당 한 화면, 일반 화면에서 스크롤 없이 7개 선택지 표시 | user | 직접 선택 | layout만 변경 | confirmed | resolved |
| D-007 | Watch | 측정/현재 단계, 최근 1시간, 오늘 이벤트/AUQ의 3개 화면 | user | 핵심 요약 3화면 선택 | safe snapshot relay | confirmed | resolved |
| D-008 | Watch 시작 | Phone 시작, 차단 시 Watch 확인 알림, 중지는 양쪽 | user | Android 제한을 반영한 선택 | control/status 계약 | confirmed | resolved |
| D-009 | 음성 | STT 즉시 전송, 해당 답변만 자동 TTS | user | 직접 선택 | typed auto-read 유지 | confirmed | resolved |
| D-010 | 호환 | 내부 key와 기존 API 유지, calendar API 추가 | user | 승인 계획 | migration 없음 | confirmed | resolved |
| D-011 | Test contract | Add the new calendar route to the existing public-operation inventory test. | agent | Full Docker pytest found the approved additive route missing from `EXPECTED_PUBLIC_OPERATIONS`. | Test-only synchronization; no product behavior change. | confirmed | resolved |
| D-012 | Patch accounting | Raise WS2 production added-line cap from 850 to 1000. | agent | The final branch diff is +974 production lines because the user-approved pre-existing dirty Chat/NavHost voice/session work must be preserved and ships with this branch. | Accounting-only adjustment; files, behavior, dependencies, and architecture are unchanged. | confirmed | resolved |
| D-013 | Patch accounting | Raise WS3 production added-line cap from 900 to 930. | agent | Final review required an already-running Watch service to acknowledge a new Phone request and restored the baseline first-launch sensor/notification permission setup needed by the approved confirmation fallback. | Accounting-only adjustment; mapped files, dependencies, architecture, and approved behavior are unchanged. | confirmed | resolved |
| D-014 | 최근 1시간 시각 디자인 | Phone과 Watch 차트를 웨어러블 수면 단계 차트처럼 4개 이름 레인, 굵고 둥근 색상 구간, 가는 세로 전환선, 옅은 레인 가이드와 명시적 공백으로 표현한다. | user | 사용자가 이전에 제공한 Galaxy Watch와 Apple Watch 수면 단계 예시를 다시 지정했다. | 기존 Canvas 두 곳만 바꾸며 timestamp, 단계 기준, gap, API와 저장 데이터는 유지한다. | confirmed | resolved |
| D-015 | 주간 calendar | `day`와 `month` 사이에 `week`를 추가하고 선택 날짜가 포함된 현지 월요일~일요일을 단계·이벤트·AUQ의 7개 일별 bucket으로 사용한다. | user | 사용자가 주간 대시보드를 명시적으로 요청했다. 월요일 시작은 기존 현지시간 calendar의 최소 확장이다. | D-004를 확장하며 저장 데이터와 migration은 바꾸지 않는다. | confirmed | resolved |
| D-016 | DGX cooldown 배포 | source/default의 900초 계약을 유지하고 DGX `.env` 수정 및 backend 재생성을 문서화한다. 기존 반복 알림은 배포 전 상태로 본다. | user | 사용자가 현재 DGX에는 cooldown 변경을 아직 올리지 않았다고 확인했다. | alert 알고리즘 추가 변경 없이 배포 증거만 남는다. | confirmed | resolved |
| D-017 | 대시보드·AUQ 후속 수정 | Phone 최근 1시간 차트 높이를 키우고 단독 bucket도 짧고 둥근 bar로 표시한다. AUQ 7개 응답은 한 줄에 하나의 전체 폭 버튼으로 배치한다. 환자 대시보드의 선택 측정·저장 PPG preview를 제거하고 실시간 PPG·EDA를 표시한다. 표시 신호는 기존 20초 고정격자 보간과 채널별 MinMax를 재사용하며 업로드·저장 원본은 바꾸지 않는다. | user | 실기기 확인 후 사용자가 직접 요청했고 전처리는 가장 가까운 기존 모델·업로드 경로를 재사용한다. | Phone 표시만 변경하며 backend·DB·API·Watch UI·모델 입력·저장은 유지한다. | confirmed | resolved |
| D-018 | 달력 미래 경계 | 선택 일이 오늘, 선택 주가 현재 월요일~일요일 주, 선택 월이 현재 월이면 `다음`을 비활성화한다. 미래 anchor는 거부하고 날짜 선택기에서도 미래 날짜를 비활성화한다. | user | 일간·주간·월간 대시보드가 현재 날짜보다 미래로 이동하지 못하고 버튼이 비활성화되어야 한다는 직접 후속 요청이다. | Phone 달력 제어·상태만 변경하며 API와 저장 데이터는 유지한다. | confirmed | resolved |
| D-019 | 단계 집계 축 | Calendar 단계 막대를 100% 비율이 아닌 실제 횟수로 누적한다. 10초 간격 기준 시간당 360회, 일당 8,640회를 고정 최댓값으로 사용하고 무측정 bucket은 비워 둔다. | user | 사용자가 횟수 기반 누적 막대와 시간별 최대 360회를 명시했다. | Phone Canvas·상세 문구만 바꾸며 API stage count와 저장 데이터는 유지한다. | confirmed | resolved |

### 질문 기록

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | 단계 | 최종 단계명/문구 | 전체 client 영향 | 위험·높게 감지 | D-001 | answered | 사용자 확정 |
| Q-002 | 알림 | 3회의 의미 | 민감도·저장 영향 | 3회 연속 위험 | D-002 | answered | 사용자 확정 |
| Q-003 | Calendar | 일·월 적용 차트 | API/UI 범위 | 전체 차트 | D-004 | answered | 사용자 확정 |
| Q-004 | AUQ | 공식 표기/결과 | 오인 방지 | 연구용 adaptation·중립 총점 | D-005 | answered | 사용자 확정 |
| Q-005 | Wear | 화면 범위·remote fallback | UI/permission 영향 | 3화면+확인 알림 | D-007 | answered | D-007과 D-008을 사용자 확정 |
| Q-006 | 음성/rPPG | TTS 범위·rPPG 알림 | playback/알림 영향 | 해당 답변만, Watch-only | D-009 | answered | D-003과 D-009를 사용자 확정 |

## 5. 요구사항과 인수 기준

### 기능 요구사항

- **FR-001:** `<.25`, `<.50`, `<.75`, `>=.75` threshold와 D-001 문구를 Phone/Watch에 적용한다.
- **FR-002:** 최근 1시간 Phone/Watch 차트는 실제 10초 timestamp 위에 수면 단계형 굵고 둥근 단계 색상 구간, 가는 세로 전환선, 4개 레인 이름을 표시하고 누락 구간은 연결하지 않는다.
- **FR-003:** calendar API는 day의 local hour, week의 현지 월요일~일요일 7개 local date, month의 local date별 stage/event/AUQ bucket을 반환한다.
- **FR-004:** Phone day/week/month 선택은 모든 차트를 함께 바꾸고 no-data와 valid-zero를 구분한다.
- **FR-014:** DGX 배포 계약은 `ALERT_DANGER_THRESHOLD=0.75`, `ALERT_DANGER_STREAK=3`, `ALERT_MAX_GAP_SECONDS=20`, `ALERT_COOLDOWN_SECONDS=900`을 설정하고 폐기 변수를 제거한 뒤 backend container를 재생성한다.
- **FR-015:** Phone 최근 1시간 차트는 별도의 더 큰 높이를 사용하고 단독 10초 bucket도 둥근 수평 bar로 보인다. 네 단계 lane, 세로 전환선, 실제 공백, 선택 상세는 유지한다.
- **FR-016:** AUQ의 일곱 문장 선택지를 일곱 개 전체 폭 행으로 표시한다. 문항당 한 화면, 최소 48dp, 일반 글꼴 no-scroll 의도와 큰 글꼴 scroll fallback은 유지한다.
- **FR-017:** 대시보드 신호 영역은 실시간 Watch PPG·EDA만 포함하고 저장 측정 선택/preview UI를 제거한다. 최근 20초 신호는 기존 upload grid에 보간하고 채널별 MinMax로 화면용 정규화하며, 없거나 stale인 채널은 0선으로 만들지 않는다.
- **FR-018:** Phone 달력 이동은 미래 현지 기간으로 진입하지 않는다. 오늘, 현재 월요일~일요일 주, 현재 월에서는 `다음`이 비활성화되고 날짜 선택기는 내일 이후를 비활성화하며 상태 handler는 미래 anchor를 무시한다.
- **FR-019:** Calendar 단계 차트는 퍼센트 정규화 없이 실제 단계별 횟수를
  누적한다. 일간 시간별 막대는 고정 `0~360회`, 주간·월간 일별 막대는
  고정 `0~8,640회` 축을 사용한다. 무측정 bucket은 비워 두고 선택 상세는
  전체·단계별 횟수를 표시한다.
- **FR-005:** AUQ를 `자기설문`으로 통일하고 정보 dialog와 한 화면 7개 선택지를 제공한다.
- **FR-006:** AUQ 저장 성공 뒤 `총점 X/48` 중립 결과를 보여주고, skip은 바로 Chat으로 간다.
- **FR-007:** Phone이 시작/중지를 소유하고 Watch ack 뒤 MonitoringService를 시작하며 Watch는 stop/확인만 제공한다.
- **FR-008:** Watch started와 sample 수신 뒤 기존 20초 window를 10초마다 인증 API로 자동 전송한다.
- **FR-009:** Watch는 3개 요약 화면만 표시하며 credential/probability/raw history를 보관하지 않는다.
- **FR-010:** Watch prediction만 D-002 알림에 참여하고 동일 alertId를 양쪽에서 중복 제거한다.
- **FR-011:** STT 성공은 typed draft를 건드리지 않고 하나의 voice message로 즉시 전송한다.
- **FR-012:** 그 voice message의 assistant reply만 자동 재생한다.
- **FR-013:** rPPG prediction/DB/AUQ/Chat/latest state는 유지하되 alertAction/alertId를 만들지 않는다.

### 비기능 요구사항

- **NFR-001:** 기존 architecture, encryption, auth, Wear Data Layer boundary, unrelated dirty changes를 보존한다.
- **NFR-002:** alert streak/cooldown은 restart·duplicate·concurrency에도 유지되도록 기존 transaction과 patient advisory lock을 사용한다.
- **NFR-003:** calendar는 IANA timezone/view/anchor를 검증하고 DST·월 길이를 timezone-aware boundary로 처리한다.
- **NFR-004:** dependency, generic framework, DB migration, shared abstraction을 추가하지 않는다.
- **NFR-005:** remote health FGS 실패는 confirmation/error로 명시하고 Phone을 측정 중으로 표시하지 않는다.

### 인수 기준

- **AC-001:** Backend 테스트가 .75 경계, 3회 연속, gap reset, 900초 cooldown, 중복/동시 단일 alert, rPPG 제외를 증명한다.
- **AC-002:** Calendar 테스트가 day/week/month, 월요일·일요일 경계, timezone/DST/no-data/valid-zero/AUQ를 증명한다.
- **AC-007:** API·배포 문서가 실제 secret 없이 DGX 15분 cooldown `.env` 수정과 backend 재생성·검증 명령을 제공한다.
- **AC-008:** Phone 화면에서 최근 1시간 차트가 집계 차트보다 확실히 크고, 단독 측정은 점이 아닌 bar로 보이며, AUQ는 한 줄 한 버튼이고, 신호 카드에는 저장 측정 문구 없이 PPG와 EDA가 각각 표시된다.
- **AC-009:** JVM test가 PPG/EDA 채널 분리, 고정격자 크기, 채널별 MinMax 범위와 상수 처리, freshness, 누락 채널 비생성을 검증하고 App unit/lint/debug build가 통과한다.
- **AC-010:** JVM 테스트가 일·주·월 다음 이동 경계와 미래 anchor 거부를 검증한다. 비활성 `다음`과 날짜 선택기 최대 날짜가 컴파일되고 App unit/lint/debug build가 통과한다.
- **AC-011:** App compile과 unit test가 통과하고 단계 UI에 퍼센트 계산
  경로가 없으며, 차트는 횟수 축을, 선택 상세는 전체·단계별 실제 횟수를
  표시한다.
- **AC-003:** Phone 테스트가 정확한 문구, categorical gap, calendar, AUQ 결과/skip, STT 즉시 전송, TTS correlation, retry를 증명하고 Phone/Wear build와 화면 검토가 수면 단계형 최근 1시간 차트를 확인한다.
- **AC-004:** Wear 테스트/build가 control/status, confirmation, 3화면, stage-only snapshot, alertId dedup을 증명한다.
- **AC-005:** Backend full pytest, Core/App/Wear unit, lint, debug APK build가 통과한다.
- **AC-006:** 기기 재연결 뒤 Phone 시작→Watch sensor→Phone upload→prediction→양쪽 stage/alert가 확인된다.

### 오류/경계

- 위험 미만 또는 20초 초과 gap → streak reset.
- cooldown 중 위험 3회 → prediction만 저장, alert/event 없음.
- duplicate window → 기존 prediction/alert 반환, streak/알림 중복 없음.
- notification consent off → 기존 의미대로 prediction만 저장.
- Watch start 차단 → confirmation_required/error, Phone monitoring 미시작.
- Watch disconnect → 양쪽 측정 종료와 기존 blocker 표시.
- STT 실패/무음 → 전송하지 않고 typed draft 유지.
- voice 502 → bubble/client ID 유지, 기존 1회 retry.
- AUQ 저장 실패 → 결과 화면 금지, retry/skip 유지.

## 6. 구현 전략과 방향

### STRAT-1 — 기존 소유자 확장

- **방향:** `preserve`
- **접근:** 기존 repository/service/Compose/Data Layer 소유자에 필요한 parameter/branch만 추가한다.
- **재사용:** R-001~R-008.
- **최소성:** 새 public interface는 calendar route와 bounded Wear control/snapshot뿐이다.
- **제한:** 내부 key/API 유지, Watch 정확한 확률 미수신, AUQ category 없음.
- **제외:** sensor scheduler refactor, alert table, 공식 AUQ 교체, generic cross-device/chart framework.
- **호환/rollback:** schema migration 없음. Backend/Phone/Wear 동시 배포, 이전 image/APK rollback.
- **방향 승인:** 별도 divergence 없음.
- **열린 질문 영향:** 없음.

### 검토한 대안

| 전략 | 방향 | 장점 | 추가 코드/위험 | 결정 |
| --- | --- | --- | --- | --- |
| alert-state table | divergence | 단순 조회 | 불필요한 migration/owner | 거절 |
| Watch backend 직접 접근 | divergence | 독립 dashboard | auth/network 중복·credential 위험 | 거절 |
| 공식 한국어 AUQ 주장 | divergence | 강한 표현 | 근거 없음·오인 | D-005로 거절 |
| chart dependency | divergence | drawing 편의 | 기존 Canvas 중복 | 거절 |

## 7. 변경 지도와 예산

### 변경 지도

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | production | `apps/backend/app/ml/craving/pipeline.py` | `AlertConfig`, `AlertEvaluator` | edit | current rolling evaluator | danger probability/gap/cooldown context를 평가한다. | FR-010, NFR-002 | WS1 | preserve |
| CH-002 | production | `apps/backend/app/repositories/postgres.py` | `persist_sensor_prediction`, calendar rows | extend | existing prediction transaction/dashboard SQL | patient lock, prior Watch context, 단일 alert와 calendar 집계를 추가한다. | FR-003, FR-010 | WS1 | preserve |
| CH-003 | production | `apps/backend/app/services/sensor.py` | `_ingest_locked` | extend | existing repository persistence/SSE | notification policy와 저장된 alert 응답을 사용한다. | FR-010 | WS1 | preserve |
| CH-004 | production | `apps/backend/app/services/rppg.py` | `RppgService._process` | edit | current alert_decider branch | camera prediction에 alert를 만들지 않는다. | FR-013 | WS1 | preserve |
| CH-005 | production | `apps/backend/app/repositories/rppg.py` | `finish_success` | edit | current optional alert insert | camera prediction만 저장한다. | FR-013 | WS1 | preserve |
| CH-006 | production | `apps/backend/app/services/dashboard.py` | `DashboardService.craving_calendar` | extend | current craving dashboard service | day/month stage/event/AUQ bucket을 반환한다. | FR-003 | WS1 | preserve |
| CH-017 | production | `apps/backend/app/api/v1/routes/dashboard.py` | `craving_calendar` | extend | current patient dashboard routes | authenticated calendar route를 추가한다. | FR-003 | WS1 | preserve |
| CH-018 | production | `apps/backend/app/schemas/dashboard.py` | calendar query types | extend | current range literal types | `day` 또는 `month` query contract를 정의한다. | FR-003 | WS1 | preserve |
| CH-041 | test | `apps/backend/tests/test_sse_payload.py` | `EXPECTED_PUBLIC_OPERATIONS` | extend | current exact endpoint inventory | 승인된 `/api/me/craving-calendar` operation을 추가한다. | AC-005 | WS1 | preserve |
| CH-019 | test | `apps/backend/tests/test_alerts.py` | danger streak/cooldown tests | extend | current alert evaluator cases | boundary/gap/cooldown을 증명한다. | AC-001 | WS1 | preserve |
| CH-020 | test | `apps/backend/tests/test_sensor_routes_v25.py` | persistence alert cases | extend | current sensor repository fake | duplicate와 Watch alert persistence를 증명한다. | AC-001 | WS1 | preserve |
| CH-021 | test | `apps/backend/tests/test_craving_bar_dashboard_v25.py` | calendar aggregation cases | extend | current dashboard fakes | day/month/timezone/empty bucket을 증명한다. | AC-002 | WS1 | preserve |
| CH-022 | test | `apps/backend/tests/test_rppg_v25.py` | camera alert exclusion | extend | current rPPG service tests | rPPG alert가 없음을 증명한다. | AC-001 | WS1 | preserve |
| CH-007 | production | `apps/mobile/core/src/main/kotlin/com/neurotruth/mobile/core/CravingStage.kt` | `CravingStage` display labels/messages | edit | R-001 | D-001 문구를 적용한다. | FR-001 | WS2 | preserve |
| CH-008 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/DashboardRepository.kt` | calendar DTO/parser/client | extend | current dashboard parser | calendar API와 recent-hour를 소비한다. | FR-002, FR-003, FR-004 | WS2 | preserve |
| CH-029 | production | `apps/mobile/core/src/main/kotlin/com/neurotruth/mobile/core/net/ApiEndpoints.kt` | `cravingCalendar` | extend | current dashboard endpoints | calendar query를 만든다. | FR-003 | WS2 | preserve |
| CH-009 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt` | recent-hour/calendar UI | extend | existing Canvas sections | stage step timeline과 day/month navigation을 그린다. | FR-002, FR-004 | WS2 | preserve |
| CH-030 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt` | calendar selection/state | extend | current dashboard state | 모든 차트의 day/month 선택을 동기화한다. | FR-004 | WS2 | preserve |
| CH-010 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/AuqScreen.kt` | copy/layout/result UI | extend | current one-item screen | info dialog, compact layout, neutral result를 추가한다. | FR-005, FR-006 | WS2 | preserve |
| CH-031 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/AuqViewModel.kt` | result state | extend | current submission state | 저장 확인 뒤 결과를 노출한다. | FR-006 | WS2 | preserve |
| CH-032 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/NeuroTruthNavHost.kt` | AUQ result navigation | extend | current AUQ route | 제출 결과에서 Chat으로 이동하고 skip은 바로 이동한다. | FR-006 | WS2 | preserve |
| CH-011 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/chat/ChatViewModel.kt` | STT dispatch/TTS correlation | extend | R-005 | voice 즉시 전송과 해당 reply TTS를 추가한다. | FR-011, FR-012 | WS2 | preserve |
| CH-033 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/chat/ChatScreen.kt` | voice progress/notices | edit | current microphone UI | transcript-ready draft 대신 즉시 전송 상태를 표시한다. | FR-011 | WS2 | preserve |
| CH-012 | test | `apps/mobile/core/src/test/kotlin/com/neurotruth/mobile/core/CravingStageTest.kt` | display boundaries/copy | extend | current stage tests | 정확한 4단계 문구를 증명한다. | AC-003 | WS2 | preserve |
| CH-023 | test | `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/data/DashboardParserTest.kt` | calendar/timeline parser cases | extend | current dashboard tests | calendar와 sparse stage mapping을 증명한다. | AC-003 | WS2 | preserve |
| CH-024 | test | `apps/mobile/core/src/test/kotlin/com/neurotruth/mobile/core/AuqTest.kt` | result score cases | extend | current AUQ tests | 결과가 0–48임을 증명한다. | AC-003 | WS2 | preserve |
| CH-025 | test | `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/ui/chat/ChatResponseParserTest.kt` | voice reply correlation cases | extend | current chat tests | voice dispatch/reply 재생 결정을 증명한다. | AC-003 | WS2 | preserve |
| CH-013 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/service/MonitoringService.kt` | measurement command/ack and relay | extend | existing MessageClient | explicit start/stop, ack gate, stage/alertId/dashboard snapshot relay를 추가한다. | FR-007, FR-008, FR-009, FR-010 | WS3 | preserve |
| CH-034 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/service/PredictionPayloadParser.kt` | Watch payload | extend | R-008 | 확률 없이 stage code/copy와 alert ID를 전달한다. | FR-009, FR-010 | WS3 | preserve |
| CH-035 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/home/HomeScreen.kt` | Watch measurement control | extend | current Watch card | 사용자용 start/stop/confirmation 상태를 추가한다. | FR-007 | WS3 | preserve |
| CH-036 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/home/HomeViewModel.kt` | measurement intent/ack state | extend | current connection reconciliation | 연결 시 자동 시작을 제거하고 Watch ack 뒤 Phone monitoring을 시작한다. | FR-007, FR-008 | WS3 | preserve |
| CH-014 | production | `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/PredictionListenerService.kt` | control/prediction/snapshot listener | extend | R-008 | request/status, stage-only snapshot, alert ID, confirmation을 처리한다. | FR-007, FR-009, FR-010 | WS3 | preserve |
| CH-037 | production | `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/SensorTrackingService.kt` | start/stop status ack | extend | R-007 | 실제 service start/stop을 Phone에 ack한다. | FR-007, FR-008 | WS3 | preserve |
| CH-038 | production | `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/MainActivity.kt` | three summary pages/local stop | extend | current Watch screen | 세 화면과 확인/local-stop 동작을 표시한다. | FR-007, FR-009 | WS3 | preserve |
| CH-039 | production | `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/SensorState.kt` | display-safe companion state | extend | current display state | 단계 timeline, 오늘 요약, request, alert-ID 상태를 보유한다. | FR-009, FR-010 | WS3 | preserve |
| CH-040 | production | `apps/mobile/wearos/src/main/AndroidManifest.xml` | Wear listener filters/permissions | edit | current Wear services | control/dashboard path와 background sensor prerequisite를 반영한다. | FR-007, NFR-005 | WS3 | preserve |
| CH-015 | test | `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/ui/home/MonitoringCommandTest.kt` | Phone control/ack cases | add | existing pure policy test style | Phone state가 Watch ack에 의해 시작됨을 증명한다. | AC-004 | WS3 | preserve |
| CH-042 | test | `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/service/SseFrameParserTest.kt` | Watch relay privacy contract | extend | current `toWatchPayload` test | Watch relay가 `stageCode`와 alert metadata만 포함하고 binary class, probability, raw biosignal은 포함하지 않음을 증명한다. | FR-009, AC-004 | WS3 | preserve |
| CH-026 | test | `apps/mobile/wearos/src/test/kotlin/com/neurotruth/mobile/wear/WearControlContractTest.kt` | control/snapshot/dedup cases | add | existing pure payload style | Wear request/status/alertId 결정을 증명한다. | AC-004 | WS3 | preserve |
| CH-016 | docs | `apps/test_mobile_app/SERVER_API_SPEC.md` | calendar/alert/Data Layer/AUQ/voice contracts | edit | current server API spec | 실제 public contract를 기록한다. | FR-003, FR-007, FR-010, FR-011 | WS4 | preserve |
| CH-027 | docs | `docs/prd/PRD_neurotruth_mobile.ko.md` | patient and Watch flows | edit | current mobile PRD | stages, AUQ, calendar, alert, voice를 동기화한다. | FR-001, FR-005, FR-007, FR-010, FR-011, FR-013 | WS4 | preserve |
| CH-028 | docs | `docs/deployment/MOBILE_DEVICE_TEST_GUIDE.ko.md` | device acceptance flow | edit | current device guide | Phone-controlled Watch와 dual alert 검증을 추가한다. | AC-006 | WS4 | preserve |
| CH-043 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt` | `StageTimelineFrame` | edit | existing Canvas lane/gap/tap implementation | tap 선택과 실제 gap은 유지하고 얇은 선을 굵고 둥근 단계 구간과 가는 전환선으로 바꾼다. | FR-002, AC-003 | WS5 | preserve |
| CH-044 | production | `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/MainActivity.kt` | `TimelinePage` | edit | existing fixed one-hour Canvas | 데이터·dependency 추가 없이 같은 수면 단계형 표현과 우측 레인 이름을 compact하게 적용한다. | FR-002, AC-003 | WS5 | preserve |
| CH-045 | production | `apps/backend/app/schemas/dashboard.py` | `DashboardCalendarView` | edit | existing day-or-month literal | authenticated query contract에 `week`를 추가한다. | FR-003 | WS6 | preserve |
| CH-046 | production | `apps/backend/app/services/dashboard.py` | `DashboardService.craving_calendar` | edit | existing local-boundary and daily-bucket path | anchor를 현지 월요일로 정규화하고 7개 day bucket과 timezone-aware UTC 경계를 반환한다. | FR-003 | WS6 | preserve |
| CH-047 | test | `apps/backend/tests/test_craving_bar_dashboard_v25.py` | weekly calendar cases | extend | current day/month fake-repository tests | 월요일~일요일, 월 경계, 7개 bucket과 invalid view를 증명한다. | AC-002 | WS6 | preserve |
| CH-048 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/DashboardRepository.kt` | calendar client/constants | edit | existing day/month validation | response model 변경 없이 `week`를 허용하고 요청한다. | FR-004 | WS7 | preserve |
| CH-049 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt` | calendar selection/navigation | edit | existing day/month anchor state | 주간 anchor를 7일 단위로 이동하고 같은 calendar endpoint를 사용한다. | FR-004 | WS7 | preserve |
| CH-050 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt` | calendar controls/copy | edit | existing range chips and calendar summary | `일간`과 `월간` 사이에 `주간`을 넣고 선택 주 기간을 표시한다. | FR-004 | WS7 | preserve |
| CH-051 | test | `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/data/DashboardParserTest.kt` | weekly client/parser cases | extend | existing day/month parser tests | weekly request 허용과 7개 일별 bucket parsing을 증명한다. | AC-002 | WS7 | preserve |
| CH-052 | docs | `apps/test_mobile_app/SERVER_API_SPEC.md` | weekly API contract | edit | current calendar API section | day, week, month 요청과 weekly bucket 의미를 기록한다. | FR-003, AC-002 | WS8 | preserve |
| CH-053 | docs | `docs/prd/PRD_neurotruth_mobile.ko.md` | weekly dashboard flow | edit | current day/month dashboard section | 주간 선택과 동기화된 차트 동작을 추가한다. | FR-004, AC-002 | WS8 | preserve |
| CH-054 | docs | `docs/deployment/DGX_RUNTIME_INVENTORY.ko.md` | DGX cooldown rollout | edit | current unresolved cooldown checklist | secret 없는 환경 검증, backend 재생성, 기기 재시험 명령을 추가한다. | FR-014, AC-007 | WS8 | preserve |
| CH-055 | docs | `.env.example` | alert environment defaults | edit | current Watch-only alert block | 900초 cooldown과 폐기 변수 제거 계약을 유지한다. | FR-014, AC-007 | WS8 | preserve |
| CH-056 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt` | `StageTimelineFrame`, signal section | edit | existing Canvas chart and live signal card | 최근 1시간 Canvas 확대, 단독 bucket bar, 저장 preview 제거, 실시간 PPG·EDA 분리 표시 | FR-015, FR-017, AC-008 | WS9 | preserve |
| CH-057 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/AuqScreen.kt` | response layout | edit | current seven response buttons | 2열 배치를 제거하고 한 줄에 하나의 전체 폭 버튼 사용 | FR-016, AC-008 | WS9 | preserve |
| CH-058 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/LivePpgSource.kt` | live trace builder | edit | existing bounded 20-second Watch buffer | EDA와 화면용 고정격자/채널별 MinMax를 추가하고 freshness/no-data 의미 유지 | FR-017, AC-009 | WS9 | preserve |
| CH-059 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt` | dashboard signal state | edit | existing live polling | 저장 preview UI state/call을 제거하고 실시간 두 채널만 제공 | FR-017, AC-008 | WS9 | preserve |
| CH-060 | test | `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/data/LivePpgWindowTest.kt` | live signal preprocessing tests | extend | existing pure JVM window tests | PPG/EDA grid, MinMax, 상수·누락·freshness 검증 | FR-017, AC-009 | WS9 | preserve |
| CH-061 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt` | calendar future-period guard | edit | existing day/week/month anchor navigation | 결정론적 현지 기간 비교를 추가하고 직접 미래 anchor를 무시하며 `onCalendarNext`가 오늘/현재 주/현재 월을 넘겨 load하지 못하게 한다. | FR-018, AC-010 | WS9 | preserve |
| CH-062 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt` | next-button/date-picker limits | edit | existing `CalendarControls` | 현재 기간에서 `다음`을 비활성화하고 Material 날짜 선택기에서 내일 이후를 선택하지 못하게 한다. | FR-018, AC-010 | WS9 | preserve |
| CH-063 | test | `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardCalendarNavigationTest.kt` | calendar navigation boundary cases | add | existing pure JVM policy-test style | 일·월요일 기준 주·월의 과거/현재/미래 동작과 직접 미래 anchor를 검증한다. | FR-018, AC-010 | WS9 | preserve |

### 변경 예산

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 13 | 8 | 0 | 550 | None | None |
| WS2 | 16 | 11 | 2 | 1000 | None | None |
| WS3 | 16 | 10 | 1 | 930 | None | None |
| WS4 | 5 | 0 | 0 | 250 | None | None |
| WS5 | 2 | 2 | 0 | 140 | None | None |
| WS6 | 3 | 2 | 0 | 80 | None | None |
| WS7 | 4 | 3 | 0 | 100 | None | None |
| WS8 | 4 | 0 | 0 | 100 | None | None |
| WS9 | 6 | 4 | 0 | 250 | None | None |

## 8. 작업 계획

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | Backend durable Watch-only alert와 day/month calendar API 구현 | None | P1 | CH-001, CH-002, CH-003, CH-004, CH-005, CH-006, CH-017, CH-018, CH-041, CH-019, CH-020, CH-021, CH-022 | `apps/backend/app/**`, `apps/backend/tests/**` | `apps/mobile/**`, `apps/backend/alembic/**`, existing unrelated changes outside mapped symbols | FR-003, FR-010, FR-013, NFR-001, NFR-002, NFR-003, NFR-004, AC-001, AC-002, AC-005 | focused pytest, then backend suite | verified |
| WS2 | Phone stage/dashboard/AUQ/voice UX와 테스트 구현 | None | P1 | CH-007, CH-008, CH-029, CH-009, CH-030, CH-010, CH-031, CH-032, CH-011, CH-033, CH-012, CH-023, CH-024, CH-025 | `apps/mobile/core/**`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/**`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/**`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/**`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/chat/**`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/NeuroTruthNavHost.kt`, `apps/mobile/app/src/test/**` | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/home/**`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/service/**`, `apps/mobile/wearos/**`, `apps/backend/**` | FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-011, FR-012, NFR-001, NFR-004, AC-003, AC-005 | Core/App unit tests and app lint/assemble | verified |
| WS3 | Phone-controlled sensing과 Watch companion/control/alert UI 구현 | None | P1 | CH-013, CH-034, CH-035, CH-036, CH-014, CH-037, CH-038, CH-039, CH-040, CH-015, CH-042, CH-026 | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/service/**`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/home/**`, `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/ui/home/**`, `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/service/SseFrameParserTest.kt`, `apps/mobile/wearos/**` | `apps/backend/**`, Phone dashboard/AUQ/chat, Core stage owner | FR-007, FR-008, FR-009, FR-010, NFR-001, NFR-004, NFR-005, AC-004, AC-005, AC-006 | focused JVM tests, Wear/App unit/build | verified |
| WS4 | 개발/API/기기 테스트 Markdown 동기화 | WS1, WS2, WS3 | Serial | CH-016, CH-027, CH-028 | `apps/test_mobile_app/SERVER_API_SPEC.md`, `docs/prd/PRD_neurotruth_mobile.ko.md`, `docs/deployment/MOBILE_DEVICE_TEST_GUIDE.ko.md` | PPTX, source code, unrelated docs | FR-001, FR-003, FR-005, FR-007, FR-010, FR-011, FR-013, AC-006 | API/term/path comparison | verified |
| WS5 | Phone과 Watch 최근 1시간 차트를 승인된 웨어러블 수면 단계 시각 언어로 재디자인 | WS2, WS3 | Serial | CH-043, CH-044 | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt`, `apps/mobile/wearos/src/main/kotlin/com/neurotruth/mobile/wear/MainActivity.kt` | APIs, repositories, models, alerts, other screens, dependencies | FR-002, AC-003 | Phone/Wear unit tests, lint, debug builds, and rendered/device visual review when available | verified |
| WS6 | backend calendar contract에 월요일~일요일 주간 보기를 추가 | None | P2 | CH-045, CH-046, CH-047 | `apps/backend/app/schemas/dashboard.py`, `apps/backend/app/services/dashboard.py`, `apps/backend/tests/test_craving_bar_dashboard_v25.py` | repositories, migrations, alert behavior, mobile | FR-003, AC-002 | focused backend pytest | verified |
| WS7 | Phone dashboard에 주간 선택과 이동을 추가 | None | P2 | CH-048, CH-049, CH-050, CH-051 | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/DashboardRepository.kt`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt`, `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/data/DashboardParserTest.kt` | backend, Wear, Home, AUQ, Chat | FR-004, AC-002 | app unit tests, lint, debug build | verified |
| WS8 | runtime slice 이후 API·PRD·DGX 환경 지침 동기화 | WS6, WS7 | Serial | CH-052, CH-053, CH-054, CH-055 | `apps/test_mobile_app/SERVER_API_SPEC.md`, `docs/prd/PRD_neurotruth_mobile.ko.md`, `docs/deployment/DGX_RUNTIME_INVENTORY.ko.md`, `.env.example` | source code, PPTX, secrets | FR-003, FR-004, FR-014, AC-002, AC-007 | term/path/secret scan | verified |
| WS9 | backend·저장 계약 변경 없이 실기기 검토의 대시보드·AUQ·실시간 신호·미래 달력 수정을 적용 | WS5 | Serial | CH-056, CH-057, CH-058, CH-059, CH-060, CH-061, CH-062, CH-063 | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardScreen.kt`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/auq/AuqScreen.kt`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/data/LivePpgSource.kt`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardViewModel.kt`, `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/data/LivePpgWindowTest.kt`, `apps/mobile/app/src/test/kotlin/com/neurotruth/mobile/ui/dashboard/DashboardCalendarNavigationTest.kt` | backend, DB, API contracts, model code, Watch UI, sensor upload/persistence, unrelated mobile screens | FR-015, FR-016, FR-017, FR-018, AC-008, AC-009, AC-010 | focused calendar/live-signal JVM tests, App unit test, lint, debug build, Phone screenshot review | in_progress |

### 병렬 근거

WS1은 backend만, WS2는 Phone/Core stage/dashboard/AUQ/chat만, WS3는 Phone Home/service/parser와 Wear만 수정한다. public contract는 이 명세로 고정되어 있고 쓰기 범위가 겹치지 않는다. WS4는 실제 patch 뒤 실행한다.

### 최종 통합

각 slice focused test와 diff/budget 검토 후 backend full pytest, Core/App/Wear unit, lint, APK build, Docker fake integration을 실행한다. 모두 통과한 뒤 사용자에게 Phone/Watch 재연결을 요청한다.

## 9. 검증·배포·위험

### 검증

- Backend: alert policy/persistence/rPPG/calendar focused와 full pytest.
- Phone: stage/parser/chart/AUQ/STT/TTS/control state unit, lint, assemble.
- Wear: control/status/fallback/dedup/snapshot state unit, lint, assemble.
- Docker: config/build, health/ready/OpenAPI, fake Bedrock/STT/DGX.
- Device: 권한, Phone start/stop, auto upload, prediction, 양쪽 stage/alert, AUQ result, STT/TTS, disconnect.

### 최소성 검토

- 모든 production path를 CH-001~016에 매핑한다.
- dependency/schema/generic abstraction/관련 없는 refactor를 거절한다.
- worker 이전 path snapshot과 diff를 비교해 기존 사용자 변경을 보존한다.
- 기존 SQL/Canvas/navigation/scheduler/MessageClient를 우선 확장한다.

### 배포/rollback

1. Backend/Phone/Wear를 함께 build/test한다.
2. DGX 배포 전 DB backup을 만들되 migration은 실행하지 않는다.
3. 세 구성요소를 같은 계약으로 배포하고 readiness를 검증한다.
4. 문제 시 이전 image/APK로 rollback한다.

### 위험

| 위험 | 영향 | 완화 |
| --- | --- | --- |
| Wear health FGS 차단 | sample 없음 | ack gate, confirmation_required, Watch notification, background permission test |
| 중복/지연 window | alert 폭증 | DB history+patient lock+900초 cooldown+alertId dedup |
| timezone/DST | 잘못된 bucket | local boundary와 DST/month test |
| AUQ 압축 | text clipping | 일반 no-scroll, 큰 글꼴 scroll fallback |
| voice 중복 | message 중복 | one client ID, 기존 retry, correlated TTS test |
| dirty work overwrite | 사용자 변경 손실 | branch 보존, disjoint slice, main diff review |

## 10. 개정과 진행

### 설계 개정 기록

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-25T00:00:00+09:00 | user-approved-plan-implementation | 승인된 계획과 여섯 결정군으로 구현 계약을 생성했다. | D-001, D-002, D-003, D-004, D-005, D-006, D-007, D-008, D-009, D-010 | Q-001, Q-002, Q-003, Q-004, Q-005, Q-006 |
| 2 | 2026-07-25T02:15:00+09:00 | docker-full-pytest | 승인된 calendar endpoint에 필요한 public-operation inventory test target을 추가했다. | D-011 | — |
| 3 | 2026-07-25T03:00:00+09:00 | ws2-final-diff-accounting | 기존 dirty Chat/NavHost 작업을 최종 WS2 branch patch budget에 포함했다. | D-012 | — |
| 4 | 2026-07-25T04:00:00+09:00 | ws3-stage-only-relay-verification | 이미 승인된 stage-only Watch relay를 검증하는 기존 payload 테스트 경로를 명시했다. production 동작과 방향은 바뀌지 않았다. | D-010 | — |
| 5 | 2026-07-25T04:30:00+09:00 | ws3-final-recovery-accounting | 승인된 Phone-controlled Watch fallback에 필요한 최소 restart-ack와 최초 실행 권한 보정을 반영했다. | D-013 | — |
| 6 | 2026-07-26T12:00:00+09:00 | user-sleep-stage-chart-reference | 최근 1시간 차트 시각 영역만 재개하고 데이터/API 변경 없는 두 파일 Canvas 재디자인으로 매핑했다. | D-014 | — |
| 7 | 2026-07-26T15:00:00+09:00 | user-weekly-dashboard-and-dgx-cooldown | 주간 calendar 확장과 DGX `.env` 900초 cooldown 배포·기기 재시험 요구를 추가했다. | D-015, D-016 | — |
| 8 | 2026-07-26T16:00:00+09:00 | user-device-review-dashboard-auq-signals | 최근 1시간 웨어러블 차트 확대, AUQ 한 줄 한 응답, 저장 preview를 실시간 전처리 PPG·EDA로 교체하는 계약을 추가했다. 사용자의 직접 요청이 이 좁은 후속 개정을 확인한다. | D-017 | — |
| 9 | 2026-07-26T17:30:00+09:00 | user-calendar-future-boundary | Phone 일·주·월 이동에 현재 기간 경계를 추가하고 날짜 선택기 미래 날짜를 비활성화하며 결정론적 JVM 검증을 매핑했다. 사용자의 직접 요청이 이 좁은 후속 개정을 확인한다. | D-018 | — |

### 구현 진행 기록

| 시각 | Spec revision | Slice | 상태 | 근거/비고 |
| --- | --- | --- | --- | --- |
| 2026-07-25T00:00:00+09:00 | 1 | — | ready | 사용자가 전체 계획과 구현을 승인했다. 기존 dirty worktree를 보존한 채 `feat/stage-watch-auq-voice-ux` branch를 만들었다. |
| 2026-07-25T01:00:00+09:00 | 1 | WS1 | implementing | 명세 검증을 통과했다. 검증기가 Android test scope overlap을 탐지해 backend slice부터 시작했다. |
| 2026-07-25T02:00:00+09:00 | 1 | WS1 | completed | 초기 scope/patch 검사는 12 changed files, 8 production files, production +410 lines, 신규 production/dependency/migration 0으로 통과했다. compileall과 alert smoke도 통과했다. |
| 2026-07-25T02:01:00+09:00 | 1 | WS2 | implementing | WS1 diff 검토 뒤 Phone stage/dashboard/AUQ/voice slice를 시작했다. |
| 2026-07-25T02:15:00+09:00 | 2 | WS1 | verification_followup | Docker focused pytest 43개가 통과했다. full pytest는 endpoint inventory 1건과 local schema bind-mount 오류 2건만 노출했고 CH-041에 test-only follow-up을 기록했다. |
| 2026-07-25T03:00:00+09:00 | 3 | WS2 | patch_accounting | 기존 Chat/NavHost dirty work를 포함한 final branch diff는 13 files, 10 production files, production +974 lines이고 신규 production/dependency/migration은 없다. |
| 2026-07-25T03:01:00+09:00 | 3 | WS2 | verified | Scope/patch 검사와 Core/App test, compile, lint, debug APK build가 통과했다. |
| 2026-07-25T03:02:00+09:00 | 3 | WS3 | implementing | Phone Home/service와 Wear control/companion slice를 시작했다. |
| 2026-07-25T04:01:00+09:00 | 4 | WS3 | verification_followup | 메인 검토에서 Watch relay의 불필요한 legacy binary class를 제거하고 stage-only/privacy assertion을 추가했다. Core/App/Wear 전체 테스트, lint, 두 debug APK build가 통과했다. |
| 2026-07-25T04:10:00+09:00 | 4 | WS3 | verified | scope와 patch-budget 검증 통과: changed 12, production 9, new production 0, production +888, dependency/shared abstraction 없음. |
| 2026-07-25T04:11:00+09:00 | 4 | WS4 | implementing | 모든 runtime slice 검증 후 문서 동기화를 시작했다. |
| 2026-07-25T04:31:00+09:00 | 5 | WS3 | correction_verified | 이미 실행 중인 Watch가 tracker 재시작 없이 새 Phone request를 ack하고 최초 실행 sensor/background/notification 권한 준비가 복구됐다. Android 전체 test/lint/APK build가 통과했다. |
| 2026-07-25T04:40:00+09:00 | 5 | WS4 | verified | 매핑된 문서 3개의 scope/patch 검증을 통과했고 stale listener/start/STT/AUQ 용어를 제거한 뒤 실제 route/constant와 대조했다. |
| 2026-07-25T04:50:00+09:00 | 5 | — | complete | Backend Docker pytest 178개 통과. Core/App/Wear test, 양쪽 lint, 양쪽 APK build 통과. 임시 PostgreSQL smoke stack을 `20260717_0005`까지 migrate하고 `/health`, `/ready`, OpenAPI calendar route를 확인한 뒤 임시 리소스를 제거했다. |
| 2026-07-26T11:00:00+09:00 | 5 | — | post_completion_audit | 최종 화면·런타임 검수에서 Watch 차트 높이 0, 단계 색상 고정, 종료 ACK 경합, Phone 차트 가장자리 잘림을 보정했다. Docker 검증에서 발견한 schema 권위 파일의 잘못된 `docs/` 이동을 내용 변경 없이 repository root로 복구했다. Backend Docker pytest 178개, Core/App/Wear test, 양쪽 lint, 양쪽 APK build, Compose config와 schema bind mount가 통과했다. 후속 APK 재설치 시점에는 ADB 기기가 표시되지 않았다. |
| 2026-07-26T12:00:00+09:00 | 6 | WS5 | implementing | 사용자가 기존 Phone/Watch 최근 1시간 4단계 차트를 Galaxy/Apple 수면 단계 스타일로 변경하도록 승인했다. |
| 2026-07-26T13:00:00+09:00 | 6 | WS5 | verified | Phone과 Watch Canvas가 굵고 둥근 단계 구간, 색상 분할 전환선, 단계색 가이드, 실제 공백, 화면 안전 라벨을 사용하도록 변경됐다. Core/App/Wear 테스트, 양쪽 lint, 양쪽 debug APK 빌드가 통과했다. 연결된 ADB 기기가 없어 실제 기기 화면 캡처 검토는 수행하지 못했다. |
| 2026-07-26T13:01:00+09:00 | 6 | — | complete | WS5는 두 production 파일 범위 안에서 완료됐고 API, 데이터, 모델, dependency, migration 변경 없이 Android 전체 검증을 통과했다. |
| 2026-07-26T15:00:00+09:00 | 7 | — | ready | 사용자의 후속 요청이 주간 calendar와 DGX cooldown 배포 확장을 명시적으로 승인했다. WS6와 WS7은 서로 독립적으로 준비됐다. |
| 2026-07-26T15:30:00+09:00 | 7 | WS6 | verified | Scope·patch 검사가 3개 파일, production 2개, production +6줄로 통과했고 새 dependency·abstraction은 없다. Python syntax compile은 통과했으며 host 번들 환경에 project dependency가 없어 focused pytest는 실행하지 못했다. |
| 2026-07-26T15:31:00+09:00 | 7 | WS7 | verified | Scope·patch 검사가 4개 파일, production 3개, production +35줄로 통과했다. App unit test, lint, Kotlin compile, debug APK build가 통과했다. |
| 2026-07-26T15:45:00+09:00 | 7 | WS8 | verified | 매핑된 문서·환경 template 4개의 scope·patch 검사가 통과했고 주간 용어, DGX 900초 배포 명령, 참조 경로, 예시값 전용 secret 처리를 검증했다. |
| 2026-07-26T15:46:00+09:00 | 7 | — | complete | Docker backend calendar focused test 14개와 Compose config가 통과했고 Android unit/lint/debug build도 통과했다. DGX 실제 배포와 Phone/Watch 15분 관찰은 로컬 구현이 아닌 운영 인수 단계로 남는다. |
| 2026-07-26T16:00:00+09:00 | 8 | — | ready | 실기기 검토에서 Phone 표시 문제 세 가지가 확인됐다. 사용자가 좁은 WS9 수정을 직접 요청·승인했으며 API·DB·모델·업로드·저장·Watch UI·dependency·migration 변경은 없다. |
| 2026-07-26T16:05:00+09:00 | 8 | WS9 | implementing | 검증된 후속 slice를 5개 파일 범위로 위임했으며 신규 production file·dependency·shared abstraction·backend·저장 변경은 없다. |
| 2026-07-26T17:10:00+09:00 | 8 | WS9 | correction_verified | 읽기 전용 검수에서 짧은 AUQ 화면과 실제 공백을 덮을 수 있는 단독 단계 bar 위험을 확인했다. AUQ는 7개 전체 폭 응답이 화면을 넘을 때만 스크롤되고, 짧은 단계 구간은 인접 측정 전 명시적 공백을 보존하도록 보정했다. |
| 2026-07-26T17:20:00+09:00 | 8 | WS9 | validation_pending | App 단위 테스트, lint, debug APK 빌드, diff 검사와 5개 파일 변경 예산이 통과했다. 현재 ADB에는 Watch만 보여 Phone APK 설치와 화면 캡처 검토만 인수 단계로 남았다. |
| 2026-07-26T17:30:00+09:00 | 9 | WS9 | implementing | 사용자가 일·주·월 미래 이동과 날짜 선택을 막는 좁은 확장을 직접 승인했다. 동일한 Phone 전용 slice를 6개 파일로 재개하며 신규 production file·dependency·abstraction과 backend·API·DB·Watch 변경은 없다. |
| 2026-07-26T17:50:00+09:00 | 9 | WS9 | correction_verified | 읽기 전용 검수에서 현재 주·현재 월 이동이 오늘 이후 raw anchor를 남길 수 있음을 확인하고 보정했다. 반환 anchor, 직접 anchor 변경, DatePicker 초기값·선택 가능 날짜·확인 전달이 모두 현지 오늘 이하를 강제하고 rollover 테스트를 추가했으며 후속 검수에서 남은 문제는 없었다. |
| 2026-07-26T17:55:00+09:00 | 9 | WS9 | validation_pending | Spec·scope·patch 검사가 6개 파일, production 4개, 신규 production 0개, production 추가 217/250으로 통과했다. App 전체 단위 테스트, lint, debug APK 빌드와 diff 검사도 통과했다. ADB에는 여전히 Watch만 보여 Phone 설치와 화면 캡처 인수 확인이 남았다. |
