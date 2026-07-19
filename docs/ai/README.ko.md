# NeuroTruth AI 작업공간

최종 업데이트: 2026-07-19

## 목적

이 폴더는 단일 계층형 FastAPI process에서 backend가 소유하는 AI 동작을 문서화합니다. Bedrock adapter는 `apps/backend/app/agents/bedrock.py`, 기능별 owner는 `agents/{intervention,state_summary,report}.py`, versioned text는 `prompts/*.py`, 인증 session orchestration은 `services/session.py`에 있습니다.

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

- 갈망 alert level과 state class 판단은 backend deterministic code가 담당합니다. 현재 free-dialogue safety 판단은 LLM-only이며 탐지나 긴급 연락을 보장하지 않고, 성공한 turn도 intervention row를 만들지 않습니다.
- Intervention agent는 output repair를 한 번 수행합니다. Provider 실패 또는 두 번째 invalid output은 정제된 HTTP `502`를 반환하며 fallback assistant message를 저장하지 않습니다.
- State-summary와 report agent는 기본값 `STATE_SUMMARY_AI_ENABLED=false`, `REPORT_AI_ENABLED=false` 뒤에 보존되며 deterministic class를 바꾸지 않습니다.
- Android 앱은 backend endpoint만 호출합니다.
- Backend는 암호화된 AI turn, dialogue ledger, intervention, state inference, report를 sensor prediction과 연결된 UUID session에 저장합니다.
- State-summary AI가 꺼져도 deterministic evidence는 저장되고 snapshot은 `summaryStatus="unavailable"`, `summary=null`을 반환합니다. Bedrock 호출과 state-summary model-version 등록은 없습니다.
- Report AI가 꺼지면 자동 생성을 건너뛰고 `POST .../reports`는 HTTP `202`와 `{"reportId":null,"version":null,"status":"not_started"}`를 반환합니다. 기존 `GET` 이력은 유지되고 이력이 없으면 `[]`이며 Bedrock 호출과 report model-version 등록은 없습니다.
- 각 플래그를 켜면 보존된 Bedrock 동작이 복원됩니다. LLM provider는 계속 backend만 소유합니다.
- Dialogue prompt는 versioned optional question guide를 사용하고 asked/refused topic을 다시 묻지 않으며 오류 반환 전에 validation repair를 한 번 수행합니다.
- 신규 session은 legacy slot 또는 memory를 쓰지 않습니다. 기존 13-slot session/report는 backfill 없이 read-only로 남습니다.

## Prompt 위치

현재 prompt와 orchestration 소유 위치는 다음과 같습니다.

```text
apps/backend/app/agents/{intervention,state_summary,report}.py
apps/backend/app/prompts/{intervention,state_summary,report}.py
apps/backend/app/services/session.py
```

| Constant | 역할 |
|---|---|
| `free-dialogue-v4-met-cbt-informed` | Safety-aware free dialogue |
| `state-summary-v1` | Evidence-only state summary |
| `state-rule-v1` | Deterministic state inference version |
| `niaaa-samhsa-who-ko-v1` | Optional Korean question guide |
| report prompt version | Evidence-linked asynchronous report |

## Dialogue Topic

선택형 topic ID는 `safety`, `current_environment`, `alcohol_access`, `trigger`, `emotion_body`, `past_coping`, `support`, `desired_help`입니다. 필수 field나 questionnaire가 아니라 대화 guide입니다. 답변 거부를 존중하며 completion percentage를 계산하지 않습니다.

## Agent 문서

| 문서 | 목적 |
|---|---|
| [Agent 개요](agents/README.ko.md) | Backend AI 공통 규칙 |
| [대화 Agent](agents/01_dialogue_agent.ko.md) | 텍스트 중재 동작 |
| [Legacy Slot 추출 Agent](agents/02_slot_extraction_agent.ko.md) | Read-only historical slot contract |
| [Report Agent](agents/03_handoff_agent.ko.md) | Evidence-linked report 생성/status |

영어 원본: [AI workspace](README.md)

## 최신 검증

- Network-free 검증은 fake adapter로 dialogue output validation, repeated-topic control, deterministic intervention/state inference, 비동기 report failure isolation을 확인합니다.
- `us-east-1`에서 GPT-5.5 Mantle Responses 최소 실제 호출이 통과했습니다. 이전 `us.anthropic.claude-sonnet-4-6` bearer chat/handoff 통과 기록은 rollback 경로의 과거 검증 이력으로 보존합니다.
- Bearer token이나 credential 값은 문서 또는 테스트 출력에 기록하지 않습니다.
- 한국어 STT는 구현된 선택적·기본 OFF 기능이고, 카메라 rPPG는 구현된 기본 ON 기능이지만 동의와 준비 상태 gate를 유지합니다. AI 음성 출력은 Android 로컬 TTS입니다. 설계가 deferred된 것은 아니지만 실제 기기/DGX 인수 검증은 남아 있습니다.
