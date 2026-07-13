# NeuroTruth 모바일 AUQ 재실행 차단·비동기 인계·채팅 제한시간 설정 명세

- **Language role:** Korean mirror spec
- **Spec status:** Finalized
- **Spec version:** 1.2
- **Last updated:** 2026-07-13
- **English source:** `neurotruth/docs/specs/2026-07-13-neurotruth-mobile-auq-async-handoff-timeout-spec.md`
- **Korean mirror:** `neurotruth/docs/specs/2026-07-13-neurotruth-mobile-auq-async-handoff-timeout-spec.ko.md`
- **Requester / owner:** NeuroTruth project requester
- **Implementation status:** Implemented

## 0. Codex 구현 핸드오프

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: neurotruth/docs/specs/2026-07-13-neurotruth-mobile-auq-async-handoff-timeout-spec.md
korean_mirror_spec: neurotruth/docs/specs/2026-07-13-neurotruth-mobile-auq-async-handoff-timeout-spec.ko.md
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

## 1. 요약

Android 폰 앱은 이전에 뒤이어 온 REQUIRED 갈망 alert가 활성 상담 chat을 8문항 AUQ/state-check 화면으로 교체할 수 있었다. Handoff report 생성도 Bedrock 작업보다 짧은 20초 mobile read timeout을 가진 하나의 동기 HTTP request에 의존했다. 이 기능은 공통 상담 활성 latch, 비동기 handoff job 접수/status polling, 기본 60분의 영속 관리자 chat timeout, 상담 전용 초기화를 추가한다. Prediction 기록과 watch 상태 전달은 계속하지만, 상담 활성 중에는 chat을 닫을 때까지 phone 표시와 watch 진동/notification을 모두 차단한다.

## 2. 목표

- G1. 상담 chat 활성 중 AUQ가 다시 열리지 않게 한다.
- G2. 상담 활성 중 phone과 watch의 user-facing alert를 차단하면서 prediction 기록과 phone-to-watch 상태 전달은 계속한다.
- G3. 하나의 장시간 mobile request 없이 handoff report를 생성하고 저장한다.
- G4. Chat 응답 대기를 기본 60분으로 하고 관리자 설정을 허용한다.
- G5. Sensor history와 server URL을 지우지 않고 상담 상태만 초기화한다.
- G6. 기존 public endpoint 호환성을 유지한다.

## 3. 비목표

- NG1. 대화 turn 수 제한을 추가하지 않는다.
- NG2. 갈망 prediction, alert 분류, watch UI, LLM model/provider, prompt, sensor upload timeout을 바꾸지 않는다.
- NG3. 영속 분산 job queue를 추가하지 않는다. Handoff registry는 크기가 제한된 process-local 상태다.
- NG4. 기존 전체 데이터 초기화를 제거하거나 바꾸지 않는다.

## 4. 사용자 및 사용 사례

### 4.1 대상 사용자

- Required 갈망 alert 뒤 text intervention을 사용하는 환자.
- Phone 상담 흐름을 설정/초기화하는 관리자 또는 연구자.
- Handoff 생성을 모니터링하는 backend 운영자.

### 4.2 주요 사용 사례

- UC1. 환자가 AUQ를 제출하고 chat을 계속하는 동안 뒤이은 alert가 AUQ를 다시 열지 않고 기록된다.
- UC2. 환자가 handoff 생성을 시작한 뒤 chat을 계속하고 완료 report를 확인한다.
- UC3. 관리자가 chat 응답 제한시간을 바꾸거나 상담 상태만 초기화한다.
- UC4. Legacy client가 기존 동기 handoff를 계속 사용한다.

## 5. 최종 결정

