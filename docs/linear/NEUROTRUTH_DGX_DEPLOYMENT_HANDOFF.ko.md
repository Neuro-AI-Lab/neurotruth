# Linear 등록용 — NeuroTruth DGX Spark 배포·인계 및 실기기 검증

## 기본 정보

**제목**

```text
[NeuroTruth] DGX Spark Docker 배포 인계·비밀값 목록·Phone/Watch E2E 검증
```

**권장 상태**

```text
In Progress
```

**권장 우선순위**

```text
High
```

**권장 라벨**

```text
backend, dgx, deployment, android, wear-os, stt, rppg, security
```

## 설명

NeuroTruth backend, PostgreSQL, 관리자 web, Whisper STT를 DGX Spark Docker Compose로 운영하고 기존 FactorizePhys rPPG 서비스와 연결한다. 배포에 필요한 서버 주소·포트·환경 변수·SSH 확인 항목을 비밀값을 노출하지 않는 형태로 정리하고, Phone/Watch 실제 기기 흐름을 검증한다.

상세 인계 문서:

```text
docs/deployment/DGX_RUNTIME_INVENTORY.ko.md
```

## 배포 구성

| 구성요소 | endpoint/device |
|---|---|
| 외부 모바일 API | `http://223.194.33.26:58441` |
| DGX backend | `192.168.68.50:25991` |
| 내부 web | `192.168.68.50:45511` |
| 내부 FactorizePhys | `192.168.68.50:8000`, CUDA |
| Docker STT | `stt:8001`, PyTorch CUDA |
| PostgreSQL | `db:5432`; 선택적 host debug `44551→5432` |
| 모바일 | Phone은 backend만 호출 |

## 완료된 작업

- [x] backend `/health`, `/ready`, model status 확인
- [x] Alembic `20260717_0005 (head)` 확인
- [x] 갈망 모델 CUDA `actualDevice=cuda:0` 확인
- [x] STT `engine=pytorch`, `actualDevice=cuda:0`, `fallback=false` 확인
- [x] FactorizePhys `model_loaded=true`, `device=cuda:0` 확인
- [x] 관리자 web HTTP 200 확인
- [x] Phone과 Watch 최신 debug APK 설치
- [x] Phone → Watch 측정 시작
- [x] Watch 센서 → Phone Data Layer 배치 전송
- [x] Phone → backend sensor window 업로드
- [x] backend prediction → Phone/Watch 단계 표시
- [x] 실제 데이터가 있는 최근 1시간 4단계 timeline 렌더링
- [x] Phone/Watch 양쪽 알림 전달
- [x] 앱 crash·ANR 없음 확인
- [x] 비밀값 이름·보관 위치·검증 절차 문서화
- [x] PostgreSQL host mapping을 `44551:5432`로 수정
- [x] alert 환경 변수 템플릿을 3회 위험·15분 cooldown 계약으로 수정

## 남은 작업

- [ ] DGX `.env`에서 `ALERT_COOLDOWN_SECONDS=900` 적용
- [ ] 폐기된 alert 환경 변수 제거
- [ ] backend image 재빌드·재생성
- [ ] 위험 3회 연속 후 첫 알림만 생성되는지 확인
- [ ] 15분 동안 추가 Phone/Watch alert가 없는지 확인
- [ ] SSH port, 외부 SSH/VPN 주소, host key fingerprint를 보안 채널에서 확인
- [ ] secret manager 항목 소유자·회전일·만료일 기록
- [ ] 배포 commit SHA와 배포 시각 기록
- [ ] 운영 전 HTTPS 적용 여부 결정

## 실기기 테스트 결과

2026-07-26에 Galaxy Phone `SM-S926N`과 Galaxy Watch `SM-L320`으로 다음 흐름을 확인했다.

```text
Phone 측정 시작
→ Watch SensorTrackingService
→ PPG/EDA 포함 센서 수집
→ Wear Data Layer
→ Phone MonitoringService
→ 20초 window / 10초 cadence
→ DGX backend prediction
→ Phone 최신 단계와 최근 1시간 timeline
→ Watch 요약과 Phone/Watch 알림
```

핵심 파이프라인은 완주했다. 다만 Phone과 Watch에 약 30초 간격으로 여러 알림이 생성됐다. backend 코드 기본값은 900초지만 DGX `.env`의 기존 `ALERT_COOLDOWN_SECONDS=30`이 이를 덮어쓴 것으로 판단한다.

## 재배포 명령

```bash
cd ~/AI_Champion/neurotruth

DC="sudo docker compose --env-file .env -f apps/db/docker-compose.yml -f apps/db/docker-compose.dgx.yml"

$DC config --quiet
$DC up -d --build --force-recreate backend stt
$DC ps
$DC exec -T backend alembic current

curl -fsS http://127.0.0.1:25991/health
curl -fsS http://127.0.0.1:25991/ready
curl -fsS http://127.0.0.1:8000/health
```

`docker compose config` 전체 출력과 `.env` 내용은 Linear에 첨부하지 않는다.

## 인수 기준

- [ ] 배포한 Git SHA가 문서와 일치한다.
- [ ] backend·STT·rPPG가 모두 CUDA에서 준비된다.
- [ ] Phone은 외부 backend 주소만 호출한다.
- [ ] PostgreSQL, STT, rPPG가 공인망에 직접 노출되지 않는다.
- [ ] 실제 API key·비밀번호·AES/JWT key·SSH 개인키가 Git/Linear/log에 없다.
- [ ] 위험 단계 3회 연속에서 alert 1건이 생성된다.
- [ ] 첫 alert 이후 15분 cooldown이 유지된다.
- [ ] Phone과 Watch가 같은 `alertId`를 중복 없이 표시한다.
- [ ] 측정 중지 시 양쪽 foreground service가 종료된다.
- [ ] 배포·장애·rollback 담당자가 인계 문서를 통해 재현할 수 있다.

## 보안 주의

- Linear에는 secret의 이름과 담당자만 기록한다.
- 실제 값은 DGX `.env`와 승인된 secret manager에만 보관한다.
- SSH 비밀번호와 개인키는 첨부하지 않는다.
- participant data를 공인 HTTP로 전송하는 상태를 운영 완료로 간주하지 않는다.

## 관련 파일

```text
docs/deployment/DGX_RUNTIME_INVENTORY.ko.md
docs/deployment/DGX_SPARK_DEPLOYMENT.ko.md
docs/deployment/MOBILE_DEVICE_TEST_GUIDE.ko.md
apps/db/docker-compose.yml
apps/db/docker-compose.dgx.yml
.env.example
apps/mobile/app/src/main/assets/server_config.properties
```
