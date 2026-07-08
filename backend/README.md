# Neurotruth Backend

FastAPI 기반의 실시간 알코올 갈망 예측 서버입니다. Android 앱은 10초 센서 윈도우를 `POST /sensor-window`로 보내고, 서버는 모델 추론 결과를 `GET /prediction-stream` SSE로 다시 보냅니다.

## 현재 모델 설정

현재 서버는 `backend/model` 폴더의 피처 기반 RandomForest 번들을 기준으로 동작합니다. torch/GPU는 더 이상 사용하지 않습니다.

```text
bundle: backend/model/weights/rf_dependent.joblib
model:  RandomForestClassifier (subject-dependent)
input:  PPG/GSR 원신호에서 추출한 67개 피처 (HR/HRV/SI/RR + EDA tonic 통계)
```

번들은 `feature_classification` 학습 파이프라인이 저장한 `{keep, imputer, scaler, clf}` 묶음이며, 학습과 동일한 51.2Hz 피처 추출 조건으로 서빙합니다.

모델 입력:

| Sensor | 앱 채널 | 서빙 처리 |
|---|---|---|
| PPG | `PPG_GREEN` fallback `PPG_IR`, `PPG_RED` | 51.2Hz × 512 로 보간 → NeuroKit HR/HRV/SI/RR |
| GSR | `EDA` | 51.2Hz × 512 로 보간 → EDA tonic 통계 (Mean/SD/Min/Max/Range/Median/IQR/Slope) |

앱 payload에 HR, Accel, SkinTemp 같은 다른 센서가 포함돼도 현재 모델 추론에는 사용하지 않습니다.

> ⚠️ 워치 EDA는 1Hz(윈도우당 ~10샘플)라 512로 보간해도 실제 정보량은 제한적입니다. 현재 번들은 subject-dependent 모델(BalancedAcc ≈ 0.57)로, 같은 사용자가 학습에 섞여 있어 처음 보는 사용자에게는 성능이 더 낮아질 수 있습니다.

## 전처리 흐름

1. 앱에서 들어온 `samples` 중 PPG와 EDA만 선택합니다.
2. payload의 `windowStartMs` ~ `windowEndMs` 기준으로 51.2Hz raw grid, 512 samples에 선형보간합니다 (학습과 동일한 샘플레이트로 업샘플링).
3. `backend/model/features.py`로 PPG 피처(HR/HRV/SI/RR)와 EDA tonic 통계를 추출합니다.
4. 번들의 `keep` 컬럼만 선택하고, 결측은 `imputer`(median) → `scaler`로 학습과 동일하게 변환합니다.
5. `RandomForestClassifier.predict_proba`로 class별 확률을 계산합니다.
6. SSE `event: craving`으로 class `0/1/2`를 broadcast합니다.

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

마지막 예측의 피처 추출/추론 디버그 정보를 확인할 수 있습니다. 앱 연동 중 값이 이상할 때 가장 먼저 확인하는 endpoint입니다.

주요 필드:

| Field | Meaning |
|---|---|
| `ready` | 모델 로드 완료 여부 |
| `modelPath` | 사용 중인 joblib 번들 |
| `modelName` | 번들 모델 종류 (`rf`) |
| `featureCount` | 모델이 사용하는 피처 수 (`keep`) |
| `classLabels` | class 라벨 |
| `lastPrediction.channels` | raw/resampled 값 범위 |
| `lastPrediction.usedFeatureCount` | 이번 윈도우에서 실제 채워진 피처 수 |
| `lastPrediction.probabilities` | class별 RandomForest 확률 |

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

모델 번들은 저장소에 커밋하지 않습니다. 로컬 실행 또는 배포 전에 아래 위치에 별도로 배치하거나 `MODEL_PATH` 환경 변수로 경로를 지정합니다.

```text
backend/model/weights/rf_dependent.joblib
```

`.pt`, `.pth`, `.onnx`, `.pkl`, `.joblib`, `.npy`, `.npz` 파일은 `backend/.gitignore`에 의해 제외됩니다.

## Docker 실행

