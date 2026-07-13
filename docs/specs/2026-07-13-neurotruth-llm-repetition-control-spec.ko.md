# NeuroTruth LLM 반복 제어 명세

- **Language role:** Korean mirror spec
- **Spec status:** Finalized
- **Spec version:** 1.1
- **Last updated:** 2026-07-13
- **English source:** `neurotruth/docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.md`
- **Korean mirror:** `neurotruth/docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.ko.md`
- **Requester / owner:** NeuroTruth 프로젝트 운영자
- **Implementation status:** Implemented

## 0. Codex 구현 인계

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: neurotruth/docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.md
korean_mirror_spec: neurotruth/docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.ko.md
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

복사해 사용할 구현 프롬프트:

```text
$feature-planner Implement `neurotruth/docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.md` using IMPLEMENTATION_ORCHESTRATION. Use the English source spec as authoritative, keep `neurotruth/docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.ko.md` synchronized, spawn worker sub-agents for source-code edits, and have the main agent verify minimal diffs and update progress records. If the spec has a gap, use TARGETED_REFINEMENT for only that gap before continuing.
```

## 1. 요약

공개 API나 provider를 바꾸지 않고 NeuroTruth backend 소유 Bedrock 중재 대화의 반복 질문을 줄인다. 구현은 완료 상태를 인식하는 chat/slot 프롬프트, 누적 slot 전체를 이용한 정확한 missing-slot 계산, 새 비안전 질문이 최근 assistant 기록과 강하게 겹칠 때 한 번만 수행하는 결정론적 repair 호출을 결합한다. repair도 다른 응답을 만들지 못하면 backend가 반복 질문을 제거하고 질문 없는 짧은 확인 응답을 반환한다. 변경은 NeuroTruth backend AI/orchestration, 집중 테스트, 관련 AI 문서로 제한한다.

## 2. 목표

- G1. 사용자가 이미 답했거나 부정·모름·거부로 답한 주제를 모델이 다시 묻지 않게 한다.
- G2. 명시적인 부정·모름·거부 답변을 근거가 있는 완료 craving-slot 값으로 보존한다.
- G3. 누적된 current slot과 새 extraction을 합쳐 missing slot을 계산해 과거 완료 주제가 다시 missing이 되지 않게 한다.
- G4. 강한 반복 질문 후보를 감지·repair하면서 intervention API 응답 schema를 바꾸지 않는다.

## 3. 비목표

- NG1. Sonnet 4.6 provider, Bedrock credential, request transport, alert policy, mobile UI, database schema, handoff prompt, sensor inference를 변경하지 않는다.
- NG2. session 종료, turn 제한, embedding, 외부 NLP service, dependency를 추가하지 않는다.
- NG3. 임상 safety policy를 재설계하지 않으며 safety/probe/crisis 질문은 반복 억제에서 제외한다.
- NG4. 관련 없는 기존 NeuroTruth worktree 변경이나 과거 spec을 재작성하지 않는다.

## 4. 사용자와 사용 사례

### 4.1 대상 사용자

- craving alert 이후 NeuroTruth 텍스트 중재를 사용하는 환자.
- 대화 품질과 handoff readiness를 평가하는 연구자 또는 운영자.

### 4.2 주요 사용 사례

- UC1. 사용자가 craving 질문을 부정하거나 모른다거나 답변을 거부하면 assistant는 같은 주제를 다시 묻지 않고 진행한다.
- UC2. Sonnet이 최근 비안전 질문을 가깝게 바꾸어 말하면 backend는 한 번 repair 호출해 다른 응답을 반환한다.
- UC3. repair가 실패하거나 여전히 반복이면 backend는 질문이 아닌 부분 또는 안전한 확인 응답을 반환한다.

## 5. 최종 결정

| ID | 영역 | 결정 | 근거 |
| --- | --- | --- | --- |
| D1 | Chat prompt | 질문은 최대 1개, 공감 강제 금지, 실제 missing slot 하나 우선, 답변·부정·모름·거부 주제를 완료로 취급한다. | 사용자 요청과 검증된 NeuroSync 실험 결과 |
| D2 | Slot prompt | 명시적 부정·모름·거부를 짧은 사용자 원문 인용을 포함한 non-empty flat string으로 보존하고, 직전 단일 주제 assistant 질문에만 매핑한다. | 검증된 NeuroSync 실험 결과 |
| D3 | Missing state | filtered `currentSlots`와 non-empty 신규 extraction을 합쳐 `missingSlots`를 계산한다. | repository 결함 근거 |
| D4 | Detection | NFKC/case 정규화, 선행 확인 표현 제거, 문장부호·공백 제거 후 후보 질문을 최근 assistant 질문 3개와 비교한다. exact match 또는 표준 라이브러리 `SequenceMatcher` ratio `0.86` 이상을 사용한다. | Agent 보수적 기본값 |
| D5 | Repair | 반복 비안전 질문이면 동일 model 설정과 명확한 비반복 지시로 추가 Bedrock 호출을 최대 한 번 수행한다. | Agent 보수적 기본값 |
| D6 | Fallback | repair가 실패하거나 계속 반복이면 반복 의문문을 제거하고, 의미 있는 내용이 남지 않으면 질문 없는 고정 한국어 확인 응답을 반환한다. | Agent 보수적 기본값 |
| D7 | Safety | 자살, 자해, 타해, 즉각 위험, 응급 도움, 명시적 safety/probe/crisis 내용은 감지·repair를 우회한다. | Safety 보존 기본값 |
| D8 | Compatibility | `/api/intervention/chat`, `/api/intervention/slots`, `/api/llm/chat` request/response shape를 유지하며 repair metadata를 공개하지 않는다. | Agent 보수적 기본값 |

