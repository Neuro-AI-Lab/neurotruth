# NeuroTruth Agent Overview

Last updated: 2026-07-13

## Public Backend Endpoints

| Agent behavior | Backend endpoint |
|---|---|
| Dialogue | `/api/intervention/chat` |
| Slot extraction | `/api/intervention/slots` |
| Handoff, synchronous compatibility | `/api/intervention/handoff` |
| Handoff job submission | `/api/intervention/handoff/jobs` |
| Handoff job status | `/api/intervention/handoff/jobs/{job_id}` |

The Bedrock adapter and prompt builders live in `apps/backend/app/ai/bedrock_agents.py`.

Prompt constants in that file:

```text
CHAT_SYSTEM_PROMPT
SLOTS_SYSTEM_PROMPT
HANDOFF_SYSTEM_PROMPT
```

Korean mirror: [Agent overview Korean](README.ko.md).

## Shared Rules

- Do not make alert decisions with the LLM.
- Do not infer alcohol use from sensor data alone.
- Preserve user wording when summarizing sensitive context.
- Do not invent missing slot values.
- Avoid diagnosis, medication advice, or clinical certainty claims.
- Preserve the request session ID so dialogue, slots, and handoff remain linked in backend memory.
- Do not expose provider raw failures or credentials through asynchronous job status.
