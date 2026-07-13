# NeuroTruth Bedrock GPT-5.5 전환 명세

- **Language role:** Korean mirror spec
- **Spec status:** Finalized
- **Spec version:** 1.1
- **Last updated:** 2026-07-13
- **English source:** `neurotruth/docs/specs/2026-07-13-neurotruth-bedrock-gpt-55-migration-spec.md`
- **Korean mirror:** `neurotruth/docs/specs/2026-07-13-neurotruth-bedrock-gpt-55-migration-spec.ko.md`
- **Requester / owner:** NeuroTruth project requester
- **Implementation status:** Implemented

## 0. Codex 구현 핸드오프

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: neurotruth/docs/specs/2026-07-13-neurotruth-bedrock-gpt-55-migration-spec.md
korean_mirror_spec: neurotruth/docs/specs/2026-07-13-neurotruth-bedrock-gpt-55-migration-spec.ko.md
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

NeuroTruth의 chat, slot extraction, repair, handoff generation에 Amazon Bedrock의 OpenAI GPT-5.5를 사용합니다. GPT-5.6을 먼저 확인했지만 `not_found_error`가 반환되었고, 이어서 GPT-5.5가 `us-east-1` 실제 호출에서 성공하는 것을 확인했습니다. GPT-5.5는 `bedrock-mantle` OpenAI Responses API 전용이므로 `openai.*` model ID에는 Mantle protocol을 선택하고, rollback을 위해 기존 Converse 경로는 보존합니다.

## 2. 목표

- G1. `openai.gpt-5.5`를 NeuroTruth 설정 모델로 사용합니다.
- G2. 중재 route를 바꾸지 않고 Bedrock Mantle Responses API payload와 response parsing을 지원합니다.
- G3. 기존 Claude Converse 구현을 설정만으로 되돌릴 수 있는 rollback 경로로 보존합니다.

## 3. 비목표

- NG1. AWS에서 존재하지 않는다고 반환한 GPT-5.6은 추가하지 않습니다.
- NG2. prompt, 반복 방지 정책, slot schema, mobile UI, database schema, public intervention API를 변경하지 않습니다.
- NG3. OpenAI Python package를 추가하지 않고 기존 standard-library HTTP 방식을 사용합니다.

## 4. 사용자 및 사용 사례

### 4.1 대상 사용자

- NeuroTruth 운영자와 모바일 중재 사용자.

### 4.2 주요 사용 사례

- UC1. Backend chat, slots, handoff 호출이 mobile 변경 없이 GPT-5.5를 사용합니다.
- UC2. 운영자는 Claude model ID를 복원해 기존 Converse 동작으로 되돌릴 수 있습니다.

## 5. 최종 결정

| ID | 영역 | 결정 | 출처 |
| --- | --- | --- | --- |
| D1 | Model | `openai.gpt-5.5`를 사용하며 GPT-5.6은 사용할 수 없습니다. | 사용자 우선순위 및 실제 검증 |
| D2 | API | `openai.*` ID는 `https://bedrock-mantle.{region}.api.aws/openai/v1/responses`로 route합니다. | AWS model contract |
| D3 | 인증 | `AWS_BEARER_TOKEN_BEDROCK`을 재사용하며 Mantle에서 없으면 명확히 실패합니다. | 기존 project credential |
| D4 | 호환성 | OpenAI가 아닌 model ID는 기존 bearer/IAM Converse 경로를 사용합니다. | Agent default |
| D5 | 설정 | 기본값과 local `.env` model ID를 `openai.gpt-5.5`로 설정하고 `AWS_REGION=us-east-1`을 유지합니다. | 사용자 요청 및 검증된 region |
| D6 | Inference parameter | Adapter의 `temperature` 인자는 보존하지만 실제 API가 지원하지 않으므로 GPT-5.5 request에서는 제외합니다. | 실제 protocol 검증 |

## 6. 기능 요구사항

