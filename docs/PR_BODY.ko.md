# Pull Request

## 권장 제목

```text
feat: V22 갈망 대시보드와 AUQ 0~48 계약 정렬
```

## 변경 목적

V22 모바일 화면·API 명세에 맞춰 백엔드, 환자용 Kotlin 앱, 데이터 계약을 함께 정렬합니다.

- 최근 1시간 갈망 확률을 10초 단위로 조회하고 모바일 선 그래프로 표시합니다.
- 갈망 상태를 `안전 · 관찰 · 주의 · 심각` 네 단계와 승인된 문구로 통일합니다.
- AUQ 응답을 문항별 `0~6`, 총점 `0~48` 계약으로 전환합니다.
- Watch와 카메라 예측의 출처 및 최신 결과 우선순위를 명확히 합니다.
- 모든 신규 세션은 선택형 AUQ를 거쳐 자유대화로 진입하고, 활성 세션은 그대로 재개합니다.

## 주요 변경 사항

### 백엔드

- `GET /api/me/craving-probability-series?range=1h`
  - 최근 60분, 10초 bucket, 최대 360점
  - 데이터가 없는 구간은 생성하거나 보간하지 않음
- Watch prediction과 SSE에 `source="watch_sensor"`를 추가했습니다.
- 카메라 prediction은 기존 `source="camera_rppg"`를 유지합니다.
- AUQ 신규 요청은 `version="2.0"`, 응답 8개, 문항별 `0..6`, 총점 `0..48`만 허용합니다.
- 계약이 맞지 않으면 `422 invalid_auq_scale`을 반환합니다.
- AUQ bucket에 `averageScore`를 추가하고 `averageNormalizedScore`는 호환용으로 유지합니다.

### AUQ 기존 데이터 변환

암호화된 기존 `1..7 / 8..56` AUQ를 `0..6 / 0..48`로 변환하는 확인형 CLI를 추가했습니다.

```powershell
docker compose --env-file .env -f apps/db/docker-compose.yml exec -T backend `
  python -m app.maintenance.migrate_auq_zero_based --dry-run

docker compose --env-file .env -f apps/db/docker-compose.yml exec -T backend `
  python -m app.maintenance.migrate_auq_zero_based `
  --confirm CONVERT-AUQ-TO-0-48
