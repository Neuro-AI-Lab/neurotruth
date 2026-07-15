# NeuroTruth V2.5 인증, 암호화 데이터, 에이전트 및 데이터베이스 재설계

- **언어 역할:** Korean mirror spec
- **명세 상태:** Finalized
- **영어 원본:** `neurotruth/docs/specs/2026-07-14-neurotruth-v2-5-auth-encrypted-agent-db-redesign-spec.md`
- **한국어 미러:** `neurotruth/docs/specs/2026-07-14-neurotruth-v2-5-auth-encrypted-agent-db-redesign-spec.ko.md`
- **날짜:** 2026-07-14
- **구현 모드:** `IMPLEMENTATION_ORCHESTRATION`

## 0. Codex 구현 인계

```text
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: neurotruth/docs/specs/2026-07-14-neurotruth-v2-5-auth-encrypted-agent-db-redesign-spec.md
korean_mirror_spec: neurotruth/docs/specs/2026-07-14-neurotruth-v2-5-auth-encrypted-agent-db-redesign-spec.ko.md
authoritative_spec: english_source_spec
worker_rule: source_code_changes_must_be_delegated_to_worker_subagents
</codex_feature_planner_handoff>
```

## 1. 요약

NeuroTruth의 임시 6개 테이블 영속성 계층을 새로운 V2.5 데이터베이스로 교체하고 백엔드, Android 휴대폰, 워치 릴레이, 관리자 웹 및 LLM 에이전트를 인증된 환자 소유권과 암호화된 동의 기반 영속성으로 전환한다. 환자는 직접 가입하고 즉시 활성화된다. 시스템은 동의된 모든 센서, 예측, 알림, AUQ, 채팅, 슬롯, 개입, 메모리, 보고서 및 감사 데이터를 저장한다. 이전 PostgreSQL 볼륨은 백업된 채 변경 없이 유지하며 기존 데이터는 마이그레이션하지 않는다.

## 2. 목표

- Alembic으로 관리하는 하나의 권위 있는 18개 테이블 V2.5 스키마를 확립한다.
- 환자 직접 가입, 관리자 코드 가입, 역할 권한 부여, 회전식 refresh 세션 및 안전한 모바일 credential 저장을 추가한다.
- 모든 민감 내용과 보존되는 원시 센서 윈도우를 백엔드 AES-256-GCM으로 암호화한다.
- idempotent 센서 수집과 추적 가능한 모델 버전을 통해 동의된 실험 데이터를 완전하게 수집한다.
- 현재 에이전트 슬롯을 승인된 13개 슬롯의 비반복 대화 흐름 및 결정론적 개입 선택으로 교체한다.
- 민감정보 접근, 삭제, 개입 활성화 및 세션 timeout을 위한 관리자 제어와 완전한 감사 추적을 제공한다.

## 3. 비목표

- 기존 런타임 데이터를 V2.5로 마이그레이션하거나 backfill하는 것.
- 음성 STT/TTS, 오디오 캡처 또는 오디오 artifact 구현.
- rPPG 캡처 또는 추론.
- 관리자 실시간 채팅, 긴급 대응 큐, 자동 긴급 연락 또는 사람과의 연결이 보장된다는 주장.
- 대량 데이터셋 다운로드 API 또는 자동 보존 기한 삭제.
- 롤백 후 V2.5 데이터를 backport하는 것.

## 4. 사용자 및 사용 사례

- **환자:** 직접 가입하고 로그인하며 동의를 관리하고 휴대폰/워치 센서 윈도우를 스트리밍하고 예측과 알림을 수신하며 AUQ 및 13개 주제 채팅을 완료하고 적격 개입을 받고 보고서를 열람하며 중단된 활성 세션을 재개한다.
- **관리자:** 회전식 가입 코드로 가입하고 웹 콘솔에 로그인하며 환자 상태와 타임라인을 검토하고 기록된 사유가 있을 때만 민감 내용을 복호화하며 임시 비밀번호를 발급하고 전역 설정을 변경하고 감사되는 환자 삭제를 시작한다.
- **시스템 운영자:** 데이터베이스, 암호화, JWT, 저장소 및 전송 구성을 제공하고 신규 배포, readiness 검사, 백업 및 롤백을 수행한다.

