# DGX Spark STT PyTorch CUDA 전환

## 목적

기존 `faster-whisper` 컨테이너는 DGX Spark ARM64에서 CPU 전용
`CTranslate2` wheel을 설치해 `actualDevice=cpu`로 동작했다. 현재 STT
서비스는 DGX에서 Hugging Face Whisper를 PyTorch CUDA로 실행한다. 모바일과
백엔드 API는 변경하지 않는다.

DGX 배포에서는 CPU 자동 전환을 막는다. CUDA 초기화가 실패하면 느린 CPU
서비스로 숨지 않고 `stt_cuda_unavailable`을 반환한다.

## 1. PyTorch Whisper 모델 받기

```bash
cd ~/AI_Champion/neurotruth
mkdir -p models/whisper-large-v3-turbo-pytorch

sudo docker run --rm \
  -v "$PWD/models:/models" \
  python:3.12-slim \
  sh -lc 'pip install --no-cache-dir huggingface_hub && python -c "from huggingface_hub import snapshot_download; snapshot_download(repo_id=\"openai/whisper-large-v3-turbo\", local_dir=\"/models/whisper-large-v3-turbo-pytorch\")"'
```

필수 파일을 확인한다.

```bash
test -s models/whisper-large-v3-turbo-pytorch/config.json
test -s models/whisper-large-v3-turbo-pytorch/model.safetensors
```

기존 `models/whisper-large-v3-turbo` CTranslate2 모델은 로컬 CPU 비상용으로
남겨 둔다. 두 모델 폴더 모두 Git에 올리지 않는다.

## 2. `.env` 설정

```dotenv
STT_ENABLED=true
STT_PYTORCH_MODEL_PATH=/home/neuroai/AI_Champion/neurotruth/models/whisper-large-v3-turbo-pytorch
STT_MODEL_PATH=/home/neuroai/AI_Champion/neurotruth/models/whisper-large-v3-turbo
STT_CONNECT_TIMEOUT_SECONDS=5
STT_READ_TIMEOUT_SECONDS=120
STT_MAX_UPLOAD_MIB=10
```

경로는 `realpath` 결과에 맞게 수정한다. DGX Compose override가 컨테이너의
`STT_DEVICE=cuda`와 `STT_ALLOW_CPU_FALLBACK=false`를 강제한다.

## 3. STT만 다시 빌드하고 실행

```bash
cd ~/AI_Champion/neurotruth

sudo docker compose \
  --env-file .env \
  -f apps/db/docker-compose.yml \
  -f apps/db/docker-compose.dgx.yml \
  build --no-cache stt

sudo docker compose \
  --env-file .env \
  -f apps/db/docker-compose.yml \
  -f apps/db/docker-compose.dgx.yml \
  up -d --force-recreate stt backend
```

첫 시작은 약 1.6GB 모델을 GPU로 올리므로 평소보다 오래 걸릴 수 있다.

## 4. GPU 실행 확인

```bash
sudo docker compose \
  --env-file .env \
  -f apps/db/docker-compose.yml \
  -f apps/db/docker-compose.dgx.yml \
  exec -T stt python -c \
'import torch; print(torch.__version__); print(torch.cuda.is_available()); print(torch.cuda.get_device_name(0))'
```

내부 상태를 확인한다.

```bash
sudo docker compose \
  --env-file .env \
  -f apps/db/docker-compose.yml \
  -f apps/db/docker-compose.dgx.yml \
  exec -T backend python -c \
'import httpx, json; print(json.dumps(httpx.get("http://stt:8001/health", timeout=10).json(), ensure_ascii=False, indent=2))'
```

정상 결과의 핵심 값은 다음과 같다.

```json
{
  "enabled": true,
  "available": true,
  "engine": "pytorch",
  "requestedDevice": "cuda",
  "actualDevice": "cuda:0",
  "fallback": false,
  "fallbackReason": null,
  "errorCode": null
}
```

`actualDevice=cpu`는 DGX 배포에서 정상 상태가 아니다. `available=false`이면
다음 로그에서 오류 유형을 확인한다.

```bash
sudo docker compose \
  --env-file .env \
  -f apps/db/docker-compose.yml \
  -f apps/db/docker-compose.dgx.yml \
  logs --tail=200 --no-color stt
```

## 5. 실제 음성 속도 측정

모바일에서 같은 5~10초 한국어 문장을 세 번 전송한다. 첫 요청은 CUDA
warm-up 때문에 느릴 수 있으므로 두 번째와 세 번째 처리 시간을 기준으로
판단한다. 상태 API가 `cuda:0`인데도 느리면 오디오 길이, 동시 요청, DGX GPU
사용률을 함께 확인한다.
