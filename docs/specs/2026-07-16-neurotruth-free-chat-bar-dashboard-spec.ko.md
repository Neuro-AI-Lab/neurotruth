# NeuroTruth 자유대화 및 바 대시보드 명세

- **Language role:** Korean mirror spec
- **Spec status:** Finalized
- **Spec version:** 1.0
- **Last updated:** 2026-07-16
- **English source:** `docs/specs/2026-07-16-neurotruth-free-chat-bar-dashboard-spec.md`
- **Korean mirror:** `docs/specs/2026-07-16-neurotruth-free-chat-bar-dashboard-spec.ko.md`
- **Requester / owner:** NeuroTruth project team
- **Implementation status:** In implementation

## 0. Codex 구현 인계

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: docs/specs/2026-07-16-neurotruth-free-chat-bar-dashboard-spec.md
korean_mirror_spec: docs/specs/2026-07-16-neurotruth-free-chat-bar-dashboard-spec.ko.md
authoritative_spec: english_source_spec
implementation_requires:
  - use_feature_planner_skill
  - read_english_source_spec_first
  - keep_korean_mirror_synchronized
  - spawn_worker_subagents_for_source_code_edits
  - main_agent_only_orchestrates_and_verifies
  - enforce_minimal_source_changes
  - return_to_targeted_refinement_on_spec_gap
worker_rule: source_code_changes_must_be_delegated_to_worker_subagents
main_agent_allowed:
  - read_files
  - search_repository
  - inspect_diffs
  - run_validation
  - update_spec_progress_records
  - prepare_worker_prompts
main_agent_forbidden:
  - direct_feature_source_code_edits
  - unrelated_refactors
  - formatting_only_churn
  - dependency_upgrades_unless_spec_requires
  - generated_file_churn_unless_spec_requires
