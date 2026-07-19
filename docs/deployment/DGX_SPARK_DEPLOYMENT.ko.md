# NeuroTruth DGX Spark 배포 가이드

최종 갱신: 2026-07-19

## 1. 배포 구성

DGX Spark에서 다음 구성을 실행한다.

```text
Phone / Web
    │  HTTP(S) :25991
    ▼
NeuroTruth backend ── PostgreSQL
    ├─ craving model: PyTorch CUDA
    ├─ STT service: Whisper PyTorch CUDA
    └─ FactorizePhys rPPG API: 별도 서비스, 비공개 :8000
```

Phone은 NeuroTruth 백엔드에만 연결한다. STT, PostgreSQL, FactorizePhys를 공인 인터넷에 직접 노출하지 않는다.

## 2. DGX Spark 준비

DGX Spark에는 Docker와 NVIDIA Container Toolkit이 기본 설치·구성되어 있다. NVIDIA 공식 문서: [DGX Spark NVIDIA Container Runtime](https://docs.nvidia.com/dgx/dgx-spark/nvidia-container-runtime-for-docker.html).

```bash
docker --version
docker compose version
nvidia-ctk --version
docker run --rm --gpus=all nvcr.io/nvidia/cuda:13.0.1-devel-ubuntu24.04 nvidia-smi
```

마지막 명령에서 GPU·드라이버·CUDA 정보가 보여야 한다. 기본 구성이 손상된 경우에만 [NVIDIA Container Toolkit 설치 가이드](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html)에 따라 복구한다.

## 3. 저장소와 브랜치 준비

```bash
mkdir -p ~/AI_Champion
cd ~/AI_Champion
git clone https://github.com/Neuro-AI-Lab/neurotruth.git
cd neurotruth
git fetch origin
git switch Master
git pull --ff-only origin Master
```

PR 병합 전 시험 배포라면 `git switch <검증할-브랜치>`를 사용한다. 운영 배포는 병합된 `Master`와 태그 또는 커밋 SHA를 기록한다.

## 4. 모델 파일 준비

모델은 Git에 넣지 않는다.

### 갈망 모델

다음 파일을 DGX에 복사한다.

```text
apps/backend/model/weights/final_model_win20s_high2p4/model_weights.pt
apps/backend/model/weights/final_model_win20s_high2p4/model_metadata.json
```

배포 전 SHA-256을 `.env.example`의 `CRAVING_MODEL_SHA256`과 대조한다.

### Whisper PyTorch 모델

```bash
mkdir -p models/whisper-large-v3-turbo-pytorch

docker run --rm \
  -v "$PWD/models:/models" \
  python:3.12-slim \
  sh -lc 'pip install --no-cache-dir huggingface_hub && python -c "from huggingface_hub import snapshot_download; snapshot_download(repo_id=\"openai/whisper-large-v3-turbo\", local_dir=\"/models/whisper-large-v3-turbo-pytorch\")"'

test -s models/whisper-large-v3-turbo-pytorch/config.json
test -s models/whisper-large-v3-turbo-pytorch/model.safetensors
```

필요한 경우 CPU 비상용 CTranslate2 모델을 `models/whisper-large-v3-turbo`에 별도로 둔다. DGX Compose는 CPU fallback을 비활성화한다.

## 5. `.env` 작성

```bash
cp .env.example .env
chmod 600 .env
```

예시는 자리표시자다. 실제 키·비밀번호·IP를 문서나 Git에 저장하지 않는다.

```dotenv
POSTGRES_USER=<db-user>
POSTGRES_PASSWORD=<strong-random-password>
POSTGRES_DB=neurotruth

DATA_ENCRYPTION_KEYS_B64=v1:<base64-encoded-32-byte-key>
DATA_ENCRYPTION_CURRENT_KEY_ID=v1
JWT_SIGNING_KEY=<at-least-32-random-bytes>
ADMIN_SIGNUP_CODE=<one-time-admin-code>

APP_ENV=production
ALLOW_INSECURE_HTTP=false
BACKEND_HOST_PORT=25991
WEB_HOST_PORT=45511

AWS_BEARER_TOKEN_BEDROCK=<bedrock-token>
BEDROCK_MODEL_ID=openai.gpt-5.5
AWS_REGION=us-east-1
STATE_SUMMARY_AI_ENABLED=false
REPORT_AI_ENABLED=false

CRAVING_INFERENCE_DEVICE=auto

STT_ENABLED=true
STT_PYTORCH_MODEL_PATH=/home/<user>/AI_Champion/neurotruth/models/whisper-large-v3-turbo-pytorch
STT_MODEL_PATH=/home/<user>/AI_Champion/neurotruth/models/whisper-large-v3-turbo
STT_DEVICE=cuda
STT_ALLOW_CPU_FALLBACK=false

RPPG_ENABLED=true
RPPG_BASE_URL=http://<dgx-private-ip-or-service-name>:8000
RPPG_MAX_UPLOAD_MIB=40
RPPG_MAX_CONCURRENCY=1
```

AES 키 생성 예:

```bash
openssl rand -base64 32
openssl rand -hex 48
```

생성 결과는 비밀 관리 시스템에 보관하고 셸 기록·채팅·PR에 붙이지 않는다.

## 6. FactorizePhys rPPG 연결

FactorizePhys API는 이 저장소의 Compose 서비스가 아니다. 다음 중 하나를 준비한다.

1. DGX의 기존 비공개/LAN `:8000` 서비스 사용: `RPPG_BASE_URL=http://<DGX-LAN-IP>:8000`
2. 별도 컨테이너를 같은 Docker 네트워크에 연결: `RPPG_BASE_URL=http://rppg:8000`

요구사항:

- 백엔드에서만 접근 가능해야 한다.
- 모델 준비 상태와 health/status 응답이 정상이어야 한다.
- 업로드 평문은 tmpfs에서 처리하고 처리 후 삭제해야 한다.
- 실제 연구 데이터 사용 전 TLS 또는 신뢰할 수 있는 사설망을 사용한다.

FactorizePhys 설치·모델 패키징 자체는 이 저장소의 배포 범위 밖이다.

## 7. Compose 검증과 실행

```bash
docker compose \
  --env-file .env \
  -f apps/db/docker-compose.yml \
  -f apps/db/docker-compose.dgx.yml \
  config
```

민감 정보가 터미널 로그·CI 산출물에 남지 않도록 `config` 출력을 공유하지 않는다.

```bash
docker compose \
  --env-file .env \
  -f apps/db/docker-compose.yml \
  -f apps/db/docker-compose.dgx.yml \
  up -d --build

docker compose \
  --env-file .env \
  -f apps/db/docker-compose.yml \
  -f apps/db/docker-compose.dgx.yml \
  ps
```

백엔드는 시작 전에 `alembic upgrade head`를 실행한다. 현재 요구 revision은 `20260717_0005`다.

## 8. 배포 확인

### 컨테이너와 로그

```bash
docker compose --env-file .env -f apps/db/docker-compose.yml -f apps/db/docker-compose.dgx.yml logs --tail=200 --no-color backend
docker compose --env-file .env -f apps/db/docker-compose.yml -f apps/db/docker-compose.dgx.yml logs --tail=200 --no-color stt
```

### 공개 상태 API

```bash
curl -fsS http://127.0.0.1:25991/health
curl -fsS http://127.0.0.1:25991/ready
curl -fsS http://127.0.0.1:25991/model/status
curl -I http://127.0.0.1:45511/
```

`/api/stt/status`와 `/api/rppg/status`는 인증이 필요하므로 환자 access token으로 확인한다.

```bash
curl -fsS -H "Authorization: Bearer <patient-access-token>" http://127.0.0.1:25991/api/stt/status
curl -fsS -H "Authorization: Bearer <patient-access-token>" http://127.0.0.1:25991/api/rppg/status
```

DGX STT 정상 핵심값:

```json
{
  "enabled": true,
  "available": true,
  "engine": "pytorch",
  "requestedDevice": "cuda",
  "actualDevice": "cuda:0",
  "fallback": false
}
```

`actualDevice=cpu`는 DGX 운영 정상 상태가 아니다.

### GPU 확인

```bash
docker compose --env-file .env -f apps/db/docker-compose.yml -f apps/db/docker-compose.dgx.yml exec -T stt python -c 'import torch; print(torch.cuda.is_available()); print(torch.cuda.get_device_name(0))'
```

## 9. 네트워크와 모바일 설정

- 외부에는 백엔드 HTTPS만 노출한다.
- Phone의 API base URL은 `https://<backend-host>`로 설정한다.
- Phone이 `:8000` rPPG 또는 `:8001` STT에 직접 연결하면 안 된다.
- HTTP는 통제된 LAN 실험에서만 사용하고 이 경우 `ALLOW_INSECURE_HTTP=true`를 명시적으로 설정한다.

## 10. 업데이트

```bash
cd ~/AI_Champion/neurotruth
git fetch origin
git switch Master
git pull --ff-only origin Master

docker compose --env-file .env -f apps/db/docker-compose.yml -f apps/db/docker-compose.dgx.yml config
docker compose --env-file .env -f apps/db/docker-compose.yml -f apps/db/docker-compose.dgx.yml up -d --build
docker compose --env-file .env -f apps/db/docker-compose.yml -f apps/db/docker-compose.dgx.yml ps
```

이미지·설정 변경 전 PostgreSQL 볼륨과 암호화 저장 볼륨을 백업한다. `.env`와 모델 디렉터리는 교체하지 않는다.

## 11. 롤백

1. 장애 배포의 쓰기를 중단한다.
2. 직전 정상 Git 태그 또는 SHA로 전환한다.
3. 동일 `.env`와 모델을 사용해 이미지를 재빌드한다.
4. 스키마 downgrade는 실행하지 않는다. `20260717_0005`는 20초 캡처 데이터가 있을 때 downgrade를 거부한다.
5. DB 복원이 필요하면 배포 전 백업으로 새 볼륨을 구성해 검증한 뒤 전환한다.

```bash
git switch --detach <last-known-good-sha>
docker compose --env-file .env -f apps/db/docker-compose.yml -f apps/db/docker-compose.dgx.yml up -d --build
```

## 12. 문제 해결

| 증상 | 확인 |
|---|---|
| `ready` 실패 | DB health, Alembic head, 암호화 키, 저장 볼륨 권한 |
| STT `available=false` | 모델 mount, CUDA 접근, STT 로그 |
| STT `actualDevice=cpu` | DGX override 적용 여부와 `STT_ALLOW_CPU_FALLBACK=false` |
| rPPG 상태 불가 | `RPPG_BASE_URL`, FactorizePhys health, 사설망·방화벽 |
| rPPG 403 | 환자 동의 4종 |
| rPPG 422 | MP4, 19.5~20.5초, 영상 메타데이터·품질 |
| 백엔드 재시작 반복 | Alembic 오류, 필수 환경변수, 모델 SHA-256 |
