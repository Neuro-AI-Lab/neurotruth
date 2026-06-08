# My Project

## 서버 구성

| 서버 | 컨테이너 | 포트 |
|------|----------|------|
| 서버 A (GPU) | llm-server | 8001 |
| 서버 B (백엔드) | backend | 8000 |
| 서버 B (백엔드) | frontend | 3000 |
| 서버 B (백엔드) | db | 내부 전용 |

## 처음 시작하기

### 1. 레포 클론
```bash
git clone https://github.com/<your-org>/<your-repo>.git
cd my-project
```

### 2. 환경변수 설정
```bash
cp .env.example .env
# .env 파일을 열어서 실제 값으로 수정
nano .env
```

### 3. 서버 B 실행 (백엔드 + 프론트 + DB)
```bash
docker-compose -f docker-compose.backend.yml up -d --build
```

### 4. 서버 A 실행 (GPU 서버)
```bash
docker-compose -f docker-compose.gpu.yml up -d --build
```

## 자주 쓰는 명령어

```bash
# 컨테이너 상태 확인
docker-compose -f docker-compose.backend.yml ps

# 로그 확인
docker-compose -f docker-compose.backend.yml logs -f backend
docker-compose -f docker-compose.backend.yml logs -f db

# 컨테이너 재시작
docker-compose -f docker-compose.backend.yml restart backend

# 전체 중지
docker-compose -f docker-compose.backend.yml down

# DB 데이터까지 완전 삭제 (주의!)
docker-compose -f docker-compose.backend.yml down -v
```

## GitHub Secrets 등록 (CI/CD용)

GitHub 레포 → Settings → Secrets and variables → Actions 에서 등록:

| Secret 이름 | 설명 |
|-------------|------|
| `BACKEND_SERVER_HOST` | 서버 B의 IP 또는 도메인 |
| `GPU_SERVER_HOST` | 서버 A의 IP 또는 도메인 |
| `SERVER_USER` | SSH 접속 유저명 (예: ubuntu) |
| `SSH_PRIVATE_KEY` | SSH 개인키 내용 (`cat ~/.ssh/id_rsa`) |

## 컨테이너 간 통신 구조

```
[Mobile App] ──HTTPS──▶ [frontend :3000]
                              │ /api/ 프록시
                              ▼
                         [backend :8000] ──내부──▶ [db :5432]
                              │
                         HTTP 요청 (인터넷)
                              ▼
                         [llm-server :8001] (서버 A)
```
