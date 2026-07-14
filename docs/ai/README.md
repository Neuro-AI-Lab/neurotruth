# NeuroTruth AI Workspace

Last updated: 2026-07-13

## Purpose

This folder documents backend-owned AI behavior. The implementation lives in `apps/backend/app/ai`.

## Provider

NeuroTruth uses AWS Bedrock GPT-5.5 through the Bedrock Mantle Responses API.
The `openai.gpt-5.5` model requires `AWS_BEARER_TOKEN_BEDROCK`. Non-OpenAI
model IDs retain the previous Bedrock Runtime `converse` bearer/IAM behavior as
a configuration-only rollback path.

```text
AWS_BEARER_TOKEN_BEDROCK=<bedrock-api-key>
BEDROCK_MODEL_ID=openai.gpt-5.5
AWS_REGION=us-east-1
BEDROCK_TIMEOUT_SECONDS=60
```

GPT-5.5 requires `AWS_BEARER_TOKEN_BEDROCK` and a Mantle-supported region; the
current verified region is `us-east-1`. IAM role, AWS profile, and standard AWS
environment credentials remain available for non-OpenAI Converse models.

## Boundary

- Backend deterministic code decides craving alert level.
- Backend AI helpers generate text responses, extract slots, and draft handoff reports.
- Android calls backend endpoints only.
- Backend memory persists AI turns, slots, and reports under the same session ID used by sensor predictions.
- Mobile handoff generation is accepted as a backend job and polled separately; the LLM provider remains backend-only.
- Dialogue prompts and orchestration avoid re-asking filled slots and perform one deterministic repair when a non-safety question repeats recent assistant history.

## Prompt Location

System prompts are defined in:

```text
apps/backend/app/ai/bedrock_agents.py
```

| Constant | Role |
|---|---|
| `CHAT_SYSTEM_PROMPT` | Dialogue agent |
| `SLOTS_SYSTEM_PROMPT` | Slot extraction agent |
| `HANDOFF_SYSTEM_PROMPT` | Handoff report agent |

## Required Slot Keys

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

## Agent Docs

| Document | Purpose |
|---|---|
| [Agent overview](agents/README.md) | Shared backend AI conventions |
| [Dialogue agent](agents/01_dialogue_agent.md) | Text intervention behavior |
| [Slot extraction agent](agents/02_slot_extraction_agent.md) | Craving slot extraction |
| [Handoff agent](agents/03_handoff_agent.md) | Handoff report generation |

## Korean Docs

| Document | Purpose |
|---|---|
| [AI workspace Korean](README.ko.md) | Korean mirror of this overview |
| [Agent overview Korean](agents/README.ko.md) | Korean shared backend AI conventions |
| [Dialogue agent Korean](agents/01_dialogue_agent.ko.md) | Korean text intervention behavior |
| [Slot extraction agent Korean](agents/02_slot_extraction_agent.ko.md) | Korean craving slot extraction |
| [Handoff agent Korean](agents/03_handoff_agent.ko.md) | Korean handoff report generation |

## Latest Validation

- Mocked Bedrock chat, JSON parsing, slot filtering, failure handling, and handoff tests passed.
- Backend network-free suite passes 50 tests, including GPT-5.5 Mantle routing/parsing, Converse rollback, repeated-question control, and asynchronous handoff job lifecycle/error isolation.
- A minimal live GPT-5.5 Mantle Responses request passed in `us-east-1`. The earlier bearer-token chat/handoff pass with `us.anthropic.claude-sonnet-4-6` is retained as historical rollback validation.
- No bearer token or credential value is written to documentation or test output.
