# NeuroTruth DGX Spark STT 및 Android TTS 설치 가이드

이 문서는 NeuroTruth의 음성 기능을 DGX Spark와 Android 휴대폰에 배포하는 절차를 설명합니다.

> **2026-07-18 변경:** DGX Spark의 운영 STT는 PyTorch CUDA 방식으로
> 전환되었습니다. GPU 설치와 재배포는
> [`DGX_STT_PYTORCH_CUDA.ko.md`](./DGX_STT_PYTORCH_CUDA.ko.md)를 먼저
> 따르세요. 아래 CTranslate2 절차는 로컬 CPU fallback과 이전 배포 참고용입니다.

## 1. 구성

```text
사용자 음성
  -> Android 앱에서 최대 30초 녹음
  -> NeuroTruth 백엔드 인증 API
  -> DGX 내부 PyTorch Whisper CUDA STT
  -> 편집 가능한 입력창에 인식 결과 표시
  -> 사용자가 확인 후 전송
  -> 챗봇 텍스트 응답
  -> Android 로컬 TTS로 읽기
```

- 운영 STT 모델은 DGX Spark에 `STT_PYTORCH_MODEL_PATH`로 설치합니다.
- `apps/db/docker-compose.dgx.yml`은 `STT_DEVICE=cuda`와 `STT_ALLOW_CPU_FALLBACK=false`를 강제합니다. CUDA가 준비되지 않으면 CPU로 숨지 않고 실패합니다.
- 운영 설치·빌드·검증은 [`DGX_STT_PYTORCH_CUDA.ko.md`](./DGX_STT_PYTORCH_CUDA.ko.md)를 따릅니다. 아래 3~7절은 base Compose에서만 사용하는 CTranslate2 CPU fallback 참고 절차입니다.
- TTS는 Android `TextToSpeech`를 사용하므로 DGX에 TTS 모델을 설치하지 않습니다.
- STT 컨테이너의 `8001` 포트는 Docker 내부에서만 사용하며 외부에 공개하지 않습니다.
- 음성 원본은 백엔드와 STT 컨테이너의 tmpfs에서만 처리하고 요청 종료 후 삭제합니다.
- DB에는 사용자가 확인하고 전송한 최종 텍스트와 입력 방식만 저장합니다.

## 2. 사전 조건

DGX Spark에 다음 항목이 준비되어 있어야 합니다.

- NeuroTruth 저장소: `~/AI_Champion/neurotruth`
- Docker Engine 및 Docker Compose 플러그인
- NVIDIA 드라이버 및 NVIDIA Container Toolkit
- 모델 다운로드를 위한 인터넷 연결
- NeuroTruth 루트 `.env`

GPU가 호스트에서 인식되는지 확인합니다.

```bash
nvidia-smi
```

Docker 컨테이너에서도 GPU가 인식되는지 확인합니다.

```bash
sudo docker run --rm --gpus all \
  nvidia/cuda:12.8.1-base-ubuntu24.04 \
  nvidia-smi
```

두 명령 모두 DGX GPU 정보를 표시해야 합니다.

## 3. CTranslate2 CPU fallback 모델 다운로드

이 절부터 7절까지는 DGX 운영 절차가 아닙니다. GPU를 사용할 수 없는 로컬 base Compose fallback에서 faster-whisper용 CTranslate2 모델을 사용합니다. DGX 운영 모델은 위의 PyTorch CUDA 전환 문서를 따르세요.

- 모델: `large-v3-turbo`
- Hugging Face 저장소: `mobiuslabsgmbh/faster-whisper-large-v3-turbo`
- 실행 설정: 한국어 고정, `beam_size=1`, VAD 활성화
- 장치 설정: CPU INT8 고정

프로젝트 루트로 이동해 모델 폴더를 만듭니다.

```bash
cd ~/AI_Champion/neurotruth
mkdir -p models/whisper-large-v3-turbo
```

다음 명령으로 모델을 다운로드합니다. 공개 모델이므로 일반적으로 Hugging Face 토큰은 필요하지 않습니다.

