# NeuroTruth DB 스키마 v2.5 — 대화/음성 통합

## 1. 검토 결론

v2.3의 26개 테이블은 데모 범위에 비해 과도하게 정규화되어 있었습니다. 특히 모델 입력 구간, 다대다 근거 연결, 평가도구 정의, Slot 정의, Agent 실행 로그, Safety 이벤트를 각각 독립 테이블로 분리해 전체 흐름을 이해하기 어려웠습니다.

v2.4에서는 **17개 테이블**로 줄였으며, v2.4.1에서는 세션 이탈 및 재시도 제약을 보완했습니다. v2.5에서는 채팅과 음성 대화가 같은 의미상 메시지 흐름이라는 점을 반영해 `audio_recordings`와 `stt_transcriptions`를 제거하고 `message_audio_artifacts`를 추가했습니다. 인증 세션과 전역 운영 설정을 포함한 최종 구성은 **18개 업무 테이블**입니다.

## 2. 제거하거나 합친 테이블

| v2.3 테이블 | v2.4 처리 | 이유 |
|---|---|---|
| `devices` | `sensor_recordings.device_info`에 병합 | 데모에서 환자별 장치 등록·해제 기능이 없음 |
| `signal_windows` | `craving_predictions`에 병합 | 현재는 각 입력 구간의 최종 예측을 남기는 것이 핵심 |
| `signal_window_sources` | 제거 | 별도 다대다 관계가 데모에 과함 |
| `session_prediction_links` | 제거 | 트리거 예측은 `sessions.trigger_alert_id`로 확인 가능 |
| `assessment_instrument_versions` | 제거 | AUQ 버전·채점 정보는 평가 행에 직접 보존 가능 |
| `slot_definitions` | DB에서 제거, 문서/코드 상수로 관리 | 13개 Slot이 고정되어 별도 테이블이 불필요 |
| `session_slot_sources` | `session_slots.source_message_ids` 배열로 병합 | 보고서 근거 추적은 유지하면서 테이블 감소 |
| `safety_events` | 현재 데모에서 제거 | 구조도에 독립 Safety Agent/대응 흐름이 없음. `safety_context` Slot만 유지 |
| `agent_runs` | 각 결과 테이블의 `model_version_id`와 metadata로 대체 | 데모에서는 Agent 실행 단위 감사보다 최종 결과 추적이 중요 |

## 3. v2.4.1에서 v2.5로 변경한 대화 구조

| v2.4.1 객체 | v2.5 처리 | 이유 |
|---|---|---|
| `audio_recordings` | 제거 후 `message_audio_artifacts`로 필요한 정보 통합 | 사용자 원본 음성은 메시지에 딸린 임시 외부 산출물임 |
| `stt_transcriptions` | 제거 후 최종 문장은 `messages.content_encrypted`에 통합 | 대화 처리의 기준은 최종 STT 텍스트이며 별도 전사 엔터티가 불필요함 |
| `messages` | 유지·확장 | 채팅, 사용자 음성의 최종 STT 문장, AI 답변을 하나의 순서로 보존 |
| `messages.input_modality` | `messages.modality`로 이름 변경 | 입력뿐 아니라 AI 답변의 전달 방식까지 메시지별로 표현 |
| `messages.stt_transcription_id` | 제거 | 별도 STT 테이블을 사용하지 않음 |
| `messages.content_edited_by_user` | 추가 | 사용자가 STT 결과를 수정했는지 최종 메시지에 기록 |

v2.5는 빈 PostgreSQL 16+ 데이터베이스에 적용하는 독립 스키마입니다. v2.4.1 데이터의 마이그레이션, 백필, 애플리케이션 연결은 포함하지 않습니다.

## 4. 최종 테이블 18개