| ID | Domain | 결정 | Source |
| --- | --- | --- | --- |
| D1 | State | 서비스와 foreground 경로가 함께 쓰는 상담 활성 latch를 `PhoneMonitoringState`가 소유한다. | User decision |
| D2 | Flow | AUQ 제출 또는 chat 다시 열기가 latch를 활성화하고 chat 닫기, 전체 초기화, 관리자 상담 초기화가 해제한다. | User decision |
| D3 | Alerts | 차단된 alert도 claim, 기록, publish한다. Phone-to-watch 상태는 명시적인 non-presenting action으로 계속 전달해 상담 활성 중 AUQ와 phone/watch 진동 또는 notification을 실행하지 않는다. | 실제 기기 검증 후 사용자 결정 |
| D4 | API | 기존 `POST /api/intervention/handoff`를 유지하면서 HTTP 202 `POST /api/intervention/handoff/jobs`, `GET /api/intervention/handoff/jobs/{job_id}`를 추가한다. | User decision |
| D5 | Jobs | 완료 job은 동기 generation/persistence 경로를 재사용하고 public failure를 정제한다. | Agent conservative default |
| D6 | Mobile | Chat과 handoff는 별도 busy state를 사용하고 generation POST를 자동 재접수하지 않는다. | User decision |
| D7 | Timeout | Chat read timeout 기본값은 3,600,000ms이고 관리자가 1~1,440분으로 저장한다. | User decision |
| D8 | Reset | `상담 상태 초기화`는 AUQ/chat/slot/handoff/polling을 지우고 sensor sample과 server config는 유지한다. | User decision |
| D9 | Capacity | Process-local registry는 최대 128개 job, terminal metadata TTL 1시간, 실행 상한 1시간을 사용한다. | Agent conservative default |
| D10 | Shutdown | Shutdown은 새 job을 원자적으로 거부하고 queued/running을 failed로 만들며 task를 cancel/drain한다. | Verification refinement |

## 6. 기능 요구사항

- FR1. 상담 활성 중 `registerAlertAction`은 사용자 화면 표시를 요청하지 않아야 한다.
- FR2. 차단은 prediction publish, audit state, watch 상태 전달을 막지 않아야 하지만 상담 활성 중 watch payload는 non-presenting action으로 resolve되어야 한다.
- FR3. Chat 닫기는 이미 claim한 alert를 재생하지 않고 이후의 서로 다른 alert만 허용해야 한다.
- FR4. 관리자 상담 초기화는 local chat/handoff job을 취소하고 sensor history/URL 없이 상담 UI/state만 지워야 한다.
- FR5. 비동기 handoff 접수는 immutable request snapshot과 opaque unique job ID를 사용해야 한다.
- FR6. Job status는 `jobId`, `sessionId`, queued/running/completed/failed를 노출하고 completed는 `result`, failed는 안전한 error를 포함해야 한다.
- FR7. Mobile polling은 완료, 실패, 404, deadline, reset, 새 session, ViewModel 종료 시 멈춰야 한다.
- FR8. Handoff job 실행 중에도 chat을 사용할 수 있어야 한다.
- FR9. Timeout 설정은 app/activity 재생성 뒤에도 유지되고 누락/오류 값은 60분으로 복구해야 한다.
- FR10. 멈춘 job과 server shutdown이 registry capacity를 안전하게 회수해야 한다.
- FR11. Foreground와 background prediction 경로는 동일한 상담 활성 watch 차단 결정을 적용해야 한다.

## 7. 사용자 경험 / UI 요구사항

- UI1. 활성 chat은 뒤이은 required alert가 와도 계속 표시된다.
- UI2. Handoff card는 chat-send와 독립적으로 queued/running/completed/failed를 표시한다.
- UI3. 관리자 모드는 `채팅 응답 제한시간(분)`, `제한시간 적용`, `상담 상태 초기화`를 제공한다.
- UI4. 잘못된 timeout 값은 한국어 1~1,440분 validation을 표시한다.
- UI5. Chat timeout은 user message를 유지하고 수동 재시도를 안내하며 자동 retry하지 않는다.
- UI6. 상담 활성 중 watch는 최신 craving class/alert level을 계속 표시할 수 있지만 진동하거나 craving notification을 게시하면 안 된다.

## 8. API / 데이터 / 상태 요구사항

- API1. `POST /api/intervention/handoff/jobs`는 `HandoffRequest`를 받고 HTTP 202와 `jobId`, `sessionId`, `status=queued`를 반환한다.
- API2. `GET /api/intervention/handoff/jobs/{job_id}`는 public job state를 반환하며 unknown/expired는 HTTP 404다.
- API3. `POST /api/intervention/handoff`는 backward-compatible하다.
- DATA1. 완료된 비동기 report는 기존 Postgres handoff persistence 경로와 session ID를 사용한다.
- DATA2. 관리자 preference는 timeout 분만 저장한다.
- STATE1. Mobile handoff job은 한 번에 하나이며 stale completion이 새 gate token을 해제하지 못한다.
- STATE2. Process-local job은 최대 128개, terminal TTL 1시간, runtime 1시간으로 제한한다.
- STATE3. 상담 활성 중 phone-to-watch payload는 prediction metadata를 보존하지만 `alertAction`을 `none`으로 덮어쓰고, chat 종료 뒤 미래 payload는 원래 action을 유지한다.

