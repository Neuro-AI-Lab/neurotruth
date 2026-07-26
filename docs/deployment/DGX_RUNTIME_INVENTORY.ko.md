# NeuroTruth DGX Spark 배포·접속 인계서

최종 갱신: 2026-07-26

이 문서는 DGX Spark 배포를 재현하고 운영 상태를 확인하기 위한 인계 자료다. 실제 API key, 비밀번호, refresh token, JWT, AES key, SSH 개인키는 이 문서와 Git에 저장하지 않는다. 민감값은 DGX의 권한 제한된 `.env`와 연구실이 승인한 비밀 관리 수단에 보관한다.

## 1. 현재 인수 상태

| 항목 | 확인 결과 |
|---|---|
| 저장소 | `https://github.com/Neuro-AI-Lab/neurotruth.git` |
| DGX 작업 경로 | `/home/neuroai/AI_Champion/neurotruth` |
| 요구 DB revision | `20260717_0005` |
| 갈망 모델 | `Conv1DNet`, 20초·1,024점·2채널, CUDA |
| 갈망 모델 SHA-256 | `2493e5d75fcdc9b066b341deb47f81c9db49aac37c626619b7afe1a6b2fc354c` |
| STT | Whisper large-v3-turbo, PyTorch CUDA |
| TTS | Android 기기 내장 `TextToSpeech`; DGX 모델 없음 |
| rPPG | FactorizePhys, DGX CUDA, backend만 접근 |
| 실제 Phone/Watch 흐름 | 2026-07-26 기준 Watch 센서 → Phone → backend prediction → 양쪽 상태·알림까지 확인 |
| 미완료 확인 | DGX `.env`의 15분 alert cooldown 반영 후 재시험 필요 |

배포 완료 기록에는 반드시 실제 배포한 Git commit SHA와 배포 시각을 추가한다.

```text
DEPLOYED_GIT_SHA=<40자리 SHA>
DEPLOYED_AT_UTC=<ISO-8601>
DEPLOYED_BY=<담당자>
```

로컬 작업 트리가 수정 중인 상태라면 해당 작업 트리를 그대로 DGX에 복사하지 않는다. 검증된 commit을 push한 뒤 DGX에서 그 SHA를 checkout한다.

## 2. 시스템 연결 구조

```text
Phone
  └─ http://223.194.33.26:58441
       └─ Router TCP forwarding
            └─ 192.168.68.50:25991
                 └─ NeuroTruth backend container :25991
                      ├─ PostgreSQL db:5432
                      ├─ STT stt:8001
                      ├─ FactorizePhys 192.168.68.50:8000
                      └─ Bedrock Mantle / Runtime API

Internal web
  └─ DGX host :45511 → nginx container :3000 → backend:25991
```

Phone은 공개 NeuroTruth backend만 호출한다. Phone에 rPPG, STT, PostgreSQL 주소나 자격증명을 넣지 않는다.

## 3. IP·포트 목록

| 대상 | 주소 | 공개 범위 | 용도 |
|---|---|---|---|
| 모바일 API | `http://223.194.33.26:58441` | 외부 실험망 | Phone의 유일한 server base URL |
| DGX backend | `192.168.68.50:25991` | LAN/host | router의 `58441` 전달 대상 |
| 관리자 web | `http://192.168.68.50:45511` | 내부만 | nginx web |
| FactorizePhys rPPG | `http://192.168.68.50:8000` | backend 내부/LAN만 | 20초 얼굴 영상 분석 |
| STT | `http://stt:8001` | Docker network만 | 한국어 음성 인식 |
| PostgreSQL | `db:5432` | Docker network만 | 업무 DB |
| PostgreSQL host debug | `192.168.68.50:44551` → container `5432` | 필요 시 LAN만 | DB 도구 연결; 공인망 공개 금지 |

모바일 설정 파일:

```text
apps/mobile/app/src/main/assets/server_config.properties
api_base_url=http://223.194.33.26:58441
```

현재 연결은 통제된 연구 실험용 HTTP다. 실제 참여자 데이터로 운영하기 전에는 HTTPS와 서버 인증서 검증을 적용해야 한다.

