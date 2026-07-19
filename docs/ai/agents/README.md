# NeuroTruth Agent Overview

Last updated: 2026-07-19

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

- Craving alert decisions and state classes are deterministic backend rules. Current free-dialogue safety interpretation is LLM-only and is not a reliable emergency detector.
- Do not infer alcohol use, intoxication, relapse, diagnosis, or treatment success from sensor data.
- New sessions have no slot coverage or `handoffReady`. The optional question bank guides context-sensitive dialogue without a completion target.
- Ask at most one short question per assistant turn. Never directly or indirectly re-ask an already asked/refused topic unless the patient explicitly corrects it.
- Preserve uncertainty and user wording; do not invent missing facts.
- Link dialogue, interventions, state inference, and reports to their actual `model_versions`/`prompt_version` and UUID session.
- Sensitive content is AES-256-GCM encrypted. Public errors expose no provider payload, credential, token, key, path, or decrypted content.
- Immediate-risk guidance may include 119 and Korean suicide-prevention line 109. Offer once to record requested administrator involvement, continue after acceptance/refusal, and never promise live connection or immediate contact.
- `interventionsEnabled=false` suppresses normal intervention wording/persistence only; safety guidance remains active.
- New free-dialogue turns store messages and the question/refusal ledger but do not create intervention rows. One output repair is allowed; provider failure or a second invalid output returns sanitized HTTP `502` with no fallback reply.

## State Inference Boundary

Deterministic code copies the latest valid persisted model class to `low|mid|high|unknown` and stores concrete prediction, AUQ, alert, session, and intervention evidence references. AUQ and dialogue never mathematically alter that class. With default `STATE_SUMMARY_AI_ENABLED=false`, deterministic evidence persists, public summaries are `unavailable`/`null`, and no summary call or model-version registration occurs. Realtime and longitudinal views are descriptive records, not diagnosis, prognosis, treatment outcome, or causal analysis.

With default `REPORT_AI_ENABLED=false`, report calls and model-version registration are skipped, POST returns HTTP `202`/`not_started`, and historical GET remains readable or returns `[]`. Enabling either AI flag restores the preserved behavior. AUQ is optional and no questionnaire completion is required. Historical 13-slot sessions remain read-only without backfill. Korean STT is optional/default-off; camera rPPG is default-on but consent- and readiness-gated; TTS is Android-local. Self-event capture, wearable-absent AUQ automation, craving-model experiments, live administrator chat, emergency dispatch, and bulk data download remain outside the core release.

Korean mirror: [README.ko.md](README.ko.md).