```bash
sudo docker run --rm \
  -v "$PWD/models:/models" \
  python:3.12-slim \
  sh -lc 'pip install --no-cache-dir huggingface_hub && python -c "from huggingface_hub import snapshot_download; snapshot_download(repo_id=\"mobiuslabsgmbh/faster-whisper-large-v3-turbo\", local_dir=\"/models/whisper-large-v3-turbo\")"'
```

다운로드 결과를 확인합니다.

```bash
ls -lah models/whisper-large-v3-turbo
test -s models/whisper-large-v3-turbo/model.bin
test -s models/whisper-large-v3-turbo/config.json
```

최소한 `model.bin`, `config.json`, tokenizer 관련 파일이 있어야 합니다.

> `models/whisper-large-v3-turbo/`는 실행 환경에 별도로 설치하는 대용량 모델입니다. 모델 파일과 실제 `.env`는 GitHub에 커밋하지 마세요.

## 4. CPU fallback `.env` 설정

모델 폴더의 절대 경로를 확인합니다.

```bash
cd ~/AI_Champion/neurotruth
realpath models/whisper-large-v3-turbo
```

출력된 절대 경로를 저장소 루트의 `~/AI_Champion/neurotruth/.env`에 넣습니다.

```dotenv
STT_ENABLED=true
STT_MODEL_PATH=/home/사용자명/AI_Champion/neurotruth/models/whisper-large-v3-turbo
STT_DEVICE=cpu
STT_ALLOW_CPU_FALLBACK=true
STT_CONNECT_TIMEOUT_SECONDS=5
STT_READ_TIMEOUT_SECONDS=120
STT_MAX_UPLOAD_MIB=10
```

주의사항:

- `STT_MODEL_PATH`에는 CTranslate2 fallback 폴더의 `realpath` 결과를 그대로 사용합니다. 운영 DGX PyTorch 경로는 `STT_PYTORCH_MODEL_PATH`입니다.
- `.env`에서 `~` 또는 `$HOME`을 사용하지 않습니다.
- `.env`는 비밀값을 포함하므로 GitHub에 올리지 않습니다.
- 컨테이너 내부에서는 모델이 `/models/large-v3-turbo`로 읽힙니다.

설정이 Compose에 올바르게 반영되는지 확인합니다.

```bash
sudo docker compose \
  -f apps/db/docker-compose.yml \
  config
```

출력에 실제 비밀값이 포함될 수 있으므로 이 결과를 GitHub 이슈나 채팅에 그대로 올리지 마세요.

## 5. Base Compose CPU fallback 빌드 및 실행

STT와 백엔드를 다시 빌드해 실행합니다.

```bash
cd ~/AI_Champion/neurotruth

sudo docker compose \
  -f apps/db/docker-compose.yml \
  up -d --build stt backend
```

전체 NeuroTruth 서비스를 함께 실행하려면 서비스 이름을 생략합니다.

```bash
sudo docker compose \
  -f apps/db/docker-compose.yml \
  up -d --build
```

서비스 상태와 STT 로그를 확인합니다.

```bash
sudo docker compose \
  -f apps/db/docker-compose.yml \
  ps

sudo docker compose \
  -f apps/db/docker-compose.yml \
  logs --tail=100 stt
```

## 6. CPU fallback 모델 mount 확인

STT 컨테이너가 모델 파일을 읽을 수 있는지 확인합니다.

```bash
sudo docker compose \
  -f apps/db/docker-compose.yml \
  exec -T stt sh -lc \
  'ls -lah /models/large-v3-turbo && test -s /models/large-v3-turbo/model.bin'
```

오류 없이 파일 목록이 표시되면 mount가 정상입니다.

## 7. CPU fallback STT 상태 확인

STT는 외부 포트를 열지 않으므로 백엔드 컨테이너를 통해 내부 상태를 확인합니다.

```bash
sudo docker compose \
  -f apps/db/docker-compose.yml \
  exec -T backend python -c \
'import httpx, json; print(json.dumps(httpx.get("http://stt:8001/health", timeout=10).json(), ensure_ascii=False, indent=2))'
```

CPU fallback으로 정상 로드된 경우의 예시입니다.

```json
{
  "enabled": true,
  "available": true,
  "model": "whisper-large-v3-turbo",
  "requestedDevice": "cpu",
  "actualDevice": "cpu",
  "engine": "ctranslate2",
  "fallback": false,
  "errorCode": null
}
```

