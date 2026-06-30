# Phone App

`app` 모듈은 Android 폰에서 실행됩니다. 워치에서 받은 센서 데이터를 화면에 표시하고, 서버로 10초 window를 전송하며, 서버의 갈망 예측 class를 다시 받아 워치에 전달합니다.

## 역할

- Wear OS 앱에서 보낸 센서 batch 수신
- HR, PPG Green/IR/Red, EDA, Accel X/Y/Z, SkinTemp 실시간 chart 표시
- 서버 예측 class `0/1/2` step chart 표시
- CSV 저장
- 10초 센서 window를 1초마다 서버에 POST
- 서버 SSE stream에서 갈망 class 비동기 수신
- 받은 class를 워치 `/prediction/class` 경로로 전달

## 주요 파일

| File | Role |
|---|---|
| `MainActivity.kt` | Compose UI, MPAndroidChart chart, CSV 저장 |
| `SensorViewModel.kt` | 센서 상태, 서버 window 생성, 자동 통신 시작, SSE 수신 관리 |
| `SensorRepository.kt` | 워치에서 받은 센서 Flow 허브 |
| `WearDataListenerService.kt` | Wearable MessageClient sensor message 수신 |
| `ServerUploader.kt` | HTTP POST, SSE stream parsing, prediction JSON parsing |
| `ServerConfig.kt` | `assets/server_config.properties` 로드 |
| `PhonePredictionSender.kt` | 서버 prediction class를 워치로 전달 |

## UI

폰 앱 화면은 위에서 아래 순서로 구성됩니다.

1. 수신 상태 표시
2. 서버 POST URL 입력 및 전송 상태
3. Prediction SSE URL 입력 및 수신 상태
4. 갈망 Class chart
5. HR, PPG, EDA, Accel, SkinTemp chart
6. 초기화, CSV 저장 버튼

`갈망 Class` chart는 서버에서 받은 class를 `0`, `1`, `2` 값으로 표시합니다. 센서 chart와 같은 시간축을 사용하되, y축은 `-0.1`부터 `2.1`까지 고정되어 class 변화를 읽기 쉽게 했습니다.

## 서버 설정

서버 주소는 아래 파일에 넣을 수 있습니다.

```text
app/src/main/assets/server_config.properties
```

```properties
sensor_post_url=http://SERVER_HOST:PORT/sensor-window
prediction_sse_url=http://SERVER_HOST:PORT/prediction-stream
```

앱 화면의 URL 입력칸에서도 수정할 수 있습니다. assets 파일을 바꾸면 앱을 다시 빌드/설치해야 반영됩니다.

## 자동 통신 시작

폰 앱이 첫 센서 샘플을 받으면 취득 시작으로 판단합니다. 그 뒤 10초가 지나면 자동으로 다음을 시작합니다.

- `sensor_post_url`이 있으면 서버 POST 전송 시작
- `prediction_sse_url`이 있으면 SSE 예측 수신 시작

URL이 비어 있으면 자동 시작은 보류됩니다. 이후 앱 화면에서 URL을 입력하면 이어서 시작됩니다.

## 서버 전송 Payload

폰 앱은 1초마다 최근 10초 window를 서버로 보냅니다. PPG/EDA는 timestamp를 맞춘 fixed grid로 변환됩니다.

| Channel | Rate | Count | Timestamp |
|---|---:|---:|---|
| `PPG_GREEN` | 25Hz | 250 | `windowStartMs + i * 40ms` |
| `PPG_IR` | 25Hz | 250 | `windowStartMs + i * 40ms` |
| `PPG_RED` | 25Hz | 250 | `windowStartMs + i * 40ms` |
| `EDA` | 1Hz | 10 | `windowStartMs + i * 1000ms` |

원시 샘플이 target timestamp에 정확히 없으면 선형 보간합니다. window 가장자리나 큰 gap은 가까운 값 또는 마지막 값 유지로 채웁니다. 이 정책은 실시간 POST가 끊기지 않게 하기 위한 것입니다.

서버 payload의 `sync` 예시:

```json
{
  "mode": "fixed_grid_ppg_25hz_eda_1hz_continuous",
  "fillMode": "linear_interpolation_nearest_edge_hold",
  "ppgHz": 25,
  "ppgSamplesPerChannel": 250,
  "edaHz": 1,
  "edaSamples": 10
}
```

## Prediction Receive

폰 앱은 `prediction_sse_url`에 장기 연결합니다. 서버는 처리 완료 시점마다 SSE event를 보냅니다.

```text
event: craving
data: {"class":1,"timestampMs":1750000000000}

```

폰 앱은 `class`, `cravingClass`, `prediction`, `score`, `cravingScore` 중 하나를 읽습니다. 워치에 보낼 최종 값은 반드시 정수 `0`, `1`, `2`여야 합니다.

## CSV

CSV 저장에는 센서 원시 데이터와 서버에서 받은 class가 포함됩니다.

```csv
sensor,timestamp_ms,value
HR,1750000000000,72.0
PPG_GREEN,1750000000040,32451.0
EDA,1750000001000,0.412
CRAVING_CLASS,1750000010000,1.0
```

저장 경로는 앱 전용 Documents 디렉터리입니다.

```text
/sdcard/Android/data/com.example.healthsensor/files/Documents/
```

