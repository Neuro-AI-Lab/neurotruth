# NeuroTruth F1 기반 Bedrock Sonnet 4.6 갈망 중재 명세

- **Language role:** Korean mirror spec
- **Spec status:** Finalized
- **Spec version:** 1.2
- **Last updated:** 2026-07-09
- **English source:** `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-f1-bedrock-craving-handoff-spec.md`
- **Korean mirror:** `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-f1-bedrock-craving-handoff-spec.ko.md`
- **Requester / owner:** NeuroTruth project requester
- **Implementation status:** Implemented; superseded by unified backend layout; latest mobile/backend checks passed

> 현재 상태 메모 (2026-07-09): 현재 구현은 별도 `apps/ai-server`가 아니라 `apps/backend/app/ai`의 backend-owned Bedrock agents를 사용합니다. 아래의 LLM server 언급은 통합 전 F1 구현 단계의 히스토리입니다. Mobile app은 같은 Android-facing API contract를 유지하면서 working `watch_test` UX를 기반으로 재구성되었습니다.

## 0. Codex Implementation Handoff

이 섹션은 계획/명세 단계에서 구현 단계로 넘어갈 때 Feature Planner 워크플로를 보존합니다.

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-f1-bedrock-craving-handoff-spec.md
korean_mirror_spec: C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-f1-bedrock-craving-handoff-spec.ko.md
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

복사해서 사용할 구현 프롬프트:

```text
$feature-planner Implement `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-f1-bedrock-craving-handoff-spec.md` using IMPLEMENTATION_ORCHESTRATION. Use the English source spec as authoritative, keep `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-f1-bedrock-craving-handoff-spec.ko.md` synchronized, spawn worker sub-agents for source-code edits, and have the main agent verify minimal diffs and update progress records. If the spec has a gap, use TARGETED_REFINEMENT for only that gap before continuing.
```

## 1. Summary

NeuroTruth는 `회의록.md`와 `PPT`에 기록된 데모 흐름을 지원해야 합니다: 실시간 갈망 감지, rule-based 사용자 알림, 텍스트 기반 CBT 스타일 중재 대화, 갈망 슬롯 추출, 의료진 handoff 보고서 생성. 기존 `POST /sensor-window`와 `GET /prediction-stream` 동작은 class `0/1/2`만 읽는 현재 Android 클라이언트와 계속 호환되어야 합니다. NeuroSync F1은 에이전트 책임 경계의 아키텍처 기준이지만, NeuroTruth는 알코올 갈망 전용 슬롯과 AWS Bedrock Claude Sonnet 4.6을 유일한 LLM provider로 사용해야 합니다.

## 2. Goals

- G1. 최근 갈망 예측을 평가하고 알림 메타데이터를 내보내는 configurable non-LLM rule engine을 추가합니다.
- G2. prediction, alert, chat, slot, handoff memory를 Postgres에 저장합니다.
- G3. OpenAI-only LLM server 경로를 Bedrock Sonnet 4.6 chat, slot extraction, handoff endpoint로 교체합니다.
- G4. Android phone app에 text-first intervention chat을 추가하고 phone/watch에 alert state를 표시합니다.
- G5. 기존 sensor upload, SSE prediction class delivery, watch class display 호환성을 유지합니다.

## 3. Non-Goals

- NG1. 이번 버전에서는 microphone recording UI 또는 STT 구현을 하지 않습니다.
- NG2. ADARP training, LOSO experiment, model retraining, ML performance reporting을 하지 않습니다.
- NG3. NeuroSync monorepo 전체 복사나 dependency-heavy full router port를 하지 않습니다.
- NG4. 의료 진단이나 치료 지시를 하지 않습니다. 생성 내용은 중재 지원 및 clinician handoff 보조에 한정됩니다.

## 4. Users and Use Cases

### 4.1 Target Users

- Alcohol craving monitoring demo operator.
- Android phone 및 Galaxy Watch app을 사용하는 patient.
- 생성된 handoff report를 검토하는 clinician 또는 researcher.

### 4.2 Primary Use Cases

- UC1. Patient가 wearable sensor data를 stream하고 realtime craving class update를 받으며, rule engine이 trigger될 때 recommendation 또는 required intervention을 확인합니다.
- UC2. Patient가 alert 이후 phone chat에 text를 입력하면, NeuroTruth가 CBT-style support와 turn당 하나의 간결한 질문으로 응답합니다.
- UC3. System이 chat history에서 craving-specific slots를 추출하고 clinician-facing Markdown handoff report를 생성합니다.

