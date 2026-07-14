# Phone App

최종 업데이트: 2026-07-13

`app` 모듈은 Android 폰에서 실행됩니다. 워치에서 받은 센서 데이터를 표시하고, backend로 10초 window를 전송하며, prediction SSE와 alert metadata를 받아 사용자 대시보드와 워치에 반영합니다. 텍스트 중재 chat과 handoff report도 폰에서 backend endpoint를 통해 호출합니다.

## 역할

- Wear OS 앱에서 보낸 sensor batch 수신
- 사용자용 상태 대시보드 표시
- 개발자용 실시간 chart/debug 화면 표시
- HR, PPG Green/IR/Red, EDA, Accel X/Y/Z, SkinTemp chart 표시
- 10초 sensor window를 1초마다 `POST /sensor-window`로 전송
- `GET /prediction-stream` SSE에서 class와 alert metadata 수신
- 수신한 class/alert를 watch `/prediction/class`로 전달
- `/api/intervention/chat`으로 text intervention 진행
- `/api/intervention/handoff/jobs`로 비동기 Markdown handoff 작업 접수 및 status 조회
- CSV 저장

## 현재 UI

| 화면/영역 | 내용 |
|---|---|
| User dashboard | 현재 상태, 최신 craving class, alert label, monitoring 상태, 중재 진입 |
| Developer mode | sensor charts, POST/SSE status, chat timeout 설정, 상담 상태 초기화, CSV export |
| State-check flow | 상담 활성 중 새 required alert의 AUQ 재실행을 차단하고, 상담 종료 후 새 alert부터 허용 |
| Intervention panel | user/assistant message, text input, 독립된 chat/handoff 진행 상태, handoff readiness |
| Handoff preview | missing slot summary와 generated Markdown report 표시 |

마이크/STT UI는 없습니다. 이번 버전은 text-first intervention입니다.

## 서버 설정

기본 URL은 asset 파일에서 읽습니다.

```text
apps/mobile/app/src/main/assets/server_config.properties
```

현재 물리 기기 테스트 값:

```properties
sensor_post_url=http://192.168.68.51:8000/sensor-window
prediction_sse_url=http://192.168.68.51:8000/prediction-stream
```

앱 화면에서 URL을 수정할 수 있지만, asset 값을 바꾸면 다시 빌드/설치해야 합니다. 물리 휴대폰에서는 `localhost`를 사용하지 말고 노트북 LAN IP를 사용합니다.

## 자동 통신

폰이 첫 센서 샘플을 받으면 acquisition 시작으로 판단합니다. 약 10초 뒤 URL이 설정되어 있으면 자동으로 다음 작업을 시작합니다.

- `sensor_post_url`: 최근 10초 sensor window upload
- `prediction_sse_url`: prediction SSE stream 연결

SSE 연결이 끊기면 짧은 지연 뒤 재연결합니다.

## Sensor Upload

폰 앱은 1초마다 최근 10초 window를 backend로 보냅니다. PPG와 EDA는 backend 모델 입력에 맞게 fixed grid로 정렬됩니다.

| Channel | Rate | Count | Timestamp |
|---|---:|---:|---|
| `PPG_GREEN` | 25Hz | 250 | `windowStartMs + i * 40ms` |
| `PPG_IR` | 25Hz | 250 | `windowStartMs + i * 40ms` |
| `PPG_RED` | 25Hz | 250 | `windowStartMs + i * 40ms` |
| `EDA` | 1Hz | 10 | `windowStartMs + i * 1000ms` |

원시 샘플이 부족한 경우 선형 보간, 가까운 값, 마지막 값 유지로 채워 POST 주기가 끊기지 않게 합니다.

요약 payload:

```json
{
  "sessionId": "phone-1750000000000",
  "sessionStartedAtMs": 1750000000000,
  "sequence": 42,
  "windowStartMs": 1750000032000,
  "windowEndMs": 1750000042000,
  "windowMs": 10000,
  "sync": {
    "mode": "fixed_grid_ppg_25hz_eda_1hz_continuous",
    "fillMode": "linear_interpolation_nearest_edge_hold"
  },
  "samples": [
    {"sensor": "PPG_GREEN", "timestampMs": 1750000032000, "value": 32451.0}
  ]
}
```

## Prediction SSE

Backend는 `event: craving` SSE로 class와 alert metadata를 보냅니다.

```text
event: craving
data: {"class":1,"timestampMs":1750000042500,"sessionId":"phone-1750000000000","sequence":42,"alertLevel":"recommend","alertAction":"recommend_intervention","windowMean":0.8,"triggerReason":"window_mean_recommend","alertRequired":false}

```

