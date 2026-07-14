# NeuroTruth Mobile

최종 업데이트: 2026-07-13

`apps/mobile`은 NeuroTruth의 Android phone + Wear OS 앱입니다. 워치는 Samsung Health Sensor SDK로 센서를 수집하고, 폰은 센서 대시보드와 갈망 예측/중재 UI를 제공합니다. 현재 UI와 백그라운드 동작은 기존 `watch_test` 앱 환경을 기반으로 복원했고, NeuroTruth의 alert metadata, Bedrock 텍스트 중재, handoff 기능을 연결했습니다.

## 현재 데모 흐름

```text
Galaxy Watch
  -> HR / PPG / EDA / Accel / SkinTemp 수집
  -> Wearable Data Layer batch 전송

Android Phone
  -> 실시간 상태/차트/개발자 화면 표시
  -> 10초 센서 window를 1초마다 backend로 POST
  -> prediction SSE 수신
  -> alert state를 phone/watch에 표시
  -> text intervention chat
  -> 비동기 handoff job 접수/상태 조회 및 report 표시

Backend
  -> RF craving prediction
  -> deterministic alert rule
  -> Bedrock GPT-5.5 text intervention
  -> slot extraction and Markdown handoff
```

## 모듈

| Module | Target | 역할 |
|---|---|---|
| `app` | Android phone | 사용자 대시보드, 개발자 차트, 서버 업로드/SSE, 텍스트 중재, handoff |
| `wearos` | Galaxy Watch | 센서 권한, foreground tracking, phone batch 전송, alert/class 표시 |

## 주요 기능

| 영역 | 현재 상태 |
|---|---|
| Phone user dashboard | 복원됨: 상태 카드, 예측/alert, 중재 진입, background monitoring |
| Phone developer mode | 센서 차트, upload/SSE 상태, CSV 저장, 채팅 제한시간 설정, 상담 상태 초기화 |
| Phone state-check | 상담 chat 활성 중에는 새 required alert가 AUQ를 다시 열지 않고 watch 진동/알림도 차단하며, chat을 닫은 뒤의 새로운 alert부터 다시 허용 |
| Shared session | 센서, SSE, chat, handoff가 하나의 session ID를 사용하며 데이터 초기화 시 함께 갱신 |
| Alert action policy | `alertAction` -> `alertLevel` -> metadata 없는 legacy `class` 순서로 결정 |
| Text intervention | backend `/api/intervention/chat` 호출 |
| Handoff | `POST /api/intervention/handoff/jobs` 접수 후 status polling; 생성 중에도 chat 가능 |
| Chat timeout | 기본 60분, 관리자 모드에서 1~1,440분으로 저장/변경 |
| Watch UI | 복원됨: sensor start/stop, state badge, vibration/notification behavior |
| Watch alert display | 유지됨: `/prediction/class` payload의 class와 alert metadata 표시 |

마이크/STT UI는 이번 버전에 포함하지 않습니다.

## 서버 URL

폰 앱은 아래 asset 파일에서 기본 backend URL을 읽습니다.

```text
apps/mobile/app/src/main/assets/server_config.properties
```

현재 물리 기기 테스트용 값:

```properties
sensor_post_url=http://192.168.68.51:8000/sensor-window
prediction_sse_url=http://192.168.68.51:8000/prediction-stream
```

휴대폰에서 `localhost`는 노트북이 아니라 휴대폰 자신을 뜻합니다. 물리 기기 테스트에서는 노트북과 휴대폰/워치가 같은 네트워크에 있어야 하며, 노트북 LAN IP를 사용해야 합니다.

IP가 바뀌면 repo root에서 확인합니다.

```powershell
Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -eq 'Up' }
```

asset 파일을 바꾼 뒤에는 APK를 다시 빌드하고 설치해야 합니다.

## 필요 파일