## 5. Final Decisions

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | Alert policy | Conservative defaults를 사용합니다: recent 10 prediction classes average가 `0.5` 이하이면 `none`, `0.6` to `1.4`이면 `recommend`, `1.5` 이상이면 `required`. | User decision |
| D2 | Alert configurability | Alert window, thresholds, high streak, cooldown, downtrend suppression을 env/config로 노출합니다. | User decision |
| D3 | LLM provider | AWS Bedrock Claude Sonnet 4.6만 사용하고, default `BEDROCK_MODEL_ID=anthropic.claude-sonnet-4-6`, `global.anthropic.claude-sonnet-4-6` 같은 override를 허용합니다. | User decision |
| D4 | Chat entry | Text input을 먼저 구현합니다. STT adapter와 microphone UI는 out of scope입니다. | User decision |
| D5 | Memory | Idempotent schema creation 및 `infra/db/init.sql` update로 memory를 Postgres에 저장합니다. | User decision |
| D6 | NeuroSync reuse | F1 boundaries와 prompt discipline을 재사용하되, NeuroTruth craving/CBT slots에 맞게 조정하고 full copy는 하지 않습니다. | User decision |

## 6. Functional Requirements

- FR1. `RealtimePredictionService`는 latest predictions를 평가한 뒤 각 public craving SSE event에 alert metadata를 붙여야 합니다.
- FR2. Alert metadata는 `alertLevel`, `alertAction`, `windowMean`, `triggerReason`, `alertRequired`를 포함해야 합니다.
- FR3. Public SSE event는 가능한 경우 `class`, `timestampMs`, `confidence`, `sequence`를 계속 포함해야 합니다.
- FR4. Backend는 DB persistence 실패가 realtime inference를 막지 않도록 prediction events와 alert decisions를 저장해야 합니다.
- FR5. Backend는 `LLM_SERVER_URL`을 호출하는 chat, slot extraction, handoff proxy endpoints를 제공해야 합니다.
- FR6. LLM server는 Bedrock Sonnet 4.6을 사용하는 `/ai/chat/respond`, `/ai/slots/extract`, `/ai/handoff/generate`를 제공해야 합니다.
- FR7. LLM server는 Bedrock chat path 위의 compatibility wrapper로 `/generate`를 유지해야 합니다.
- FR8. Android phone SSE parsing은 alert metadata를 받고 저장하되 old class-only payload도 계속 받아야 합니다.
- FR9. Android phone UI는 current alert state를 표시하고 text chat surface를 제공해야 합니다.
- FR10. Phone은 alert-aware chat turns를 backend에 보내고 assistant responses 및 handoff readiness를 표시해야 합니다.
- FR11. Watch는 기존 craving class와 함께 alert state를 받아 표시해야 합니다.

## 7. User Experience / UI Requirements

- UI1. Phone prediction card는 latest class와 alert label: none, recommendation, required intervention을 표시해야 합니다.
- UI2. Phone chat panel은 text input, send button, recent messages, handoff readiness/report summary state를 포함해야 합니다.
- UI3. Watch UI는 현재 compact sensor layout을 유지하고 sensor rows를 방해하지 않는 alert status text/color를 추가해야 합니다.
- UI4. Copy는 기본적으로 Korean이며 diagnostic 또는 treatment-directive language를 피해야 합니다.
- UI5. LLM server를 사용할 수 없는 empty/error state에서도 prediction streaming은 계속 동작하고 error status를 보여야 합니다.

## 8. API / Data / State Requirements

- API1. `GET /prediction-stream` event data는 JSON이어야 하며 backward-compatible해야 합니다.
- API2. Backend chat endpoint: `POST /api/intervention/chat`는 `sessionId`, `message`, optional `alert`, optional `conversationHistory`를 받고 assistant response, merged slots, handoff readiness를 반환합니다.
- API3. Backend handoff endpoint: `POST /api/intervention/handoff`는 `sessionId`와 optional explicit slots/history를 받고 Markdown report와 missing slots를 반환합니다.
- API4. LLM server `/ai/chat/respond`는 `sessionId`, `message`, `conversationHistory`, `slots`, `alertContext`를 받습니다.
- API5. LLM server `/ai/slots/extract`는 `sessionId`, `conversationHistory`, `currentSlots`를 받습니다.
- API6. LLM server `/ai/handoff/generate`는 `sessionId`, `slots`, `conversationHistory`, `alertEvents`, `predictionSummary`를 받습니다.
- DATA1. Postgres는 sessions, prediction events, alert decisions, conversation turns, craving slots, handoff reports tables를 포함해야 합니다.
- STATE1. Backend session state는 client-provided `sessionId`가 있으면 그것을 사용하고, 없으면 active sensor session timestamp 또는 generated session id로 keying합니다.