## 5. 최종 결정

- 새 데이터베이스를 사용하고 기존 볼륨은 보존하되 변경하거나 가져오지 않는다.
- V2.5의 16개 업무 테이블에 `auth_sessions`와 `system_settings`를 추가하여 사용한다.
- 환자는 직접 가입하고 즉시 활성화된다. 관리자는 현재 가입 코드가 필요하다.
- 민감한 데이터베이스 필드와 원시 센서 파일을 버전이 지정된 AES-256-GCM 키로 백엔드에서 암호화하며 평문 fallback은 허용하지 않는다.
- 동의된 모든 원시 센서 윈도우를 암호화된 백엔드 전용 볼륨에 보존한다.
- 15분 access JWT와 회전식 30일 opaque refresh token을 사용하며 refresh-token 해시만 저장한다.
- 인증되지 않은 문자열 세션 식별자를 JWT 환자가 소유하는 서버 발급 UUID 세션으로 교체한다.
- 정상 완료 전에 13개 슬롯 모두가 `answered`, `unknown` 또는 `declined` 상태에 도달해야 하며 완료된 주제를 다시 질문하지 않는다.
- 턴 수 제한을 두지 않는다. 수동 종료와 구성 가능한 기본 1시간 timeout을 사용한다.
- 모든 슬롯이 완료된 후에만 정상 개입을 허용한다. rule engine이 유형을 선택하고 LLM은 선택된 유형의 문장만 작성한다.
- 안전 안내는 전역 일반 개입 설정과 독립적으로 유지한다.
- 안전하지 않은 HTTP는 명시적인 개발 모드에서만 허용한다.

## 6. 기능 요구사항

### 6.1 계정, 인증 및 동의

- 환자 가입은 `active` 사용자를 생성한다. 관리자 가입은 현재 관리자 가입 코드 해시를 검증한다. 사용자는 `active`, `disabled`, `pending_deletion` 상태와 `must_change_password`를 지원한다.
- 비밀번호는 Argon2id를 사용한다. 로그인은 signed 15분 access JWT와 30일 opaque refresh token을 발급한다. 모든 refresh에서 토큰을 회전하며 replay가 발생하면 해당 token family를 폐기한다. 로그아웃, 비밀번호 변경, 비활성화 및 삭제는 해당 세션을 폐기한다.
- 동의 스냅샷은 append-only이다. 약관, 개인정보 및 민감정보 동의는 필수다. 생체신호 수집, AI 분석, 알림 및 보고서는 선택 사항이다. 음성은 사용할 수 없음으로 표시하고 비활성화한다. 철회하면 이전 데이터를 삭제하지 않고 새로운 기능 사용을 차단한다.

### 6.2 완전한 수집 및 예측 추적성

- 승인된 센서 업로드에는 `clientWindowId`가 필요하고 환자를 인증하며 암호화된 원시 윈도우를 저장하고 해당 `sensor_recordings` 행을 생성하거나 재사용하며 이후 prediction 및 alert 레코드를 연결한다.
- `(patient_id, client_window_id)`는 고유하다. 재시도는 저장이나 추론을 중복하지 않고 이전에 승인된 resource/result를 반환한다.
- AUQ 점수와 검색 가능한 prediction 메타데이터는 평문으로 유지하며 원시 AUQ 답변과 민감한 근거 내용은 암호화한다.
- prediction, dialogue, slot, intervention, report 및 memory 작업은 실제 `model_versions` 행과 prompt version을 참조한다.

### 6.3 세션 및 에이전트 동작

