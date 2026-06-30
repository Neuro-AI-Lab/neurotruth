# Wear OS App

`wearos` 모듈은 Galaxy Watch에서 실행됩니다. Samsung Health Sensor SDK로 생체/움직임 센서를 수집하고, Wearable Data Layer로 폰 앱에 batch 전송합니다. 폰이 서버에서 받은 갈망 class도 다시 수신해 워치 화면에 표시합니다.

## 역할

- 센서 권한 요청
- Foreground service로 센서 측정 유지
- Samsung Health Sensor SDK 연결
- HR, PPG Green/IR/Red, EDA, Accel X/Y/Z, SkinTemp 수집
- 약 200ms마다 센서 batch를 폰으로 전송
- 폰에서 전달한 갈망 class `0/1/2` 수신 및 화면 표시

## 주요 파일

| File | Role |
|---|---|
| `MainActivity.kt` | 워치 UI, 권한 요청, 측정 시작/중지 |
| `SensorTrackingService.kt` | Foreground service, 센서 Flow 수집, batch 전송 |
| `HealthSensorManager.kt` | Samsung Health Sensor SDK tracker 관리 |
| `PhoneDataSender.kt` | Wearable MessageClient로 폰에 sensor batch 전송 |
| `PredictionListenerService.kt` | 폰에서 온 갈망 class 수신 |
| `SensorState.kt` | 워치 화면 상태 Flow |

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
| `WAKE_LOCK` | 화면 꺼짐 상태 측정 유지 |

`POST_NOTIFICATIONS`는 foreground 알림 표시용입니다. 거부되어도 센서 시작 조건에서는 제외됩니다.

## 측정 흐름

```text
MainActivity
  -> 권한 확인
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

`flushAllTrackers()`는 PPG/Accel이 SDK 내부에서 길게 batch되는 지연을 줄이기 위한 처리입니다. 현재 루프는 약 200ms 간격으로 tracker flush 후 폰으로 새 샘플을 보냅니다.

## Data Layer Paths

워치에서 폰으로 보내는 sensor path입니다.

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

timestamp는 Samsung Health Sensor SDK의 data point timestamp를 그대로 사용합니다. 폰 앱은 이 timestamp를 기준으로 공통 시간축과 서버용 PPG/EDA fixed grid를 만듭니다.

## Prediction Report

폰 앱은 서버에서 받은 class를 워치로 전달합니다.

```text
path: /prediction/class
payload: {"class":1,"timestampMs":1750000000000}
```

`PredictionListenerService`는 이 값을 받아 `SensorState.cravingClass`, `SensorState.cravingText`를 갱신합니다. 워치 화면은 `CRAVE 0/1/2` 형태로 최신 class를 표시합니다.

## 운영 체크리스트

1. 워치에 Samsung Health 개발자 모드와 Health Data SDK 옵션이 켜져 있는지 확인합니다.
2. 워치 앱 권한을 모두 허용합니다.
3. 폰과 워치가 페어링되어 Wearable Data Layer가 동작하는지 확인합니다.
4. 워치 앱에서 측정을 시작합니다.
5. 폰 앱에서 chart가 움직이는지 확인합니다.
6. 서버 URL이 설정되어 있으면 폰 앱이 취득 10초 후 자동 통신을 시작합니다.