## 4. SSH 접속 정보

확인된 정보와 연구실에서 채워야 할 정보를 구분한다.

| 항목 | 값 |
|---|---|
| DGX hostname/alias | `spark-02db` |
| LAN IP | `192.168.68.50` |
| SSH user | `neuroai` |
| SSH port | `<연구실 확인 필요; 기본값으로 추정하지 말 것>` |
| 외부 SSH host 또는 VPN | `<연구실 확인 필요>` |
| host key fingerprint | `<최초 접속 전 별도 채널로 확인>` |
| 인증 방식 | SSH key 권장 |
| 개인키 위치 | `<개인 PC 또는 승인된 secret manager>` |
| sudo 정책 | `<연구실 확인 필요>` |

접속 예시:

```bash
ssh -p <confirmed-port> neuroai@<confirmed-host>
cd ~/AI_Champion/neurotruth
```

문서에 비밀번호, 개인키 본문, 복구 코드 또는 sudo 비밀번호를 기록하지 않는다.

## 5. 비밀값·API key 인벤토리

DGX의 `/home/neuroai/AI_Champion/neurotruth/.env`에 저장하고 권한을 `600`으로 제한한다.

| 환경 변수 | 필수 | 소유/발급 주체 | 비밀 관리 기록 |
|---|---:|---|---|
| `POSTGRES_PASSWORD` | 예 | NeuroTruth 운영 담당 | secret manager 항목명만 기록 |
| `DATA_ENCRYPTION_KEYS_B64` | 예 | 데이터 보안 담당 | key ID별 32-byte AES key; 값 공유 금지 |
| `DATA_ENCRYPTION_CURRENT_KEY_ID` | 예 | 데이터 보안 담당 | 현재 key ID; 비밀값 자체는 아님 |
| `JWT_SIGNING_KEY` | 예 | backend 운영 담당 | 최소 32-byte random |
| `ADMIN_SIGNUP_CODE` | 예 | 관리자 운영 담당 | 1회성/회전 가능 |
| `AWS_BEARER_TOKEN_BEDROCK` | 예 | AWS/Bedrock 담당 | 채팅·로그·Linear에 값 금지 |
| `BEDROCK_MODEL_ID` | 예 | AI 담당 | 현재 `openai.gpt-5.5` |
| `AWS_REGION` | 예 | AWS 담당 | 현재 `us-east-1` |
| `STT_PYTORCH_MODEL_PATH` | STT 사용 시 | DGX 담당 | 모델 경로이며 key 아님 |
| `RPPG_BASE_URL` | rPPG 사용 시 | DGX 담당 | 내부 endpoint; 모바일 노출 금지 |

현재 STT와 FactorizePhys에는 별도 API key가 없다. Docker 내부 또는 제한된 LAN 접근으로 보호한다. 향후 gateway 인증을 추가하면 해당 credential을 이 표에 추가한다.

비밀 보관 확인란:

```text
[ ] DGX .env 권한이 600이다.
[ ] .env가 Git 추적 대상이 아니다.
[ ] Bedrock token이 연구실 secret manager에 백업됐다.
[ ] AES keyring과 JWT key가 서버와 분리된 안전한 위치에 백업됐다.
[ ] 담당자·회전일·만료일을 secret manager metadata에 기록했다.
[ ] 터미널 캡처와 Linear에 실제 값이 없다.
```

## 6. DGX `.env` 필수 계약

아래는 변수 이름과 비민감 기본값만 보여준다.