```

- 단일 transaction과 advisory lock을 사용합니다.
- 암호화 answers를 AAD 검증 후 새로운 nonce로 재암호화합니다.
- 한 건이라도 변조됐거나 비표준 계약이면 전체 rollback합니다.
- 결과는 `audit_logs`에 기록합니다.
- 스키마 컬럼 변경이 없어 Alembic revision은 추가하지 않습니다.

### 모바일 앱

- 홈에 최신 갈망 단계, 안내 문구, 측정 시각, `Watch|카메라` 출처를 표시합니다.
- 정확한 확률 대신 다음 단계와 문구를 사용합니다.

| class-1 확률 | 단계 | 사용자 문구 |
| --- | --- | --- |
| `< 0.25` | 안전 | 아무 문제 없어요! |
| `< 0.50` | 관찰 | 관찰이 필요해요, 심각하진 않아요! |
| `< 0.75` | 주의 | 주의가 필요해요, 술이 드시고 싶으신가요? |
| `≥ 0.75` | 심각 | 갈망이 심해보여요. 챗봇과 대화를 시작할까요? |

- Watch 연결 중에는 얼굴 측정 버튼을 비활성화하고 이유를 표시합니다.
- 지연된 rPPG 결과가 더 최신 Watch 결과를 덮어쓰지 않습니다.
- rPPG 완료 이벤트는 job당 한 번만 세션·화면 이동에 반영합니다.
- 알림, 챗봇 탭, rPPG 완료에서 신규 세션은 `AUQ 작성/건너뛰기 → 자유대화`로 이동합니다.
- 활성 세션은 AUQ를 다시 요구하지 않고 재개합니다.
- AUQ UI/API payload를 문항별 `0~6`, 총점 `0~48`, version `2.0`으로 변경했습니다.

### 모바일 대시보드

- 최근 1시간 갈망 확률 선 그래프: 0~100%, 10초 단위, 누락 구간 단절
- 오늘 24시간 `안전 · 관찰 · 주의 · 심각` 중첩 막대
- 최근 7일·30일 갈망 알림 이벤트
- 오늘·7일·30일 AUQ `0~48` 막대
- 실시간/과거 PPG preview 유지
- 환자 화면의 상태 추론·리포트 상태 카드는 제거하되 백엔드 저장은 유지

## 호환성과 배포 순서

Backend와 Phone 앱은 AUQ 계약이 함께 변경되므로 동시에 배포해야 합니다.

1. PostgreSQL 백업
2. 구 Phone 앱의 AUQ 쓰기 중지
3. Backend 배포 및 `/health`, `/ready` 확인
4. AUQ 변환 CLI `--dry-run`
5. 결과 확인 후 confirmed 변환
6. 일치하는 Phone APK 배포
7. Watch·rPPG·AUQ·자유대화 흐름 확인

코드 rollback은 이전 Docker image와 APK로 수행합니다. 변환된 AUQ는 자동으로 `8~56`으로 되돌리지 않습니다.

## 검증 결과

- Backend 집중 테스트: `41 passed`
- Backend 전체 테스트: `184 passed, 1 skipped`
- Phone/Wear OS 단위 테스트 통과
- Android `lintDebug` 통과
- Phone/Wear OS `assembleDebug` 통과
- Local Docker `/health`: `ok`
- Local Docker `/ready`: `true`
- DB schema revision: `20260717_0005`
- 신규 probability-series 및 session assessment OpenAPI 경로 확인
- AUQ CLI dry-run·confirmed 실행 성공

## 남은 실기기 검증

- Watch 20초 warm-up 후 10초 cadence 업로드
- Watch 연결/해제에 따른 카메라 버튼 상태
- 얼굴 rPPG 20초 → DGX → prediction → AUQ → 챗봇
- STT 입력과 Android TTS
- 최신 Watch 결과가 지연된 카메라 결과보다 우선하는지 확인

## 보안 및 Git 제외 확인

다음 항목은 커밋하지 않습니다.

- `.env`, API 키, JWT, 암호화 키
- 로컬 PostgreSQL dump와 Docker volume
- `local.properties`, APK, Gradle build 산출물
- 환자 영상과 센서 원문

로컬 DB 테스트 세션 삭제는 Git 변경이 아닙니다. 실제 배포 DB에는 백업 후 확인형 CLI만 적용합니다.

## 체크리스트

- [x] 최근 1시간 확률 API와 모바일 그래프 반영
- [x] Watch/rPPG source 계약 반영
- [x] AUQ 0~6·0~48 검증 및 집계 반영
- [x] 암호화 AUQ 변환 CLI 추가
- [x] Watch 연결 중 카메라 비활성
- [x] 신규 세션 AUQ 작성/건너뛰기 흐름 반영
- [x] Backend 전체 테스트 통과
- [x] Phone/Wear 단위 테스트·lint·build 통과
- [x] Docker health/ready 확인
- [ ] CI 검사 통과
- [ ] 실기기 통합 테스트
- [ ] 코드 리뷰 완료

---

# Git 커밋 및 Push 가이드

현재 브랜치:

```text
feat/backend-layered-watch-rppg-final
```

## 1. V22 관련 파일만 staging

현재 작업 트리에는 별도 `Alcohol_Test` 배치 import와 과거 PPT/ZIP도 섞여 있으므로 `git add .`는 사용하지 않습니다.

```powershell
git add `
  apps/backend/app/api/v1/routes/session.py `
  apps/backend/app/repositories/postgres.py `
  apps/backend/app/services/dashboard.py `
  apps/backend/app/services/sensor.py `
  apps/backend/app/maintenance/migrate_auq_zero_based.py `
  apps/backend/tests/test_craving_bar_dashboard_v25.py `
  apps/backend/tests/test_sensor_routes_v25.py `
  apps/backend/tests/test_sessions_v25.py `
  apps/backend/tests/test_auq_zero_based_migration.py `
  apps/mobile/SERVER_API_SPEC.md `
  apps/mobile/app/src/main/java/com/example/healthsensor/AuthenticatedSessionApi.kt `
  apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt `
  apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt `
  apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringState.kt `
  apps/mobile/app/src/main/java/com/example/healthsensor/RppgModels.kt `
  apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt `
  apps/mobile/app/src/main/java/com/example/healthsensor/ServerUploader.kt `
  apps/mobile/app/src/main/java/com/example/healthsensor/WatchConnectionMonitor.kt `
  apps/mobile/app/src/test/java/com/example/healthsensor/AuthenticatedSessionApiContractTest.kt `
  apps/mobile/app/src/test/java/com/example/healthsensor/PatientDashboardParserTest.kt `
  apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt `
  apps/mobile/app/src/test/java/com/example/healthsensor/StateCheckScoringTest.kt `
  docs/specs/2026-07-21-neurotruth-v22-runtime-alignment-spec.md `
  docs/specs/2026-07-21-neurotruth-v22-runtime-alignment-spec.ko.md `
  docs/PR_BODY.ko.md
```

프런트엔드 개발 인계 Markdown도 포함하려면 다음 파일을 추가합니다.

```powershell
git add docs/neurotruth_app_design_handoff_v22.md
```

## 2. staging 검토

```powershell
git status --short
git diff --cached --stat
git diff --cached --check
```

다음 별도 작업 파일은 이 PR에 포함하지 않습니다.

```text
.env.example
apps/db/docker-compose.yml
apps/backend/app/maintenance/import_alcohol_test.py
apps/backend/tests/test_import_alcohol_test.py
docs/deployment/alcohol-test-batch-import.md
docs/deployment/alcohol-test-batch-import.ko.md
docs/specs/2026-07-21-neurotruth-alcohol-test-batch-inference-spec.md
docs/specs/2026-07-21-neurotruth-alcohol-test-batch-inference-spec.ko.md
과거 PPT·ZIP·docs/pic 자료
```

## 3. 커밋

```powershell
git commit -m "feat: V22 갈망 대시보드와 AUQ 계약 정렬"
```

## 4. Push

```powershell
git push -u origin feat/backend-layered-watch-rppg-final
```

## 5. Pull Request

- 제목은 문서 상단의 권장 제목을 사용합니다.
- 본문은 `# Pull Request`부터 `## 체크리스트`까지 복사합니다.
- Git 명령 가이드 부분은 PR 본문에서 제외해도 됩니다.
