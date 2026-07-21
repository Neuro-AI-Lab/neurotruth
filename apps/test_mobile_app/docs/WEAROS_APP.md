# Wear OS App

최종 업데이트: 2026-07-16

`wearos` 모듈은 Galaxy Watch에서 Samsung Health Sensor SDK 데이터를 수집해 Wearable Data Layer로 인증된 Phone 앱에 전달합니다. Watch는 backend credential을 저장하지 않고 backend API/SSE에 직접 연결하지 않습니다. Phone이 수신한 표시용 prediction/alert state만 `/prediction/class` Data Layer message로 다시 전달합니다.

## 역할과 경계

- HR, PPG Green/IR/Red, EDA, Accel X/Y/Z, SkinTemp 수집
- Foreground health service, sensor permission, wake lock 관리
- 약 200ms 주기의 sensor batch를 paired Phone으로 전송
- Phone이 전달한 binary craving class(`0=낮음`, `1=높음`)와 alert state 표시
- 서버가 결정한 `alertAction`/`alertLevel`에 따른 vibration/notification
- 환자 가입·로그인·동의·token·UUID session·backend upload/SSE는 모두 Phone 책임

Watch가 단독으로 환자를 인증하거나 raw data를 backend에 우회 전송하는 fallback은 없습니다. Phone 로그아웃 또는 생체신호 동의 철회 시 Phone이 backend monitoring을 중지합니다.

## Sensor Channels

| Channel | Tracker |
|---|---|
| `HR` | `HEART_RATE_CONTINUOUS` |
| `PPG_GREEN`, `PPG_IR`, `PPG_RED` | `PPG_CONTINUOUS` |
| `EDA` | `EDA_CONTINUOUS` |
| `ACCEL_X`, `ACCEL_Y`, `ACCEL_Z` | `ACCELEROMETER_CONTINUOUS` |
| `SKIN_TEMP` | `SKIN_TEMPERATURE_CONTINUOUS` |

필수 권한은 기기/API에 따라 `BODY_SENSORS`, `BODY_SENSORS_BACKGROUND`, `READ_HEART_RATE`, `READ_ADDITIONAL_HEALTH_DATA`, `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE_HEALTH`, `POST_NOTIFICATIONS`, `WAKE_LOCK`입니다. Raw PPG/EDA가 비어 있으면 Samsung Health developer mode와 지원 기기를 확인합니다.

## Data Layer

Watch → Phone sensor path:

```text
/sensor/hr, /sensor/ppg, /sensor/ppg_ir, /sensor/ppg_red,
/sensor/eda, /sensor/accel_x, /sensor/accel_y, /sensor/accel_z,
/sensor/skin_temp
```

Batch format:

```text
[count:Int(4)] + count * [timestamp:Long(8) + value:Float(4)]
```

Phone → Watch prediction path:

```text
/prediction/class
```

Payload may contain `predictionSchema`, binary `class`, `classCode`, `timestampMs`, backend UUID `sessionId`, `confidence`, `cravingProbability`, `classProbabilities`, `alertLevel`, `alertAction`, `windowMean`, `classOneRatio`, and `triggerReason`. Watch accepts only class `0` or `1`; class `2` is rejected as an incompatible legacy prediction. Watch uses `alertAction` first and `alertLevel` only when the action is absent. A class value alone is display-only and never creates a local alert. `none`/`cooldown` do not vibrate; recommendation and required states use distinct feedback. This message contains no access/refresh token or decrypted patient content.

Class-1 probability history and its graph are Phone-only. Watch shows no probability percentage or graph and preserves its existing sensor collection and Phone relay behavior.

## Build and Install

```powershell
cd apps/mobile
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
adb devices
adb -s <WATCH_SERIAL> install -r wearos/build/outputs/apk/debug/wearos-debug.apk
```

Operational check: phone/watch paired → Watch permissions granted → sensor batch visible on Phone → authenticated Phone upload/SSE active → `/prediction/class` reflected on Watch. Voice and direct Watch authentication are excluded. Camera rPPG runs only on the Phone/backend path: Watch sensor reception continues during capture, and camera predictions must never be sent over `/prediction/class`.
