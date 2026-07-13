# NeuroTruth Android Server API

최종 업데이트: 2026-07-13

이 문서는 Android phone/Wear OS 앱과 NeuroTruth backend 사이의 데이터 계약입니다. 워치 센서 데이터는 폰을 거쳐 backend로 전송되고, backend는 RF 모델 예측과 deterministic alert metadata를 SSE 스트림으로 폰에 보냅니다. 폰은 받은 class/alert를 워치에 전달하고, 텍스트 중재와 handoff는 backend intervention endpoint를 호출합니다.

## 전체 흐름

```text
Galaxy Watch
  -> HR / PPG / EDA / Accel / SkinTemp 측정
  -> Wearable Data Layer

Android Phone
  -> 최근 10초 센서 윈도우를 1초마다 서버에 POST
  -> 서버 prediction SSE 스트림을 비동기로 수신
  -> 받은 class 0/1/2를 워치에 전달

NeuroTruth Backend
  -> POST payload 기반으로 알코올 갈망 예측
  -> rule-based alert decision 생성
  -> 처리 완료 시점마다 SSE 이벤트로 class/alert 전송
  -> Bedrock GPT-5.5 text intervention / slots 처리
  -> 비동기 handoff job 생성, 저장, 상태 조회
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
  "sessionId": "phone-1750000000000",
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
| `sessionId` | `string` | 센서, 예측, 중재, slot, handoff를 연결하는 권장 session ID |
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
| `sessionId` | `string` | Backend가 확정해 되돌려주는 session ID |
| `sequence` | `long` | 센서 업로드와 동일한 세션 내 순번 |

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

## 3. Alert Metadata

Prediction SSE event는 같은 JSON 객체에 alert metadata를 포함할 수 있습니다. Phone과 watch는 `alertAction`을 먼저 사용하고, 유효한 action이 없으면 `alertLevel`, alert metadata가 전혀 없으면 legacy `class`로 fallback합니다. Class-only event는 `0=none`, `1=recommend`, `2=required`로 계속 유효합니다.

```text
event: craving
data: {"class":1,"timestampMs":1750000042500,"alertLevel":"recommend","alertAction":"recommend_intervention","windowMean":0.8,"triggerReason":"window_mean_recommend","alertRequired":false}

