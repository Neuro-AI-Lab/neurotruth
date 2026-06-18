# Galaxy Watch Health Sensor Monitor

Galaxy Watch 8에서 Samsung Health Sensor SDK로 센서 데이터를 수집하고, Galaxy S24+ 같은 Android 폰에서 실시간으로 plot하고 CSV로 저장하는 Android 멀티모듈 앱입니다.

현재 코드는 워치의 `wearos` 모듈이 센서를 읽고 Wearable Data Layer로 배치 전송하며, 폰의 `app` 모듈이 데이터를 받아 MPAndroidChart로 표시합니다.

## 현재 동작 요약

| 항목 | 현재 구현 |
|------|-----------|
| PPG | `PPG_CONTINUOUS` + `setOf(PpgType.GREEN, PpgType.IR, PpgType.RED)` |
| EDA | `EDA_CONTINUOUS`, Watch8+ 및 추가 권한 필요 |
| 전송 주기 | 워치에서 약 200ms마다 배치 전송 |
| SDK flush | `180ms 대기 -> flushAllTrackers() -> 20ms 콜백 대기 -> 전송` |
| 차트 시간창 | 최근 7초 표시 |
| X축 | 워치 timestamp 기준 공통 시간축, 1칸 = 100ms |
| Y축 | 보이는 7초 구간만 기준으로 자동 스케일링 |
| Y축 완충 | 2~98% 분위값 + 축 경계 smoothing으로 덜컥거림 감소 |
| 기본 활성 센서 | continuous 계열 9채널 |
| On-demand 센서 | 코드와 차트는 있으나 기본값은 비활성화 |

## 프로젝트 구조

```text
watch_test/
├── app/                            # 스마트폰 앱 모듈
│   └── src/main/java/com/example/healthsensor/
│       ├── MainActivity.kt         # Compose UI + MPAndroidChart 실시간 차트
│       ├── SensorViewModel.kt      # 차트 상태, 공통 시간축, CSV 원시 데이터 누적
│       ├── SensorRepository.kt     # 수신 데이터 Flow 허브
│       └── WearDataListenerService.kt
│
├── wearos/                         # 갤럭시 워치 앱 모듈
│   └── src/main/java/com/example/healthsensor/
│       ├── MainActivity.kt         # 권한 요청, 시작/중지 UI
│       ├── SensorTrackingService.kt
│       ├── HealthSensorManager.kt  # Samsung Health Sensor SDK 래퍼
│       ├── PhoneDataSender.kt      # Wearable Data Layer 배치 전송
│       └── SensorState.kt
│
└── samsung_health_sensor_sdk/       # 로컬 참고용 Samsung SDK 예시/문서
```

## 기본 활성 채널

`HealthSensorManager.kt`의 `ENABLE_ON_DEMAND_TRACKERS`가 `false`라서 기본 실행 시 continuous 계열만 수집합니다.

| 앱 채널 | HealthTrackerType | SDK ValueKey | 비고 |
|---------|-------------------|--------------|------|
| HR | `HEART_RATE_CONTINUOUS` | `HeartRateSet.HEART_RATE` | 약 1Hz |
| PPG_GREEN | `PPG_CONTINUOUS` | `PpgSet.PPG_GREEN` | `PpgType.GREEN` |
| PPG_IR | `PPG_CONTINUOUS` | `PpgSet.PPG_IR` | `PpgType.IR` |
| PPG_RED | `PPG_CONTINUOUS` | `PpgSet.PPG_RED` | `PpgType.RED` |
| EDA | `EDA_CONTINUOUS` | `EdaSet.SKIN_CONDUCTANCE` | Watch8+ |
| ACCEL_X | `ACCELEROMETER_CONTINUOUS` | `AccelerometerSet.ACCELEROMETER_X` | 3축 분리 전송 |
| ACCEL_Y | `ACCELEROMETER_CONTINUOUS` | `AccelerometerSet.ACCELEROMETER_Y` | 3축 분리 전송 |
| ACCEL_Z | `ACCELEROMETER_CONTINUOUS` | `AccelerometerSet.ACCELEROMETER_Z` | 3축 분리 전송 |
| SKIN_TEMP | `SKIN_TEMPERATURE_CONTINUOUS` | `SkinTemperatureSet.OBJECT_TEMPERATURE` | Watch5+ |

## 차트는 있지만 기본 비활성인 채널

