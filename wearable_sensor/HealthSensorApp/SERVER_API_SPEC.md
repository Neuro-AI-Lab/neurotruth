# Alcohol Craving Prediction Server API

이 문서는 Android 폰 앱과 서버 사이의 데이터 계약입니다. 워치 센서 데이터는 폰을 거쳐 서버로 전송되고, 서버는 모델 처리 결과를 SSE 스트림으로 폰에 다시 보냅니다. 폰은 받은 class를 워치에 리포트합니다.

## 전체 흐름

```text
Galaxy Watch
  -> HR / PPG / EDA / Accel / SkinTemp 측정
  -> Wearable Data Layer

Android Phone
  -> 최근 10초 센서 윈도우를 1초마다 서버에 POST
  -> 서버 prediction SSE 스트림을 비동기로 수신
  -> 받은 class 0/1/2를 워치에 전달

Prediction Server
  -> POST payload 기반으로 알코올 갈망 예측
  -> 처리 완료 시점마다 SSE 이벤트로 class 전송
```

## 앱 설정 파일

폰 앱은 아래 파일에서 서버 주소를 읽습니다.

```text
app/src/main/assets/server_config.properties
```

```properties
sensor_post_url=http://SERVER_HOST:PORT/sensor-window
prediction_sse_url=http://SERVER_HOST:PORT/prediction-stream
```

## 1. 센서 윈도우 업로드

### Request

```http
POST /sensor-window
Content-Type: application/json; charset=utf-8
Accept: application/json
```

폰 앱은 1초마다 최근 10초 구간의 센서 샘플을 전송합니다. 한 번 전송할 때 이전 윈도우와 9초 정도 겹칩니다.

PPG와 EDA는 서버 전송 직전에 같은 10초 window로 시간 동기화됩니다. PPG는 25Hz fixed grid로 채널당 250개, EDA는 1Hz fixed grid로 10개가 들어갑니다. 전송은 1초마다 계속 유지되며, 원시 샘플 gap이나 window 가장자리 부족분은 보간 또는 hold로 채웁니다.

### Payload

```json
{
  "sessionStartedAtMs": 1750000000000,
  "sequence": 42,
  "sentAtMs": 1750000042000,
  "windowStartMs": 1750000032000,
  "windowEndMs": 1750000042000,
  "windowMs": 10000,
  "sync": {
    "mode": "fixed_grid_ppg_25hz_eda_1hz_continuous",
    "fillMode": "linear_interpolation_nearest_edge_hold",
    "ppgHz": 25,
    "ppgSamplesPerChannel": 250,
    "edaHz": 1,
    "edaSamples": 10
  },
  "samples": [
    {
      "sensor": "HR",
      "timestampMs": 1750000032500,
      "value": 72.0
    },
    {
      "sensor": "PPG_GREEN",
      "timestampMs": 1750000032520,
      "value": 32451.0
    }
  ]
}
```

### Fields

| Field | Type | Description |
|---|---:|---|
| `sessionStartedAtMs` | `long` | 폰 앱 세션 시작 시각, epoch milliseconds |
| `sequence` | `long` | 폰 앱이 증가시키는 업로드 순번 |
| `sentAtMs` | `long` | 폰이 서버로 보내는 시각, epoch milliseconds |
| `windowStartMs` | `long` | 포함된 샘플 윈도우 시작 시각 |
| `windowEndMs` | `long` | 포함된 샘플 윈도우 끝 시각 |
| `windowMs` | `long` | 현재 고정값 `10000` |
| `sync` | `object` | PPG/EDA fixed-grid 동기화 정보 |
| `samples` | `array` | 센서 샘플 flat list |

### Sync Contract

| Channel | Rate | Count per payload | Timestamp grid |
|---|---:|---:|---|
| `PPG_GREEN` | 25Hz | 250 | `windowStartMs + i * 40ms`, `i=0..249` |
| `PPG_IR` | 25Hz | 250 | `windowStartMs + i * 40ms`, `i=0..249` |
| `PPG_RED` | 25Hz | 250 | `windowStartMs + i * 40ms`, `i=0..249` |
| `EDA` | 1Hz | 10 | `windowStartMs + i * 1000ms`, `i=0..9` |

`PPG_*`와 `EDA` 값은 원시 샘플을 같은 window 기준의 fixed grid로 선형 보간한 값입니다. HR, Accel, SkinTemp는 같은 window 안의 원시 샘플을 그대로 포함합니다.