</codex_feature_planner_handoff>
```

## 1. 요약

신규 세션의 구조화된 중재 흐름을 중립적 진입, 선택형 AUQ, 재시도 안전성을 갖춘 자유대화로 교체한다. 신규 대화는 결정론적 안전 감지, 필수 첫 중재 규칙, 질문 주제 coverage 또는 신규 중재 저장을 실행하지 않는다. 각 환자 메시지는 일반 Bedrock 대화 호출 최대 한 번과 validation repair 한 번만 수행하며 상태 요약 LLM은 세션 종료 시점으로 이동한다. Android 환자 대시보드는 실시간 확률 선을 삼성헬스형 바 요약으로 교체한다. 휴대폰 현지 날짜의 시간별 갈망 확률, 7/30일 일별 알림 이벤트 수, 시간별/일별 AUQ 평균을 표시한다. 관리자 웹, Wear OS, legacy 세션, 세션 리포트와 probability-series API는 호환성을 유지한다.

## 2. 목표

- G1. 인증된 채팅을 빠르고 자유로우며 bounded context, 반복 방지, 중복 없는 수동 재시도가 가능하도록 만든다.
- G2. Android에서 현재 갈망 확률과 명확한 시간별/일별 바 요약을 제공한다.
- G3. GitHub 인계 전에 로컬 Docker 배포를 재빌드하고 검증한다.
- G4. 인증, 암호화, 센서 업로드, prediction SSE, 리포트, 관리자 웹과 Watch 동작을 보존한다.

## 3. 비목표

- NG1. 주기적 LLM 리포트 문서, 집계 테이블 또는 스케줄러를 추가하지 않는다.
- NG2. 관리자 확률 차트 또는 Wear OS 차트를 추가하지 않는다.
- NG3. 자유대화 세션에서 신규 intervention row를 만들지 않는다.
- NG4. legacy 세션, 중재, 추론 또는 리포트를 삭제·재작성·backfill하지 않는다.
- NG5. 모델 재학습, 예측 정책 변경, rPPG 변경, dependency upgrade 또는 관련 없는 refactor를 하지 않는다.
- NG6. 이번 release는 응급 감지나 대응을 보장하지 않는다. 사용자가 LLM-only 안전 처리를 명시적으로 선택했다.

## 4. 사용자와 사용 사례

- UC1. 환자가 AUQ 작성 또는 건너뛰기를 선택하고 중립적 자유대화 세션에 들어가 턴마다 간결한 한국어 답변 하나를 받는다.
- UC2. Bedrock 실패 시 환자는 오류를 확인하고 사용자 메시지 row를 중복 생성하지 않고 동일 메시지를 한 번 재시도한다.
- UC3. 환자는 기기 현지시간 기준 오늘의 시간별 갈망 확률, 7/30일 알림 이벤트 수, 오늘/7/30일 AUQ 바를 본다.
- UC4. 운영자는 Docker를 통해 로컬 흐름을 검증하고 인수 후 Git commit/push 안내를 받는다.

## 5. 최종 결정

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | Dialogue | 신규 세션은 중립적 시작 이후 완전한 자유대화를 사용하며 필수 질문 순서가 없다. | User decision |
| D2 | AUQ | 채팅 전 기존 선택형 작성/건너뛰기를 유지한다. | User decision |
| D3 | Safety | 안전 해석과 위기 문구는 LLM-only이며 신규 자유대화 세션에서 서버 결정론적 위험 감지를 실행하지 않는다. | User decision |
| D4 | Intervention | 에이전트는 대화만 제공하며 신규 중재 유형을 선택하거나 저장하지 않는다. | User decision |
| D5 | Summary timing | 턴별 상태 요약 LLM 호출을 제거하고 세션 종료 시에만 생성한다. | User decision |
| D6 | Failure UX | Bedrock 실패 시 숨겨진 fallback 대신 오류와 수동 재시도 한 번을 제공한다. | User decision |
| D7 | Probability chart | 모바일 실시간 선을 오늘의 24개 현지 시간 막대로 교체한다. | User decision |
| D8 | Event chart | persisted `recommend|required` 갈망 알림 하나를 이벤트 하나로 보고 7/30일 일별 건수를 표시한다. | User decision |
| D9 | AUQ chart | 현지 오늘 시간별 및 7/30일 일별 normalized AUQ 평균과 응답 수를 표시한다. | User decision |
| D10 | Empty buckets | 센서/AUQ 누락은 0이나 생략이 아닌 회색 no-data bucket이다. | User decision |
| D11 | Surface | 신규 바는 Android 전용이며 관리자 웹과 Wear OS는 변경하지 않는다. | User decision |
| D12 | Retry limit | `clientMessageId`당 전체 시도는 최초와 수동 재시도 한 번으로 총 두 번이다. | Agent default |
| D13 | Context bound | 최신 persisted 메시지 20개와 ledger에 기록된 이전 assistant 질문/거부 최대 50개를 전달한다. | Agent default |
| D14 | DST buckets | 항상 wall-clock hour label 24개를 렌더링하고 중복 시간 sample은 합치며 건너뛴 시간은 no-data로 둔다. | Agent default |
| D15 | Retry concurrency | `0004`는 조건부 unique client-message index를 추가하고 message turn은 blocking session advisory lock을 사용해 응답 유실 retry가 완료된 원본 결과를 확인하게 한다. | Agent default |
| D16 | Repeat threshold | Unicode/punctuation을 normalize하고 전체 ledger 질문에 대해 `SequenceMatcher >= 0.82`이면 반복으로 거부한다. | Agent default |

## 6. 기능 요구사항

- FR1. 신규 세션은 `free_dialogue`로 시작하며 중립 assistant 메시지 `지금 상황이나 원하는 도움을 편하게 말씀해 주세요.`를 저장한다.
- FR2. 신규 세션 dialogue state version 2는 이전 assistant 질문 문구, 거부된 질문 문구와 최신 질문을 저장하며 legacy state는 읽을 수 있어야 한다.
- FR3. 자유대화 prompt는 최근 history와 ledger를 사용하고 질문 최대 하나, 반복/거부 질문 방지, 진단·처방·치료효과·확실성·인과 주장 금지를 적용한다.
- FR4. 신규 자유대화 턴은 결정론적 safety classification, 첫 중재 선택 또는 intervention 저장을 실행하지 않는다. Prompt는 LLM이 즉각 위험으로 판단하면 119/109를 안내하도록 하지만 제품은 이를 보장된 응급 처리로 표현하지 않는다.
- FR5. 정상 턴은 Bedrock dialogue 호출 한 번을 수행한다. 출력이 잘못되거나 이전 질문을 반복할 때만 structured repair 한 번을 허용한다.
- FR6. Session 생성과 메시지는 상태 요약 LLM을 호출하지 않는다. 수동 종료/timeout은 최종 realtime/longitudinal summary 및 기존 async session report 동작을 유지한다.
- FR7. Bedrock에 전달하는 dialogue history는 최신 메시지 20개로 제한하지만 모든 메시지는 DB에 암호화 보존한다.
- FR8. Android는 입력 메시지마다 UUID `clientMessageId`를 하나 만들고 수동 재시도에 동일 ID를 사용한다.
- FR9. 재시도는 persisted user message를 재사용하고 `dialogueAttempts`를 증가시키며 두 번째 user message 또는 assistant response 두 개를 만들지 않는다.
- FR10. Dashboard bar는 현재 indexed prediction, alert, assessment row에서 계산하며 주기적 집계 job을 만들지 않는다.
- FR11. 현재 갈망 확률은 활성 binary model의 최신 유효 `continuous_value`다.
- FR12. 시간별 갈망 bucket은 기기 현지 달력 날짜의 00:00~23:59 전체 24시간이다.
- FR13. 일별 이벤트 bucket은 persisted `recommend|required` alert를 센다. Prediction이 있고 alert가 없으면 0, prediction이 없으면 no-data다.
- FR14. AUQ는 assessment별 `(rawScore-scaleMin)/(scaleMax-scaleMin)`으로 normalize하고 bucket 평균 후 `[0,1]`로 제한하며 `sampleCount`를 반환한다.
- FR15. 현재/시간별 갈망과 event availability는 active binary craving model의 quality-passed prediction만 사용한다. AUQ bar는 `instrument_code='AUQ'`만 사용한다.
- FR16. Assessment 제출도 state-summary LLM을 호출하지 않으며 create, message, assessment 경로는 finish 전까지 summary-free다.

## 7. 사용자 경험 / UI 요구사항

- UI1. 기존 선택형 AUQ를 유지하고 safety-first phase label을 `자유 대화`로 교체한다.
- UI2. Provider failure 시 사용자 bubble을 유지하고 오류와 `다시 시도`를 표시하며 retry 소진 후 버튼을 숨긴다.
- UI3. 갈망 카드는 최신 퍼센트와 오늘의 24개 0~100% 시간별 bar를 표시하고 기존 10분 line을 화면에서 제거한다.
- UI4. 이벤트 카드는 `7일`, `30일` control과 daily bar를 제공하며 선택 bar 상세에서 `recommend`, `required`를 구분한다.
- UI5. AUQ 카드는 `오늘`, `7일`, `30일` control을 제공하고 오늘은 시간별, 장기 범위는 일별이다.
- UI6. Bar를 누르면 현지 기간, 갈망 평균/min/max 및 sample count, 이벤트 건수 또는 AUQ 평균 퍼센트와 응답 수를 표시한다.
- UI7. No-data bar는 회색이며 prediction 데이터가 있는 날의 이벤트 0건은 유효한 높이 0 bar다.
- UI8. Live Watch PPG, owned historical PPG preview, state/report status, accessibility label과 local-time 표시를 유지한다.

## 8. API / 데이터 / 상태 요구사항

### 8.1 자유대화

`POST /api/sessions/{sessionId}/messages`:

```json
{"clientMessageId":"uuid-or-null","content":"1..10000 characters"}
```

신규 Android는 UUID를 항상 제공한다. 생략한 legacy 요청은 one-shot 호환으로 허용하지만 재시도할 수 없다.

성공 응답은 기존 응답에 `userMessageId`, `assistantMessageId`, `phase:"free_dialogue"`를 추가한다. Provider/validation 실패는 HTTP 502다.

```json
{
  "detail": {
    "code": "dialogue_provider_error|dialogue_output_rejected",
    "clientMessageId": "uuid",
    "userMessageId": "uuid",
    "retryable": true,
    "attemptsRemaining": 1
  }
}
```

두 번째 실패 이후 동일 요청은 `retryable:false`, `attemptsRemaining:0`을 반환한다.

- DATA1. User message `generation_metadata`는 `clientMessageId`, `dialogueAttempts`를 저장하고 assistant metadata는 `replyToUserMessageId`를 저장한다.
- STATE1. Additive Alembic `0004`로 `sessions.interaction_phase`에 `free_dialogue`를 추가한다. 같은 migration은 client ID가 있는 user message에 `(session_id, generation_metadata->>'clientMessageId')` 조건부 unique index를 추가하며 기존 값은 변경하지 않는다.

### 8.2 모바일 바 대시보드

`GET /api/me/craving-dashboard?timezone={ianaTimezone}&eventRange=7d|30d&auqRange=today|7d|30d`

응답:

```json
{
  "timezone": "Asia/Seoul",
  "generatedAt": "UTC ISO-8601",
  "currentCraving": {"probability": 0.81, "at": "UTC ISO-8601"},
  "hourlyCraving": {
    "buckets": [{
      "localStart": "ISO-8601 with offset",
      "averageProbability": 0.72,
      "minimumProbability": 0.41,
      "maximumProbability": 0.91,
      "sampleCount": 3590
    }]
  },
  "dailyEvents": {
    "range": "7d",
    "buckets": [{
      "localDate": "2026-07-16",
      "hasPredictionData": true,
      "recommendCount": 2,
      "requiredCount": 1,
      "totalCount": 3
    }]
  },
  "auq": {
    "range": "today",
    "bucketUnit": "hour",
    "buckets": [{
      "localStart": "ISO-8601 with offset",
      "averageNormalizedScore": 0.63,
      "sampleCount": 2
    }]
  }
}
```

- API1. Valid IANA zone을 허용하고 invalid zone은 422 `invalid_timezone`이다.
- API2. 누락 value는 `sampleCount:0`과 JSON null이며 예상 hour/day bucket은 생략하지 않는다.
- API3. 기존 `/api/me/dashboard`, `/api/me/craving-probability-series`를 유지한다.
- API4. Android는 모든 query parameter를 URL-encode하며 contract test에 `Etc/GMT+5`를 포함한다.

## 9. 권한, 보안, 개인정보와 감사

- SEC1. 두 endpoint는 patient JWT ownership과 기존 AI/report consent rule을 유지한다.
- PRIV1. Message, ledger, summary와 report는 AES-256-GCM 암호화를 유지한다. Bar는 기존 비민감 aggregate metadata만 포함한다.
- AUDIT1. Dialogue failure는 raw text, provider payload, stack trace, credential 없이 code, stage, exception class, user message ID, attempt만 기록한다.
- AUDIT2. LLM-only safety는 research/demo-only이며 신뢰 가능한 응급 대응으로 표현하지 않는다.

## 10. 오류, 경계 사례와 동시성

- ERR1. Bedrock call 실패는 assistant message를 저장하지 않고 retry metadata를 반환한다.
- ERR2. Output rejection도 `dialogue_output_rejected`로 동일 retry contract를 사용한다.
- EDGE1. 완료된 `clientMessageId` 재사용은 기존 assistant response를 idempotently 반환한다.
- EDGE2. 동일 ID와 다른 content는 409 `client_message_conflict`다.
- EDGE3. DST day에도 `00`~`23` hour label을 반환하며 중복 local hour는 해당 label에 합치고 skipped local hour는 no-data bucket이다.
- CONC1. Message turn은 blocking transaction-scoped session advisory lock을 사용한다. 대기 후 `clientMessageId`를 다시 확인해 기존 assistant 결과를 반환하거나 허용된 retry를 계속한다.

## 11. 의존성과 설정

- DEP1. 신규 runtime dependency가 없다.
- DEP2. `BEDROCK_TIMEOUT_SECONDS`, 기본 3600초 session inactivity, 현재 Docker port, model config와 encryption setting을 유지한다.

## 12. Migration, rollout과 rollback

- MIG1. `0004`는 `free_dialogue` 허용과 조건부 retry-idempotency index만 추가하며 기존 session row 또는 baseline SQL을 재작성하지 않는다.
- ROLL1. Local PostgreSQL volume을 backup하고 backend/mobile을 함께 rebuild한 후 `0004`를 적용하고 Git 인계 전에 검증한다.
- BACK1. 이전 image/commit으로 rollback한다. Additive constraint는 무해하지만 신규 free-dialogue row는 이전 app에서 읽지 못하므로 완전 rollback에는 DB backup 복원이 필요하다.

## 13. 구현 경계

### 13.1 예상 변경 영역

- Backend session agents/service/routes/repository, dashboard aggregation/routes, additive migration과 focused test.
- Android authenticated session API/view-model/chat UI, patient dashboard/parser/chart와 focused test.
- Spec/progress 및 관련 README/API documentation.

### 13.2 금지 변경

- 관리자 웹, Wear OS, model inference, alert rule, rPPG, authentication, encryption primitive와 기존 session-report format.
- 관련 없는 refactor, dependency upgrade, generated churn 또는 destructive data cleanup 금지.

## 14. 인수 기준

- AC1. 신규 session은 neutral prompt와 함께 `free_dialogue`로 열리고 optional AUQ가 유지되며 신규 intervention row가 생성되지 않는다.
- AC2. 10턴 이상 자유대화가 bounded context, 질문 최대 하나, 지정 threshold의 normalized prior-question similarity reject/repair를 지킨다.
- AC3. 실패한 메시지를 동일 ID로 한 번 재시도할 수 있고 user row 하나, assistant row 최대 하나만 존재한다.
- AC4. Session 생성/message/AUQ assessment 중 state-summary LLM call이 없고 종료 시 final summary와 기존 report가 생성된다.
- AC5. Android가 오늘 24개 craving bar, 7/30일 event bar, 오늘/7/30일 AUQ bar를 올바른 local boundary로 표시한다.
- AC6. Sensor/AUQ 누락과 유효한 event 0건 bucket이 구분된다.
- AC7. 기존 auth, encrypted persistence, sensor/SSE, Watch, PPG preview, admin web과 session report regression test가 통과한다.
- AC8. Local Docker가 업데이트된 image에서 healthy/ready이며 인증된 real-Bedrock dialogue smoke test를 완료한다.

## 15. 검증 계획

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Backend | `apps/backend/.venv/Scripts/python.exe -m pytest apps/backend/tests -q` | Free chat, retry, aggregation, migration 및 regression 통과. |
| Android | `apps/mobile/gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` | Parser, bar, retry UI 및 build/lint 통과. |
| Docker | `apps/db/docker-compose.yml` rebuild 후 `/health`, `/ready`, Alembic revision, authenticated chat, dashboard API 확인. | 업데이트된 code 실행 및 DB `0004`. |
| Manual | AUQ complete/skip, 10+ turns, forced provider failure/retry, finish, seeded hourly/daily data. | AC1–AC8 확인. |

## 16. 위험과 참고

- RISK1. LLM-only safety는 provider/model 실패 시 urgent context를 놓칠 수 있다. 이는 명시적 사용자 결정이며 신뢰 가능한 crisis response release claim을 금지한다.
- RISK2. 대량 prediction history는 집계 비용이 있으므로 기존 `(patient_id,predicted_at)` index와 요청 local period만 사용한다.
- RISK3. 현재 실행 중인 local container는 최신 source fix 이전이므로 Docker rebuild 검증이 필수다.

## 17. 구현 체크리스트 / 진행 기록

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | English/Korean spec pair finalize 및 validate | Main | Completed | Spec pair | `validate_spec_pair.py`: PASS | Source edit delegation 준비 완료. |
| P2 | Backend free chat, retry, aggregation API, migration, test | Backend worker | Completed | Session agent/service/routes/repository, dashboard service/routes, `20260716_0004`, backend test와 README | Backend 전체: `150 passed, 1 skipped`; Alembic head `20260716_0004`; diff check 통과 | 신규 session은 암호화 ledger 기반 free dialogue를 사용하고 legacy session data는 보존한다. |
| P3 | Android retry flow 및 bar dashboard, test | Android worker | Completed | Authenticated API/session client, `SensorViewModel`, `MainActivity`, `PatientDashboard`, focused test와 mobile 문서 | `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`: BUILD SUCCESSFUL | Phone 전용 bar와 retry UI를 추가했으며 관리자 web과 Wear OS는 변경하지 않았다. |
| P4 | Main verification, Docker rebuild, documentation/progress | Main | Completed | Spec progress 및 local runtime 검증 | Spec validator PASS; custom-format DB backup 검증; schema `20260716_0004`에서 `/health`, `/ready` 200; 실제 Bedrock 10-turn free chat, AUQ submit/skip, finish/report, idempotency 및 dashboard smoke 통과; invalid timezone 422 | Backup: `champion/neurotruth-local-backups/neurotruth-pre-0004-20260716.dump`. Live 10-turn session은 neutral opener를 포함해 user 10건과 assistant 11건만 저장했고 intervention row를 만들지 않았으며 report를 완료했다. Forced failure/retry path는 자동 test로 검증했다. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.0 | 2026-07-16 | Feature Planner | 자유대화 및 Android bar-dashboard 명세 확정. |
| 1.1 | 2026-07-16 | Feature Planner | Delegated implementation, 자동 검증, Docker migration 및 실제 Bedrock smoke test 완료 기록. |