- FR1. Adapter는 `model_id`가 `openai.`으로 시작하면 Mantle을 선택해야 합니다.
- FR2. Mantle request는 system prompt와 순서가 보존된 conversation message를 Responses API로 전송해야 합니다.
- FR3. Adapter는 `max_output_tokens`를 전달하지만 GPT-5.5에는 `temperature`를 보내면 안 되며, non-OpenAI Converse 호출은 현재 temperature 동작을 유지합니다.
- FR4. Response parsing은 text `output[].content[]`를 결합하고 빈 output을 거부해야 합니다.
- FR5. Provider error에서 credential이 public API response나 log에 노출되면 안 됩니다.
- FR6. 기존 non-OpenAI Converse 동작은 test로 계속 보호해야 합니다.

## 7. 사용자 경험 / UI 요구사항

- UI1. Mobile 및 web UI 변경은 없습니다.
- UI2. 기존 loading, timeout, retry, async handoff 동작을 유지합니다.

## 8. API / 데이터 / 상태 요구사항

- API1. 기존 `/api/intervention/chat`, `/api/intervention/slots`, handoff contract를 유지합니다.
- DATA1. Database 및 저장 transcript migration은 없습니다.
- STATE1. API response는 adapter에 설정된 `model` string을 계속 반환합니다.

## 9. 권한, 보안, 개인정보 및 감사

- SEC1. `AWS_BEARER_TOKEN_BEDROCK`을 출력하거나 저장하지 않습니다.
- PRIV1. Mantle에는 기존 Claude에 보내던 동일한 prompt와 conversation content만 보내고 추가 data는 보내지 않습니다.
- AUDIT1. 기존 application logging과 model response metadata를 유지합니다.

## 10. 오류, 엣지 케이스 및 동시성 동작

- ERR1. Mantle bearer credential이 없으면 정제된 configuration error를 발생시켜야 합니다.
- ERR2. HTTP, malformed response, empty response 오류는 기존 backend의 정제된 error path로 전달해야 합니다.
- EDGE1. Claude 또는 다른 non-OpenAI model ID는 계속 Converse를 사용해야 합니다.
- CONC1. 호출은 request별로 격리하고 기존 `asyncio.to_thread` 경계를 유지합니다.

## 11. 의존성 및 설정

- DEP1. 새 dependency를 추가하지 않습니다.
- DEP2. `BEDROCK_MODEL_ID` 기본값은 `openai.gpt-5.5`입니다.
- DEP3. GPT-5.5에는 `AWS_BEARER_TOKEN_BEDROCK`과 Mantle 지원 region이 필요하며 현재 검증 region은 `us-east-1`입니다.

## 12. 마이그레이션, 롤아웃 및 롤백

- MIG1. Data migration은 없습니다.
- ROLL1. Adapter, test, `.env.example`, local `.env` model ID와 활성 문서를 변경한 뒤 network-free test 통과 후 backend를 rebuild/restart합니다.
- BACK1. `BEDROCK_MODEL_ID`를 `us.anthropic.claude-sonnet-4-6`으로 되돌리고 backend를 restart하면 보존된 Converse 경로를 사용합니다.

## 13. 구현 경계

### 13.1 예상 변경 영역

- `apps/backend/app/ai/bedrock_agents.py`
- `apps/backend/tests/test_bedrock_agents.py`
- `.env.example`과 `.env`의 비밀이 아닌 `BEDROCK_MODEL_ID` 값
- Provider/model/protocol을 명시하는 활성 Markdown 문서

### 13.2 금지 변경

- Mobile, watch, database, prompt, slot schema, route payload, intervention state 동작.
- 관련 없는 refactor, formatting churn, dependency upgrade, broad rewrite, generated-file churn 금지.

### 13.3 최소 변경 지침

- 기존 adapter 내부에 protocol branch를 추가합니다.
- `urllib.request`와 현재 timeout/environment convention을 재사용합니다.
- 현재 public interface와 Claude rollback 동작을 보존합니다.

## 14. 인수 기준