## 9. 권한, 보안, 개인정보 및 감사

- SEC1. 새 Android/backend permission은 필요하지 않다.
- PRIV1. 전체 conversation, credential, bearer token, provider raw failure를 로그에 남기지 않는다.
- AUDIT1. 차단된 alert도 prediction/alert state와 watch 상태 전달에 남고 phone/watch user-facing presentation만 차단한다.
- AUDIT2. Job ID는 opaque UUID이고 failed status는 `Handoff generation failed`만 반환한다.

## 10. 오류, 엣지 케이스 및 동시성 동작

- ERR1. 일시적 status 조회 실패는 deadline까지 polling을 재시도하지만 generation을 다시 접수하지 않는다.
- ERR2. HTTP 404는 backend restart 가능성을 표시하고 polling을 멈춘다.
- ERR3. Provider error, runtime timeout, shutdown은 정제된 failed state를 만든다.
- EDGE1. 차단된 alert는 claim 상태로 남아 chat 닫기 직후 stale AUQ가 재생되지 않는다.
- EDGE2. 누락/오류 timeout 저장값은 60분으로 돌아간다.
- EDGE3. Watch 전송 시점에 차단 여부를 평가해 foreground와 background receiver 모두 현재 상담 latch 상태를 사용한다.
- CONC1. Token gate가 duplicate mobile handoff를 거부하고 reset 뒤 stale release를 무시한다.
- CONC2. Registry submit/shutdown/status transition은 event-loop lock으로 보호하고 late task result가 terminal state를 덮지 못한다.

## 11. 의존성 및 설정

- DEP1. 기존 FastAPI, asyncio, Android `HttpURLConnection`, `SharedPreferences`, StateFlow, coroutine을 재사용한다.
- DEP2. 새 dependency 또는 schema migration은 없다.
- DEP3. Mobile timeout은 기본 60분, 범위 1~1,440분이며 backend job runtime/TTL은 기본 1시간이다.

## 12. 마이그레이션, 롤아웃 및 롤백

- MIG1. DB migration은 없다. 비동기 report는 기존 table을 사용한다.
- ROLL1. Backend를 rebuild/restart하고 mobile APK를 build한 뒤 ADB 연결 시 phone에 설치한다.
- BACK1. Backend/mobile source를 함께 rollback한다. 기존 동기 endpoint가 이전 mobile client를 지원한다.

## 13. 구현 경계

### 13.1 예상 변경 영역

- `apps/backend/app/main.py`
- `apps/backend/tests/test_intervention_routes.py`
- `apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringState.kt`
- `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt`
- `apps/mobile/app/src/main/java/com/example/healthsensor/ServerUploader.kt`
- `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt`
- `apps/mobile/app/src/main/java/com/example/healthsensor/PhonePredictionSender.kt`
- `apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringService.kt`
- 집중 Android unit test와 동기화 문서.

### 13.2 금지 변경

- Prediction 분류, backend alert 주기, watch UI/source, model/provider 선택, prompt, DB schema, credential, sensor transport를 바꾸지 않는다.
- 관련 없는 refactor, formatting churn, dependency upgrade, broad rewrite, generated-file churn을 하지 않는다.

### 13.3 최소 변경 지침

- 기존 StateFlow/coroutine/HTTP/FastAPI pattern을 재사용한다.
- 기존 public endpoint를 backward-compatible하게 유지한다.
- 광범위한 UI/backend rewrite 대신 작은 state helper와 집중 test를 사용한다.

## 14. 인수 기준