| 영역 | 테이블 | NeuroSync 대비 |
|---|---|---|
| 계정 | `users` | 수정 유지 |
| 환자정보 | `patient_profiles` | 수정 유지 |
| 동의 | `consent_snapshots` | 수정 유지 |
| 모델 | `model_versions` | 신규 |
| 생체신호 | `sensor_recordings` | 신규 |
| 갈망 추론 | `craving_predictions` | 신규 |
| 알림 | `craving_alerts` | `risk_events` 대체 |
| 세션 | `sessions` | 수정 유지 |
| 대화 | `messages` | 수정 유지 |
| 사용자 원본 음성 | `message_audio_artifacts` | `audio_recordings`와 STT 처리 메타데이터 통합 |
| AUQ | `craving_assessments` | `questionnaire_results` 변경 |
| Slot | `session_slots` | 신규 |
| 중재 | `interventions` | 신규 |
| 종단기록 | `memory_snapshots` | 신규 |
| 보고서 | `session_reports` | `handoff_reports` 변경 |
| 감사 | `audit_logs` | 유지 |
| 인증 | `auth_sessions` | refresh token 회전·폐기·재사용 탐지 |
| 운영 설정 | `system_settings` | 개입 ON/OFF, 채팅 timeout, 관리자 가입 코드 해시 |

`organizations`는 병원·기관 연동이 없으므로 제거했습니다. `alembic_version`은 Alembic을 사용하면 자동 생성되므로 업무 테이블 수에서 제외했습니다.

환자 계정은 직접 가입 즉시 `active`가 되며 `users.status`는 `active`, `disabled`, `pending_deletion`만 허용합니다. 임시 비밀번호가 발급된 계정은 `must_change_password`로 다음 인증 흐름에서 변경을 강제합니다. 비밀번호와 관리자 가입 코드는 Argon2id 해시만 저장합니다. `auth_sessions`에는 opaque refresh token의 SHA-256 해시만 저장하고, 회전 계보와 token family를 기록합니다.

민감 원문은 AES-256-GCM envelope로 저장합니다. envelope는 key version, 96-bit nonce, ciphertext와 128-bit tag를 포함하고 AAD는 table, column, patient UUID, record UUID에 결합됩니다. 신규 쓰기는 현재 key version을 사용하며 기존 envelope는 저장된 version으로 복호화합니다. 키 누락 또는 인증 실패에는 평문 fallback이 없습니다.

`sensor_recordings.client_window_id`는 `(patient_id, client_window_id)`로 유일합니다. 원시 센서 파일은 canonical JSON을 gzip한 뒤 AES-256-GCM으로 암호화해 backend 전용 volume에 저장하며, DB에는 상대 경로와 암호화·checksum 메타데이터만 남깁니다.

## 5. 전체 흐름

```text
users / patient_profiles / consent_snapshots
               │
               ▼
sensor_recordings
               │
               ▼
craving_predictions
               │
               ▼
craving_alerts ─────→ sessions
                         │
                         ├─ messages ← message_audio_artifacts (사용자 원본 음성만 임시 보존)
                         ├─ craving_assessments (AUQ)
                         ├─ session_slots
                         ├─ interventions
                         ├─ memory_snapshots
                         └─ session_reports
```


채팅과 음성은 세션 종류로 고정하지 않고 메시지마다 `modality`로 기록합니다. 따라서 하나의 세션 안에서 텍스트와 음성 메시지를 자유롭게 섞을 수 있습니다.

```text
사용자 채팅 ──────────────────────────────→ messages.content_encrypted
사용자 음성 → STT 최종 텍스트 ───────────→ messages.content_encrypted
            └→ 동의한 원본 음성 임시 보존 → message_audio_artifacts
AI 답변 텍스트 ───────────────────────────→ messages.content_encrypted
            └→ 필요 시 TTS로 즉시 재생    → DB에 음성/메타데이터 미저장
```

`session_slots`, `memory_snapshots`, `session_reports`는 입력 방식과 관계없이 `messages`의 기준 텍스트를 사용합니다.

## 6. 메시지와 사용자 원본 음성 저장 규칙

### 6.1 `messages`가 기준 대화 기록

| 상황 | `role` | `modality` | `content_encrypted` | 음성 산출물 |
|---|---|---|---|---|
| 사용자가 채팅 입력 | `user` | `text` | 직접 입력한 암호화 텍스트 | 없음 |
| 사용자가 음성 입력 | `user` | `voice` | 최종 STT 암호화 텍스트 | 보존 정책에 따라 최대 1개 |
| AI가 텍스트로 답변 | `assistant` | `text` | AI 답변의 암호화 텍스트 | 없음 |
| AI 답변을 TTS로 재생 | `assistant` | `voice` | TTS 입력이 된 AI 답변의 암호화 텍스트 | DB에 저장하지 않음 |
| 시스템 메시지 | `system` | `system` | 암호화된 시스템 문구 | 없음 |

