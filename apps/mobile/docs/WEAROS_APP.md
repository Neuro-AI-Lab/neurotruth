# Wear OS App

최종 업데이트: 2026-07-10

`wearos` 모듈은 Galaxy Watch에서 실행됩니다. Samsung Health Sensor SDK로 생체/움직임 센서를 수집하고, Wearable Data Layer로 폰 앱에 batch 전송합니다. 폰이 backend에서 받은 craving class와 alert metadata도 다시 수신해 워치 화면, 진동, 알림 상태에 반영합니다.

## 역할

- 센서 권한 요청
- Samsung Health Sensor SDK 연결
- Foreground service로 측정 유지
- HR, PPG Green/IR/Red, EDA, Accel X/Y/Z, SkinTemp 수집
- 약 200ms마다 sensor batch를 phone으로 전송
- Phone에서 전달한 `/prediction/class` payload 수신
- Craving class와 alert state 표시
- 중요 alert에서 watch feedback 제공

## 현재 UI

현재 워치 UI는 `watch_test` 앱의 동작을 기반으로 복원되어 있습니다.

| 영역 | 내용 |
|---|---|
| Sensor status | 측정 시작/중지, 권한/연결 상태 |
| State badge | 현재 측정 상태와 최신 class |
| Alert display | `none`, `recommend`, `required` 상태 표시 |
| Feedback | alert 변화에 따른 vibration/notification behavior |

## 수집 센서

| App Channel | HealthTrackerType | Notes |
|---|---|---|
| `HR` | `HEART_RATE_CONTINUOUS` | bpm |
| `PPG_GREEN` | `PPG_CONTINUOUS` | green raw |
| `PPG_IR` | `PPG_CONTINUOUS` | infrared raw |
| `PPG_RED` | `PPG_CONTINUOUS` | red raw |
| `EDA` | `EDA_CONTINUOUS` | skin conductance, Watch8+ |
| `ACCEL_X` | `ACCELEROMETER_CONTINUOUS` | X axis |
| `ACCEL_Y` | `ACCELEROMETER_CONTINUOUS` | Y axis |
| `ACCEL_Z` | `ACCELEROMETER_CONTINUOUS` | Z axis |
| `SKIN_TEMP` | `SKIN_TEMPERATURE_CONTINUOUS` | object temperature |

On-demand trackers are not part of the current default demo flow.

## 권한

워치 앱은 센서 시작 전 필수 권한을 확인합니다.

| Permission | Purpose |
|---|---|
| `BODY_SENSORS` | 심박, PPG 등 신체 센서 |
| `BODY_SENSORS_BACKGROUND` | 백그라운드 센서 접근 |
| `READ_HEART_RATE` | Android 16/API 36 이상 심박 권한 |
| `READ_ADDITIONAL_HEALTH_DATA` | Samsung PPG/EDA 추가 건강 데이터 |
| `ACTIVITY_RECOGNITION` | 활동/건강 센서 관련 |
| `FOREGROUND_SERVICE_HEALTH` | health foreground service |
| `POST_NOTIFICATIONS` | foreground/alert notification |
| `WAKE_LOCK` | 화면 꺼짐 상태 측정 유지 |

`POST_NOTIFICATIONS`는 알림 표시용입니다. 센서가 비어 있으면 권한, Samsung Health developer mode, 지원 기기 여부를 먼저 확인합니다.

## Samsung Health Developer Mode

Raw/additional sensor data는 워치의 Samsung Health developer mode가 필요할 수 있습니다.

1. 워치에서 Samsung Health 앱을 엽니다.
2. Samsung Health 정보 화면으로 이동합니다.
3. 버전 정보를 여러 번 탭해 developer mode를 켭니다.
4. Health Data SDK 관련 옵션을 켭니다.

## 측정 흐름