## 9. Permissions, Security, Privacy, and Audit

- SEC1. AWS credentials를 source에 저장하지 않고 standard AWS credential chain 및 env를 사용합니다.
- SEC2. Backend-to-LLM calls는 `LLM_API_KEY`와 `x-api-key`를 계속 사용해야 합니다.
- PRIV1. Persisted chat 및 handoff data는 sensitive content를 포함할 수 있으므로 explicit debug 또는 test fixtures 외에는 full user messages를 log하지 않습니다.
- AUDIT1. Alert decisions와 handoff records는 demo traceability를 위해 timestamps를 포함해야 합니다.

## 10. Error, Edge-Case, and Concurrency Behavior

- ERR1. LLM server 실패 시 Android/backend caller에 visible HTTP error를 반환하고 sensor prediction streaming은 중단하지 않습니다.
- ERR2. Bedrock이 slot extraction에서 malformed JSON을 반환하면 missing slots list가 있는 safe empty extraction result를 사용합니다.
- ERR3. DB persistence가 실패하면 warning을 log하고 realtime prediction delivery를 계속합니다.
- EDGE1. Recent classes가 회복을 나타낼 만큼 downward trend이면 alert escalation을 suppress합니다.
- EDGE2. Cooldown을 적용하여 동일 alert level이 매초 noisy intervention prompts를 만들지 않게 합니다.
- CONC1. SSE subscribers는 동일 public event를 받을 수 있습니다. DB persistence는 subscriber마다가 아니라 worker time에 prediction event당 한 번 수행해야 합니다.

## 11. Dependencies and Configuration

- DEP1. `boto3`를 통해 Bedrock runtime dependency를 `apps/ai-server`에 추가합니다.
- DEP2. Backend는 기존 `sqlalchemy`/`asyncpg` dependencies를 사용할 수 있습니다.
- DEP3. New env/config keys: `ALERT_WINDOW_SIZE`, `ALERT_RECOMMEND_MIN`, `ALERT_REQUIRED_MIN`, `ALERT_HIGH_STREAK`, `ALERT_COOLDOWN_SECONDS`, `ALERT_DOWNTREND_DELTA`, `BEDROCK_MODEL_ID`, `AWS_REGION`.
- DEP4. Dependency lockfile churn은 필요하지 않습니다.

## 12. Migration, Rollout, and Rollback

- MIG1. `infra/db/init.sql`에 `CREATE TABLE IF NOT EXISTS` statements만 추가합니다. Destructive migrations는 없습니다.
- ROLL1. 새 endpoints가 있으면 feature는 always available입니다. Rollback 중에는 env values로 alert thresholds를 neutralize할 수 있습니다.
- BACK1. 이전 backend 및 LLM images를 배포하여 rollback합니다. 새 tables는 additive이며 unused 상태로 남아도 됩니다.

## 13. Implementation Boundaries

### 13.1 Expected Change Areas

- Backend: `apps/api/app/main.py`, `apps/api/app/inference.py`, new backend support modules/tests, `infra/db/init.sql`, backend README/env docs.
- LLM server: `apps/ai-server/app/main.py`, new LLM support modules/tests, `apps/ai-server/requirements.txt`, env docs.
- Android: phone and watch Kotlin files under `apps/mobile`, 그리고 API fields 또는 displayed alert states가 바뀌는 경우 `SERVER_API_SPEC.md`와 phone/watch docs.

### 13.2 Forbidden Changes

- Model feature extraction 또는 RandomForest inference behavior는 prediction events 소비 외에는 변경하지 않습니다.
- `POST /sensor-window` request contract를 변경하지 않습니다.
- Class-only SSE compatibility를 제거하지 않습니다.
- STT 또는 microphone UI를 구현하지 않습니다.
- Unrelated NeuroSync RAG, survey, OCR, temporal, web code를 port하지 않습니다.
- 명시된 경우 외에는 unrelated refactors, formatting churn, dependency upgrades, broad rewrites, generated-file churn을 하지 않습니다.

### 13.3 Minimal-Change Guidance

- 기존 FastAPI route style과 Android `StateFlow` patterns를 재사용합니다.
- Alert rules, memory persistence, Bedrock calls에는 small helper modules를 선호합니다.
- 명시된 변경 외 public interfaces는 backward-compatible하게 유지합니다.

## 14. Acceptance Criteria

