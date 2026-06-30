# Galaxy Watch Health Sensor Monitor

Galaxy Watch 8에서 Samsung Health Sensor SDK로 센서 데이터를 수집하고, Galaxy S24+ 같은 Android 폰에서 실시간으로 plot, CSV 저장, 서버 전송하는 Android 멀티모듈 앱입니다.

현재 코드는 워치의 `wearos` 모듈이 센서를 읽고 Wearable Data Layer로 배치 전송하며, 폰의 `app` 모듈이 데이터를 받아 MPAndroidChart로 표시하고 최근 10초 윈도우를 1초마다 HTTP POST로 전송합니다.

## 공개 배포본 안내

이 폴더는 GitHub 공개 업로드용으로 정리한 배포본입니다. 보안/라이선스/로컬 환경 의존성이 있는 항목은 제외했습니다.

제외된 항목:

| 항목 | 제외 이유 |
|---|---|
| `local.properties` | 로컬 Android SDK 경로 포함 |
| `.gradle/`, `build/`, `app/build/`, `wearos/build/` | 로컬 빌드 산출물 |
| `.idea/`, `.claude/` | 로컬 IDE/도구 상태 |
| `samsung_health_sensor_sdk/` | Samsung SDK 로컬 배포물/문서 |
| `aar_extracted/` | SDK AAR 분석 산출물 |
| `wearos/libs/*.aar` | Samsung SDK 바이너리 재배포 위험 |
| 실제 서버 주소 | 환경별 endpoint이며 공개 repo에 노출하지 않음 |

공개 repo에 올린 뒤 처음 사용하는 사람은 다음을 준비해야 합니다.

1. Android Studio에서 프로젝트를 엽니다.
2. Samsung Developer에서 Samsung Health Sensor SDK를 받습니다.
3. AAR 파일을 아래 위치에 복사합니다.

```text
wearos/libs/samsung-health-sensor-api-1.4.1.aar
```

4. 서버 주소를 설정합니다.

```text
app/src/main/assets/server_config.properties
```

```properties
sensor_post_url=http://SERVER_HOST:PORT/sensor-window
prediction_sse_url=http://SERVER_HOST:PORT/prediction-stream
```

5. 빌드합니다.

```bash
./gradlew assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

## 문서

| 문서 | 내용 |
|---|---|
| [Phone App](docs/PHONE_APP.md) | 폰 앱 UI, 서버 전송, SSE 수신, 갈망 class plot |
| [Wear OS App](docs/WEAROS_APP.md) | 워치 센서 수집, 권한, Wearable Data Layer 전송, class 리포트 수신 |
| [Server API Spec](SERVER_API_SPEC.md) | 서버가 구현해야 할 POST/SSE API 계약 |

## 현재 동작 요약

| 항목 | 현재 구현 |
|------|-----------|
| PPG | `PPG_CONTINUOUS` + `setOf(PpgType.GREEN, PpgType.IR, PpgType.RED)` |
| EDA | `EDA_CONTINUOUS`, Watch8+ 및 추가 권한 필요 |
| 전송 주기 | 워치에서 약 200ms마다 배치 전송 |
| SDK flush | `180ms 대기 -> flushAllTrackers() -> 20ms 콜백 대기 -> 전송` |
| 차트 시간창 | 최근 10초 표시 |
| X축 | 워치 timestamp 기준 공통 시간축, 1칸 = 100ms |
| Y축 | 보이는 10초 구간만 기준으로 자동 스케일링 |
| Y축 완충 | 2~98% 분위값 + 축 경계 smoothing으로 덜컥거림 감소 |
| 기본 활성 센서 | continuous 계열 9채널 |
| 서버 전송 | 폰 앱에서 POST URL 입력 후 PPG/EDA 동기화 10초 window를 1초마다 JSON 전송 |
| 예측 수신 | 폰 앱에서 Prediction SSE URL로 장기 연결 후 서버 이벤트를 비동기 수신 |
| 워치 리포트 | 서버 prediction class `0/1/2`를 `/prediction/class`로 워치에 전달 |
| 자동 통신 시작 | 첫 센서 샘플 수신 후 10초가 지나면 서버 전송과 예측 수신 자동 시작 |
| 갈망 class plot | 폰 앱에서 수신 class `0/1/2`를 별도 step chart로 표시 |
| On-demand 센서 | 코드 일부는 남아 있으나 기본값은 비활성화, 현재 UI/서버 전송 대상 아님 |

## 프로젝트 구조

```text
watch_test/
├── docs/
│   ├── PHONE_APP.md                # 폰 앱 설명
│   └── WEAROS_APP.md               # 워치 앱 설명
│
├── app/                            # 스마트폰 앱 모듈
│   └── src/main/java/com/example/healthsensor/
│       ├── MainActivity.kt         # Compose UI + MPAndroidChart 실시간 차트
│       ├── SensorViewModel.kt      # 차트 상태, 공통 시간축, CSV 원시 데이터 누적
│       ├── ServerConfig.kt         # assets/server_config.properties 로드
│       ├── ServerUploader.kt       # 10초 윈도우 POST + prediction SSE 수신
│       ├── PhonePredictionSender.kt # 폰에서 워치로 craving class 전달
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
├── SERVER_API_SPEC.md               # 서버 API 계약
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