백엔드 이미지 빌드/실행 (CPU 전용):

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
| `MODEL_PATH` | `/app/model/weights/rf_dependent.joblib` | 사용할 joblib 번들 경로 |
| `INFERENCE_QUEUE_MAX` | `100` | 대기 중인 sensor window 최대 개수 |
| `SSE_KEEPALIVE_SECONDS` | `10` | SSE ping 간격 |
| `LLM_SERVER_URL` | `http://localhost:8001` | 기존 LLM proxy용 |
| `LLM_API_KEY` | empty | 기존 LLM proxy용 |

## 주요 파일

| File | Role |
|---|---|
| `app/main.py` | FastAPI route, SSE endpoint, app lifecycle |
| `app/inference.py` | 번들 로드, sensor payload 보간·피처 추출, queue worker, SSE broadcast, 윈도우별 지연시간 기록 |
| `app/inference_time.py` | 윈도우별 지연시간(comm/queue/feature/model/server) 측정 및 로그 기록 (`LatencyRecorder`) |
| `app/inference_time.txt` | latency 측정 결과 로그 (연결 시작마다 덮어쓰기, 종료 시 min/mean/max 요약) |
| `model/config.py` | 51.2Hz 피처 추출 그리드/윈도우/라벨 설정 |
| `model/features.py` | PPG(HR/HRV/SI/RR) + EDA tonic 피처 추출 (학습과 동일 로직) |
| `model/weights/rf_dependent.joblib` | 현재 서버가 사용하는 RandomForest 번들 |
| `Dockerfile` | 백엔드 컨테이너 이미지 |

## 지연시간 측정

`app/inference_time.py`의 `LatencyRecorder`가 각 sensor window의 처리 지연을 업링크→처리→다운링크 단계별로 기록합니다. 한 줄(row) 기록은 예측 결과를 앱에 SSE로 실제 전송하는 순간 `app/main.py`에서 남기므로, 서버 내부 처리시간뿐 아니라 앱까지 나가는 전송시간(`send_ms`)까지 한 줄에 담깁니다.

| Metric | Meaning |
|---|---|
| `comm_ms` | uplink 통신시간 = 서버 수신 시각 − 앱 `sentAtMs` (⚠️ 폰/서버 시계 미동기 시 오차 큼) |
| `queue_ms` | 서버 큐 대기시간 (수신 → 워커가 꺼낼 때까지) |
| `feature_ms` | PPG/GSR 피처 추출 시간 |
| `model_ms` | RandomForest 추론 시간 |
| `server_ms` | 서버 총 처리시간 (큐 진입 → 예측 완료) |
| `send_ms` | 다운링크 = 예측 완료 → SSE로 앱에 전송하는 순간 (`app/main.py`에서 측정) |

세션은 활성 `prediction-stream` 연결 수를 참조 카운트로 관리합니다. 첫 연결이 열릴 때 `app/inference_time.txt`를 새로 덮어쓰고 마지막 연결이 닫힐 때 단계별 min/mean/max 요약을 덧붙이며, 앱이 재연결하며 연결이 잠깐 겹쳐도 기록이 중간에 끊기지 않습니다.

로그 경로는 `LATENCY_LOG_PATH` 환경 변수로 바꿀 수 있으며, 기록 실패는 추론을 막지 않도록 무시됩니다.

> ⚠️ Docker로 실행하면 이 파일은 컨테이너 내부(`/app/app/inference_time.txt`)에 써집니다. 호스트에서 바로 보려면 실행 시 볼륨을 마운트하거나(`-v`) `LATENCY_LOG_PATH`를 마운트된 경로로 지정하세요.

## Debug Checklist

앱에서 결과가 이상할 때는 다음 순서로 확인합니다.

1. `GET /model/status`에서 `ready=true`인지 확인합니다.
2. `lastPrediction.channels.ppg.rawSampleCount`가 약 250(25Hz×10s)인지 확인합니다.
3. `lastPrediction.channels.gsr.rawSampleCount`가 약 10(1Hz×10s)인지 확인합니다.
4. `lastPrediction.usedFeatureCount`가 `featureCount`(67)에 근접하는지 확인합니다. 낮으면 신호 품질이 나빠 피처가 NaN으로 빠진 것입니다.
5. `docker logs`에 `Prediction failed`, `Traceback`이 있는지 확인합니다.
