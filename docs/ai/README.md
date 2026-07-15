# NeuroTruth AI Workspace

Last updated: 2026-07-15

## Purpose

This folder documents backend-owned AI behavior. Provider adapters live in `apps/backend/app/ai`; the authenticated intervention-first orchestration and versioned question/prompt policy live in `apps/backend/app/v25/session_agents.py` and `session_service.py`.

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

- Backend deterministic code decides craving alert level, safety handling, the first intervention, and state class.
- Backend AI helpers continue allowlisted intervention dialogue, summarize supplied evidence, and draft reports without changing deterministic classes.
- Android calls backend endpoints only.
- Backend persists encrypted AI turns, dialogue ledger, interventions, state inferences, and reports under the UUID session linked to sensor predictions.
- Mobile report generation/status is asynchronous; the LLM provider remains backend-only.
- Dialogue prompts use a versioned optional question guide, avoid re-asking asked/refused topics, and perform one validation repair before a deterministic fallback.
- New sessions never write legacy slots or memory. Historical 13-slot sessions and reports remain read-only without backfill.

## Prompt Location

Current intervention-first prompt, rule, and question-bank versions are defined in:

```text
apps/backend/app/v25/session_agents.py
```

| Constant | Role |
|---|---|
| `intervention-dialogue-v1` | Safety-aware intervention dialogue |
| `state-summary-v1` | Evidence-only state summary |
| `state-rule-v1` | Deterministic state inference version |
| `niaaa-samhsa-who-ko-v1` | Optional Korean question guide |
| report prompt version | Evidence-linked asynchronous report |

## Dialogue Topics

The optional topic IDs are `safety`, `current_environment`, `alcohol_access`, `trigger`, `emotion_body`, `past_coping`, `support`, and `desired_help`. They are conversation guidance, not required fields or a questionnaire. Refusal is respected and no completion percentage is calculated.

## Agent Docs

| Document | Purpose |
|---|---|
| [Agent overview](agents/README.md) | Shared backend AI conventions |
| [Dialogue agent](agents/01_dialogue_agent.md) | Text intervention behavior |
| [Legacy slot extraction agent](agents/02_slot_extraction_agent.md) | Read-only historical slot contract |
| [Report agent](agents/03_handoff_agent.md) | Evidence-linked report generation/status |

## Korean Docs

| Document | Purpose |
|---|---|
| [AI workspace Korean](README.ko.md) | Korean mirror of this overview |
| [Agent overview Korean](agents/README.ko.md) | Korean shared backend AI conventions |
| [Dialogue agent Korean](agents/01_dialogue_agent.ko.md) | Korean text intervention behavior |
| [Legacy slot extraction agent Korean](agents/02_slot_extraction_agent.ko.md) | Korean read-only historical slot contract |
| [Report agent Korean](agents/03_handoff_agent.ko.md) | Korean report generation/status |

## Latest Validation

- Network-free validation uses fake adapters for dialogue output validation, repeated-topic control, deterministic interventions/state inference, and asynchronous report failure isolation.
- A minimal live GPT-5.5 Mantle Responses request passed in `us-east-1`. The earlier bearer-token chat/handoff pass with `us.anthropic.claude-sonnet-4-6` is retained as historical rollback validation.
- No bearer token or credential value is written to documentation or test output.
