# NeuroTruth Mobile

최종 업데이트: 2026-07-16

`apps/mobile`은 환자용 Android Phone 앱과 Wear OS 센서 앱입니다. Phone이 환자 인증·동의·JWT 갱신·센서 업로드·prediction SSE·중재 세션을 소유합니다. Watch는 센서를 Phone으로 전달하고 표시 가능한 prediction 상태를 받으며 backend token을 저장하거나 backend에 직접 연결하지 않습니다.

## 현재 제품 흐름

```text
환자 가입/로그인/동의
  ← Watch PPG/GSR batch
  → Bearer POST /api/sensor-windows
  ← Bearer GET /api/predictions/stream
  → 갈망 상승 가능성 알림: 지금 대화하기 / 나중에
  → 선택형 AUQ: 작성하기 / 건너뛰고 대화하기
  → 중립 안내 → 자유 대화 → 수동 종료/비활동 timeout
  ← 상태 추론·리포트 상태·대시보드
```

NeuroTruth는 치료·진단·응급 대응 앱이 아니라 CBT 치료 중이거나 치료 의지가 있는 사용자의 기록과 갈망 상황 대화를 돕는 연구용 보조 시스템입니다. 첫 로그인에서 이 대상과 한계를 확인하고 홈에서 다시 볼 수 있습니다.

## Phone 기능

- 환자 직접 가입, 로그인, 필수/선택 동의, 비밀번호 변경
- Android Keystore 기반 refresh token 보관과 회전
- Watch 센서 수신, 인증된 sensor upload, prediction SSE
- `지금 대화하기`/`나중에`, optional AUQ, `free_dialogue` 기반 slot 없는 자유 대화
- 각 사용자 메시지에 안정적인 UUID `clientMessageId`를 사용하며 agent `502` 실패 시 같은 ID와 내용으로 한 번만 수동 재시도
- 서버의 `inactivityTimeoutSeconds`를 사용한 동적 timeout 안내
- 앱 재시작 후 active session 복구와 수동 종료
- 2-class(`낮음/높음`) prediction과 class-1 softmax 기반 `갈망 가능성(모델)` 대시보드
- 기기 현지시간 기준 오늘 24개 시간별 갈망 가능성, 7/30일 일별 갈망 이벤트, 오늘/7/30일 AUQ 평균 막대 그래프
- 데이터 없음은 회색 막대로, prediction이 있으나 이벤트가 없는 날은 유효한 `0건`으로 구분
- 실시간 Watch PPG와 소유 prediction의 최대 512점 PPG preview
- 리포트 `생성 중|준비됨|실패` 상태만 표시하고 본문은 표시하지 않음

신규 session UI와 API에는 `slots`, `missingSlots`, `handoffReady`가 없습니다. 기존 slot session은 서버의 읽기 전용 이력입니다.

## Wear OS 기능

- Samsung Health Sensor SDK 기반 PPG/EDA 수집
- Data Layer를 통한 Phone relay
- Phone이 전달한 binary class(`0=낮음`, `1=높음`)와 서버 alert metadata 표시
- backend credential과 DGX 주소 미보관

Watch는 class `2`를 호환되지 않는 legacy prediction으로 거부합니다. Class 값 자체로 알림을 만들지 않고 서버 `alertAction`을 우선하며, action이 없을 때만 `alertLevel`을 사용합니다. 확률 그래프는 Phone에만 있으며 Watch에는 추가하지 않습니다. 기존 sensor 수집과 Watch relay는 유지하지만 카메라 rPPG prediction은 Watch에 보내지 않습니다.

신규 막대 대시보드도 Phone 전용입니다. 관리자 웹과 Wear OS 화면에는 추가하지 않습니다.

## 선택적 카메라 rPPG

Phone 전면 카메라에서 한 얼굴이 안정되면 10초 영상을 촬영해 인증된 NeuroTruth backend에 업로드합니다. Backend가 DGX Spark FactorizePhys와 binary PyTorch craving model을 호출하며 Phone은 DGX 주소를 알지 못합니다.

- `RPPG_ENABLED=true`이고 backend가 ready일 때만 카메라 UI 표시
- HTTP 202 뒤 로컬 MP4 삭제, job polling과 앱 재시작 복구
- 결과는 Phone 전용 카드로 유지하고 Watch에는 전달하지 않음
- 기본 OFF이며 실제 Phone/DGX 검증 전에는 release-ready가 아님

## 서버 설정

`app/src/main/assets/server_config.properties`:

```properties
api_base_url=http://SERVER_HOST:25991
sensor_post_url=http://SERVER_HOST:25991/api/sensor-windows
prediction_sse_url=http://SERVER_HOST:25991/api/predictions/stream
```

물리 기기에서는 노트북 LAN IP를 사용합니다. HTTP는 backend가 development/test이고 `ALLOW_INSECURE_HTTP=true`인 경우에만 허용됩니다. 운영은 HTTPS가 필수입니다.

## 빌드와 설치

```powershell
cd apps/mobile
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <WATCH_SERIAL> install -r wearos/build/outputs/apk/debug/wearos-debug.apk
```

필수 로컬 항목은 JDK 17, Android SDK, PC별 `local.properties` 또는 `ANDROID_HOME`, `wearos/libs/samsung-health-sensor-api-1.4.1.aar`입니다.

## 최신 검증

| 항목 | 결과 |
|---|---|
| Phone unit tests | PASS, 61 tests / 실패 0 |
| Phone `assembleDebug` | PASS |
| Phone `lintDebug` | PASS, 오류 0 |
| Kotlin compile | PASS |

새 Phone/Watch 실기기에서 가입·알림·AUQ skip·대화·timeout·대시보드·Watch 회귀와 실제 rPPG 촬영은 수동 검증이 남아 있습니다.

`갈망 가능성(모델)`은 연구용 model output이며 진단, 임상적 갈망 강도 또는 치료 효과가 아닙니다. 현재 final weights는 사용 가능한 학습 데이터를 모두 사용했으며 해당 최종 가중치에 대한 독립 test 평가가 없습니다.

상세 계약은 [Phone App](docs/PHONE_APP.md)과 [Server API](SERVER_API_SPEC.md)를 참고합니다.