## 기본 비활성인 on-demand 후보

아래 채널은 SDK 호환성 확인용 코드 일부가 남아 있지만 워치에서 기본적으로 시작하지 않으며, 현재 폰 UI/CSV/서버 전송 대상도 아닙니다. 켜려면 별도 측정 흐름으로 분리하는 편이 안전합니다.

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
  -> HTTP POST recent 10s sensor window every 1s
  -> HTTP SSE prediction receiver
  -> Wearable MessageClient /prediction/class

[Galaxy Watch]
PredictionListenerService
  -> SensorState.cravingClass
  -> Watch UI "CRAVE 0/1/2"
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

현재 화면에는 최근 10초만 보입니다.

```kotlin
visibleWindowUnits = 100f
```

Y축은 전체 누적 데이터가 아니라 현재 보이는 10초 구간만 보고 스케일링합니다. 단, min/max를 즉시 바꾸면 움직임이 있을 때 화면이 덜컥거려서 `AxisScaleState`에 축 상태를 저장하고 부드럽게 따라가게 했습니다.

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

서버 주소는 `app/src/main/assets/server_config.properties`에 넣을 수 있습니다. 앱 화면의 URL 입력칸은 이 파일의 값을 기본값으로 읽고, 실행 중 수동 수정도 가능합니다.

```properties
sensor_post_url=http://192.168.0.10:8000/sensor-window
prediction_sse_url=http://192.168.0.10:8000/prediction-stream
```

서버 전송은 폰 앱의 `POST URL` endpoint로 1초마다 JSON을 보냅니다. payload는 최근 10초 윈도우의 `HR`, `PPG_GREEN`, `PPG_IR`, `PPG_RED`, `EDA`, `ACCEL_X`, `ACCEL_Y`, `ACCEL_Z`, `SKIN_TEMP` 샘플을 flat list로 담습니다.

서버로 나가는 `PPG_GREEN`, `PPG_IR`, `PPG_RED`, `EDA`는 같은 `windowStartMs` 기준으로 동기화됩니다. PPG 세 채널은 25Hz fixed grid라서 채널당 250개이고, EDA는 1Hz fixed grid라서 10개입니다. 원시 데이터가 잠깐 비거나 window 가장자리에 부족분이 있어도 전송은 멈추지 않고, 선형 보간/가까운 값/마지막 값 유지로 고정 timestamp grid를 채웁니다.

```text
PPG timestamp = windowStartMs + i * 40ms,   i = 0..249
EDA timestamp = windowStartMs + i * 1000ms, i = 0..9
```

예측 수신은 서버 전송과 별도 coroutine에서 동작합니다. 폰 앱의 `Prediction SSE URL` endpoint에 장기 연결하고, 서버가 모델 처리 완료 시점에 보내는 SSE 이벤트를 비동기로 읽습니다. 서버 이벤트의 JSON에서 `class`, `cravingClass`, `prediction`, `score`, `cravingScore` 중 하나를 읽습니다. 워치에 보고할 값은 반드시 `0`, `1`, `2` 중 하나여야 합니다.

폰 앱은 첫 센서 샘플을 받은 시점을 취득 시작으로 보고, 10초 뒤 `sensor_post_url`과 `prediction_sse_url`이 비어 있지 않으면 자동으로 서버 전송과 SSE 수신을 시작합니다. URL이 비어 있으면 자동 시작은 보류되고, 나중에 앱 화면에서 URL을 입력하면 이어서 시작됩니다.

예측 SSE 이벤트 예시:

```text
event: craving
data: {"class":1,"timestampMs":1750000000000}

```

폰은 받은 class를 워치로 `/prediction/class` 경로에 전달합니다. 워치 payload도 같은 JSON 형태입니다.

## 자주 볼 문제

| 증상 | 확인할 것 |
|------|-----------|
| PPG가 비어 있음 | `PPG_CONTINUOUS` 지원 여부, `PpgType.GREEN/IR/RED` 설정, Samsung 추가 건강 데이터 권한 |
| EDA가 비어 있음 | Watch8+ 여부, `READ_ADDITIONAL_HEALTH_DATA` 권한, 개발자 모드 |
| HR만 나오고 PPG/EDA가 안 나옴 | 워치 권한 재허용, 앱 재설치 후 권한 팝업 확인 |
| 차트가 일자로 보임 | 현재는 보이는 10초 구간 기준 Y축 자동 스케일 사용 |
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