아래 채널은 폰 UI, repository, CSV 저장 경로가 준비되어 있지만 워치에서 기본적으로 시작하지 않습니다. 켜려면 `HealthSensorManager.kt`의 `ENABLE_ON_DEMAND_TRACKERS`를 `true`로 바꿔야 합니다.

| 앱 채널 | HealthTrackerType | 주의사항 |
|---------|-------------------|----------|
| ECG | `ECG_ON_DEMAND` | Samsung 정책 승인 필요 가능 |
| SpO2 | `SPO2_ON_DEMAND` | on-demand 측정 |
| BIA Fat | `BIA_ON_DEMAND` | on-demand 측정 |
| BIA BMR | `BIA_ON_DEMAND` | CSV에는 `BIA_BMR`로 저장 |
| BIA Muscle | `BIA_ON_DEMAND` | on-demand 측정 |
| BIA Water | `BIA_ON_DEMAND` | on-demand 측정 |
| Sweat Loss | `SWEAT_LOSS` | 운동/상태 조건 의존 |

On-demand tracker는 SDK 제약이 강해서 여러 개를 동시에 안정적으로 계속 돌리는 용도에는 맞지 않습니다. 실시간 PPG/EDA 확인이 목적이면 기본값처럼 continuous 센서만 쓰는 편이 안정적입니다.

## 권한

워치 모듈은 다음 권한을 사용합니다.

| 권한 | 용도 |
|------|------|
| `android.permission.BODY_SENSORS` | 심박/PPG 등 신체 센서 |
| `android.permission.BODY_SENSORS_BACKGROUND` | 백그라운드 센서 접근 |
| `android.permission.health.READ_HEART_RATE` | Android 16/API 36 이상 심박 권한 |
| `com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA` | Samsung 추가 건강 센서, EDA/PPG에 필요 |
| `android.permission.ACTIVITY_RECOGNITION` | 활동/건강 센서 관련 |
| `android.permission.POST_NOTIFICATIONS` | Android 13+ 알림 표시, 선택 권한 |
| `android.permission.WAKE_LOCK` | 화면 꺼짐 중 측정 유지 |
| `android.permission.FOREGROUND_SERVICE` | Foreground service |
| `android.permission.FOREGROUND_SERVICE_HEALTH` | health 타입 foreground service |

시작 버튼은 sensor 권한이 모두 허용된 경우에만 측정을 시작합니다. `POST_NOTIFICATIONS`는 알림 표시용 선택 권한이라 거부되어도 센서 시작 조건에서는 제외됩니다.

## 설치 준비

1. Samsung Developer에서 Samsung Health Sensor SDK AAR을 다운로드합니다.
2. AAR을 아래 경로에 둡니다.

```text
wearos/libs/samsung-health-sensor-api-1.4.1.aar
```

3. Android Studio에서 `app` 모듈을 폰에 설치합니다.
4. Android Studio에서 `wearos` 모듈을 워치에 설치합니다.
5. 워치 앱에서 센서 권한을 허용합니다.

`local.properties`, `.idea/`, `.gradle/`, SDK AAR 파일은 로컬 환경 파일이라 git에 올리지 않습니다.

## Samsung Health 개발자 모드

Samsung Health Sensor SDK의 raw/추가 센서는 워치의 Samsung Health 개발자 모드가 필요할 수 있습니다.

1. 워치에서 Samsung Health 앱을 엽니다.
2. Samsung Health 정보 화면으로 이동합니다.
3. 버전 정보를 여러 번 탭해 개발자 모드를 활성화합니다.
4. 개발자 모드와 Health Data SDK 관련 옵션을 켭니다.

센서가 계속 비어 있으면 먼저 워치 권한, Samsung Health 개발자 모드, Samsung Health Sensor SDK 지원 기기 여부를 확인하세요.

## 데이터 흐름

```text
[Galaxy Watch]
SensorTrackingService
  -> HealthSensorManager
     -> HEART_RATE_CONTINUOUS
     -> PPG_CONTINUOUS (GREEN / IR / RED)
     -> EDA_CONTINUOUS
     -> ACCELEROMETER_CONTINUOUS
     -> SKIN_TEMPERATURE_CONTINUOUS
  -> 200ms batch send
  -> PhoneDataSender

Wearable Data Layer
  -> /sensor/hr
  -> /sensor/ppg
  -> /sensor/ppg_ir
  -> /sensor/ppg_red
  -> /sensor/eda
  -> /sensor/accel_x
  -> /sensor/accel_y
  -> /sensor/accel_z
  -> /sensor/skin_temp

[Android Phone]
WearDataListenerService
  -> SensorRepository
  -> SensorViewModel
  -> MainActivity charts + CSV export
```