| 파일 | 필요 이유 |
|---|---|
| `apps/mobile/local.properties` | Android SDK 경로 |
| `apps/mobile/wearos/libs/samsung-health-sensor-api-1.4.1.aar` | Samsung Health Sensor SDK |
| `apps/mobile/app/src/main/assets/server_config.properties` | phone backend URL |

현재 private repo 운용 기준에서는 Samsung AAR/JAR, 모델 weight, `.env`를 필요하면 추적할 수 있습니다. `local.properties`는 PC별 Android SDK 절대경로이므로 repository에 포함하지 않고 각 개발 PC에서 새로 만들거나 `ANDROID_HOME`을 사용합니다. public mirror로 옮길 때는 private artifact를 별도로 검토해야 합니다.

## 빌드와 테스트

```powershell
cd apps/mobile
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

최신 검증 결과:

| Check | Result |
|---|---|
| `assembleDebug` | PASS |
| `testDebugUnitTest` | PASS, 22 tests, AUQ latch/timeout/single handoff gate/watch alert 차단 payload 포함 |
| `lintDebug` | PASS after latest mobile changes |
| Phone install | Latest debug APK install and launch PASS on `SM-S926N` |
| Watch install | Latest debug APK install and launch PASS on `SM-L320` |

APK 출력 위치:

```text
apps/mobile/app/build/outputs/apk/debug/app-debug.apk
apps/mobile/wearos/build/outputs/apk/debug/wearos-debug.apk
```

## 설치

먼저 기기 목록을 확인합니다.

```powershell
adb devices
```

`adb device`가 아니라 `adb devices`입니다. 상태가 `device`여야 설치할 수 있습니다.
현재 PC처럼 `adb`가 PATH에 없으면 다음처럼 Android SDK 경로를 직접 사용합니다.

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
```

폰 설치:

```powershell
adb install -r apps/mobile/app/build/outputs/apk/debug/app-debug.apk
```

워치 설치:

```powershell
adb -s <WATCH_SERIAL> install -r apps/mobile/wearos/build/outputs/apk/debug/wearos-debug.apk
```

워치가 Wi-Fi ADB로 연결된 경우 `<WATCH_SERIAL>`은 예를 들어 `192.168.68.65:33633` 형태일 수 있습니다.

## 주요 파일

| File | 역할 |
|---|---|
| `app/src/main/java/com/example/healthsensor/MainActivity.kt` | phone Compose UI, dashboard, developer mode, intervention UI |
| `app/src/main/java/com/example/healthsensor/PhoneMonitoringState.kt` | shared session, alert claim, active-intervention AUQ suppression |
| `app/src/main/java/com/example/healthsensor/SensorViewModel.kt` | sensor state, chat timeout setting, intervention reset, handoff job polling |
| `app/src/main/java/com/example/healthsensor/ServerUploader.kt` | sensor/SSE transport, configurable chat timeout, handoff job API parsing |
| `app/src/main/java/com/example/healthsensor/PhonePredictionSender.kt` | phone-to-watch `/prediction/class` message |
| `wearos/src/main/java/com/example/healthsensor/MainActivity.kt` | watch UI and permission entry |
| `wearos/src/main/java/com/example/healthsensor/SensorTrackingService.kt` | foreground sensor tracking |
| `wearos/src/main/java/com/example/healthsensor/HealthSensorManager.kt` | Samsung Health Sensor SDK wrapper |
| `wearos/src/main/java/com/example/healthsensor/PredictionListenerService.kt` | watch-side prediction/alert receiver |

## 세부 문서

| 문서 | 내용 |
|---|---|
| [Phone App](docs/PHONE_APP.md) | 폰 UI, 서버 통신, chat/handoff, CSV |
| [Wear OS App](docs/WEAROS_APP.md) | 워치 센서 수집, 권한, Data Layer, alert 표시 |
| [Server API Spec](SERVER_API_SPEC.md) | Android와 backend 사이의 API 계약 |
