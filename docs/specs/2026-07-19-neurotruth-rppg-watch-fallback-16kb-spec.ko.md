# NeuroTruth 워치 선택형 rPPG 대체 경로 및 Android 16KB 대응 — 구현 명세

<!-- feature-planner-control
{
  "workflow": "feature-planner/v7",
  "state": "complete",
  "source_spec": "docs/specs/2026-07-19-neurotruth-rppg-watch-fallback-16kb-spec.md",
  "korean_mirror": "docs/specs/2026-07-19-neurotruth-rppg-watch-fallback-16kb-spec.ko.md",
  "spec_revision": 7,
  "reviewed_revision": 7,
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

> 영문 문서가 구현의 권위 있는 원본이고 이 파일은 동기화된 한국어 검토본이다. 사용자는 대화에서 전체 계획을 검토하고 이 revision의 구현을 명시적으로 승인했다.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `complete`, revision 7, reviewed revision 7 |
| Outcome | 연결된 Wear OS 노드가 없는 환자에게 동의 기반 20초 카메라 rPPG 주 동작을 제공하고, 연결된 환자는 워치 모니터링과 선택형 rPPG를 함께 사용한다. Android 네이티브 라이브러리는 16KB 호환 검사를 통과한다. |
| Recommended implementation | `STRAT-1` — 기존 Android ViewModel/Compose 흐름과 rPPG job 경로를 확장하고 빌드 도구 및 기존 rPPG 플래그 기본값만 갱신한다. |
| Planned production targets | `apps/mobile/app/src/main/java/com/example/healthsensor/WatchConnectionMonitor.kt::file-level`; `apps/mobile/app/src/main/java/com/example/healthsensor/WearDataListenerService.kt::onPeerConnected, onPeerDisconnected`; `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt::SensorViewModel`; `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt::UserHomeScreen, UserDashboardScreen`; `apps/mobile/app/src/main/java/com/example/healthsensor/RppgCameraScreen.kt::capture duration/countdown`; `apps/backend/app/core/config.py::SecuritySettings.rppg_enabled` |
| Expected additions | New production files: apps/mobile/app/src/main/java/com/example/healthsensor/WatchConnectionMonitor.kt; dependencies: None; shared abstractions: None |
| Work plan | `WS1`, `WS2`, reopened `WS3`, final-correction `WS4`가 모두 verified다. |
| Open questions | None |
| Agent decisions to review | None. |
| Last material change | Revision 7 — re-review와 저장소 검색이 backend, development, current AI 문서의 stale default-off wording을 찾아 성급한 WS3 parity 주장을 반증했고 bounded docs slice를 reopened했다. |

## 2. 결과와 범위

### 결과

Wear OS 워치를 소유하지 않거나 현재 연결하지 않은 환자는 홈의 강조된 동작으로 기존 20초 카메라 rPPG 측정을 명시적으로 실행할 수 있다. 워치가 연결된 환자는 워치 센서 예측을 계속 받고 rPPG를 선택적인 시점 측정으로 사용할 수 있다. Phone APK에서 알려진 비호환 CameraX 및 ML Kit 16KB 네이티브 바이너리를 제거한다.

### 범위 내

- 센서 샘플 신선도와 독립적으로 초기 Wearable Data Layer connected-node query와 기존 listener-service peer event로 워치 가용성을 판단한다.
- checking, connected, disconnected, error 상태를 구분하고 disconnected일 때만 rPPG를 주 동작으로 표시한다.
- 기존 네 가지 rPPG 동의와 카메라 job/prediction routing을 보존한다.
- 준비 상태와 `RPPG_ENABLED` 플래그를 유지하면서 rPPG를 기본 활성화한다.
- AGP, Gradle, ML Kit와 기존 CameraX family를 최소 검증된 16KB 호환 버전으로 올린다.
- 활성 루트/모바일/backend/web/development/API/product 문서를 갱신하고 Android, backend, 공개 API, APK 정렬, 실제 기기 동작을 검증한다.

### 범위 밖 / 비목표

- 백그라운드 카메라, 자동 반복 측정, 연결 끊김 알림, 센서 staleness timeout을 만들지 않는다.
- 신규 backend endpoint, response field, database migration, Alembic revision, prediction model, model input을 만들지 않는다.
- rPPG 암호화, 영상 보관 동의, Watch prediction relay 정책, 최신 결과 순서를 바꾸지 않는다.
- 현재 backend 구조개편이나 dirty working tree의 무관한 정리를 하지 않는다.

### 사용자와 주 흐름

1. 인증된 환자가 biosignal, AI analysis, camera-rPPG, face-video-retention 동의를 허용한다.
2. Phone이 Wear OS connected-node 목록을 조회하고 변경을 구독한다.
3. 연결 노드가 없으면 Home이 대체 경로를 설명하고 20초 얼굴 측정을 주 동작으로 제공한다. 노드가 있으면 워치 모니터링을 주 경로로 두고 rPPG는 보조로 유지한다.
4. 성공한 camera job은 기존 `/api/rppg/jobs` 흐름을 거쳐 `camera_rppg` Phone prediction을 게시한다. 이후 워치 prediction이 오면 최신 prediction을 대체한다.

### 현재 가정과 제약

- “워치 미연결”은 sensor packet 부재가 아니라 `connectedNodes.isEmpty()`다(D-004).
- rPPG는 연속 탐지가 아니라 사용자가 시작하는 전면 시점 측정이다(D-003).
- 기존 backend/DGX service가 ready를 반환해야 하며 그렇지 않으면 UI가 대체 측정 불가를 표시한다(D-006).
- 현재 미커밋 구조개편은 사용자 소유이므로 보존한다.

## 3. 저장소 패턴 기준선

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Wear 연결 | Phone-to-Watch 전송이 이미 `Wearable.getNodeClient(context).connectedNodes`를 조회한다. | `apps/mobile/app/src/main/java/com/example/healthsensor/PhonePredictionSender.kt::sendPrediction` | 기존 Play Services Wearable을 재사용하고 연결 dependency를 추가하지 않는다. |
| Phone 상태 | `SensorViewModel`이 Compose가 구독하는 `StateFlow`를 노출한다. | `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt` | Android 상태 소유권은 ViewModel, 렌더링은 Compose에 둔다. |
| rPPG routing | Home이 camera를 열고 완료 job을 `CravingPrediction`으로 변환하며 camera prediction은 Phone에만 남는다. | `MainActivity.kt::UserHomeScreen`; `RppgJobResult.toPrediction`; `SensorViewModel.handleCameraPrediction` | 두 번째 prediction 경로 없이 기존 job, persistence, alert, latest-prediction 흐름을 재사용한다. |
| 동의/준비 상태 | `MobileAuthState.canCaptureRppg`가 네 동의를 조합하고 `RppgServiceStatus.canStart`가 capture를 차단한다. | `MobileAuthRuntime.kt`; `RppgModels.kt` | privacy와 provider readiness gate를 약화하지 않는다. |
| Android build | AGP 8.2.2, Gradle 8.2, CameraX 1.3.2, bundled ML Kit face detection 16.1.6에서 확인된 16KB 경고가 발생한다. | `apps/mobile/build.gradle`; `gradle-wrapper.properties`; `apps/mobile/app/build.gradle`; final APK ELF inspection | SDK 34와 dependency family를 유지하고 필요한 version만 갱신한다. |
| Backend flag | rPPG startup은 `SecuritySettings.rppg_enabled`가 제어한다. | `apps/backend/app/core/config.py`; `apps/backend/app/core/runtime.py` | default만 바꾸고 validation/readiness 동작을 유지한다. |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | Wearable `NodeClient` dependency | `apps/mobile/app/build.gradle`; `PhonePredictionSender.kt` | 초기 node를 조회하고 node-list 변경을 구독한다. |
| R-002 | `SensorViewModel` StateFlow pattern | `SensorViewModel.kt` | `WatchConnectionState`를 노출하고 확정된 disconnect에서 sensor-receiving 상태를 초기화한다. |
| R-003 | Existing Home rPPG action | `MainActivity.kt::UserDashboardScreen` | navigation 추가 없이 강조와 보조 문구만 바꾼다. |
| R-004 | Existing consent and readiness gates | `MobileAuthState.canCaptureRppg`; `RppgServiceStatus.canStart` | CTA 활성화와 unavailable 설명에 사용한다. |
| R-005 | Existing camera prediction route | `SensorViewModel.handleCameraPrediction`; `RppgPredictionRoutingPolicy` | Phone-only `camera_rppg` 게시와 최신 결과 동작을 유지한다. |
| R-006 | Existing Android contract test style | `apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt` | 집중된 pure-state 및 monitor lifecycle test를 추가한다. |

## 4. 결정과 질문

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | 기존 흐름 | 카메라 rPPG는 이미 Phone의 주 prediction 경로에 들어가고 Watch에는 전달되지 않는다. | repository | `MainActivity.kt`; `SensorViewModel.handleCameraPrediction`; `RppgPredictionRoutingPolicy.relayToWatch=false` | 신규 backend prediction 경로가 아니라 UI/connectivity 작업만 필요하다. | not-required | resolved |
| D-002 | 16KB | legacy packaging 우회 없이 AGP 8.5.2, Gradle 8.7, ML Kit face detection 16.1.7로 올린다. | user | 검토된 계획이 Android/ML Kit 16KB 지침을 따른다. | SDK 34를 유지하면서 알려진 비호환 얼굴 감지 binary를 제거한다. | confirmed | resolved |
| D-003 | 대체 UX | 확정 disconnect에서 즉시 주 CTA를 표시하고 capture는 알림 없는 사용자 시작 20초 전면 동작으로 유지한다. | user | refinement에서 선택했다. | 갑작스런 camera 진입과 background capture가 없다. | confirmed | resolved |
| D-004 | 연결 정의 | 빈 Wear connected-node 목록을 사용하고 sensor freshness timeout은 사용하지 않는다. | user | 사용자는 일시 packet gap이 아니라 Watch 없는 환자가 목적이라고 정정했다. | hardware connection과 `isReceiving`을 분리한다. | confirmed | resolved |
| D-005 | 동의 | biosignal, AI, camera-rPPG, face-video-retention 동의를 유지한다. | user | refinement에서 선택했다. | 현재 privacy/API 계약을 보존한다. | confirmed | resolved |
| D-006 | 설정 | flag/readiness check를 유지하면서 rPPG default를 enabled로 바꾼다. | user | ready deployment에서 fallback을 기본 제공하도록 선택했다. | 운영 중지 기능을 없애지 않고 code/default documentation을 갱신한다. | confirmed | resolved |
| D-007 | 내부 상태 | 존재하지 않는 NodeClient connected-node listener를 live update에 사용한다. | agent | 초기 계획이 NodeClient가 list-change registration을 제공한다고 가정했다. | `play-services-wearable:18.1.0`에서 사용할 수 없는 target이다. | overridden | superseded |
| D-008 | 구현 승인 | 현재 working tree 위에 이 검토된 계획을 정확히 구현한다. | user | 사용자가 “PLEASE IMPLEMENT THIS PLAN”을 명시했다. | revision 1을 ready로 만들고 모든 mapped slice를 승인한다. | confirmed | resolved |
| D-009 | Live connection events | Initial/refresh query는 `NodeClient.connectedNodes`를 유지하고 `WearDataListenerService.onPeerConnected/onPeerDisconnected`가 refresh를 trigger하며 monitor가 ViewModel teardown에서 local app listener를 해제한다. | agent | 설치된 Wearable 18.1.0 `NodeClient`에는 connected-list listener가 없고 기존 system-managed `WearableListenerService`가 peer callback owner다. | Polling, sensor timeout, CapabilityClient resource, new dependency 대신 기존 service/manifest를 WS1에 추가한다. | confirmed | resolved |
| D-010 | Settings test | Default-enabled rPPG로 fixture가 storage를 선언하거나 opt out해야 하므로 기존 generic `SecuritySettings` test target을 확장하고 default/override evidence를 추가한다. | repository | `tests/test_security_v25.py::test_settings_require_secure_material_and_guard_http`가 D-006 이후 `RPPG_STORAGE_ROOT` 누락으로 실패한다. | Production behavior 변경 없이 WS2에 test file 하나를 추가한다. | not-required | resolved |
| D-011 | Wear listener registration | 기존 filtered `MESSAGE_RECEIVED` service registration을 유지하고 폐기된 `BIND_LISTENER`는 추가하지 않는다. 등록된 `WearableListenerService`가 peer callback을 계속 소유한다. | repository | AGP 8.5.2 lint는 모든 Wear event로 app을 깨우는 `BIND_LISTENER`를 거부하며 공식 listener-service 문서에는 connected/disconnected lifecycle event가 포함된다. | `AndroidManifest.xml`을 WS1에서 제거하고 현재 filtered service registration을 보존한다. | not-required | resolved |
| D-012 | rPPG status retry | Ready가 아니거나 status를 확인하지 못한 상태는 measurement-start action이 아니라 status-retry action을 보여야 하며 consent-required는 disabled로 유지한다. | repository | 기존 status-fetch failure는 dashboard policy에서 explicit disabled response와 구분할 수 없는 `RppgServiceStatus(false,false,false)`로 축약된다. | 잘못된 “server switched off” 단정을 피하고 기존 `prepareCapture()` path가 camera를 열지 않은 채 status를 refresh하도록 한다. | not-required | resolved |
| D-013 | CameraX 16KB dependency | SDK 34와 동일한 네 개의 직접 선언된 CameraX artifact를 유지하면서 기존 CameraX family를 1.3.2에서 1.4.0으로 올린다. | repository | Final APK inspection에서 ML Kit 16.1.7 `libface_detector_v2_jni.so`는 `p_align=2**14`지만 CameraX 1.3.2 `libimage_processing_util_jni.so`는 ARM64에서 `p_align=2**12`로 남는다. | 기존 version edit을 한 줄 확장하며 dependency family, API, packaging workaround를 추가하지 않는다. | not-required | resolved |
| D-014 | 활성 문서 일치 | rPPG를 default-off라고 남겨 둔 현재 운영 문서를 수정하고 처음에는 PRD와 이전 specification을 historical record로 취급한다. | repository | 코드와 배포 기본값을 true로 바꾼 뒤 저장소 전체 검색에서 `apps/backend/README.md`, `docs/dev-environment.md`, `apps/web/README.md`의 오래된 설명을 찾았다. | Bilingual PRD가 현재 product contract이므로 초기 boundary가 너무 좁었다. | overridden | superseded |
| D-015 | Capture timing | 화면 countdown과 controller stop deadline이 하나의 internal 20초 capture constant를 사용하게 한다. | repository | 최종 review에서 UI는 10초를 세지만 `RECORDING_MS`는 20,000 ms로 유지되는 것을 찾았다. | 사용자에게 정확한 countdown을 표시하고 focused contract test가 duration drift를 막는다. | not-required | resolved |
| D-016 | Product-document parity | Bilingual PRD를 현재 문서로 취급해 Watch-optional flow, point-in-time limit, default-on flag, off switch, fail-closed readiness wording을 맞추고 이전 날짜 implementation spec은 historical로 유지한다. | repository | PRD가 현재 product summary이면서 Watch만을 흐름으로 설명하고 rPPG를 default-off라고 남겨 두었다. | 이전 구현 기록을 다시 쓰지 않고 상충하는 현재 product guidance를 제거한다. | not-required | resolved |
| D-017 | 저장소 전체 활성 문서 | WS3을 reopen해 현재 backend/development/AI rPPG summary를 모두 맞추고 이전 날짜 feature specification과 planning snapshot은 historical evidence로 유지한다. | repository | Re-review가 backend README 두 곳과 development docs 한 곳을 찾았고 저장소 검색이 current AI docs의 combined STT/rPPG default-off wording을 찾았다. | 의도적으로 historical인 record는 보존하면서 남은 모순을 제거한다. | not-required | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | 대체 trigger | manual CTA, CTA+notification, automatic foreground entry 중 선택. | camera/privacy 동작을 바꾼다. | 수동 즉시 CTA. | D-003 | answered | 사용자가 즉시 CTA를 선택했다. |
| Q-002 | 연결 정의 | sensor timeout 또는 실제 Wear node connection을 선택. | false disconnect와 대상 사용자를 바꾼다. | 실제 connected-node 목록. | D-004 | answered | 사용자가 timeout 가정을 정정하고 Watch connection을 선택했다. |
| Q-003 | 동의 | retention consent를 유지하거나 transient video로 재설계. | privacy, storage, API 범위를 바꾼다. | 기존 consent 유지. | D-005 | answered | 사용자가 기존 consent 계약을 선택했다. |
| Q-004 | 기능 default | rPPG 기본 활성화 또는 deployment opt-in. | watchless fallback의 기본 사용 가능성을 결정한다. | flag/readiness를 유지한 기본 활성화. | D-006 | answered | 사용자가 기본 ON을 선택했다. |
| Q-005 | Live connection events | 기존 listener-service peer callback 또는 app-capability declaration을 live refresh로 선택한다. | 검토된 NodeClient-listener API가 존재하지 않아 exact Android target과 connection semantics가 달라진다. | `WearDataListenerService` peer callback을 재사용하고 generic connected-node semantics를 보존한다. | D-009 | answered | 사용자가 기존 service 추천안을 승인했다. |

## 5. 요구사항과 인수 기준

### 기능 요구사항

- **FR-001:** Android는 initial connected-node query와 기존 `WearDataListenerService` peer callback이 trigger한 refresh에서 checking, connected, disconnected, error Watch state를 노출해야 한다.
- **FR-002:** Home은 confirmed disconnect에서만 primary rPPG CTA를 표시하고 connected 사용자는 optional rPPG를 유지해야 하며 checking/error가 disconnect라고 거짓 표시하면 안 된다.
- **FR-003:** consent 실패는 non-actionable로 유지하고 provider readiness 또는 미확인 status는 measurement-start action이나 잘못된 fallback 주장 대신 정확한 status-retry action을 표시해야 한다.
- **FR-004:** camera result는 현재 `camera_rppg` Phone prediction path를 유지하고 Watch에는 가지 않으며 이후 Watch prediction에 최신 상태를 넘겨야 한다.
- **FR-005:** `RPPG_ENABLED`는 `true`가 default여야 하지만 environment override, validation, status endpoint, runtime readiness check를 유지해야 한다.
- **FR-006:** Phone build는 AGP 8.5.2, Gradle 8.7, CameraX 1.4.0, ML Kit face detection 16.1.7을 사용하고 compile/target SDK 34를 유지해야 한다.
- **FR-007:** 활성 root/mobile/backend/web/development/API/product documentation은 관련 위치에서 Watch-optional 동작을 설명하고 point-in-time 한계, consent/readiness, source routing, 기본 활성화, 변경 없는 공개 endpoint를 일관되게 설명해야 한다.
- **FR-008:** 화면 rPPG countdown과 camera controller stop deadline은 같은 20초 capture contract에서 파생돼야 한다.

### 비기능 요구사항

- **NFR-001:** 기존 public URL, method, response shape, model behavior, database schema, Alembic history, encryption, video retention을 보존한다.
- **NFR-002:** Owner ViewModel clear 시 monitor의 app-local event subscription을 제거하고 Wearable listener service lifecycle은 system-managed로 유지하며 sensor timing으로 disconnect를 추론하지 않는다.
- **NFR-003:** production dependency, compatibility bypass, broad refactor, unrelated cleanup을 추가하지 않는다.
- **NFR-004:** 기존 사용자 변경을 보존하고 Android connection error를 confirmed disconnect와 구분한다.

### 인수 기준

- **AC-001:** unit test가 node 0개, 1개 이상, query failure, listener update, listener removal, CTA/consent/readiness state matrix를 증명한다.
- **AC-002:** 현재 기준선을 낮추지 않고 Phone unit test/lint와 Phone/Wear debug build가 통과한다.
- **AC-003:** Phone APK가 16KB zip/ELF alignment check를 통과하고 SM-S926N에 이전 compatibility warning 없이 설치된다.
- **AC-004:** 실제 기기에서 SM-L320 disconnect가 app restart 없이 Home을 바꾸고 ready/consented 20초 rPPG job을 허용하며 reconnect가 Watch monitoring을 복구하면서 optional rPPG를 유지한다.
- **AC-005:** backend compile/pytest와 기존 API-structure/OpenAPI check가 API 또는 migration 변경 없이 통과한다.
- **AC-006:** 영문/한글 spec과 root/mobile/API documentation이 최종 동작 및 한계에 합의한다.
- **AC-007:** Backend test가 default-enabled rPPG, explicit `RPPG_ENABLED=false`, ambient `.env` 값에 의존하지 않는 unrelated secure-settings construction을 증명한다.

### 경계 및 실패 사례

- 초기 query 대기 → connected/disconnected 대신 “checking”을 표시하고 rPPG 가용성은 보이되 confirmed fallback으로 강조하지 않는다.
- node query/listener error → connection-check failure를 표시하고 consent/service readiness가 독립적으로 허용할 때만 rPPG를 허용한다.
- 빈 node 목록 → `isReceiving`을 초기화하고 Watch absent/disconnected를 표시하며 rPPG를 강조한다.
- consent 누락 → 필요한 모든 consent category를 설명하고 camera를 열지 않는다.
- rPPG disabled/unavailable/model not loaded → service unavailability를 설명하고 Watch 동작은 유지한다.
- camera 작업 도중/이후 Watch reconnect → accepted camera job을 취소하지 않고 다음 성공 Watch prediction이 최신이 될 수 있다.

## 6. 구현 전략과 방향

### STRAT-1 — 기존 Phone 상태 및 Listener Service 확장

- **Direction:** `preserve`
- **Current approach:** Initial `NodeClient` query를 위한 local monitor 하나를 추가하고 기존 `WearDataListenerService` peer callback을 refresh event로 재사용하며 `SensorViewModel`에서 state를 노출하고 기존 Home composable이 rPPG copy/emphasis를 선택하게 한다. 기존 build/config default와 documentation만 갱신한다.
- **Existing flow to reuse:** R-001부터 R-006.
- **Why this is minimal:** Backend rPPG path가 data flow를 이미 충족하고 Phone에 manifest-declared Wearable listener service가 있다. 이 owner를 확장하면 polling, CapabilityClient resource, new dependency가 필요 없다.
- **Behavior-preserving limitations:** fallback은 사용자가 시작하는 point measurement이고 consent/rPPG service가 ready가 아니면 사용할 수 없다.
- **Explicit exclusions:** 신규 endpoint, storage path, local rPPG model, background camera, timeout detector, notification, connection framework, backend refactor가 없다.
- **Compatibility and migration posture:** DB migration/public API change가 없다. rollback은 이전 build version, flag default, Android UI/monitor를 복원하며 persisted data는 영향을 받지 않는다.
- **Direction approval:** 불필요하다. 모든 변경이 가장 가까운 owner/dependency를 보존한다.
- **Open-question sensitivity:** 없음.

### 검토한 실질 대안

| Strategy | Direction | 이점 | 추가 코드 또는 위험 | 결정 |
| --- | --- | --- | --- | --- |
| Sensor staleness timeout | preserve | 연결됐지만 stream이 멈춘 Watch를 감지한다. | 사용자 요구인 watchless-user와 맞지 않고 radio gap에서 false trigger할 수 있다. | rejected |
| Automatic/background camera fallback | user-approved-divergence | 더 연속적으로 보일 수 있다. | privacy, permission, battery, foreground-service, UX 범위가 커지고 camera는 여전히 얼굴 정렬이 필요하다. | rejected |
| New backend fallback endpoint/model | user-approved-divergence | 별도 orchestration이 가능하다. | 기존 rPPG job/prediction path를 복제하고 public contract를 바꾼다. | rejected |

## 7. 수정 지도와 변경 예산

### 수정 지도

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/WatchConnectionMonitor.kt` | file-level | add | `PhonePredictionSender.kt::sendPrediction` | Initial/refresh connected-node query, app-local peer-event subscription, four-state mapping, close cleanup, local presentation policy/test seam을 감싼다. | FR-001, FR-002, NFR-002, AC-001을 구현한다. | WS1 | preserve |
| CH-002 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt` | `SensorViewModel` | extend | existing `StateFlow` ownership and `onCleared` | monitor state를 소유/노출하고 disconnect에서 `isReceiving`을 초기화하며 monitor를 닫는다. | FR-001, NFR-002, AC-004를 구현한다. | WS1 | preserve |
| CH-003 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt` | `UserHomeScreen, UserDashboardScreen` | extend | existing Home rPPG button and `UserStatusTile` | 정확한 connection state, fallback explanation, primary/secondary CTA, unavailable guidance를 렌더링한다. | FR-002, FR-003, FR-004, AC-004를 구현한다. | WS1 | preserve |
| CH-004 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt` | `RppgContractsTest` | extend | existing rPPG policy/contract tests | monitor lifecycle/state와 CTA gating case를 추가한다. | AC-001과 FR-004를 증명한다. | WS1 | preserve |
| CH-005 | config | `apps/mobile/build.gradle` | Android plugin version | edit | existing plugins block | AGP 8.2.2를 8.5.2로 바꾼다. | FR-006과 AC-003을 구현한다. | WS2 | preserve |
| CH-006 | config | `apps/mobile/gradle/wrapper/gradle-wrapper.properties` | `distributionUrl` | edit | existing Gradle wrapper | Gradle 8.2를 8.7로 바꾼다. | FR-006과 AC-003을 구현한다. | WS2 | preserve |
| CH-007 | config | `apps/mobile/app/build.gradle` | CameraX and ML Kit dependency versions | edit | existing camera dependencies | CameraX 1.3.2를 1.4.0으로, face detection 16.1.6을 16.1.7로 바꾼다. | FR-006과 AC-003을 구현한다. | WS2 | preserve |
| CH-008 | production | `apps/backend/app/core/config.py` | `SecuritySettings.rppg_enabled` | edit | existing feature flag | default만 false에서 true로 바꾼다. | FR-005와 NFR-001을 구현한다. | WS2 | preserve |
| CH-009 | config | `.env.example` | `RPPG_ENABLED` | edit | existing environment template | `RPPG_ENABLED=true`를 게시한다. | FR-005와 AC-006을 구현한다. | WS2 | preserve |
| CH-010 | docs | `README.md` | rPPG overview/configuration | edit | existing feature/config sections | default-on Watch-optional rPPG와 readiness limitation을 문서화한다. | FR-007과 AC-006을 구현한다. | WS3 | preserve |
| CH-011 | docs | `apps/mobile/README.md` | Phone feature/validation sections | edit | existing rPPG and Watch descriptions | connection-state fallback과 16KB prerequisite를 문서화한다. | FR-007과 AC-006을 구현한다. | WS3 | preserve |
| CH-012 | docs | `apps/mobile/docs/PHONE_APP.md` | Home/rPPG/Watch flow | edit | existing Phone flow | UI state matrix, consent/readiness, point-in-time behavior를 문서화한다. | FR-007과 AC-006을 구현한다. | WS3 | preserve |
| CH-013 | docs | `apps/mobile/SERVER_API_SPEC.md` | patient rPPG section | edit | existing endpoint contract | Android가 Watch/rPPG presentation을 client-side에서 고르고 endpoint는 그대로임을 명시한다. | FR-007, NFR-001, AC-006을 구현한다. | WS3 | preserve |
| CH-017 | docs | `apps/backend/README.md` | optional rPPG configuration | edit | existing rPPG default paragraph | 오래된 default-off 설명을 default-on, 명시적 off switch, readiness gate 설명으로 교체한다. | FR-007과 AC-006을 구현한다. | WS3 | preserve |
| CH-018 | docs | `docs/dev-environment.md` | rPPG environment and deployment guidance | edit | existing sample environment and rPPG section | true 기본값을 게시하고 명시적 disable/readiness 안내를 보존한다. | FR-007과 AC-006을 구현한다. | WS3 | preserve |
| CH-019 | docs | `apps/web/README.md` | camera rPPG administration | edit | existing retention/default bullet | 활성 관리자 문서를 default-on과 fail-closed readiness 동작에 맞춘다. | FR-007과 AC-006을 구현한다. | WS3 | preserve |
| CH-020 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgCameraScreen.kt` | capture duration/countdown | edit | existing countdown and `RECORDING_MS` | UI countdown과 stop deadline을 하나의 20초 constant에서 파생한다. | FR-008을 구현하고 최종 review finding을 해결한다. | WS4 | preserve |
| CH-021 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt` | capture duration contract | extend | existing rPPG contract tests | Shared capture contract가 20초임을 assert한다. | FR-008을 증명한다. | WS4 | preserve |
| CH-022 | docs | `docs/prd/PRD_neurotruth.md` | product summary/non-goals/flow/deployment | edit | current English PRD | Watch-optional 동작과 default/readiness policy를 맞춘다. | FR-007과 AC-006을 구현한다. | WS4 | preserve |
| CH-023 | docs | `docs/prd/PRD_neurotruth.ko.md` | product summary/non-goals/flow/deployment | edit | current Korean PRD mirror | English Watch-optional 및 default/readiness policy를 mirror한다. | FR-007과 AC-006을 구현한다. | WS4 | preserve |
| CH-024 | docs | `docs/ai/README.md` | implemented optional capabilities | edit | current AI architecture summary | default-off STT와 default-on readiness-gated rPPG를 구분한다. | FR-007과 AC-006을 구현한다. | WS3 | preserve |
| CH-025 | docs | `docs/ai/README.ko.md` | implemented optional capabilities | edit | current Korean AI architecture summary | CH-024를 mirror한다. | FR-007과 AC-006을 구현한다. | WS3 | preserve |
| CH-026 | docs | `docs/ai/agents/README.md` | capability status summary | edit | current agent overview | TTS/deferred scope를 보존하면서 STT와 rPPG default를 구분한다. | FR-007과 AC-006을 구현한다. | WS3 | preserve |
| CH-027 | docs | `docs/ai/agents/README.ko.md` | capability status summary | edit | current Korean agent overview | CH-026을 mirror한다. | FR-007과 AC-006을 구현한다. | WS3 | preserve |
| CH-028 | docs | `docs/ai/agents/03_handoff_agent.md` | current capability note | edit | current handoff-agent page | combined default-off capability statement를 수정한다. | FR-007과 AC-006을 구현한다. | WS3 | preserve |
| CH-029 | docs | `docs/ai/agents/03_handoff_agent.ko.md` | current capability note | edit | current Korean handoff-agent page | CH-028을 mirror한다. | FR-007과 AC-006을 구현한다. | WS3 | preserve |
| CH-014 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/WearDataListenerService.kt` | `onPeerConnected, onPeerDisconnected` | extend | `WearDataListenerService.onMessageReceived` | Sensor parsing을 바꾸지 않고 peer connection change를 monitor local refresh event owner에 게시한다. | FR-001, NFR-002, AC-004를 구현한다. | WS1 | preserve |
| CH-016 | test | `apps/backend/tests/test_security_v25.py` | `SecuritySettings` tests | extend | `test_settings_require_secure_material_and_guard_http` | Unrelated security test를 새 rPPG default와 분리하고 default true 및 explicit false를 증명한다. | FR-005, AC-005, AC-007을 증명한다. | WS2 | preserve |

### 변경 예산

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 5 | 4 | 1 | 230 | None | None |
| WS2 | 6 | 1 | 0 | 2 | None | None |
| WS3 | 13 | 0 | 0 | 0 | None | None |
| WS4 | 4 | 1 | 0 | 4 | None | None |

Production-line budget은 expansion alarm이지 compression target이 아니다. `WatchConnectionMonitor`는 AC-001에 필요한 local injectable source seam을 포함할 수 있지만 shared connectivity framework가 되면 안 된다.

## 8. 작업 계획

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | Lifecycle-safe Watch state와 rPPG fallback presentation을 focused test와 함께 추가한다. | None | PG1 | CH-001, CH-002, CH-003, CH-004, CH-014 | `apps/mobile/app/src/main/java/com/example/healthsensor/WatchConnectionMonitor.kt`, `apps/mobile/app/src/main/java/com/example/healthsensor/WearDataListenerService.kt`, `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt`, `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt`, `apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt` | backend, Gradle/config, docs, Wear OS source, Android manifest | FR-001, FR-002, FR-003, FR-004, NFR-002, NFR-003, NFR-004, AC-001, AC-004 | `apps/mobile/gradlew.bat :app:testDebugUnitTest` | verified |
| WS2 | 검토된 16KB version과 rPPG default enable을 적용한다. | None | PG1 | CH-005, CH-006, CH-007, CH-008, CH-009, CH-016 | `apps/mobile/build.gradle`, `apps/mobile/gradle/wrapper/gradle-wrapper.properties`, `apps/mobile/app/build.gradle`, `apps/backend/app/core/config.py`, `.env.example`, `apps/backend/tests/test_security_v25.py` | Android Kotlin/UI/tests, docs, runtime behavior | FR-005, FR-006, NFR-001, NFR-003, AC-002, AC-003, AC-005, AC-006, AC-007 | Android assemble plus focused backend settings tests | verified |
| WS3 | 활성 root/mobile/backend/web/development/API/AI documentation을 검토된 동작과 동기화한다. | None | PG1 | CH-010, CH-011, CH-012, CH-013, CH-017, CH-018, CH-019, CH-024, CH-025, CH-026, CH-027, CH-028, CH-029 | `README.md`, `apps/mobile/README.md`, `apps/mobile/docs/PHONE_APP.md`, `apps/mobile/SERVER_API_SPEC.md`, `apps/backend/README.md`, `docs/dev-environment.md`, `apps/web/README.md`, `docs/ai/README.md`, `docs/ai/README.ko.md`, `docs/ai/agents/README.md`, `docs/ai/agents/README.ko.md`, `docs/ai/agents/03_handoff_agent.md`, `docs/ai/agents/03_handoff_agent.ko.md` | production/test/config files, PRDs owned by WS4, historical dated specs, planning snapshots, and spec pair | FR-007, NFR-001, AC-006 | `git diff --check` and repository-wide active-default/endpoint cross-check | verified |
| WS4 | 최종 countdown과 현재 PRD parity finding을 해결한다. | WS1, WS3 | PG2 | CH-020, CH-021, CH-022, CH-023 | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgCameraScreen.kt`, `apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt`, `docs/prd/PRD_neurotruth.md`, `docs/prd/PRD_neurotruth.ko.md` | backend, build/config, other docs, prior implementation specs | FR-007, FR-008, AC-002, AC-006 | focused Phone unit tests and `git diff --check` | verified |

### 병렬화 근거

PG1 target은 서로 겹치지 않는다. WS1은 Android Kotlin/test, WS2는 build/config와 격리된 backend default 하나, WS3는 documentation만 소유한다. Public endpoint와 state contract는 이 reviewed revision에서 이미 고정됐다. Main agent가 세 slice 검토 후 build/API/device check를 통합 실행한다.

### 최종 통합

Slice별 spec scope/budget check, Android unit/lint/build, backend compile/pytest 및 API-structure test, APK 16KB alignment 검사 후 SM-S926N/SM-L320에서 disconnect–rPPG–reconnect 인수를 수행한다.

## 9. 검증, 배포, 위험

### 검증 계획

- WS1: query/listener lifecycle, state mapping, CTA emphasis, consent/readiness, 기존 camera Phone-only routing의 focused JUnit test.
- WS2: Gradle wrapper/version resolution, Phone/Wear debug assembly, backend settings/runtime test, config documentation cross-check.
- Integration: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:wearos:assembleDebug`; backend `compileall`과 `test_api_structure.py`를 포함한 full pytest.
- Binary: built APK의 `zipalign -c -P 16 4` 및 ELF segment alignment 검사.
- Device: 16KB SM-S926N 설치, compatibility dialog 없음 확인, SM-L320 connected/disconnected/rPPG completion/reconnection 상태 확인.

### 최종 검증 기록

| Check | Result |
| --- | --- |
| Android | Phone unit test 87개, `lintDebug`, Phone `assembleDebug`, Wear `assembleDebug`가 통과했다. |
| 16KB artifact | `zipalign -c -P 16 4`가 통과했고 ARM64/x86_64 native library 네 개의 모든 PT_LOAD segment가 `p_align=0x4000`이다. |
| Backend | Compileall과 full pytest `159 passed, 1 skipped`가 통과했고 OpenAPI는 39 path/42 operation이며 공개 `/v1` 또는 `/api/v1` prefix가 없다. |
| Watch | Final Wear APK를 SM-L320에 성공적으로 설치했고 process PID 15509로 실행했다. |
| Phone limitation | ADB에는 SM-L320만 보였다. SM-S926N 설치/compatibility-dialog 확인과 physical disconnect → primary CTA → 20초 job → reconnect 흐름은 실행하지 못해 live-device acceptance 항목으로 남긴다. |

### 최소성 및 스타일 충실도 검토

- 모든 changed file을 해당 mapped CH entry에 대응시키고 unrelated edit을 거부한다.
- 새 monitor가 기존 NodeClient만 감싸며 rPPG, prediction, authentication, provider logic을 복제하지 않는지 확인한다.
- timeout, notification, background capture, new dependency, endpoint, migration, broad formatting이 없는지 확인한다.
- 모든 worker slice에 `specctl.py check-scope`와 `check-patch`를 실행한다.

### 배포와 롤백

Ready rPPG storage root와 DGX endpoint를 갖춘 backend/Phone image를 build/deploy한다. `RPPG_ENABLED=false`는 긴급 rollback switch로 남는다. Code rollback에 data migration은 필요 없고 이전 Android APK와 flag default를 복원하면 기존 row와 암호화 video를 보존한 채 이전 presentation으로 돌아간다.

### 위험과 완화

| 위험 | 영향 | 완화 또는 근거 |
| --- | --- | --- |
| 연결된 generic Wear node를 Watch로 취급한다. | Sensor data가 없어도 rPPG가 secondary로 남는다. | 사용자가 선택한 connection 정의이고 peer callback은 exact NodeClient refresh만 trigger하며 connected 상태에서도 rPPG는 계속 사용할 수 있다. |
| Node query 실패 | App이 connection을 확정할 수 없다. | 명시적 `ERROR`를 쓰고 disconnected라고 주장하지 않으며 ready/consented rPPG는 독립적으로 허용한다. |
| 기본 활성화된 rPPG에 storage/DGX 설정이 없다. | Backend startup validation 또는 status가 실패한다. | 기존 fail-closed validation, readiness status, UI explanation, environment override를 유지한다. |
| Build upgrade가 plugin incompatibility를 드러낸다. | Android build가 실패한다. | 최소 AGP 8.5.2/Gradle 8.7 pair를 사용하고 Kotlin/SDK version을 유지하며 두 module을 build한다. |
| Camera rPPG를 continuous Watch coverage로 오해한다. | 사용자가 monitoring을 과대평가한다. | UI/docs에서 20초 사용자 시작 point measurement라고 명시한다. |

## 10. Revision과 진행

### 설계 변경 이력

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-19T17:48:11+09:00 | reviewed-chat-plan | Repository evidence를 바탕으로 구현 준비된 bilingual design을 만들고 모든 user choice와 명시적 implementation authorization을 기록했다. | D-001, D-002, D-003, D-004, D-005, D-006, D-007, D-008 | Q-001, Q-002, Q-003, Q-004 |
| 2 | 2026-07-19T18:18:00+09:00 | implementation-spec-gap | 사용할 수 없는 NodeClient-listener 가정을 기존 listener-service callback strategy로 교체하고 default-enabled rPPG가 드러낸 stale settings test를 mapping했다. | D-007, D-009, D-010 | Q-005 |
| 3 | 2026-07-19T18:45:00+09:00 | validation-correction | Lint가 거부한 broad Wear binding을 제거하고 unconfirmed/non-ready rPPG status에 정확한 retry-only presentation을 명시했다. | D-011, D-012 | — |
| 4 | 2026-07-19T19:00:00+09:00 | binary-validation-correction | 남은 4KB ARM64 ELF를 CameraX 1.3.2까지 추적하고 같은 family의 최소 1.4.0 upgrade와 documentation correction을 mapping했다. | D-013 | — |
| 5 | 2026-07-19T19:20:00+09:00 | active-document-parity | 현재 운영 문서 세 곳의 stale default-off 설명을 찾아 exact parity edit을 mapping했다. | D-014 | — |
| 6 | 2026-07-19T19:30:00+09:00 | final-review-correction | 10/20초 capture timing 불일치를 mapping하고 bilingual PRD를 Watch/default/readiness parity가 필요한 현재 계약으로 확인했다. | D-015, D-016 | — |
| 7 | 2026-07-19T19:40:00+09:00 | active-docs-reopen | Re-review와 저장소 검색이 남은 contradictory current docs를 찾아 historical dated specification은 바꾸지 않고 WS3을 reopened/확장했다. | D-017 | — |

### 구현 진행 기록

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-19T17:48:11+09:00 | 1 | — | ready | User가 reviewed plan 구현을 명시적으로 승인했고 open question이 없다. |
| 2026-07-19T18:05:00+09:00 | 1 | WS1, WS2, WS3 | in_progress | Strict validation을 통과했고 서로 겹치지 않는 PG1 slice 세 개를 bounded implementation으로 배정했다. |
| 2026-07-19T18:18:00+09:00 | 2 | WS1, WS2 | blocked | Missing NodeClient listener와 required settings-test target에 대한 targeted refinement를 열었고 독립적으로 검토한 WS3은 verified다. |
| 2026-07-19T18:31:00+09:00 | 2 | WS1, WS2 | in_progress | 사용자가 D-009를 승인했고 targeted revision 2를 검토 완료해 두 blocked slice를 재개했다. |
| 2026-07-19T18:45:00+09:00 | 3 | WS2 | verified | Build/config/default change가 focused settings/rPPG test, ambient-environment regression review, full backend compile, `159 passed, 1 skipped`를 통과했다. |
| 2026-07-19T18:45:00+09:00 | 3 | WS1 | in_progress | Full Android validation이 폐기된 `BIND_LISTENER`를 거부해 manifest change를 제거했고 read-only review가 bounded service-status retry correction을 열었다. |
| 2026-07-19T19:00:00+09:00 | 4 | WS1 | verified | 두 bounded correction 이후 Android unit test와 lint가 통과했고 read-only reviewer finding이 해소됐으며 full Phone/Wear assembly가 통과했다. |
| 2026-07-19T19:00:00+09:00 | 4 | WS2, WS3 | in_progress | APK ZIP alignment는 통과했지만 ELF inspection에서 유일한 remaining ARM64 4KB library가 CameraX 1.3.2로 추적되어 version/documentation correction을 열었다. |
| 2026-07-19T19:20:00+09:00 | 5 | WS2 | verified | CameraX 1.4.0이 full Android unit/lint/Phone/Wear build와 final 64-bit ELF 및 APK ZIP alignment check를 통과했다. |
| 2026-07-19T19:20:00+09:00 | 5 | WS3 | in_progress | Active-document parity review로 backend, development-environment, web 문서의 exact default/readiness wording correction을 열었다. |
| 2026-07-19T19:30:00+09:00 | 6 | WS3 | superseded | 초기 7-document check는 complete로 보였지만 revision 7 re-review가 추가 contradictory line을 찾아 slice를 reopened했다. |
| 2026-07-19T19:30:00+09:00 | 6 | WS4 | in_progress | 최종 review로 bounded capture-countdown 및 bilingual current-PRD correction을 열었다. |
| 2026-07-19T19:40:00+09:00 | 7 | WS3 | in_progress | Re-review가 성급한 parity record를 무효화해 exact backend/development/AI correction을 mapping했고 historical planning snapshot은 그대로 두며 WS4 code/PRD correction은 최종 검증 대기 중이다. |
| 2026-07-19T19:50:00+09:00 | 7 | WS3 | verified | Backend, development, AI summary wording을 고친 뒤 활성 문서 13개가 whitespace와 repository-wide default/readiness cross-check를 통과했다. |
| 2026-07-19T20:05:00+09:00 | 7 | WS4 | verified | Scope/patch budget이 4/4 file, 4/4 production added line으로 통과했고 shared 20초 contract가 Phone test 87개, lint, Phone/Wear build를 통과했으며 bilingual PRD에 P0–P2 finding이 없었다. |
| 2026-07-19T20:05:00+09:00 | 7 | integration | complete | Backend compile 및 `159 passed, 1 skipped`, 공개 v1 prefix 없는 39-path/42-operation OpenAPI, APK ZIP/64-bit ELF 16KB check, SM-L320 설치/실행이 통과했다. SM-S926N은 없어 남은 physical acceptance를 완료했다고 주장하지 않고 기록했다. |