- 환자는 `created` 또는 `in_progress` 세션을 최대 하나만 가질 수 있다. 앱은 `chat_timeout_seconds` 안에 이를 재개하며 수동 조기 종료 또는 timeout은 `abandoned`로 설정한다.
- 13개 슬롯 키는 `episode_trigger`, `current_context`, `alcohol_context`, `drinking_status`, `habit_pattern`, `emotional_context`, `physical_context`, `alcohol_expectancy`, `coping_context`, `support_context`, `user_goal`, `safety_context`, `additional_context`이다.
- 암호화된 각 슬롯 payload는 `status: answered|unknown|declined`와 `data`를 포함한다. 모든 상태는 완료로 계산한다. 한 메시지는 여러 슬롯을 업데이트할 수 있다. dialogue agent는 최대 한 개의 질문을 하고 미완료 슬롯만 대상으로 하며 완료된 주제를 직접 또는 간접적으로 다시 다루지 않는다.
- 질문 우선순위는 안전, 현재 음주, 현재 환경, 알코올 접근성, 촉발 요인, 감정/신체 맥락, 기대, 습관, 대처, 지원, 목표, 추가 맥락 순이다. `handoffReady=true`와 정상 완료에는 13개 슬롯 모두가 필요하다.
- 조기 종료는 보고서 동의가 활성화된 경우에만 비동기 부분 보고서를 생성하며, 완료 및 미완료 주제를 명시적으로 기록한다. 종단 메모리는 정상 완료 후에만 업데이트한다.

### 6.4 안전 및 개입

- 안전 위험 감지 시 채팅에서 환자가 관리자 개입을 원하는지 한 번 묻는다. 수락 또는 거절을 기록하고 남은 슬롯 수집을 계속한다. 연결을 약속해서는 안 된다. 즉각적 위험 내용에는 구성된 긴급 정보를 포함하며 한국 기본값은 자살예방상담전화 109이다.
- 모든 슬롯 완료 후 정상 개입을 결정론적으로 선택한다. 알코올 접근성/거절 필요는 `leave_location|refusal_practice`, 긴장/신체적 각성은 `breathing|grounding`, 갈망 기대/습관은 `urge_surfing|attention_shift`, 이용 가능한 지원자는 `social_support`, 그 외는 `self_monitoring|hydration`에 매핑한다.
- LLM은 선택된 유형의 문장만 작성할 수 있다. `interventions_enabled=false`이면 정상 개입 문구를 출력하지 않고 `interventions` 행도 생성하지 않는다. 안전 확인과 긴급 안내는 활성 상태로 유지한다.

## 7. 사용자 경험 / UI 요구사항

- 모바일은 환자 가입, 로그인, 필수 임시 비밀번호 변경, 동의 설정, 모니터링 상태, UUID 세션 재개, 채팅 수동 종료 제어 및 보고서 상태를 제공한다. 로그아웃은 모니터링과 prediction streaming을 중지한다.
- 워치는 계속 휴대폰을 통해 릴레이하며 백엔드 credential을 직접 저장하거나 전송하지 않는다.
- 관리자 웹은 가입 코드 인증, 환자 요약/타임라인, 사유 기반 민감 내용 열람, 임시 비밀번호 할당, 수동 삭제, 개입 ON/OFF 및 채팅 timeout 구성을 제공한다.
- 비활성화되었거나 동의가 철회된 기능은 민감한 백엔드 세부정보를 노출하지 않고 사용할 수 없는 이유를 설명한다.
- 채팅은 별도의 안전 배너를 표시해서는 안 된다. 일회성 관리자 개입 질문과 긴급 안내는 대화 안에 표시한다.

## 8. API / 데이터 / 상태 요구사항

### 8.1 스키마 권위 및 상태

Git repository root의 `neurotruth_schema_definition_v2_5.md`와 `neurotruth_schema_v2_5.sql`이 repository-contained authoritative source이다. Alembic과 Compose는 이 in-repository SQL을 resolve해야 하며 parent workspace에 의존해서는 안 된다. V2.5 스키마는 기존 16개 테이블 설계에 다음을 추가한다.

