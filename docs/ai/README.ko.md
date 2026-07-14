# NeuroTruth AI 작업공간

최종 업데이트: 2026-07-13

## 목적

이 폴더는 backend가 소유하는 AI 동작을 문서화합니다. 구현 코드는 `apps/backend/app/ai`에 있습니다.

## Provider

NeuroTruth는 AWS Bedrock Mantle Responses API를 통해 GPT-5.5를 사용합니다. `openai.gpt-5.5` 모델에는 `AWS_BEARER_TOKEN_BEDROCK`이 필수입니다. OpenAI 이외의 model ID는 설정만 되돌려 사용할 수 있도록 기존 Bedrock Runtime `converse` bearer/IAM 동작을 유지합니다.

```text
AWS_BEARER_TOKEN_BEDROCK=<bedrock-api-key>
BEDROCK_MODEL_ID=openai.gpt-5.5
AWS_REGION=us-east-1
BEDROCK_TIMEOUT_SECONDS=60
```

GPT-5.5에는 `AWS_BEARER_TOKEN_BEDROCK`과 Mantle 지원 region이 필요하며 현재 검증된 region은 `us-east-1`입니다. IAM role, AWS profile, 표준 AWS 환경변수 credential은 OpenAI 이외의 Converse 모델에서 계속 사용할 수 있습니다.

## 책임 경계

- 갈망 alert level 판단은 backend의 deterministic code가 담당합니다.
- Backend AI helper는 텍스트 응답 생성, slot extraction, handoff report 초안을 담당합니다.
- Android 앱은 backend endpoint만 호출합니다.
- Backend memory는 AI turn, slot, report를 sensor prediction과 동일한 session ID로 저장합니다.
- Mobile handoff generation은 backend job으로 접수하고 별도로 polling하며 LLM provider는 계속 backend만 소유합니다.
- Dialogue prompt/orchestration은 채워진 slot을 다시 질문하지 않고, 최근 assistant 질문과 겹치는 non-safety 질문을 한 번 deterministic repair합니다.

## Prompt 위치

System prompt는 아래 파일에 정의되어 있습니다.

```text
apps/backend/app/ai/bedrock_agents.py
```

| Constant | 역할 |
|---|---|
| `CHAT_SYSTEM_PROMPT` | 대화 agent |
| `SLOTS_SYSTEM_PROMPT` | Slot extraction agent |
| `HANDOFF_SYSTEM_PROMPT` | Handoff report agent |

## 필수 Slot Key

```text
trigger
duration
intensity
recent_alcohol_use
physiological_context
coping_attempt
safety_concern
support_context
intervention_summary
```

## Agent 문서

| 문서 | 목적 |
|---|---|
| [Agent 개요](agents/README.ko.md) | Backend AI 공통 규칙 |
| [대화 Agent](agents/01_dialogue_agent.ko.md) | 텍스트 중재 동작 |
| [Slot 추출 Agent](agents/02_slot_extraction_agent.ko.md) | 갈망 slot extraction |
| [Handoff Agent](agents/03_handoff_agent.ko.md) | Handoff report 생성 |

영어 원본: [AI workspace](README.md)

## 최신 검증

- Mock Bedrock 기반 chat, JSON parsing, slot filtering, 오류 처리, handoff 테스트가 통과했습니다.
- GPT-5.5 Mantle routing/parsing, Converse rollback, 반복질문 제어, 비동기 handoff job lifecycle/error 격리를 포함한 backend network-free 테스트 50개가 통과했습니다.
- `us-east-1`에서 GPT-5.5 Mantle Responses 최소 실제 호출이 통과했습니다. 이전 `us.anthropic.claude-sonnet-4-6` bearer chat/handoff 통과 기록은 rollback 경로의 과거 검증 이력으로 보존합니다.
- Bearer token이나 credential 값은 문서 또는 테스트 출력에 기록하지 않습니다.