```

| Field | Type | Description |
|---|---:|---|
| `alertLevel` | `string` | `none`, `recommend`, or `required` |
| `alertAction` | `string` | `none`, `recommend_intervention`, `required_intervention`, or `cooldown` |
| `windowMean` | `number` | Optional recent prediction mean |
| `triggerReason` | `string` | `window_warming_up`, `window_mean_recommend`, `window_mean_required`, `high_streak`, `downtrend_suppressed`, or `cooldown_active` |
| `alertRequired` | `boolean` | `true` for required intervention |
| `confidence` | `number` | Optional model confidence |
| `sequence` | `long` | Optional prediction/upload sequence |

`none`과 `cooldown`은 phone notification, watch vibration, 자동 화면 이동을 억제합니다. `recommend_intervention`은 non-blocking 권고를, `required_intervention`은 phone의 8문항 상태 확인 후 text intervention 진입을 의미합니다. Phone에서 intervention chat이 활성화된 동안 새 required event는 prediction/history와 watch 상태 전달에는 반영되지만, phone-to-watch payload의 `alertAction`은 `none`으로 override되어 AUQ/state-check와 watch 진동/notification을 다시 실행하지 않습니다. Chat을 닫은 뒤 도착한 새로운 required event부터 원래 action을 다시 표시할 수 있습니다.

## 4. Text Intervention Endpoints

폰 앱은 `sensor_post_url` 또는 `prediction_sse_url`에서 backend base URL을 추론해 아래 endpoint를 호출합니다. 이 endpoint들은 `/sensor-window` upload 계약을 바꾸지 않습니다.

### Chat

```http
POST /api/intervention/chat
Content-Type: application/json; charset=utf-8
Accept: application/json
```

```json
{
  "sessionId": "1750000000000",
  "message": "지금 술 생각이 강하게 납니다.",
  "alert": {
    "alertLevel": "recommend",
    "alertAction": "recommend_intervention",
    "windowMean": 0.8,
    "triggerReason": "window_mean_recommend",
    "alertRequired": false
  },
  "slots": {},
  "conversationHistory": [
    {"role": "user", "content": "오늘 저녁에 약속이 있습니다."},
    {"role": "assistant", "content": "지금 가장 강한 유발 요인은 무엇인가요?"}
  ]
}
```

Android client는 응답의 assistant text, merged slots, handoff readiness, missing slots, summary를 표시합니다.

Chat request의 connect timeout은 짧게 유지하고 response read timeout은 기본 60분입니다. 관리자 모드에서 1~1,440분 범위로 변경하며 이후 chat request부터 적용합니다. Timeout이 나면 사용자 메시지는 화면에 남고 자동 재전송하지 않습니다.

권장 response shape:

```json
{
  "response": "지금은 2분만 술과 거리를 두는 행동부터 해볼까요?",
  "slots": {
    "trigger": "저녁 약속"
  },
  "missingSlots": ["duration", "intensity"],
  "handoffReady": false,
  "summary": "저녁 약속을 앞두고 갈망을 보고함"
}
```

### Slots

```http
POST /api/intervention/slots
Content-Type: application/json; charset=utf-8
Accept: application/json
```

```json
{
  "sessionId": "1750000000000",
  "slots": {},
  "conversationHistory": [
    {"role": "user", "content": "약속 장소 근처라 술 생각이 납니다."},
    {"role": "assistant", "content": "갈망 강도는 0부터 10 사이에서 어느 정도인가요?"}
  ]
}
```

Slot key:

```text
trigger
duration
intensity
recent_alcohol_use
physiological_context
coping_attempt
safety_concern
support_context
intervention_summary
```

### Synchronous Handoff Compatibility

```http
POST /api/intervention/handoff
Content-Type: application/json; charset=utf-8
Accept: application/json
```

```json
{
  "sessionId": "1750000000000",
  "slots": {},
  "conversationHistory": [
    {"role": "user", "content": "지금 술 생각이 강하게 납니다."},
    {"role": "assistant", "content": "지금 주변에 술이 가까이 있나요?"}
  ]
}
```

Response는 `reportMarkdown` 같은 Markdown report field와 optional `missingSlots`를 포함해야 합니다.

이 동기 endpoint는 기존 client 호환성을 위해 유지합니다. 현재 Android phone 앱은 아래 비동기 job endpoint를 사용합니다.

### Asynchronous Handoff Job Submission

```http
POST /api/intervention/handoff/jobs
Content-Type: application/json; charset=utf-8
Accept: application/json
```

Request body는 synchronous handoff와 같은 `sessionId`, optional `slots`, `conversationHistory`, `alertEvents`, `predictionSummary` 계약을 사용합니다. Backend는 요청 시점의 snapshot을 고정하고 Bedrock 생성이 끝날 때까지 HTTP 연결을 유지하지 않습니다.

Accepted response:

```http
HTTP/1.1 202 Accepted
```

```json
{
  "jobId": "6f3a8f8e-f930-421e-9a2a-e0a26ad77520",
  "sessionId": "1750000000000",
  "status": "queued"
}
```

### Asynchronous Handoff Job Status

```http
GET /api/intervention/handoff/jobs/{job_id}
Accept: application/json
```

Queued/running response:

```json
{
  "jobId": "6f3a8f8e-f930-421e-9a2a-e0a26ad77520",
  "sessionId": "1750000000000",
  "status": "running"
}
```

Completed response:

```json
{
  "jobId": "6f3a8f8e-f930-421e-9a2a-e0a26ad77520",
  "sessionId": "1750000000000",
  "status": "completed",
  "result": {
    "reportMarkdown": "# Handoff report\n...",
    "missingSlots": []
  }
}
```

Failed response:

```json
{
  "jobId": "6f3a8f8e-f930-421e-9a2a-e0a26ad77520",
  "sessionId": "1750000000000",
  "status": "failed",
  "error": "Handoff generation failed"
}
```

알 수 없거나 만료된 `job_id`는 HTTP 404입니다. Android client는 한 번에 하나의 handoff job만 접수하고 약 1.5초마다 짧은 GET request로 상태를 조회합니다. 일시적인 조회 실패는 재조회하지만 generation POST를 자동 재전송하지 않습니다. Job polling과 chat 전송은 별도 busy state를 사용하므로 handoff 생성 중에도 chat을 계속할 수 있습니다.

Backend process-local registry는 최대 128개 job을 보관하고 completed/failed metadata를 1시간 뒤 만료합니다. 개별 job 실행 상한도 1시간이며, shutdown 시 신규 접수를 거부하고 queued/running job을 안전한 failed 상태로 정리합니다. Provider raw error나 credential은 status response에 포함하지 않습니다.

## 5. Phone-to-Watch Prediction Report

Wear message path는 `/prediction/class`로 유지됩니다. Payload는 `class`를 포함하고 alert metadata를 함께 포함할 수 있습니다.

```json
{
  "class": 1,
  "timestampMs": 1750000042500,
  "sessionId": "phone-1750000000000",
  "score": 0.87,
  "confidence": 0.74,
  "sequence": 42,
  "alertLevel": "required",
  "alertAction": "required_intervention",
  "windowMean": 1.7,
  "triggerReason": "window_mean_required",
  "alertRequired": true
}
```

## Server-Side Processing Notes

- 같은 `sessionId`와 증가하는 `sequence`를 사용해서 세션 내 윈도우 순서를 추적합니다. `sessionStartedAtMs`만 보내는 기존 client도 계속 지원합니다.
- 업로드는 1초마다 오지만 윈도우 길이는 10초라서 인접 payload 간 샘플이 많이 중복됩니다.
- 모델이 특정 업로드에 대한 결과를 낼 경우 SSE 이벤트 JSON에 `sequence`를 추가해도 됩니다. 현재 폰 앱은 `sequence`를 필수로 사용하지 않습니다.
- 서버가 continuous score를 따로 쓰더라도 워치에 표시할 값은 최종 class `0/1/2`로 변환해서 보내야 합니다.