- AC1. `BEDROCK_MODEL_ID=openai.gpt-5.5`이면 Mantle Responses endpoint를 사용하고 text를 추출합니다.
- AC2. Non-OpenAI model ID에서는 기존 Converse test가 계속 통과합니다.
- AC3. Missing token, HTTP failure, malformed output, empty output과 지원되지 않는 `temperature` 제외가 안전하게 처리되고 test됩니다.
- AC4. Dependency 추가 없이 모든 backend test와 compile check가 통과합니다.
- AC5. GPT-5.5 최소 실제 호출이 성공하고 restart 후 backend health가 유지됩니다.

## 15. 검증 계획

| 확인 | 명령 또는 방법 | 예상 결과 |
| --- | --- | --- |
| Spec pair | `validate_spec_pair.py docs/specs/2026-07-13-neurotruth-bedrock-gpt-55-migration-spec.md` | PASS |
| Unit tests | `apps/backend/.venv/Scripts/python.exe -m pytest` | 전체 PASS |
| Static check | `apps/backend/.venv/Scripts/python.exe -m compileall app tests` | Exit 0 |
| Live provider | `us-east-1`에서 최소 `openai.gpt-5.5` Responses request | Credential 출력 없이 성공 |
| Runtime smoke | Backend rebuild/restart 후 `/health` 호출 | HTTP 200, model ready |

## 16. 위험 및 참고사항

- RISK1. GPT-5.5는 Claude와 endpoint/schema가 다르므로 집중 adapter test로 protocol drift를 완화합니다.
- RISK2. 최초 접근은 일시적인 subscription setup error를 반환할 수 있으며, 자동 설정 완료 후 실제 access 성공을 확인했습니다.
- RISK3. Model 비용과 응답 style이 달라질 수 있으며 품질 평가는 운영자가 수행합니다.
- RISK4. GPT-5.5는 현재 `temperature`를 거부하며 public adapter argument는 Converse 호환성을 위해서만 유지합니다.
- RISK5. 최소 Mantle 요청은 통과했지만 최신 전체 `/api/intervention/chat` smoke는 slot extraction 단계에서 HTTP 502(`Bedrock slot request failed`)로 실패했습니다. 실제 상담을 release-ready로 판단하기 전에 이 통합 경로를 후속 수정해야 합니다.

## 17. 구현 체크리스트 / 진행 기록

| ID | 작업 / 범위 | 담당 | 상태 | 변경 파일 | 검증 | 참고 |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | AWS model availability와 account access 검증 | Main | Completed | 없음 | GPT-5.6: 404 not found, GPT-5.5: live success | Credential은 출력하지 않았습니다. |
| P2 | Mantle adapter/config/test/docs 구현 | Worker | Completed | `apps/backend/app/ai/bedrock_agents.py`, `apps/backend/tests/test_bedrock_agents.py`, `.env`, `.env.example`, 활성 Markdown 문서 | 집중 adapter test 22개 통과, compileall exit 0 | Converse rollback을 보존하고 지원되지 않는 `temperature`를 제외했습니다. |
| P3 | 전체 suite와 runtime 검증 | Main | Completed | Spec 진행 기록만 변경 | 50 tests passed, compileall exit 0, 실제 adapter 호출 성공, Docker rebuild 및 `/health` 통과 | Container model ID는 `openai.gpt-5.5`이며 기존 Starlette deprecation warning 1개가 남아 있습니다. |
| P4 | 전체 intervention route smoke 실행 | Main | Follow-up required | 문서만 변경 | Synthetic `POST /api/intervention/chat`가 HTTP 502 반환 | 최소 GPT-5.5 접근은 검증됐지만 slot extraction이 `Bedrock slot request failed`로 실패했습니다. Credential과 식별 정보는 기록하지 않았습니다. |

## 18. 개정 이력

| 버전 | 날짜 | 작성자 | 변경사항 |
| --- | --- | --- | --- |
| 1.0 | 2026-07-13 | Feature Planner | 실제 model 검증 후 GPT-5.5 migration 결정을 확정했습니다. |
| 1.0 | 2026-07-13 | Main verifier | 구현 완료, test, 실제 호출, runtime smoke 결과를 기록했습니다. |
| 1.1 | 2026-07-13 | Main verifier | 성공한 최소 provider 호출과 별도로 전체 intervention route의 slot-extraction 실패를 기록했습니다. |