DGX 운영 상태는 이 예시와 달라야 합니다. 운영 검증에서는 `engine=pytorch`, `requestedDevice=cuda`, `actualDevice=cuda:0`, `fallback=false`를 요구합니다. `docker-compose.dgx.yml`과 CPU fallback 절차를 함께 사용하지 마세요.

모바일 앱이 사용하는 환자 인증 상태 API를 확인할 수 있습니다. Access token은 명령 기록에 직접 입력하지 않고 임시 환경변수로 전달합니다.

```bash
read -rsp 'Patient access token: ' ACCESS_TOKEN
echo
curl -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  http://127.0.0.1:25991/api/stt/status
unset ACCESS_TOKEN
```

`GET /api/stt/status`와 실제 transcription은 모두 환자 인증을 요구합니다. Status는 기능/모델 가용성만 반환하고 credential이나 모델 artifact 경로를 노출하지 않습니다. Transcription은 환자 인증에 더해 `voice` 동의와 active session을 요구합니다. 비로그인 status 요청이 `401`인 것은 정상입니다.

## 8. Android TTS 설정

현재 NeuroTruth는 서버 TTS API를 사용하지 않습니다. AI 답변은 Android 기기의 `TextToSpeech` 엔진이 한국어로 읽습니다.

삼성 휴대폰의 일반적인 설정 경로는 다음과 같습니다. 기기 및 One UI 버전에 따라 이름이 조금 다를 수 있습니다.

```text
설정
  -> 일반
  -> 글자 읽어주기 또는 텍스트 음성 변환
  -> 기본 엔진 선택
  -> 한국어 음성 데이터 설치
```

Google 음성 서비스 또는 Samsung TTS 중 한국어를 지원하는 엔진을 선택합니다.

앱에서 확인합니다.

1. 챗봇을 시작합니다.
2. AI 응답 말풍선의 `듣기`를 누릅니다.
3. 재생 중 `정지`가 동작하는지 확인합니다.
4. 자동 읽기 옵션을 켠 뒤 다음 AI 응답이 자동 재생되는지 확인합니다.
5. 챗봇 화면을 벗어났을 때 음성이 중지되는지 확인합니다.

TTS가 실패해도 텍스트 답변은 그대로 표시되어야 합니다.

## 9. 실제 기기 인수 테스트

다음 순서로 휴대폰과 DGX 연동을 확인합니다.

1. 백엔드 `/health`와 `/ready`가 정상인지 확인합니다.
2. STT 상태에서 `available=true`인지 확인합니다.
3. 환자 계정에서 `voice` 선택 동의를 활성화합니다.
4. Android 마이크 권한을 허용합니다.
5. 챗봇 마이크 버튼으로 5~10초의 한국어 음성을 녹음합니다.
6. 인식 결과가 입력창에 들어오는지 확인합니다.
7. 텍스트를 수정할 수 있고 자동 전송되지 않는지 확인합니다.
8. 전송 후 AI 답변이 정상적으로 표시되는지 확인합니다.
9. `듣기/정지` 및 자동 읽기를 확인합니다.
10. STT 처리 후 컨테이너 tmpfs에 녹음 파일이 남지 않는지 확인합니다.

tmpfs 잔존 파일을 확인합니다.

```bash
sudo docker compose \
  -f apps/db/docker-compose.yml \
  -f apps/db/docker-compose.dgx.yml \
  exec -T stt sh -lc 'find /dev/shm/neurotruth-stt -maxdepth 1 -type f -print'

sudo docker compose \
  -f apps/db/docker-compose.yml \
  -f apps/db/docker-compose.dgx.yml \
  exec -T backend sh -lc 'find /dev/shm/neurotruth-stt -maxdepth 1 -type f -print'
```

정상적으로 요청이 끝났다면 파일이 출력되지 않아야 합니다.

## 10. 오류 점검표

