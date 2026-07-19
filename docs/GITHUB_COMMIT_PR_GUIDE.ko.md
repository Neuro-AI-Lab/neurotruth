# NeuroTruth 최종 변경 커밋·Pull Request 가이드

최종 갱신: 2026-07-19

이 문서는 현재 작업 트리를 안전하게 세 개의 논리적 커밋으로 정리하고 GitHub PR을 만드는 절차다. 명령은 자동 실행되지 않았다.

## 1. 현재 기준

- 현재 작업 브랜치: `feat/stt-pytorch-cuda`
- 현재 HEAD: `3a7c8ea`
- `origin/Master`: `72c2d2d`에서 기존 브랜치 PR #15가 병합됨
- 따라서 기존 브랜치에 새 변경을 계속 push하지 않고 새 브랜치를 만든다.
- PR base: `Neuro-AI-Lab/neurotruth:Master`

## 2. 커밋 금지 항목

다음 파일과 데이터는 staging 전에 반드시 제외한다.

- `.env`, 실제 토큰·비밀번호·암호화 키
- `models/`, Whisper·갈망 모델 파일
- APK/AAB, Gradle·Python·Node 빌드 결과와 캐시
- `~$*.pptx`, `*.pptx.inspect.ndjson` 같은 PowerPoint 임시·검사 파일
- 로그, DB dump·볼륨, CSV/TSV export
- 실제 환자 영상·음성·전사·복호화 센서 데이터

확인:

```bash
git status --short
git status --ignored --short
git diff --check
```

`git add .` 또는 `git add -A`는 사용하지 않는다.

## 3. 새 브랜치

현재 변경을 유지한 채 새 브랜치를 만든다.

```bash
git switch -c feat/backend-layered-watch-rppg-final
git fetch origin
```

## 4. 커밋 1 — 백엔드 계층 재구성

권장 메시지:

```text
refactor(backend): split FastAPI into layered services
```

staging 대상:

```bash
git add apps/backend/app/main.py
git add apps/backend/app/adapters apps/backend/app/agents apps/backend/app/api
git add apps/backend/app/core apps/backend/app/ml apps/backend/app/models
git add apps/backend/app/prompts apps/backend/app/repositories apps/backend/app/schemas
git add apps/backend/app/services apps/backend/app/storage
git add apps/backend/app/ai apps/backend/app/security apps/backend/app/v25
git add apps/backend/app/alerts.py apps/backend/app/inference.py apps/backend/app/inference_time.py
git add apps/backend/app/inference_time.txt apps/backend/app/memory.py apps/backend/app/settings.py
git add apps/backend/tests
git add apps/backend/README.md apps/db/docker-compose.yml
git add docs/specs/2026-07-19-neurotruth-api-ai-restructure-spec.md
git add docs/specs/2026-07-19-neurotruth-api-ai-restructure-spec.ko.md
```

검사 후 커밋한다.

```bash
git diff --cached --stat
git diff --cached --check
git commit -m "refactor(backend): split FastAPI into layered services"
```

커밋 설명에 다음 호환성을 기록한다.

- 공개 URL·HTTP 메서드 유지
- DB·Alembic revision 변경 없음
- `STATE_SUMMARY_AI_ENABLED=false`, `REPORT_AI_ENABLED=false` 기본값
- 중재 대화·STT·갈망 예측·rPPG 유지

## 5. 커밋 2 — Watch 선택형 rPPG와 16KB 대응

권장 메시지:

```text
feat(mobile): add watch-optional rPPG fallback and 16KB support
```

```bash
git add .env.example
git add apps/mobile
git add docs/specs/2026-07-19-neurotruth-rppg-watch-fallback-16kb-spec.md
git add docs/specs/2026-07-19-neurotruth-rppg-watch-fallback-16kb-spec.ko.md
git diff --cached --stat
git diff --cached --check
git commit -m "feat(mobile): add watch-optional rPPG fallback and 16KB support"
```

커밋 설명:

- `connectedNodes` 기반 4상태 Watch monitor
- 미연결 시 20초 얼굴 rPPG 주 CTA, 연결 시 보조 CTA
- 동의·서버 준비 상태 gate
- `camera_rppg` Phone-only routing
- AGP 8.5.2, Gradle 8.7, ML Kit 16.1.7, CameraX 1.4.0
- 공개 API·DB 변경 없음

## 6. 커밋 3 — 문서와 V16 핸드오프

권장 메시지:

```text
docs(handoff): finalize v16 handoff and deployment guides
```

```bash
git add README.md
git add apps/web/README.md apps/db/README.md
git add docs/ai docs/prd docs/dev-environment.md
git add docs/deployment
git add docs/neurotruth_app_design_handoff_v16.md
git add docs/GITHUB_COMMIT_PR_GUIDE.ko.md
git add docs/handoff/neurotruth_app_design_handoff_v15.pptx
git add docs/handoff/neurotruth_app_design_handoff_v16.pptx
git add docs/handoff/neurotruth_app_design_handoff_v9.pptx
git add docs/handoff/neurotruth_app_design_handoff_v10.pptx
git add docs/handoff/neurotruth_app_design_handoff_v11.pptx
git add docs/handoff/neurotruth_app_design_handoff_v12.pptx
git add docs/handoff/neurotruth_app_design_handoff_v10.pptx.inspect.ndjson
git add docs/handoff/neurotruth_app_design_handoff_v11.pptx.inspect.ndjson
git add docs/handoff/neurotruth_app_design_handoff_v12.pptx.inspect.ndjson
git add docs/neurotruth_app_design_handoff_v9.md
git add docs/neurotruth_app_design_handoff_v10.md
git add docs/neurotruth_app_design_handoff_v11.md
git add docs/neurotruth_app_design_handoff_v12.md
git diff --cached --stat
git diff --cached --check
git commit -m "docs(handoff): finalize v16 handoff and deployment guides"
```