폰은 `class`, `cravingClass`, `prediction`, `score`, `cravingScore` 중 하나를 class 후보로 읽습니다. 워치로 보낼 값은 최종적으로 정수 `0`, `1`, `2`여야 합니다.

사용자 동작은 `alertAction` -> `alertLevel` -> alert metadata가 전혀 없는 legacy `class` 순서로 결정합니다. `none`과 `cooldown`은 표시 데이터만 갱신하고 알림, 진동, 자동 화면 이동을 만들지 않습니다. `recommend_intervention`은 현재 화면을 유지하면서 알림을 표시하고, `required_intervention`은 기존 8문항 상태 확인을 연 뒤 제출 후 텍스트 chat으로 연결합니다. Chat이 활성화된 동안 뒤이어 온 required event는 계속 기록되고 watch 상태 데이터도 전달되지만, phone은 watch payload의 `alertAction`을 `none`으로 보내 AUQ와 watch 진동/notification을 함께 차단합니다. Chat을 닫은 뒤 발생한 새로운 required event부터 원래 action과 AUQ를 다시 허용합니다. Legacy class-only stream은 `0=none`, `1=recommend`, `2=required`로 계속 동작합니다.

## Text Intervention

폰은 backend host를 `sensor_post_url` 또는 `prediction_sse_url`에서 추론해 intervention endpoint를 호출합니다.

| Endpoint | 용도 |
|---|---|
| `POST /api/intervention/chat` | 사용자 메시지에 대한 assistant 응답과 slot update |
| `POST /api/intervention/handoff/jobs` | 현재 conversation/slot snapshot 기반 비동기 handoff 작업 접수 |
| `GET /api/intervention/handoff/jobs/{job_id}` | queued/running/completed/failed 상태와 완료 report 조회 |
| `POST /api/intervention/handoff` | 기존 동기 handoff 계약; 호환성을 위해 유지 |

`PhoneMonitoringState`가 sensor, SSE, chat, slots, handoff에서 공유하는 session ID와 상담 활성 latch를 소유합니다. Chat request에는 `sessionId`, `message`, `alert`, `slots`, `conversationHistory`가 들어가며, backend가 SSE나 응답으로 돌려준 session ID가 있으면 이를 우선 사용합니다. Chat HTTP read timeout은 기본 60분이며 관리자 모드에서 1~1,440분으로 저장할 수 있습니다. Handoff는 한 번에 하나만 접수하고 약 1.5초 간격으로 상태를 조회하며, 생성 요청 자체는 자동 재전송하지 않습니다. 데이터 전체 초기화는 새 session을 만들고, 별도 `상담 상태 초기화`는 센서 기록과 URL을 유지한 채 AUQ/chat/slot/handoff/polling 상태만 초기화합니다.

## 관리자 상담 설정

사용자 화면 아래의 `관리` 영역을 약 2.5초 길게 누르면 관리자 모드가 열립니다.

- `채팅 응답 제한시간(분)`: 기본 60분, 허용 범위 1~1,440분. 저장값은 앱 화면 재생성 후에도 유지됩니다.
- `상담 상태 초기화`: 진행 중 chat/handoff job polling을 중단하고 AUQ, chat, slot, handoff UI 상태를 초기화합니다. Sensor raw data와 server URL은 유지합니다.

## CSV 저장

CSV에는 sensor raw data와 prediction class가 포함됩니다.

```csv
sensor,timestamp_ms,value
HR,1750000000000,72.0
PPG_GREEN,1750000000040,32451.0
EDA,1750000001000,0.412
CRAVING_CLASS,1750000010000,1.0
```

저장 위치:

```text
/sdcard/Android/data/com.example.healthsensor/files/Documents/
```

## 주요 파일

| File | Role |
|---|---|
| `MainActivity.kt` | Compose UI, dashboard, developer mode, intervention panel |
| `PhoneMonitoringState.kt` | session identity, alert claim, active-intervention AUQ latch |
| `SensorViewModel.kt` | sensor state, chat timeout, intervention reset, async handoff polling |
| `SensorRepository.kt` | watch sensor Flow hub |
| `WearDataListenerService.kt` | Wearable MessageClient sensor message receiver |
| `ServerUploader.kt` | HTTP POST, SSE parsing, configurable chat timeout, async handoff API calls |
| `ServerConfig.kt` | `assets/server_config.properties` loader |
| `PhonePredictionSender.kt` | prediction class and alert metadata forwarding to watch |