원시 샘플이 target timestamp 양쪽에 있으면 선형 보간합니다. Window 앞/뒤 가장자리에 원시 샘플이 부족하면 첫 값 또는 마지막 값을 유지합니다. 큰 gap이 있으면 gap 사이를 억지로 길게 선형 보간하지 않고 가까운 값을 사용합니다. 따라서 PPG/EDA timestamp와 sample count는 고정되고, 임시 결측 때문에 POST 주기가 멈추지 않습니다.

### Sample Fields

| Field | Type | Description |
|---|---:|---|
| `sensor` | `string` | 센서 채널명 |
| `timestampMs` | `long` | 워치에서 측정된 샘플 시각, epoch milliseconds |
| `value` | `number` | 센서 값 |

### Sensor Names

| Sensor | Meaning |
|---|---|
| `HR` | Heart rate, bpm |
| `PPG_GREEN` | PPG green raw |
| `PPG_IR` | PPG infrared raw |
| `PPG_RED` | PPG red raw |
| `EDA` | Electrodermal activity / skin conductance |
| `ACCEL_X` | Accelerometer X |
| `ACCEL_Y` | Accelerometer Y |
| `ACCEL_Z` | Accelerometer Z |
| `SKIN_TEMP` | Skin temperature |

### Response

서버는 `2xx` 상태 코드를 반환하면 됩니다. 폰 앱은 response body를 필수로 사용하지 않습니다.

```json
{
  "ok": true
}
```

## 2. 예측 결과 SSE 스트림

### Request

```http
GET /prediction-stream
Accept: text/event-stream, application/json
Cache-Control: no-cache
```

폰 앱은 이 endpoint에 장기 연결합니다. 서버는 모델 처리 결과가 준비될 때마다 SSE 이벤트를 보냅니다.

### Required Response Headers

```http
HTTP/1.1 200 OK
Content-Type: text/event-stream; charset=utf-8
Cache-Control: no-cache
Connection: keep-alive
```

### Event Format

```text
event: craving
data: {"class":1,"timestampMs":1750000042500}

```

`data:`에는 JSON 객체가 들어가야 합니다. 폰 앱은 아래 key 중 하나를 class 값으로 읽습니다.

| Key | Type | Note |
|---|---:|---|
| `class` | `int` or numeric string | 권장 |
| `cravingClass` | `int` or numeric string | 허용 |
| `prediction` | `int` or numeric string | 허용 |
| `score` | `int` or numeric string | 허용, 단 워치 리포트용으로는 정수 `0/1/2`만 가능 |
| `cravingScore` | `int` or numeric string | 허용, 단 워치 리포트용으로는 정수 `0/1/2`만 가능 |

값은 반드시 정수 `0`, `1`, `2` 중 하나여야 합니다.

| Class | Meaning |
|---:|---|
| `0` | low craving |
| `1` | medium craving |
| `2` | high craving |

### Optional Fields

| Field | Type | Description |
|---|---:|---|
| `timestampMs` | `long` | 예측 생성 시각. 없으면 폰 수신 시각을 사용 |
| `score` | `number` | 서버 내부 confidence 또는 score. class와 별도로 참고용 전달 가능 |

예시:

```text
event: craving
data: {"class":2,"score":0.87,"timestampMs":1750000042500}

```

### Keep-Alive

현재 폰 앱은 SSE read timeout을 15초로 둡니다. 15초 이상 이벤트가 없을 수 있으면 서버는 keep-alive comment를 10초 이내 간격으로 보내는 것이 좋습니다.

```text
: ping

```

연결이 끊기거나 timeout이 나면 폰 앱은 약 2초 후 자동 재연결합니다.

## Server-Side Processing Notes

- 같은 `sessionStartedAtMs`와 증가하는 `sequence`를 사용해서 세션 내 윈도우 순서를 추적할 수 있습니다.
- 업로드는 1초마다 오지만 윈도우 길이는 10초라서 인접 payload 간 샘플이 많이 중복됩니다.
- 모델이 특정 업로드에 대한 결과를 낼 경우 SSE 이벤트 JSON에 `sequence`를 추가해도 됩니다. 현재 폰 앱은 `sequence`를 필수로 사용하지 않습니다.
- 서버가 continuous score를 따로 쓰더라도 워치에 표시할 값은 최종 class `0/1/2`로 변환해서 보내야 합니다.