`UNIQUE (session_id, sequence_no)`가 세션 안의 메시지 순서를 보장합니다. `content_edited_by_user`는 사용자가 최종 STT 문장을 직접 수정했는지를 나타냅니다.

### 6.2 `message_audio_artifacts`의 범위

이 테이블은 향후 음성 기능이 활성화될 때 동의하에 backend 전용 암호화 volume에 임시 보존하는 **사용자 원본 음성**만 기록합니다. `storage_uri`에는 volume 기준 상대 경로만 저장하며 파일은 key version과 nonce를 포함하는 AES-256-GCM envelope입니다. 현재 V2.5 구현에서는 음성 수집이 비활성화되어 이 테이블에 신규 자료를 기록하지 않습니다.

- `(message_id, session_id)` 복합 FK로 같은 세션의 메시지만 연결합니다.
- `(session_id, patient_id)` 복합 FK로 세션 환자를 일치시킵니다.
- `(consent_snapshot_id, patient_id)` 복합 FK로 같은 환자의 동의만 연결합니다.
- `UNIQUE (message_id)`로 메시지당 원본 음성을 최대 1개만 허용합니다.
- `delete_after`는 필수이며 backend volume 파일의 실제 삭제 후 `deleted_at`을 기록합니다.
- STT 모델, 신뢰도, 지연시간과 객체 형태의 `processing_metadata`를 선택적으로 기록합니다.
- STT 재시도·후보 문장·스트리밍 청크와 AI TTS 파일·모델·생성 메타데이터는 저장하지 않습니다.

텍스트 메시지에는 음성 산출물이 없습니다. 음성 입력이라도 원본 보존을 생략한 경우 DB 트리거로 산출물 생성을 강제하지 않으므로 `messages`만 존재할 수 있습니다.

## 7. 세션 상태와 동일 알림 재시도 정책

`craving_alerts`로부터 세션 행이 생성되더라도 사용자가 알림에 응답하지 않을 수 있습니다. 이 경우 세션은 실제로 시작되지 않았으므로 `started_at`은 `NULL`인 상태에서 `abandoned`로 전환될 수 있습니다.

| 상태 | `started_at` | `ended_at` | 의미 |
|---|---:|---:|---|
| `created` | `NULL` | `NULL` | 세션 행만 생성되고 사용자 응답 전 |
| `in_progress` | 필수 | `NULL` | 사용자가 실제 대화를 시작함 |
| `completed` / `report_ready` / `closed` | 필수 | 필수 | 시작된 세션이 정상적으로 종료됨 |
| `abandoned` | 선택 | 필수 | 시작 전 응답 없음 또는 시작 후 사용자 이탈 |

따라서 `abandoned`에는 두 경로가 모두 허용됩니다.

```text
알림 생성 → 세션 created → 응답 없음 → abandoned
알림 생성 → 세션 in_progress → 사용자 이탈 → abandoned
```

또한 동일 알림으로 생성된 세션이 `abandoned`가 되면 사용자가 알림을 다시 눌러 새 세션을 만들 수 있어야 합니다. 이를 위해 `UNIQUE(trigger_alert_id)` 제약은 제거하고 다음 부분 유니크 인덱스를 사용합니다.

```sql
CREATE UNIQUE INDEX uq_sessions_non_abandoned_trigger_alert
    ON public.sessions (trigger_alert_id)
    WHERE trigger_alert_id IS NOT NULL
      AND status <> 'abandoned';
```

이 인덱스는 한 알림에 대해 동시에 여러 개의 진행 가능한 세션이 만들어지는 것은 막으면서, 이전 세션이 `abandoned`인 경우에는 재시도를 허용합니다.

## 8. Slot에 저장하는 정보

Slot은 갈망 점수를 저장하지 않습니다.

- 생체신호 기반 갈망 유무: `craving_predictions`
- 주관적 갈망 점수: `craving_assessments`
- 대화에서 확인한 맥락: `session_slots`

