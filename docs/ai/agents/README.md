# NeuroTruth Agent Overview

Last updated: 2026-07-15

The backend owns every agent prompt, model-version reference, encrypted result, and safety rule. Mobile sends authenticated patient messages to a server-issued UUID session; it does not carry prompts or call Bedrock directly.

## Current API

| Behavior | Endpoint |
|---|---|
| Open/resume session | `POST /api/sessions`, `GET /api/sessions/{sessionId}` |
| Safety-aware intervention dialogue | `POST /api/sessions/{sessionId}/messages` |
| AUQ | `POST /api/sessions/{sessionId}/assessments` |
| Manual finish | `POST /api/sessions/{sessionId}/finish` |
| Report generation/status | `POST/GET /api/sessions/{sessionId}/reports` |

All routes require an authenticated patient and ownership. The old `/api/llm/chat` and `/api/intervention/*` routes are not current agent APIs.

## Shared Rules

- Alert decisions, safety handling, and the first intervention are deterministic backend rules, never LLM choices.
- Do not infer alcohol use, intoxication, relapse, diagnosis, or treatment success from sensor data.
- New sessions have no slot coverage or `handoffReady`. The optional question bank guides context-sensitive dialogue without a completion target.
- Ask at most one short question per assistant turn. Never directly or indirectly re-ask an already asked/refused topic unless the patient explicitly corrects it.
- Preserve uncertainty and user wording; do not invent missing facts.
- Link dialogue, interventions, state inference, and reports to their actual `model_versions`/`prompt_version` and UUID session.
- Sensitive content is AES-256-GCM encrypted. Public errors expose no provider payload, credential, token, key, path, or decrypted content.
- Immediate-risk guidance may include 119 and Korean suicide-prevention line 109. Offer once to record requested administrator involvement, continue after acceptance/refusal, and never promise live connection or immediate contact.
- `interventionsEnabled=false` suppresses normal intervention wording/persistence only; safety guidance remains active.

## State Inference Boundary

Deterministic code copies the latest valid persisted model class to `low|mid|high|unknown` and stores concrete prediction, AUQ, alert, session, and intervention evidence references. AUQ and dialogue never mathematically alter that class. The LLM may summarize only the supplied evidence; summary failure leaves the deterministic inference intact as `unavailable`. Realtime and longitudinal views are descriptive records, not diagnosis, prognosis, treatment outcome, or causal analysis.

The first ordinary intervention is selected after deterministic safety handling; AUQ is optional and no questionnaire completion is required. Historical 13-slot sessions remain read-only without backfill. Voice/STT/TTS, rPPG, self-event capture, wearable-absent AUQ automation, craving-model experiments, live administrator chat, emergency dispatch, and bulk data download are outside the core release.

Korean mirror: [README.ko.md](README.ko.md).