```text
MainActivity
  -> permission check
  -> SensorTrackingService.start()

SensorTrackingService
  -> foreground notification
  -> partial wake lock
  -> HealthSensorManager.connect()
  -> tracker start
  -> sensor Flow collect
  -> 180ms delay
  -> flushAllTrackers()
  -> 20ms callback wait
  -> PhoneDataSender.sendBatch()
```

`flushAllTrackers()`는 PPG/Accel이 SDK 내부에서 길게 batch되는 지연을 줄이기 위한 처리입니다.

## Data Layer Paths

워치에서 폰으로 보내는 sensor path:

| Path | Data |
|---|---|
| `/sensor/hr` | HR |
| `/sensor/ppg` | PPG green |
| `/sensor/ppg_ir` | PPG infrared |
| `/sensor/ppg_red` | PPG red |
| `/sensor/eda` | EDA |
| `/sensor/accel_x` | Accel X |
| `/sensor/accel_y` | Accel Y |
| `/sensor/accel_z` | Accel Z |
| `/sensor/skin_temp` | Skin temperature |

Batch payload format:

```text
[count:Int(4)] + count * [timestamp:Long(8) + value:Float(4)]
```

timestamp는 Samsung Health Sensor SDK data point timestamp를 그대로 사용합니다.

## Prediction / Alert Report

Phone에서 watch로 보내는 message path는 그대로 유지합니다.

```text
path: /prediction/class
```

Class-only payload도 동작합니다.

```json
{
  "class": 1,
  "timestampMs": 1750000000000
}
```

현재 payload는 alert metadata도 포함할 수 있습니다.

```json
{
  "class": 1,
  "timestampMs": 1750000000000,
  "sessionId": "phone-1750000000000",
  "alertLevel": "recommend",
  "alertAction": "recommend_intervention",
  "windowMean": 0.8,
  "triggerReason": "window_mean_recommend",
  "alertRequired": false
}
```

Watch는 `alertAction` -> `alertLevel` -> alert metadata가 전혀 없는 legacy `class` 순서로 동작을 결정합니다. `none`과 `cooldown`은 최신 `CRAVE 0/1/2`와 alert state만 갱신하고 진동이나 알림을 만들지 않습니다. `recommend_intervention`은 짧은 진동과 권고 문구를, `required_intervention`은 더 강한 진동 패턴과 휴대폰 상태 확인 안내를 사용합니다. Class-only payload는 `0=none`, `1=recommend`, `2=required` fallback으로 계속 지원합니다.

Wear manifest는 phone companion이 필요한 앱임을 명시하도록 `com.google.android.wearable.standalone=false`를 포함합니다.

## 설치

워치가 ADB에 보이는지 확인합니다.

```powershell
adb devices
```

워치 APK 설치:

```powershell
adb -s <WATCH_SERIAL> install -r apps/mobile/wearos/build/outputs/apk/debug/wearos-debug.apk
```

최신 debug APK는 `SM-L320` watch에 설치되고 실행되었습니다. 활성 chat 중 반복 진동/notification 차단의 payload test는 통과했으며 전체 실제 interaction 관찰은 남아 있습니다.

## 주요 파일

| File | Role |
|---|---|
| `MainActivity.kt` | watch UI, permission entry, start/stop |
| `SensorTrackingService.kt` | foreground sensor tracking and batch send |
| `HealthSensorManager.kt` | Samsung Health Sensor SDK tracker wrapper |
| `PhoneDataSender.kt` | Wearable Data Layer sender |
| `PredictionListenerService.kt` | phone-to-watch prediction/alert receiver |
| `SensorState.kt` | watch UI state |

## 운영 체크리스트

1. Phone and watch are paired.
2. Samsung Health developer mode is enabled if raw sensors are empty.
3. Sensor permissions are granted on the watch.
4. Backend is reachable from the phone over LAN.
5. Phone app receives watch sensor data.
6. Phone app uploads `/sensor-window` and receives `/prediction-stream`.
7. Watch receives `/prediction/class` and updates class/alert UI.