- AC1. 활성 chat 중 여러 required prediction이 와도 chat이 유지되고 AUQ가 실행되지 않는다.
- AC2. Chat을 닫은 뒤 새로운 required prediction이 오면 AUQ를 다시 실행할 수 있다.
- AC3. 차단 중에도 prediction state와 watch 상태 전달은 계속하지만 반복 backend REQUIRED event가 phone 또는 watch 진동/notification을 만들지 않는다.
- AC4. Phone은 handoff job ID를 빠르게 받고 interactive 상태를 유지하며 완료 report를 표시/저장한다.
- AC5. Failed, expired, timed-out, shutdown job은 duplicate generation 없이 안전한 terminal 동작을 보인다.
- AC6. Chat timeout은 기본 60분이며 저장되고 관리자가 1~1,440분으로 바꿀 수 있다.
- AC7. `상담 상태 초기화`는 상담 상태를 지우고 sensor history/URL을 보존한다.
- AC8. 기존 동기 handoff 동작을 유지한다.
- AC9. Registry capacity는 timeout/shutdown 뒤 회복되고 shutdown 시작 뒤 submit을 거부한다.

## 15. 검증 계획

| Check | Command 또는 Method | Expected Result |
| --- | --- | --- |
| Backend tests | `apps/backend`에서 `.venv\\Scripts\\python.exe -m pytest -q` | 외부 LLM 호출 없이 50 tests 통과 |
| Backend compile | `.venv\\Scripts\\python.exe -m compileall -q app tests` | Exit 0 |
| Android tests/build | `apps/mobile`에서 `.\\gradlew.bat testDebugUnitTest assembleDebug` | Latch/timeout/gate test와 build 성공 |
| Android lint | `.\\gradlew.bat lintDebug` | Build 성공 |
| Runtime smoke | `/health`, OpenAPI, unknown job GET | Health ok/model ready, POST 202 선언, GET 존재, unknown 404 |
| Device check | `adb devices -l` | 기기 연결 시 install/실기기 flow, 없으면 skip 기록 |

## 16. 위험 및 참고사항

- RISK1. Process-local job은 backend restart를 넘기지 못하며 mobile은 404에서 멈추고 generation을 복제하지 않는다.
- RISK2. 1시간 mobile timeout은 chat request를 오래 유지할 수 있으며 관리자 모드에서 줄일 수 있다.
- RISK3. 실제 기기 검증에서 backend REQUIRED event가 설정된 30초 cooldown마다 반복됨을 확인했다. Payload-level 차단은 backend audit record를 바꾸지 않고 상담 중 watch 반복 alert를 막는다.

## 17. 구현 체크리스트 / 진행 기록

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | 원인 및 명세 쌍 | Main | Completed | 이 spec pair | Repository inspection | 요청자가 async save, 1시간 chat timeout, 관리자 reset/config를 승인함. |
| P2 | Backend async handoff | Backend worker | Completed | `apps/backend/app/main.py`, `tests/test_intervention_routes.py` | 44 pytest 통과, compile exit 0 | Reviewer가 발견한 shutdown/hung-job recovery도 수정함. |
| P3 | Android flow/settings/UI | Mobile worker | Completed | Phone state, ViewModel, uploader, UI, 집중 tests | Unit tests/build/lint 통과 | Watch source 변경 없음. |
| P4 | Runtime 및 문서 | Main | Completed | Docker backend 및 활성 Markdown docs | Health/OpenAPI/404 smoke 통과 | 최신 APK build, ADB device가 없어 install skip. |
| P5 | 상담 활성 중 watch 표시 차단 및 재설치 | Mobile worker / Main | Completed | `PhonePredictionSender.kt`, `PhoneMonitoringService.kt`, `SensorViewModel.kt`, `PhonePredictionSenderTest.kt`, 활성 docs | Unit test 22개 통과, Phone/Wear build 및 lint 통과, 두 APK 설치 성공 | 활성 payload는 `alertAction=none`만 override하며 설치 후 실제 chat 관찰은 사용자 flow에서 남아 있다. |

## 18. 개정 이력

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.0 | 2026-07-13 | Feature Planner | 최초 승인 및 구현 feature definition. |
| 1.1 | 2026-07-13 | Feature Planner | Spec pair 표준화, shutdown/runtime 보강, 최종 검증 및 문서 상태 반영. |
| 1.2 | 2026-07-13 | Feature Planner | Watch 상태 전달은 보존하면서 상담 활성 중 반복 watch 진동/notification을 차단하도록 실제 기기 동작을 변경했다. |
