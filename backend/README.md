# Neurotruth Backend

FastAPI 기반의 실시간 알코올 갈망 예측 서버입니다. Android 앱은 10초 센서 윈도우를 `POST /sensor-window`로 보내고, 서버는 모델 추론 결과를 `GET /prediction-stream` SSE로 다시 보냅니다.

## 현재 모델 설정

현재 서버는 `backend/model` 폴더의 최신 학습 코드와 checkpoint를 기준으로 동작합니다.

```text
checkpoint: backend/model/weights/fold3_best.pt
model:      DualBranchNet
scale:      per-window minmax
```

모델 입력:

| Branch | Sensor | Shape | Sampling |
|---|---|---:|---:|
| PPG | `PPG_GREEN` fallback `PPG_IR`, `PPG_RED` | `(B, 1, 250)` | 25Hz x 10s |
| GSR | `EDA` | `(B, 1, 10)` | 1Hz x 10s |

앱 payload에 HR, Accel, SkinTemp 같은 다른 센서가 포함돼도 현재 모델 추론에는 사용하지 않습니다.

## 전처리 흐름

1. 앱에서 들어온 `samples` 중 PPG와 EDA만 선택합니다.
2. payload의 `windowStartMs` ~ `windowEndMs` 기준으로 51.2Hz raw grid, 512 samples에 보간합니다.
3. `backend/model/preprocessing.py`의 학습-time 전처리를 그대로 호출합니다.
4. 전처리 결과는 PPG 25Hz/250 samples, GSR 1Hz/10 samples가 됩니다.
5. checkpoint의 `scale_mode`와 `scale_method`에 맞게 scaling합니다.
6. `DualBranchNet(ppg, gsr)`로 추론합니다.
7. SSE `event: craving`으로 class `0/1/2`를 broadcast합니다.

현재 checkpoint는 `scale_mode=perwin`, `scale_method=minmax`라서 각 윈도우마다 PPG/GSR branch를 별도로 `0~1` 범위로 scaling합니다.

## API

### Health

```http
GET /health
```

서버 생존 여부와 모델 상태를 함께 반환합니다.

### Model Status

```http
GET /model/status
```

마지막 예측의 전처리/스케일링 디버그 정보를 확인할 수 있습니다. 앱 연동 중 값이 이상할 때 가장 먼저 확인하는 endpoint입니다.

주요 필드:

| Field | Meaning |
|---|---|
| `ready` | 모델 로드 완료 여부 |
| `modelPath` | 사용 중인 checkpoint |
| `scaleMode`, `scaleMethod` | checkpoint scaling 설정 |
| `ppgSamples`, `gsrSamples` | 모델 입력 길이 |
| `lastPrediction.channels` | raw/resampled/preprocessed 값 범위 |
| `lastPrediction.scaled` | 모델 입력 직전 scaling 결과 |
| `lastPrediction.probabilities` | class별 softmax 확률 |

### Sensor Upload

```http
POST /sensor-window
Content-Type: application/json
```

앱이 1초마다 최근 10초 윈도우를 보냅니다. 서버는 요청을 빠르게 큐에 넣고 `{"ok": true}`를 반환합니다.

### Prediction Stream

```http
GET /prediction-stream
Accept: text/event-stream
```

SSE 이벤트 형식:

```text
event: craving
data: {"class":1,"timestampMs":1782805371381,"confidence":0.52,"sequence":777}

```

15초 read timeout을 피하기 위해 예측이 없으면 주기적으로 `: ping` keep-alive comment를 보냅니다.

## Android App 설정

PC와 Android 폰이 같은 네트워크에 있을 때 `SERVER_HOST`를 PC의 LAN IP로 바꿉니다.

```properties
sensor_post_url=http://SERVER_HOST:8000/sensor-window
prediction_sse_url=http://SERVER_HOST:8000/prediction-stream
```

PC IP가 바뀌면 `Get-NetIPAddress -AddressFamily IPv4`로 다시 확인해야 합니다.

## 모델 파일 보안

모델 checkpoint는 저장소에 커밋하지 않습니다. 로컬 실행 또는 배포 전에 아래 위치에 별도로 배치하거나 `MODEL_PATH` 환경 변수로 경로를 지정합니다.

```text
backend/model/weights/fold3_best.pt
```

`.pt`, `.pth`, `.onnx`, `.pkl`, `.joblib`, `.npy`, `.npz` 파일은 `backend/.gitignore`에 의해 제외됩니다.

## Docker 실행

백엔드 이미지만 직접 실행:

```powershell
docker build -t neurotruth-backend-test ./backend
docker run -d --rm --name neurotruth-backend-monitor -p 8000:8000 neurotruth-backend-test
```

상태 확인:

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8000/model/status
docker logs -f neurotruth-backend-monitor
```

중지:

```powershell
docker stop neurotruth-backend-monitor
```

## 환경 변수

| Variable | Default | Description |
|---|---|---|
| `MODEL_PATH` | `/app/model/weights/fold3_best.pt` | 사용할 checkpoint 경로 |
| `MODEL_DEVICE` | `auto` | `auto`, `cpu`, `cuda` 등 torch device |
| `INFERENCE_QUEUE_MAX` | `100` | 대기 중인 sensor window 최대 개수 |
| `SSE_KEEPALIVE_SECONDS` | `10` | SSE ping 간격 |
| `LLM_SERVER_URL` | `http://localhost:8001` | 기존 LLM proxy용 |
| `LLM_API_KEY` | empty | 기존 LLM proxy용 |

## 주요 파일

| File | Role |
|---|---|
| `app/main.py` | FastAPI route, SSE endpoint, app lifecycle |
| `app/inference.py` | 모델 로드, sensor payload 전처리, queue worker, SSE broadcast |
| `model/config.py` | 학습/추론 신호 길이와 scaling 설정 |
| `model/preprocessing.py` | 학습 때 사용한 PPG/GSR filter/resample |
| `model/model.py` | `DualBranchNet` 모델 정의 |
| `model/weights/fold3_best.pt` | 현재 서버가 사용하는 checkpoint |
| `Dockerfile` | 백엔드 컨테이너 이미지 |

## Debug Checklist

앱에서 결과가 이상할 때는 다음 순서로 확인합니다.

1. `GET /model/status`에서 `ready=true`인지 확인합니다.
2. `lastPrediction.channels.ppg.rawSampleCount`가 약 250인지 확인합니다.
3. `lastPrediction.channels.gsr.rawSampleCount`가 약 10인지 확인합니다.
4. `scaled.ppg.min/max`, `scaled.gsr.min/max`가 현재 설정상 `0~1`로 들어오는지 확인합니다.
5. `docker logs`에 `Prediction failed`, `Traceback`이 있는지 확인합니다.