위 V9~V12와 과거 검사 파일 경로는 현재 작업 트리의 삭제를 기록하기 위한 staging이다. 새 V16 검사 파일은 staging하지 않는다.

## 7. 최종 검증과 Master 동기화

```bash
git status --short
git log --oneline --decorate -5
git rebase origin/Master
```

충돌이 발생하면 사용자 변경을 보존하면서 파일별로 해결하고 전체 검증을 다시 실행한다.

권장 검증:

```bash
python -m compileall -q apps/backend/app apps/backend/tests
python -m pytest apps/backend/tests -q

cd apps/mobile
./gradlew testDebugUnitTest lintDebug :app:assembleDebug :wear:assembleDebug
cd ../..

docker compose --env-file .env.example -f apps/db/docker-compose.yml -f apps/db/docker-compose.dgx.yml config --no-interpolate
git diff origin/Master...HEAD --check
```

Windows에서는 `./gradlew` 대신 `gradlew.bat`를 사용한다.

## 8. Push

```bash
git push -u origin feat/backend-layered-watch-rppg-final
```

## 9. Pull Request

권장 제목:

```text
refactor: finalize backend layers and watch-optional rPPG fallback
```

GitHub CLI 사용 시:

```bash
gh pr create \
  --repo Neuro-AI-Lab/neurotruth \
  --base Master \
  --head feat/backend-layered-watch-rppg-final \
  --title "refactor: finalize backend layers and watch-optional rPPG fallback" \
  --body-file PR_BODY.md
```

`PR_BODY.md`는 로컬 임시 파일로 만들고 필요하지 않으면 커밋하지 않는다. 아래 본문을 복사한다.

```markdown
## Summary

- Restructure the single FastAPI backend into API, schema, service, agent, model, repository, adapter, and storage layers.
- Add a Watch-optional 20-second face rPPG path and Android 16KB page-size compatibility.
- Refresh frontend handoff, DGX Spark deployment, and API documentation.

## Architecture

- Keeps one `apps/backend` FastAPI process.
- Removes duplicate `app.v25` and legacy inference modules after moving their behavior.
- Keeps public routes, authentication, consent rules, DB schema, and Alembic head unchanged.
- Leaves intervention dialogue, STT, craving prediction, and rPPG active.
- Keeps state-summary and report AI disabled by default through environment flags.

## Watch and rPPG behavior

- Watch connection uses `connectedNodes`, not sensor timeout.
- `DISCONNECTED` shows the 20-second face measurement as the primary CTA.
- `CONNECTED` keeps Watch monitoring and exposes face measurement as a secondary CTA.
- Consent and `/api/rppg/status` readiness remain mandatory.
- `camera_rppg` results stay Phone-only; a later Watch prediction can replace the latest display.

## Compatibility

- No public API path or HTTP method changes.
- No DB migration or Alembic revision changes.
- No new service-to-service public endpoint.
- Text and voice both use `/api/sessions/{id}/messages`; only `inputModality` differs.

## Validation

- Backend: 159 passed, 1 skipped.
- Python compile: passed.
- OpenAPI: 39 paths, 42 operations, no public `/v1` prefix.
- Android: 87 unit tests passed.
- Android lint, Phone assembleDebug, Wear assembleDebug: passed.
- 16KB verification: zipalign passed; four 64-bit ELF files use `p_align=0x4000`.
- Wear SM-L320 install and launch: passed.

## Remaining physical-device acceptance

- SM-S926N was not connected for the final run.
- Phone 16KB compatibility dialog removal remains to be confirmed on-device.
- Watch disconnect → primary rPPG CTA → 20-second job → result → Watch reconnect remains to be confirmed end to end.

## Deployment notes

- DGX uses the base Compose file plus `docker-compose.dgx.yml`.
- STT must report PyTorch, requested CUDA, actual `cuda:0`, and no CPU fallback.
- FactorizePhys remains an external/private rPPG service configured through `RPPG_BASE_URL`.

## Rollback

- Redeploy the last-known-good Git SHA and images with the same secrets and model mounts.
- Do not downgrade schema data after 20-second rPPG captures; restore a verified pre-deployment backup if DB rollback is required.

## Checklist

- [ ] CI and review checks pass
- [ ] No secrets, models, APKs, logs, or participant media are included
- [ ] API and mobile contract changes are reviewed together
- [ ] DGX smoke tests pass
- [ ] SM-S926N physical-device acceptance is completed or explicitly deferred
```

## 10. PR 전 마지막 보안 검사

```bash
git diff --name-only origin/Master...HEAD
git grep -n -I -E "(BEGIN.*PRIVATE KEY|AWS_BEARER_TOKEN_BEDROCK=.+|JWT_SIGNING_KEY=.+|DATA_ENCRYPTION_KEYS_B64=.+)" HEAD -- . ':!*.example' ':!docs/GITHUB_COMMIT_PR_GUIDE.ko.md'
```

자리표시자가 아닌 값이 검색되면 push하지 말고 해당 파일과 Git 기록을 정리한다.