## 6. 기능 요구사항

- FR1. `CHAT_SYSTEM_PROMPT`는 현재 history, `currentSlots`, `missingSlots`를 우선하고 간결한 질문을 최대 1개만 하며 완료 주제를 바꾸어 다시 묻지 않는다.
- FR2. 중립적인 사실 답변에 template 충족만을 위한 감정이나 공식적인 공감을 만들지 않는다.
- FR3. `build_chat_messages`는 per-turn context에 filtered current slot과 결정론적 missing-slot 목록을 포함한다.
- FR4. `SLOTS_SYSTEM_PROMPT`는 user-role 내용을 사실 근거로 사용하고 assistant turn은 직전 질문 주제를 확인하는 데만 사용한다.
- FR5. 명시적 부정·모름·거부는 해당 주제 하나의 grounded non-empty flat value가 되며 관련 없는 slot은 null 또는 unchanged다.
- FR6. 기존 grounded `currentSlots`를 null, empty, 더 약한 추론으로 덮어쓰지 않는다.
- FR7. `_ai_slots_extract`는 누적 merge slot state에서 `missingSlots`를 계산하면서 기존 route-level merge와 allowlist filtering을 유지한다.
- FR8. 반복 감지는 질문을 포함한 assistant 응답과 최근 assistant turn 3개만 검사한다.
- FR9. 반복 비안전 후보는 repair 호출을 한 번만 발생시키며 repair loop는 금지한다.
- FR10. repair 실패 또는 반복 repair에는 결정론적 local question 제거/fallback을 사용하고 기존 성공 chat response schema를 반환한다.
- FR11. Safety-exempt 후보는 repair나 억제 없이 바로 반환한다.

## 7. 사용자 경험 / UI 요구사항

- UI1. Mobile 또는 web UI 변경은 필요하지 않다.
- UI2. 사용자는 반복 질문 감소와 끊기지 않는 API 응답만 보며 새 status label이나 repair 알림은 보지 않는다.

## 8. API / 데이터 / 상태 요구사항

- API1. 공개 endpoint payload와 response key의 하위 호환성을 유지한다.
- DATA1. Slot value는 기존 JSON-compatible `Any`를 유지하며 부정·모름·거부 완료 상태는 flat string을 사용해 migration이 필요 없다.
- STATE1. Client가 제공하는 `conversationHistory`와 `currentSlots`가 cross-turn state의 원천이며 새 server-side repetition store를 추가하지 않는다.

## 9. 권한, 보안, 개인정보, 감사

- SEC1. 기존 Bedrock credential 처리와 secret redaction 동작을 유지한다.
- PRIV1. 전체 prompt, 사용자 메시지, repair 비교 텍스트를 log하지 않는다.
- AUDIT1. 새 persistence나 공개 audit field를 추가하지 않으며 집중 테스트를 구현 증거로 사용한다.

## 10. 오류, 경계 사례, 동시성 동작

- ERR1. 최초 Bedrock 호출 실패 시 기존 HTTP 502 동작을 유지한다.
- ERR2. 선택적 repair 호출만 실패하면 성공한 최초 응답을 HTTP 502로 바꾸지 않고 결정론적 fallback을 사용한다.
- EDGE1. 질문이 없는 응답은 repair를 발생시키지 않는다.
- EDGE2. 같은 craving 대화 안이라도 다른 질문이면 변경 없이 반환한다.
- EDGE3. 잘못된 slot JSON은 기존 safe empty extraction을 유지하지만 missing-slot 계산에는 유효한 `currentSlots`를 포함한다.
- CONC1. 모든 감지 상태는 request payload 내부에만 있고 공유 mutable global session state를 추가하지 않는다.

## 11. 의존성과 설정

- DEP1. Python 표준 라이브러리 `unicodedata`, `re`, `difflib.SequenceMatcher`만 재사용한다.
- DEP2. Package, environment variable, lockfile, model 설정을 추가하지 않는다.

## 12. Migration, rollout, rollback

- MIG1. Database 또는 output migration은 필요하지 않다.
- ROLL1. 공개 API 계약이 동일하므로 기존 backend와 함께 배포하며 live smoke 전에 mock Bedrock 호출로 검증한다.
- BACK1. 범위 내 backend AI/orchestration/docs/test 파일만 되돌리며 저장 session은 계속 호환된다.

## 13. 구현 경계