- AC1. Prediction classes `[0,0,1,1,1,1,1,1,1,1]`가 default로 평가되면 alert metadata는 `recommend`를 보고합니다.
- AC2. Prediction classes average가 최소 `1.5`이고 cooldown 또는 downtrend suppression 상태가 아니면 alert metadata는 `required`와 `alertRequired=true`를 보고합니다.
- AC3. Class-only SSE event를 Android가 parse하면 class display는 계속 작동하고 alert fields는 none으로 default됩니다.
- AC4. Bedrock credentials와 valid chat request가 있으면 `/ai/chat/respond`는 Korean assistant response를 반환하고 diagnose 또는 prescribe하지 않습니다.
- AC5. Conversation history가 있으면 `/ai/slots/extract`는 craving-specific slot keys만 반환합니다.
- AC6. Slots와 history가 있으면 `/ai/handoff/generate`는 evidence/limitations와 missing-slot information이 포함된 Markdown을 반환합니다.
- AC7. Backend chat request가 있을 때 DB가 사용 가능하면 backend는 user turn과 assistant turn을 저장합니다.
- AC8. Android phone이 `recommend` 또는 `required`를 받으면 phone과 watch가 alert state를 표시합니다.

## 15. Validation Plan

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Backend unit tests | `python -m pytest` from `neurotruth/apps/api` if pytest is available | Alert rules, DB schema idempotency, proxy behavior pass |
| LLM unit tests | `python -m pytest` from `neurotruth/apps/ai-server` if pytest is available | Mocked Bedrock chat/slots/handoff tests pass |
| Android build | `.\gradlew.bat assembleDebug` from `neurotruth/apps/mobile` | Debug APK build succeeds |
| Docker smoke | `docker compose -f infra/deploy/docker-compose.backend.yml config` and `docker compose -f infra/deploy/docker-compose.gpu.yml config` if Docker is available | Compose config is valid |
| Manual API smoke | Start backend and LLM server, call `/health`, `/prediction-stream`, `/api/intervention/chat` | Endpoints respond as specified |

## 16. Risks and Open Notes

- RISK1. Local environment may not have AWS credentials, Docker, Android SDK, or pytest; mocked tests를 사용하고 skipped integration checks를 보고합니다.
- RISK2. Bedrock model IDs는 account/region에 따라 다를 수 있으므로 `BEDROCK_MODEL_ID`와 `AWS_REGION`은 overrideable해야 합니다.
- RISK3. Android build는 이 repository 밖의 local SDK/Samsung dependencies를 요구할 수 있습니다.

## 17. Implementation Checklist / Progress Record

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Finalize spec pair | Main | Complete | `docs/specs/...` | `validate_spec_pair.py`: PASS | English source와 Korean mirror가 동기화되었습니다. |
| P2 | Backend alert rules, memory, proxy routes | Worker | Complete | `apps/api/app/alerts.py`, `apps/api/app/memory.py`, `apps/api/app/inference.py`, `apps/api/app/main.py`, `apps/api/tests/*`, `infra/db/init.sql`, `.env.example` | Historical `compileall`: PASS; consolidation 이후 current backend pytest: PASS | Rule engine, SSE metadata, Postgres memory, LLM proxy routes, slot allowlist, focused tests를 이후 backend consolidation 전에 구현했습니다. |
| P3 | LLM server Bedrock F1-style agents | Worker | Complete | `apps/ai-server/app/main.py`, `apps/ai-server/app/bedrock_agents.py`, `apps/ai-server/requirements.txt`, `apps/ai-server/tests/*`, `apps/ai-server/Dockerfile` | Historical `compileall`: PASS; consolidation 이후 backend-owned Bedrock tests로 superseded | Bedrock Sonnet 4.6 adapter와 chat/slots/handoff endpoints를 구현한 뒤 `apps/backend/app/ai`로 이동했습니다. |
| P4 | Android phone/watch alert and text chat UI | Worker | Complete | `apps/mobile/...` Kotlin and docs | Source scan: PASS; `assembleDebug`: PASS; `testDebugUnitTest`: PASS | Phone/watch alert display와 text chat/handoff flow를 구현했고 STT/microphone UI는 추가하지 않았습니다. |
| P5 | Main verification and progress sync | Main | Complete | `docs/specs/...` | Spec validation PASS; `git diff --check` PASS; Docker config PASS; phone/watch install PASS | Backend 통합과 mobile reconstruction 이후 최신 검증 상태로 업데이트했습니다. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.0 | 2026-07-09 | Feature Planner | Initial finalized spec pair for requested implementation. |
| 1.1 | 2026-07-09 | Feature Planner | 구현 완료 및 environment-limited verification 결과를 기록했습니다. |
| 1.2 | 2026-07-09 | Codex | Unified backend consolidation, Android build/unit tests, Docker config, device install PASS 상태를 반영했습니다. |