```dotenv
POSTGRES_USER=<secret-reference>
POSTGRES_PASSWORD=<secret-reference>
POSTGRES_DB=neurotruth
POSTGRES_HOST_PORT=44551

DATA_ENCRYPTION_KEYS_B64=<secret-reference>
DATA_ENCRYPTION_CURRENT_KEY_ID=v1
JWT_SIGNING_KEY=<secret-reference>
ADMIN_SIGNUP_CODE=<secret-reference>

APP_ENV=development
ALLOW_INSECURE_HTTP=true
BACKEND_HOST_PORT=25991
WEB_HOST_PORT=45511

AWS_BEARER_TOKEN_BEDROCK=<secret-reference>
BEDROCK_MODEL_ID=openai.gpt-5.5
AWS_REGION=us-east-1

CRAVING_INFERENCE_DEVICE=auto

ALERT_DANGER_THRESHOLD=0.75
ALERT_DANGER_STREAK=3
ALERT_MAX_GAP_SECONDS=20
ALERT_COOLDOWN_SECONDS=900

STT_ENABLED=true
STT_PYTORCH_MODEL_PATH=/home/neuroai/AI_Champion/neurotruth/models/whisper-large-v3-turbo-pytorch
STT_DEVICE=cuda
STT_ALLOW_CPU_FALLBACK=false

RPPG_ENABLED=true
RPPG_BASE_URL=http://192.168.68.50:8000
RPPG_MAX_UPLOAD_MIB=40
RPPG_MAX_CONCURRENCY=1
```

폐기된 alert 변수 `ALERT_WINDOW_SIZE`, `ALERT_RECOMMEND_COUNT`, `ALERT_HIGH_STREAK`, `ALERT_DOWNTREND_DELTA`는 DGX `.env`에서 제거한다.

## 7. 배포·재시작

```bash
cd ~/AI_Champion/neurotruth
git fetch origin
git switch <deployment-branch-or-tag>
git pull --ff-only
git rev-parse HEAD

DC=(sudo docker compose --env-file .env -f apps/db/docker-compose.yml -f apps/db/docker-compose.dgx.yml)

"${DC[@]}" config --quiet
"${DC[@]}" up -d --build
"${DC[@]}" ps
```

Compose 결과 전체를 공유하면 secret이 노출될 수 있으므로 `config` 전체 출력은 Linear나 채팅에 붙이지 않는다.

특정 서비스 재생성:

```bash
"${DC[@]}" up -d --build --force-recreate backend
"${DC[@]}" up -d --build --force-recreate stt
```

### 7.1 DGX 15분 cooldown 반영

`.env` 전체를 출력하지 말고 DGX 로컬 편집기에서 alert 항목만 수정한다.

```bash
cd ~/AI_Champion/neurotruth
chmod 600 .env
nano .env
```

네 alert 값을 `0.75`, `3`, `20`, `900`으로 맞추고 폐기 변수 네 개는 삭제한다. 다음 명령은 허용된 alert 값만 출력하며 비밀값은 읽어 내보내지 않는다.

```bash
for key in ALERT_DANGER_THRESHOLD ALERT_DANGER_STREAK ALERT_MAX_GAP_SECONDS ALERT_COOLDOWN_SECONDS; do
  value=$(awk -F= -v key="$key" '$1 == key {value=$2} END {print value}' .env)
  printf '%s=%s\n' "$key" "${value:-MISSING}"
done
if grep -Eq '^(ALERT_WINDOW_SIZE|ALERT_RECOMMEND_COUNT|ALERT_HIGH_STREAK|ALERT_DOWNTREND_DELTA)=' .env; then
  echo "ERROR: deprecated alert variable remains"; exit 1
fi

DC=(sudo docker compose --env-file .env -f apps/db/docker-compose.yml -f apps/db/docker-compose.dgx.yml)
"${DC[@]}" config --quiet
"${DC[@]}" up -d --build --force-recreate backend
"${DC[@]}" exec -T backend python -c 'import os; expected={"ALERT_DANGER_THRESHOLD":"0.75","ALERT_DANGER_STREAK":"3","ALERT_MAX_GAP_SECONDS":"20","ALERT_COOLDOWN_SECONDS":"900"}; actual={k:os.getenv(k) for k in expected}; print(actual); raise SystemExit(0 if actual == expected else 1)'
```

마지막 Python 출력은 비민감 alert 설정 네 개만 포함해야 한다. 그중 하나라도 다르거나 명령이 실패하면 기기 시험을 시작하지 않는다.

## 8. 배포 검증