### 13.1 예상 변경 영역

- `neurotruth/apps/backend/app/ai/bedrock_agents.py`
- `neurotruth/apps/backend/app/main.py`
- `neurotruth/apps/backend/tests/test_bedrock_agents.py`
- `neurotruth/apps/backend/tests/test_intervention_routes.py`
- `neurotruth/docs/ai/` 아래의 관련 영문/한글 파일

### 13.2 금지 변경

- Mobile, database, inference, alert, credential, deployment, dependency, handoff generation 동작을 수정하지 않는다.
- `neurosync`, `neurosync_LLM_test`, `neurosync_test`를 수정하지 않는다.
- 관련 없는 NeuroTruth worktree 변경을 revert하거나 reformat하지 않는다.
- 관련 없는 refactor, formatting churn, dependency upgrade, broad rewrite, generated-file churn을 하지 않는다.

### 13.3 최소 변경 지침

- 반복 helper는 기존 Bedrock helper module 안에 pure하고 unit-test 가능하게 둔다.
- 검증된 gap이 없는 한 orchestration 변경은 `_ai_chat_respond`와 `_ai_slots_extract` 안에 둔다.
- 기존 function signature와 response shape를 유지한다.

## 14. 인수 기준

- AC1. 완료된 slot이 있을 때 chat context를 만들면 `currentSlots`와 정확한 `missingSlots`가 모두 포함된다.
- AC2. current slot과 신규 extraction이 있을 때 missing slot을 반환하면 과거 완료 key는 missing에 포함되지 않는다.
- AC3. 단일 주제 질문 뒤 명시적 부정·모름·거부가 오면 slot prompt는 grounded non-empty value 하나를 저장하고 다른 slot으로 확대하지 않도록 지시한다.
- AC4. 최근 exact 또는 강하게 유사한 비안전 질문이면 backend가 repair 호출을 정확히 한 번 수행한다.
- AC5. 다른 질문, 질문 없는 응답, safety 질문이면 repair를 호출하지 않는다.
- AC6. Repair 실패 또는 반복 repair여도 응답은 HTTP-success-compatible이고 반복 질문을 포함하지 않는다.
- AC7. 기존 backend 테스트 24개와 신규 집중 테스트가 외부 network 없이 통과한다.
- AC8. 구현 경계 밖 파일을 이 feature가 변경하지 않는다.

## 15. 검증 계획

| 검사 | 명령 또는 방법 | 기대 결과 |
| --- | --- | --- |
| Spec pair | `python validate_spec_pair.py neurotruth/docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.md` | PASS |
| Backend tests | `apps/backend/.venv/Scripts/python.exe -B -m pytest -q` from `neurotruth/apps/backend` | 기존 및 집중 테스트 통과, network 없음 |
| Compile | `apps/backend/.venv/Scripts/python.exe -B -m compileall -q app tests` | Exit 0 |
| Scope | 변경 파일 목록을 section 13과 비교 | 예상 파일과 이 spec pair만 변경 |
| Live provider | 자동 실행하지 않음 | 사용자가 이후 수동 smoke 가능 |

## 16. 위험과 참고사항

- RISK1. 텍스트 유사도는 보수적인 heuristic이며 semantic repeat는 prompt가 처리하고 repair guard는 exact·강한 유사 표현을 대상으로 한다.
- RISK2. Cross-turn 효과는 client history 품질에 의존하며 누락된 history를 이 stateless 변경이 복원할 수 없다.
- RISK3. NeuroTruth repository는 이미 대규모 restructure로 dirty하므로 worker는 명시적으로 허용된 현재 파일만 수정해야 한다.

## 17. 구현 체크리스트 / 진행 기록

| ID | 작업 / 범위 | 담당 | 상태 | 변경 파일 | 검증 | 비고 |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | 현재 backend 점검 및 baseline 확립 | Main | Completed | Spec pair only | 기존 backend 24 tests PASS | Repository에 기존 unrelated 변경이 있음. |
| P2 | Prompt, slot state, repetition repair 구현 | Worker | Completed | `apps/backend/app/ai/bedrock_agents.py`, `apps/backend/app/main.py`, 집중 test 2개, AI agent doc 4개 | Worker: 38 tests PASS, compile exit 0 | 후속 교정으로 명시적 사용자 slot update는 보존하고 null/empty output은 기존 값을 덮어쓰지 않음, live Bedrock 호출 없음. |
| P3 | 독립 검증 및 진행 기록 동기화 | Main | Completed | Spec pair only | Main: 38 tests PASS, compile exit 0, spec validation PASS, 변경 시간대 파일이 section 13과 일치 | 기존 Starlette `python_multipart` pending-deprecation warning만 있음, public API와 handoff 동작 유지. |

## 18. 개정 이력

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.0 | 2026-07-13 | Feature Planner | NeuroTruth 내부 LLM 반복 제어 초기 확정 명세. |
| 1.1 | 2026-07-13 | Codex | Prompt, 명시적 정정을 지원하는 누적 slot, 1회 repair, fallback, 문서, 독립 검증 완료 기록. |
