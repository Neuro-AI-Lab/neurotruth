# PRD: NeuroTruth 중재 지원 플랫폼

최종 업데이트: 2026-07-19

영어 원본: [PRD_neurotruth.md](PRD_neurotruth.md)

## 제품 요약

NeuroTruth는 CBT 치료 중이거나 치료 의지가 있는 사용자를 위한 인증 기반 mobile-first 보조적 중재 연구 prototype입니다. 환자가 Android에서 직접 가입하고 기능별 동의 후 연결된 Galaxy Watch가 있으면 sensor batch를 Phone relay로 전송합니다. 연결된 Watch가 없으면 동의와 준비 상태를 확인한 뒤 사용자가 직접 20초 얼굴 rPPG 측정을 실행할 수 있고, Watch가 연결돼 있어도 선택적인 시점 측정으로 사용할 수 있습니다. Backend는 동의한 raw window를 암호화해 보존하고 deterministic prediction/alert/safety/first-intervention logic을 수행하며, Bedrock 자율 대화와 evidence-linked state inference, report status, audit record를 저장합니다. 의료행위, 진단, 치료 또는 긴급 대응을 대체하지 않습니다. Web surface는 관리자 전용입니다.

## 목표

- 환자 직접 가입 즉시 활성화, role 기반 인증과 rotating refresh session.
- Sensor, prediction, alert, AUQ, conversation, intervention, state inference, report, audit data의 완전한 동의 기반 수집.
- 민감 content와 raw sensor file의 AES-256-GCM 암호화 및 model/prompt 추적.
- Deterministic alert, safety, first-intervention rule. LLM은 allowlist 범위의 중재 대화와 제공된 근거 요약만 담당.
- 한 turn에 짧은 질문 최대 하나, 암호화된 asked/refused topic ledger, 필수 questionnaire coverage가 없는 구조화된 자율 대화.
- Watch credential 없이 Phone이 backend 인증과 선택적 Watch relay를 소유하며, 수동 rPPG 경로에는 연결된 Watch가 필요하지 않음.
- 환자/관리자 `24h|7d|30d` dashboard, reason-gated reveal, 임시 비밀번호, 확인 기반 삭제, intervention toggle, timeout control.

## 비목표

- 백그라운드 또는 연속 카메라 측정, 자동 반복 rPPG, 시점 카메라 결과를 연속 Watch monitoring으로 취급하는 동작. 카메라 rPPG는 기본 ON이지만 동의, 서버 준비 상태, 통제된 실제 기기 검증 gate를 유지합니다.
- 진단, 약물 안내, 임상적 확실성 또는 치료 효과 주장.
- 즉각적인 갈망 감소 연출, CBT 효과 주장 또는 intervention timing의 인과 해석.
- 갈망 모델 재학습, class balancing, threshold 실험, moving-average label 실험.
- Android나 Watch의 LLM 직접 접근.
- 관리자 실시간 chat, 긴급 queue/출동, 자동 연락 또는 사람 연결 보장.
- Bulk dataset/report download, legacy data backfill 또는 신규 schema-to-legacy 변환.

## 핵심 흐름

```text
Phone 환자 가입/login/동의
  <- Watch sensor batch
  -> 인증된 암호화 sensor ingestion
  <- prediction SSE + deterministic alert
  -> 사용자 대화 승인 + 선택형 AUQ
  -> safety check + deterministic first intervention
  -> slot completion 없는 구조화된 자율 대화
  -> state inference + 암호화된 비동기 report status
  -> 환자/관리자 dashboard
```

Watch는 backend에 직접 연결하지 않습니다. Phone은 Android Keystore 기반 저장소에 refresh credential을 보관하고 `401` 후 한 번 회전하며 복구 실패 시 logout합니다.

Phone은 Wear OS connected-node 목록으로 Watch 가용성을 판단합니다. 빈 목록이 확정되면 수동 20초 얼굴 측정을 주 동작으로 표시하고, connected/checking/error 상태에서는 사실에 맞는 비대체 안내를 유지합니다. 카메라 결과는 `camera_rppg` 출처로 Phone에만 남고 이후 Watch prediction이 도착하면 자연스럽게 최신 결과가 될 수 있습니다.

## 동의와 데이터

약관, 개인정보, 민감정보 동의는 필수입니다. 생체신호, AI 분석, 알림, report generation은 독립적인 선택 gate입니다. 선택형 한국어 STT는 사용자가 확인하기 전까지 편집 가능한 text만 만들고, 확인 후 일반 message 경로에 들어가며, AI 음성 출력은 Android 로컬 TTS입니다. 동의 변경은 immutable snapshot을 추가합니다. 철회는 신규 처리를 차단하지만 과거 record를 자동 삭제하지 않습니다.