```bash
"${DC[@]}" exec -T backend alembic current
curl -fsS http://127.0.0.1:25991/health
curl -fsS http://127.0.0.1:25991/ready
curl -fsS http://127.0.0.1:25991/model/status
curl -I http://127.0.0.1:45511/
curl -fsS http://127.0.0.1:8000/health

"${DC[@]}" exec -T backend python -c \
'import httpx, json; print(json.dumps(httpx.get("http://stt:8001/health", timeout=20).json(), ensure_ascii=False, indent=2))'
```

합격 기준:

| 검사 | 합격값 |
|---|---|
| Alembic | `20260717_0005 (head)` |
| backend `/ready` | `ready=true` |
| 갈망 모델 | `ready=true`, `actualDevice=cuda:0`, checksum 일치 |
| STT | `enabled=true`, `available=true`, `engine=pytorch`, `actualDevice=cuda:0`, `fallback=false` |
| rPPG | `status=ok`, `model_loaded=true`, `device=cuda:0` |
| web | HTTP 200 |
| 외부 API | `http://223.194.33.26:58441/health` 응답 |

인증 API 상태는 실제 token 값을 출력하지 않고 HTTP status와 비민감 필드만 기록한다.

## 9. 실제 기기 인수

```text
[ ] Phone 로그인·동의
[ ] Phone에서 Watch 측정 시작
[ ] Watch PPG/GSR 수집
[ ] 20초 warm-up 후 10초마다 backend 업로드
[ ] prediction source=watch_sensor
[ ] Phone과 Watch의 단계가 동일
[ ] 위험 단계 3회 연속일 때 양쪽에 같은 alertId 알림
[ ] 첫 알림 후 15분 동안 추가 alert 없음
[ ] Phone 측정 중지 후 양쪽 foreground service 종료
[ ] rPPG 20초 → DGX → prediction → AUQ → 챗봇
[ ] STT 즉시 전송과 해당 답변 TTS
```

2026-07-26 실측에서는 핵심 센서·예측·양쪽 표시가 완주했지만 DGX 설정이 30초 cooldown을 사용해 약 30초 간격의 알림이 반복됐다. `ALERT_COOLDOWN_SECONDS=900` 반영 후 이 항목을 다시 확인해야 배포 완료로 판정한다.

재시험은 다른 환자 시험을 멈춘 뒤 위험 3회 연속으로 첫 양쪽 알림을 만든 시점부터 시작한다. 같은 `alertId`가 Phone과 Watch에 표시되는지 기록하고, 측정을 유지한 채 아래 타이머가 끝날 때까지 추가 알림이 없어야 한다.

```bash
date -u '+cooldown retest start: %Y-%m-%dT%H:%M:%SZ'
sleep 900
date -u '+cooldown retest end:   %Y-%m-%dT%H:%M:%SZ'
```

15분 동안 두 번째 알림이 오거나 갈망 이벤트가 추가되면 실패다. 첫 알림 1건, 양쪽 동일 `alertId`, 15분 추가 알림 0건을 배포 기록에 남긴다.

## 10. 장애 대응과 인계 원칙

- `/ready` 실패: DB, Alembic, AES keyring, storage volume을 먼저 확인한다.
- 대화 `502`: Bedrock token·region·model ID와 provider 로그의 비민감 오류 코드를 확인한다.
- STT unavailable: model mount, GPU visibility, `stt` 로그를 확인한다.
- rPPG unavailable: `192.168.68.50:8000/health`, tmpfs, 암호화 storage를 확인한다.
- 알림 반복: backend 컨테이너의 `ALERT_COOLDOWN_SECONDS`가 `900`인지 값만 로컬 터미널에서 확인하고 캡처에는 포함하지 않는다.
- rollback: DB downgrade를 실행하지 않고 last-known-good Git SHA와 동일 volume으로 이미지를 재생성한다.

다음 정보는 연구실 담당자가 별도 보안 채널로 완성해야 한다.

```text
[ ] SSH port
[ ] 외부 SSH/VPN 접속 주소
[ ] DGX host key fingerprint
[ ] secret manager 이름과 각 secret 항목 소유자
[ ] Bedrock token 만료일/회전 담당자
[ ] router port-forwarding 담당자
[ ] 배포 완료 Git SHA
```