| 증상 또는 코드 | 원인 | 확인 방법 |
| --- | --- | --- |
| `stt_disabled` | `STT_ENABLED=false` | DGX `.env` 수정 후 `stt`, `backend` 재생성 |
| `stt_cuda_unavailable` | DGX PyTorch CUDA 초기화 실패; 운영 override는 CPU fallback을 금지 | `DGX_STT_PYTORCH_CUDA.ko.md`, `nvidia-smi`, `model.safetensors`, `docker-compose.dgx.yml`, STT 로그 확인 |
| `stt_model_missing` | PyTorch 또는 CTranslate2 model mount 오류 | DGX는 `STT_PYTORCH_MODEL_PATH`의 `config.json`/`model.safetensors`, CPU fallback은 `STT_MODEL_PATH`의 `model.bin`/`config.json` 확인 |
| `stt_model_load_failed` | Base Compose CTranslate2 fallback 모델 불완전/로드 실패 | `STT_DEVICE=cpu`, `STT_ALLOW_CPU_FALLBACK=true`, `model.bin`, STT 로그 확인 |
| `engine=ctranslate2`, `actualDevice=cpu` | 명시적으로 선택한 base-Compose CPU fallback | 운영 DGX라면 override 누락이므로 `docker-compose.dgx.yml`을 포함해 재배포 |
| HTTP `403` | `voice` 동의 없음 | 앱의 동의 설정에서 음성 동의 활성화 |
| HTTP `413` | 음성 파일이 10 MiB 초과 또는 녹음이 너무 김 | 최대 30초 이내로 다시 녹음 |
| HTTP `415` | `.m4a`, `.wav` 이외 형식 | Android 녹음 형식 또는 multipart content type 확인 |
| HTTP `422` | 음성 없음 또는 인식 가능한 발화 없음 | 마이크 권한과 녹음 내용을 확인 후 재시도 |
| HTTP `503` | STT service/model unavailable | `/api/stt/status`, 내부 `/health`, STT 로그 확인 |
| HTTP `504` | STT timeout | DGX 부하와 `STT_READ_TIMEOUT_SECONDS` 확인 |
| TTS 한국어 미지원 | 기기 TTS 한국어 데이터 없음 | Android 설정에서 한국어 음성 데이터 설치 |

## 11. 중지 및 비활성화

STT만 중지하려면 `.env`를 다음과 같이 변경합니다.

```dotenv
STT_ENABLED=false
```

그다음 컨테이너를 재생성합니다.

```bash
sudo docker compose \
  -f apps/db/docker-compose.yml \
  -f apps/db/docker-compose.dgx.yml \
  up -d --force-recreate stt backend
```

STT가 비활성화되어도 텍스트 챗봇과 Android 로컬 TTS는 계속 사용할 수 있습니다.

## 12. GitHub 업로드 범위

GitHub에는 다음 코드와 문서를 올립니다.

- `apps/stt-service/`
- `apps/backend/app/api/v1/routes/stt.py`
- `apps/backend/app/services/stt.py`
- `apps/backend/app/adapters/stt_client.py`
- STT 관련 backend/mobile 변경 사항과 테스트
- `apps/db/docker-compose.yml`
- `apps/db/docker-compose.dgx.yml`
- `.env.example`
- 이 문서

다음 항목은 올리지 않습니다.

- 실제 `.env`
- `models/whisper-large-v3-turbo/` 모델 파일
- API key, access token, 암호화 키
- 실제 환자 음성 또는 transcript
- Docker volume, DB dump, 로그

업로드 전 확인합니다.

```bash
git status --short
git check-ignore -v .env
git diff --check
```

모델 폴더가 `git status`에 표시되면 절대로 `git add .`을 실행하지 말고, 모델 폴더를 저장소 밖으로 옮기거나 로컬 Git exclude에 등록합니다.

```bash
printf '/models/whisper-large-v3-turbo/\n' >> .git/info/exclude
```

`.git/info/exclude`는 해당 DGX clone에만 적용되며 GitHub에는 올라가지 않습니다.

## 참고 자료

- [faster-whisper 공식 저장소](https://github.com/SYSTRAN/faster-whisper)
- [faster-whisper large-v3-turbo CTranslate2 모델](https://huggingface.co/mobiuslabsgmbh/faster-whisper-large-v3-turbo)
- [faster-whisper 모델 이름 매핑](https://github.com/SYSTRAN/faster-whisper/blob/master/faster_whisper/utils.py)