배치 payload 형식은 다음과 같습니다.

```text
[count:Int(4)] + count * [timestamp:Long(8) + value:Float(4)]
```

구형 단일 샘플 payload와 예전 `/sensor/bia_bmi` 경로도 일부 역호환 처리합니다.

## 차트 동작

폰 차트는 모든 센서가 같은 시간축을 씁니다.

```kotlin
x = (timestamp - firstTimestamp) / 100f
```

현재 화면에는 최근 7초만 보입니다.

```kotlin
visibleWindowUnits = 70f
```

Y축은 전체 누적 데이터가 아니라 현재 보이는 7초 구간만 보고 스케일링합니다. 단, min/max를 즉시 바꾸면 움직임이 있을 때 화면이 덜컥거려서 `AxisScaleState`에 축 상태를 저장하고 부드럽게 따라가게 했습니다.

- 축이 더 넓어져야 할 때: 빠르게 확장
- 축이 다시 좁아질 때: 천천히 수축
- 큰 순간 튐 완화: 2~98% 분위값 기준 사용

관련 코드는 `app/src/main/java/com/example/healthsensor/MainActivity.kt`의 `applyVisibleScale()`에 있습니다.

## CSV 저장

폰 앱 하단의 CSV 저장 버튼을 누르면 세션 동안 받은 원시 데이터가 저장됩니다.

저장 경로:

```text
/sdcard/Android/data/com.example.healthsensor/files/Documents/sensor_YYYYMMDD_HHmmss.csv
```

CSV 형식:

```csv
sensor,timestamp_ms,value
HR,1750000000000,72.0
PPG_GREEN,1750000000040,32451.0
PPG_IR,1750000000053,28130.0
PPG_RED,1750000000066,19847.0
EDA,1750000001000,0.412
ACCEL_X,1750000000040,0.12
ACCEL_Y,1750000000040,-9.78
ACCEL_Z,1750000000040,0.34
SKIN_TEMP,1750000005000,33.4
```

On-demand 채널을 켠 경우 `ECG`, `SPO2`, `BIA_FAT`, `BIA_BMR`, `BIA_MUSCLE`, `BIA_WATER`, `SWEAT_LOSS`도 같은 형식으로 저장됩니다.

## 자주 볼 문제

| 증상 | 확인할 것 |
|------|-----------|
| PPG가 비어 있음 | `PPG_CONTINUOUS` 지원 여부, `PpgType.GREEN/IR/RED` 설정, Samsung 추가 건강 데이터 권한 |
| EDA가 비어 있음 | Watch8+ 여부, `READ_ADDITIONAL_HEALTH_DATA` 권한, 개발자 모드 |
| HR만 나오고 PPG/EDA가 안 나옴 | 워치 권한 재허용, 앱 재설치 후 권한 팝업 확인 |
| 차트가 일자로 보임 | 현재는 보이는 7초 구간 기준 Y축 자동 스케일 사용 |
| 차트가 덜컥거림 | `applyVisibleScale()`의 smoothing alpha 조정 |
| On-demand 차트가 비어 있음 | `ENABLE_ON_DEMAND_TRACKERS = false`가 기본값 |
| ECG가 비어 있음 | Samsung 정책 승인 또는 on-demand 제약 가능 |

## 참고 문서

- [Samsung Health Sensor SDK](https://developer.samsung.com/health/sensor)
- [Data Specifications](https://developer.samsung.com/health/sensor/guide/data-specifications.html)
- [HealthTrackerType API Reference](https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/HealthTrackerType.html)
- [Release Notes](https://developer.samsung.com/health/sensor/release-note.html)
- [EDA Codelab](https://developer.samsung.com/codelab/health/electrodermal-activity.html)
- [PPG / SpO2 Codelab](https://developer.samsung.com/codelab/health/blood-oxygen-heart-rate.html)

## 라이선스

앱 코드는 프로젝트 라이선스를 따릅니다. Samsung Health Sensor SDK와 SDK 예제/문서는 Samsung Developer 라이선스를 따르며, AAR 파일은 직접 재배포하지 않습니다.