- `auth_sessions`: refresh 해시, family, 회전 계보, 폐기, 만료 및 기기 메타데이터.
- `system_settings`: 최소한 `interventions_enabled`, `chat_timeout_seconds=3600` 및 관리자 가입 코드 해시.

`init.sql`은 확장만 설치한다. SQL 참조, 스키마 정의 문서 및 baseline Alembic migration은 동일한 제약, index, foreign key, enum/check 및 세션 lifecycle을 표현해야 한다.

### 8.2 공개 인증 API

- `POST /api/auth/patient/signup`
- `POST /api/auth/admin/signup`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `POST /api/auth/change-password`
- `GET /api/me`
- `PATCH /api/me`
- `POST /api/me/consents`
- `POST /api/sensor-windows`
- `GET /api/predictions/stream`
- `POST /api/sessions`
- `GET /api/sessions/{id}`
- `POST /api/sessions/{id}/messages`
- `POST /api/sessions/{id}/assessments`
- `POST /api/sessions/{id}/finish`
- `POST /api/sessions/{id}/reports`
- `GET /api/sessions/{id}/reports`

인증되지 않은 기존 `/sensor-window`, `/prediction-stream`, `/api/intervention/*` 문자열 세션 계약은 제거한다. JWT subject와 role이 소유권과 권한을 확립한다. 서버가 UUID 채팅 세션을 발급한다.

### 8.3 암호화 필드 및 파일 형식

- 데이터베이스 envelope는 ciphertext, 새로운 96-bit nonce, 128-bit tag 및 key version을 저장한다. AAD는 테이블, 컬럼, 환자 UUID 및 레코드 UUID를 결합한다.
- 원시 센서 저장 형식은 `canonical JSON -> gzip -> AES-256-GCM`이다. 데이터베이스 메타데이터에는 상대 암호화 경로, modality, sampling rate, 시간 범위, byte size, 암호화 전 SHA-256 checksum 및 암호화 메타데이터만 포함한다.
- 오류 발생 시 평문 파일이나 영구 고아 암호화 파일이 남지 않도록 파일과 데이터베이스 영속화를 조정한다.

## 9. 권한, 보안, 개인정보 및 감사

- `DATA_ENCRYPTION_KEYS_B64`는 버전이 지정된 key ring이고 `DATA_ENCRYPTION_CURRENT_KEY_ID`가 쓰기 키를 선택한다. 읽기는 저장된 버전을 선택한다. 키 누락/알 수 없는 키 및 인증 실패는 hard failure이다.
- 관리자는 기본적으로 요약을 받는다. 민감한 복호화에는 비어 있지 않은 사유가 필요하며 actor, target, reason, action 및 timestamp를 `audit_logs`에 기록한다.
- 비밀번호, 암호화 키, access/refresh token, 복호화된 내용, 원시 센서 경로, ciphertext payload 또는 provider 내부 정보를 로그로 남기지 않는다. 모델 실패는 정제된 코드이다.
- 환자 삭제에는 role 권한, 사유 및 명시적 확인이 필요하다. 계정을 `pending_deletion`으로 잠그고 세션을 폐기하며 암호화 파일 및 연결 데이터를 제거하고 계정을 tombstone 처리하며 비민감 감사 기록만 보존한다. 부분 실패는 재시도 가능한 상태로 유지한다.
- 대량 데이터셋 다운로드 endpoint는 존재하지 않는다. 선택 동의 철회는 과거 데이터를 삭제하지 않는다.
- production 시작에는 HTTPS가 필요하다. `ALLOW_INSECURE_HTTP=true`는 개발 전용이다.

## 10. 오류, 엣지 케이스 및 동시성 동작