Sensor window는 UUID `clientWindowId`를 요구합니다. 동일 retry는 idempotent하며 다른 content로 재사용하면 거부합니다. Backend는 canonical JSON → gzip → AES-256-GCM으로 backend 전용 volume에 저장하고 각 recording을 prediction/alert row와 연결합니다.

## Session과 에이전트 동작

환자당 active UUID session은 최대 하나입니다. 신규 session은 `safety_check`와 `intervention_dialogue`를 거칩니다. 수동 종료는 `completed`, 기본 3,600초 inactivity timeout은 `abandoned`입니다. AUQ는 선택이며 건너뛰어도 대화, state inference, 종료, 동의 기반 report 생성을 차단하지 않습니다.

신규 session은 `session_slots`를 만들거나 legacy memory를 갱신하지 않고 `slots`, `missingSlots`, `handoffReady`를 반환하지 않습니다. 암호화된 dialogue ledger가 같은 질문의 우회 반복을 막으면서 관련 topic 순서는 유연하게 유지합니다. 과거 13-slot session은 읽을 수 있지만 수정할 수 없고 backfill하지 않습니다.

Deterministic rule이 현재 근거에서 첫 allowlisted intervention을 선택합니다. 이후 LLM은 allowlisted type만 제안할 수 있으며 전달된 제안은 각각 version과 함께 저장합니다. `interventionsEnabled=false`는 일반 intervention 문구/row만 억제하고 safety guidance와 state inference는 유지합니다.

State inference는 최신 유효 model class를 `low|mid|high|unknown`으로 복사하고 구체적인 evidence ID를 기록합니다. AUQ, 대화, intervention event는 별도 근거이며 합성 임상 점수를 만들지 않습니다. LLM summary는 class를 바꿀 수 없고 독립적으로 실패할 수 있습니다.

## 안전

즉각적 위험 dialogue는 119와 자살예방 상담전화 109를 보여주고 관리자 도움 요청 사실을 기록할지 한 번 물을 수 있습니다. 수락/거절을 감사 기록에 남기고 대화를 계속합니다. 이 기록은 실시간 monitoring이 아니며 연락이나 대응을 보장하지 않는다고 명시해야 합니다.

## 관리자 경험

Web console은 code-gated admin 가입/login, 환자 summary/timeline, `24h|7d|30d` class/AUQ/event/intervention/report-status dashboard, nonblank-reason 민감정보 reveal, 사유 기반 임시 비밀번호, 정확한 UUID 확인 삭제, 전역 `interventionsEnabled`/`chatTimeoutSeconds` 설정을 지원합니다. Dashboard에서 raw PPG 또는 report body를 노출하지 않으며 환자 UI나 export endpoint도 없습니다.

선택적 카메라 rPPG 확장은 capture metadata summary, 사유 기반 감사 inline 영상 재생, 사유와 정확한 UUID를 요구하는 삭제를 추가합니다. 영상 다운로드 버튼이나 endpoint는 없습니다. 권한 있는 열람자가 렌더링된 byte를 기술적으로 보존할 수 있으므로 최소 권한, 운영 정책, 감사가 필요합니다.

## 보안과 배포

인증·암호화 stack은 PostgreSQL 16+, Alembic head, versioned AES keyring, JWT signing key, admin signup code, encrypted sensor volume, HTTPS를 요구합니다. 명시적인 insecure HTTP는 development/test에서만 허용됩니다. 배포 시 fresh `postgres_data_v25`와 `encrypted_sensor_data`를 만들고 legacy volume을 migration 없이 rollback용으로 백업·보존합니다.

카메라 rPPG는 수락된 성공/실패 얼굴 영상을 관리자 감사 삭제 전까지 AES-256-GCM으로 암호화 영구 보존합니다. Backend만 private DGX service를 호출합니다. 공동 배포는 DGX를 internal-only로 유지하고 upload tmpfs와 `DELETE_UPLOADED_VIDEO=true`를 요구합니다. `RPPG_ENABLED=true`가 기본이고 `false`는 배포 off switch로 남으며, 활성 배포도 storage, DGX, model, runtime readiness를 모두 통과하기 전에는 fail closed합니다.

## 인수 요약

- Auth/role/refresh replay, consent gate, encrypted round trip/tamper failure, sensor idempotency, ownership을 테스트합니다.
- Slot 없는 신규 session, legacy read-only, 반복 방지, safety 이후 계속, 선택형 AUQ, deterministic first intervention, state inference, report-status 격리를 fake adapter로 테스트합니다.
- Phone auth recovery, consent, authenticated upload/SSE, UUID resume, logout cleanup, Watch relay를 테스트합니다.
- 환자/관리자 dashboard, PPG ownership 거부, 관리자 no-PPG 응답, reason audit, settings, 임시 비밀번호, deletion retry를 테스트합니다.