| Slot | 의미 | 자연스러운 발화 예시 | 보고서 활용 |
|---|---|---|---|
| `episode_trigger` | 이번 갈망을 유발한 사건 | “회의에서 크게 지적받고 나서 계속 기분이 안 좋아요.” | 주요 촉발 요인 |
| `current_context` | 현재 장소·시간·혼자 여부 | “지금 집에 왔고 혼자 있어요.” | 발생 당시 환경 |
| `alcohol_context` | 주변 술, 술 단서, 구매 접근성 | “냉장고를 열 때마다 맥주가 보여서 신경 쓰여요.” | 즉시 음주 가능성 |
| `drinking_status` | 아직 미음주/이미 음주/추가 음주 고민 | “결국 한 캔 마셨는데 더 살까 고민 중이에요.” | 현재 행동 단계 |
| `habit_pattern` | 반복되는 음주 습관 | “힘든 날에는 집에서 TV 보면서 마시곤 했어요.” | 반복 패턴 |
| `emotional_context` | 스트레스·분노·외로움 등 | “억울하고 화가 나서 아무 생각도 하기 싫어요.” | 정서 유발 요인 |
| `physical_context` | 피로·불면·금단감 등 | “너무 지쳐서 뭘 할 힘이 없어요.” | 신체 상태/중재 제약 |
| `alcohol_expectancy` | 음주로 기대하는 효과 | “마시면 생각이 좀 멈추고 잠도 잘 와요.” | 음주 유지 인지 |
| `coping_context` | 과거/현재 대처와 장애물 | “산책하면 나아졌는데 오늘은 너무 피곤해요.” | 중재 선택 근거 |
| `support_context` | 연락 가능한 사람과 도움 요청 장애물 | “동생은 받아주겠지만 걱정시킬까 미안해요.” | 사회적 지원 |
| `user_goal` | 사용자가 원하는 단기 목표 | “완전히 끊을 자신은 없지만 오늘은 안 마시고 싶어요.” | 세션 목표 |
| `safety_context` | 운전·약물 병용·심한 증상 등 | “술을 마셨는데 조금 있다 운전해야 해요.” | 즉시 안전 안내 |
| `additional_context` | 나머지 중요 정보 | 정형 Slot에 넣기 어려운 핵심 사실 | 보고서 보충 |

## 9. 현재 이진 분류와 향후 확장

현재는 다음과 같이 저장합니다.

```json
{
  "predicted_class_index": 1,
  "predicted_class_code": "craving",
  "predicted_class_probability": 0.83,
  "class_probabilities": {
    "no_craving": 0.17,
    "craving": 0.83
  }
}
```

0과 1의 의미는 DB에 고정하지 않고 `model_versions.output_schema`에서 관리합니다. 나중에 LOW/MID/HIGH 또는 회귀 모델로 바뀌면 테이블을 새로 만들지 않고 모델 버전과 예측 컬럼을 확장해 사용할 수 있습니다.

## 10. 향후 필요할 때만 추가할 기능과 테이블

다음 기능이 실제 요구사항으로 확정될 때 별도로 추가합니다.

- 장치 등록/해제 및 여러 장치 관리: `devices`
- 동일 입력 구간을 여러 모델이 공유: `signal_windows`
- Agent별 실행 비용·재시도·trace 분석: `agent_runs`
- 응급 대응 워크플로: `safety_events`
- 병원/의료진 전달: `organizations`, `clinician_profiles`, `report_deliveries`
- RAG: 별도 `rag` 스키마
- STT 재시도 이력, 복수 후보, 스트리밍 청크: 별도 음성 처리 이력/청크 테이블
- AI 음성 파일 보존 및 TTS 분석: 별도 TTS 산출물 구조
- v2.4.1 데이터 전환 및 현재 런타임 연결: 별도 마이그레이션·백엔드 계약 작업

## 11. 주석 확인 방법

SQL에는 두 종류의 설명을 넣었습니다.

1. `--` 주석: SQL 파일을 읽을 때 바로 보이는 설명
2. `COMMENT ON TABLE/COLUMN`: DBeaver에서 테이블 또는 컬럼의 **Comment/Description**으로 확인 가능한 설명

따라서 스키마 적용 후 DBeaver에서 컬럼을 선택하면 각 컬럼의 역할을 확인할 수 있습니다.