- 데이터베이스, 필수 Alembic 리비전 또는 암호화 키를 사용할 수 없으면 readiness가 실패한다. 백엔드는 영속성 없이 조용히 계속 실행되지 않는다.
- 제시된 토큰이 다른 면에서 올바른 형식이더라도 refresh-token 재사용은 전체 family를 폐기한다.
- 두 번째 활성 세션의 동시 생성은 중복을 만들지 않고 거부하거나 기존 활성 세션을 반환한다.
- 중복 `clientWindowId` 업로드는 idempotent이다. 동일한 ID를 사용하는 충돌 payload는 이전 데이터를 덮어쓰지 않고 거부 및 감사한다.
- 암호화 envelope 손상, AAD 불일치, 알 수 없는 key version 또는 tag 실패는 정제된 실패를 반환하고 부분 평문을 절대 반환하지 않는다.
- 중단된 센서 쓰기는 임시 artifact를 정리한다. 실패한 삭제는 `pending_deletion` 상태로 남아 재시도할 수 있다.
- 에이전트/provider 실패는 세션과 완료 슬롯을 보존하고 안전한 재시도 응답을 반환하며 정제된 실패 코드만 저장한다.
- 조기 종료 보고서 job은 session/report version별로 idempotent하다. 정상 개입이 비활성화되어도 안전 응답은 계속 사용할 수 있다.

## 11. 의존성 및 구성

- PostgreSQL 16+, Alembic, Argon2id 구현, JWT signing/verification, AES-256-GCM을 지원하는 cryptography library 및 기존 backend/mobile/web stack.
- 필수 secret/configuration에는 database URL, JWT signing material, `DATA_ENCRYPTION_KEYS_B64`, `DATA_ENCRYPTION_CURRENT_KEY_ID`, 관리자 가입 코드/해시 구성, 암호화 센서 볼륨 root 및 production HTTPS 설정이 포함된다.
- 구성 가능한 값에는 `chat_timeout_seconds`(기본값 `3600`), `interventions_enabled`, access TTL(15분), refresh TTL(30일), 한국 긴급 자원 문구 및 배포 하드웨어에서 검증한 Argon2id cost parameter가 포함된다.
- Android refresh credential은 Keystore 기반 암호화 저장소를 사용한다. 워치는 인증된 휴대폰 릴레이에 의존한다.
- 자동 LLM 테스트는 fake Bedrock adapter를 사용하며 실제 모델 품질 평가는 수동이다.

## 12. 마이그레이션, 출시 및 롤백

- 기존 `postgres_data`를 백업하고 보존하며 V2.5 baseline을 이에 대해 절대 실행하지 않는다.
- `postgres_data_v25`와 백엔드 전용 암호화 센서 볼륨을 생성하고 새로운 Alembic baseline을 적용하며 설정/model version을 seed하고 클라이언트를 전환하기 전에 readiness를 검증한다.
- 기존 인증되지 않은 계약을 의도적으로 제거하므로 호환되는 backend, mobile, watch relay 및 administrator web을 함께 배포한다.
- 수집을 활성화하기 전에 fresh-stack schema, auth, encryption, client 및 Compose smoke test를 실행한다.
- 롤백은 V2.5 서비스를 중지하고 이전 이미지와 보존된 기존 볼륨을 다시 연결한다. V2.5 데이터는 역변환하지 않는다.

## 13. 구현 경계

- 소스 코드 변경은 worker sub-agent가 수행해야 한다. main agent는 명세 동기화, minimal-diff 검토, 검증 및 최종 보고를 담당한다.
- 구현을 인증, 동의, V2.5 영속성, 암호화, 완전한 센서 보존, 승인된 에이전트 동작, 관리자 제어 및 호환되는 클라이언트 변경으로 제한한다.
- 음성, rPPG, 관리자 실시간 개입, 긴급 출동, 데이터셋 export, 기존 데이터 변환 또는 자동 보존 기한 삭제를 구현하지 않는다.
- 관련 없는 사용자 변경을 보존하고 생성된 secret, credential, database, 암호화 데이터 및 build artifact가 Git에 포함되지 않도록 한다.

## 14. 인수 기준

- **AC-01 새 스키마:** 빈 PostgreSQL 16+ 데이터베이스가 필수 Alembic 리비전에 도달하고 일관된 18개 테이블 계약, check, foreign key, index 및 one-active-session 제약을 포함한다.
- **AC-02 필수 readiness:** DB, migration 또는 encryption-key 구성이 누락되면 readiness가 실패하며 API는 영속화되지 않은 모드로 계속 실행되지 않는다.
- **AC-03 암호화:** 보호 필드와 센서 파일에 평문이 포함되지 않는다. 고유 nonce, AAD binding, 혼합 key version 및 성공적인 round trip을 검증하며 ciphertext, AAD 또는 tag 변조는 거부된다.
- **AC-04 직접 가입 및 역할:** 환자는 직접 가입하고 즉시 로그인할 수 있다. 활성 가입 코드 없이는 관리자 가입이 실패하며 patient/admin 권한 경계를 강제한다.
- **AC-05 토큰 보안:** refresh rotation이 작동하고 replay는 family를 폐기하며 logout/password change/disable/delete는 올바른 세션을 폐기한다.
- **AC-06 동의 gating:** 필수 동의를 강제하고 선택 기능을 독립적으로 gate하며 변경 시 불변 snapshot을 생성하고 철회는 새로운 처리만 중지한다.
- **AC-07 완전한 수집:** 인증되고 동의된 센서 윈도우는 암호화되어 보존되고 `clientWindowId`로 중복 제거되며 해당 prediction 및 alert와 연결되고 인증된 환자에게 귀속된 상태를 유지한다.
- **AC-08 에이전트 완료:** 13개 슬롯 모두 `answered`, `unknown`, `declined`를 지원하고 multi-slot 추출이 작동하며 완료된 주제를 반복하지 않고 13개가 모두 완료되기 전에는 정상 handoff 및 intervention이 불가능하다.
- **AC-09 세션 종료:** 중단된 세션은 구성된 timeout 안에 재개되고 수동 조기 종료와 timeout은 `abandoned`가 되며 동의된 부분 보고서는 누락 주제를 나열하고 memory는 정상 완료 시에만 업데이트된다.
- **AC-10 안전 동작:** 개입 제안은 한 번만 묻고 수락/거절을 거짓 약속 없이 기록하며 슬롯 대화는 계속되고 intervention이 전역에서 꺼져 있어도 긴급 안내는 계속 제공된다.
- **AC-11 결정론적 개입:** rule engine이 유형을 선택하고 LLM은 이를 변경할 수 없으며 전역 설정은 일반 문구와 영속화를 모두 억제한다.
- **AC-12 관리자 제어:** 사유 기반 decryption, 설정 변경, 임시 비밀번호 및 삭제는 role로 보호되고 감사되며 삭제 실패는 재시도 가능 상태로 남고 dataset-download endpoint는 존재하지 않는다.
- **AC-13 클라이언트 전환:** 모바일 signup/login/consent/token recovery/logout과 UUID session resume가 작동하고 watch relay는 phone credential을 사용하며 admin web은 승인된 제어만 노출한다.
- **AC-14 전송:** 안전하지 않은 HTTP는 명시적인 개발 모드에서만 시작되며 production 모드에서는 거부된다.
- **AC-15 롤백:** 기존 DB 볼륨은 복원 가능한 상태로 유지되며 구현 단계에서 이를 변경하거나 backfill하지 않는다.

## 15. 검증 계획

- 백엔드 network-free 테스트: 새 migration, 제약, lifecycle 전이, authentication/role, token rotation/replay, consent, AES-GCM round trip/key rotation/tampering, 암호화 파일 일관성, sensor idempotency, prediction/alert link, audit 및 deletion retry.
- Fake-Bedrock 에이전트 테스트: 13-slot extraction, multi-slot message, unknown/declined 완료, 반복 방지, safety acceptance/refusal, partial report, intervention mapping/toggle, model-version link 및 정제된 실패.
- Android 테스트: signup/login, Keystore credential recovery, forced password change, consent gating, 인증된 upload/SSE, logout cleanup 및 UUID session resume. 워치 credential 없이 watch relay를 검증한다.
- 웹 테스트: 관리자 signup-code 인증, 권한 부여, timeline, 사유 기반 decryption, setting, temporary password, deletion 및 audit 생성.
- Compose smoke: 새 V2.5 및 암호화 센서 볼륨, readiness, end-to-end 인증 수집 및 보존된 기존 볼륨 복원. 실제 LLM 품질은 수동 평가로 유지한다.

## 16. 위험 및 미해결 참고사항

- 이 명세에 해결되지 않은 제품 결정은 없다.
- 선택한 알고리즘을 약화하지 않고 배포 하드웨어에서 Argon2id cost parameter를 benchmark해야 한다.
- 전체 원시 데이터 보존은 저장 공간 및 침해 영향을 증가시킨다. 암호화 볼륨 용량과 운영자 백업 보안을 모니터링해야 한다.
- 하나의 손상된 백엔드 프로세스가 활성 복호화 키에 접근할 수 있다. 최소 권한 배포와 secret rotation은 운영 요구사항으로 유지한다.
- 안전 흐름은 요청을 기록하지만 사람의 개입을 전달하지 않는다. UI와 prompt는 그와 다른 의미를 암시해서는 안 된다.
- API hard cut은 조정된 클라이언트 배포가 필요하며 보존된 기존 stack만이 유일한 롤백 경로다.
- **운영상 주의사항:** 사용자의 현재 `.env`에는 새로운 V2.5 key name이 없다. 구현과 격리 검증은 완료되었지만 필수 V2.5 secret 및 configuration이 제공되기 전까지 실제 배포는 release-ready가 아니다. 이는 구현 blocker가 아니라 운영 readiness 요구사항이다.

## 17. 구현 체크리스트 / 진행 기록

| Phase | Status | Record |
| --- | --- | --- |
| P1 | Complete | 승인된 범위와 최종 결정 사항을 기록함. |
| P2 | Complete | 영어 권위 명세를 확정함. |
| P3 | Complete | 한국어 mirror를 동기화하고 backend/schema, Android, administrator web 및 documentation 소스 구현을 위임된 worker sub-agent를 통해 완료함. |
| P4 | Complete | Main-agent 검증 완료: backend `115 passed`, Android `50 passed`, React production build compile, spec-pair validator PASS. Biosignal raw-only 수집, AI prediction, notification alert 표시/저장, report에 대한 독립 consent gate를 검증함. 실제 격리 PostgreSQL 16 instance가 정확한 18-table/index 계약과 함께 Alembic revision `20260715_0001`에 도달했고 runtime readiness는 `true`였음. Live dummy patient signup, JWT 발급 및 consent 복원이 성공했고 암호화된 이름 저장소에 평문이 없으며 refresh credential은 SHA-256 hash로 저장됨. Legacy route는 없었고 `.env`는 변경되지 않았음. Docker smoke는 고유 이름의 temporary resource를 사용하고 검증 후 정리했으며 legacy volume은 건드리지 않았음. 실제 배포에는 Section 16의 `.env` 운영상 주의사항이 적용됨. |

## 18. 개정 이력

| Date | Revision | Description |
| --- | --- | --- |
| 2026-07-14 | 1.0 | 승인된 V2.5 재설계 계획으로 영어 권위 명세를 확정함. |
| 2026-07-15 | 1.1 | 동기화된 worker 구현과 최종 검증 근거를 기록하고 남은 배포 configuration 주의사항을 문서화함. |
