# NeuroTruth F1-Based Craving Intervention With Bedrock Sonnet 4.6 Spec

- **Language role:** English source spec
- **Spec status:** Finalized
- **Spec version:** 1.2
- **Last updated:** 2026-07-09
- **English source:** `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-f1-bedrock-craving-handoff-spec.md`
- **Korean mirror:** `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-f1-bedrock-craving-handoff-spec.ko.md`
- **Requester / owner:** NeuroTruth project requester
- **Implementation status:** Implemented; superseded by unified backend layout; latest mobile/backend checks passed

> Current status note (2026-07-09): The implemented system now uses backend-owned Bedrock agents in `apps/backend/app/ai`, not a separate `apps/ai-server`. Historical LLM-server references below describe the earlier F1 implementation step before consolidation. The mobile app has also been reconstructed from the working `watch_test` UX while preserving the same Android-facing API contract.

## 0. Codex Implementation Handoff

This section preserves the Feature Planner workflow when moving from planning/spec refinement to implementation.

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

Copy-paste implementation prompt:

```text
$feature-planner Implement `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-f1-bedrock-craving-handoff-spec.md` using IMPLEMENTATION_ORCHESTRATION. Use the English source spec as authoritative, keep `C:\Users\NeuroAI-Laptop\Desktop\Project\champion\neurotruth\docs\specs\2026-07-09-neurotruth-f1-bedrock-craving-handoff-spec.ko.md` synchronized, spawn worker sub-agents for source-code edits, and have the main agent verify minimal diffs and update progress records. If the spec has a gap, use TARGETED_REFINEMENT for only that gap before continuing.
```

## 1. Summary

NeuroTruth must support the demo flow documented in `회의록.md` and `PPT`: realtime craving detection, rule-based user alerting, text-based CBT-style intervention chat, craving slot extraction, and clinician handoff report generation. Existing `POST /sensor-window` and `GET /prediction-stream` behavior must remain backward-compatible for current Android clients that only read class `0/1/2`. NeuroSync F1 is the architectural reference for agent responsibility boundaries, but NeuroTruth must use alcohol-craving-specific slots and AWS Bedrock Claude Sonnet 4.6 as the only LLM provider.

## 2. Goals

- G1. Add a configurable, non-LLM rule engine that evaluates recent craving predictions and emits alert metadata.
- G2. Persist prediction, alert, chat, slot, and handoff memory in Postgres.
- G3. Replace the OpenAI-only LLM server path with Bedrock Sonnet 4.6 chat, slot extraction, and handoff endpoints.
- G4. Add text-first intervention chat in the Android phone app and show alert state on phone and watch.
- G5. Keep existing sensor upload, SSE prediction class delivery, and watch class display compatible.

## 3. Non-Goals

- NG1. No microphone recording UI or STT implementation in this version.
- NG2. No ADARP training, LOSO experiment, model retraining, or ML performance reporting.
- NG3. No broad NeuroSync monorepo copy or dependency-heavy full router port.
- NG4. No medical diagnosis or treatment directive; generated content is intervention support and clinician handoff assistance only.

## 4. Users and Use Cases

### 4.1 Target Users

- Alcohol craving monitoring demo operator.
- Patient using the Android phone and Galaxy Watch app.
- Clinician or researcher reviewing a generated handoff report.

### 4.2 Primary Use Cases

- UC1. A patient streams wearable sensor data, receives realtime craving class updates, and sees a recommendation or required intervention when the rule engine triggers.
- UC2. A patient enters text in a phone chat after an alert; NeuroTruth responds with CBT-style support and one concise question per turn.
- UC3. The system extracts craving-specific slots from chat history and generates a clinician-facing Markdown handoff report.

## 5. Final Decisions

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | Alert policy | Use conservative defaults: recent 10 prediction classes average less than or equal to `0.5` means `none`, `0.6` to `1.4` means `recommend`, and greater than or equal to `1.5` means `required`. | User decision |
| D2 | Alert configurability | Expose alert window, thresholds, high streak, cooldown, and downtrend suppression through env/config. | User decision |
| D3 | LLM provider | Use AWS Bedrock Claude Sonnet 4.6 only, default `BEDROCK_MODEL_ID=anthropic.claude-sonnet-4-6`, allow override such as `global.anthropic.claude-sonnet-4-6`. | User decision |
| D4 | Chat entry | Implement text input first; STT adapter and microphone UI are out of scope. | User decision |
| D5 | Memory | Persist memory in Postgres using idempotent schema creation and `infra/db/init.sql` updates. | User decision |
| D6 | NeuroSync reuse | Reuse F1 boundaries and prompt discipline, adapted to NeuroTruth craving/CBT slots, not a full copy. | User decision |

## 6. Functional Requirements

- FR1. `RealtimePredictionService` must attach alert metadata to each public craving SSE event after evaluating the latest predictions.
- FR2. Alert metadata must include `alertLevel`, `alertAction`, `windowMean`, `triggerReason`, and `alertRequired`.
- FR3. The public SSE event must still include `class`, `timestampMs`, `confidence`, and `sequence` when available.
- FR4. The backend must persist prediction events and alert decisions without blocking realtime inference if DB persistence fails.
- FR5. The backend must expose chat, slot extraction, and handoff proxy endpoints that call `LLM_SERVER_URL`.
- FR6. The LLM server must expose `/ai/chat/respond`, `/ai/slots/extract`, and `/ai/handoff/generate` using Bedrock Sonnet 4.6.
- FR7. The LLM server must keep `/generate` as a compatibility wrapper over the Bedrock chat path.
- FR8. Android phone SSE parsing must accept and store alert metadata while still accepting old class-only payloads.
- FR9. Android phone UI must show current alert state and provide a text chat surface.
- FR10. The phone must send alert-aware chat turns to the backend and display assistant responses and handoff readiness.
- FR11. The watch must receive and display the alert state alongside the existing craving class.

## 7. User Experience / UI Requirements

- UI1. Phone prediction card must show latest class plus alert label: none, recommendation, or required intervention.
- UI2. Phone chat panel must include a text input, send button, recent messages, and handoff readiness/report summary state.
- UI3. Watch UI must keep the current compact sensor layout and add alert status text/color without disrupting sensor rows.
- UI4. Copy must be Korean by default and avoid diagnostic or treatment-directive language.
- UI5. Empty state must remain usable when the LLM server is unavailable: show an error status and keep prediction streaming active.

## 8. API / Data / State Requirements

- API1. `GET /prediction-stream` event data must remain JSON and backward-compatible.
- API2. Backend chat endpoint: `POST /api/intervention/chat` accepts `sessionId`, `message`, optional `alert`, optional `conversationHistory`, and returns assistant response, merged slots, and handoff readiness.
- API3. Backend handoff endpoint: `POST /api/intervention/handoff` accepts `sessionId` and optional explicit slots/history, returns Markdown report and missing slots.
- API4. LLM server `/ai/chat/respond` accepts `sessionId`, `message`, `conversationHistory`, `slots`, and `alertContext`.
- API5. LLM server `/ai/slots/extract` accepts `sessionId`, `conversationHistory`, and `currentSlots`.
- API6. LLM server `/ai/handoff/generate` accepts `sessionId`, `slots`, `conversationHistory`, `alertEvents`, and `predictionSummary`.
- DATA1. Postgres must include tables for sessions, prediction events, alert decisions, conversation turns, craving slots, and handoff reports.
- STATE1. Backend session state is keyed by a client-provided `sessionId` when present, otherwise the active sensor session timestamp or generated session id.

## 9. Permissions, Security, Privacy, and Audit

- SEC1. Do not store AWS credentials in source; use standard AWS credential chain and env.
- SEC2. Backend-to-LLM calls must continue using `LLM_API_KEY` and `x-api-key`.
- PRIV1. Persisted chat and handoff data may contain sensitive content; avoid logging full user messages except in explicit debug or test fixtures.
- AUDIT1. Alert decisions and handoff records must include timestamps for demo traceability.

## 10. Error, Edge-Case, and Concurrency Behavior

- ERR1. If the LLM server fails, return a visible HTTP error to Android/backend callers and do not interrupt sensor prediction streaming.
- ERR2. If Bedrock returns malformed JSON for slot extraction, use a safe empty extraction result with missing slots listed.
- ERR3. If DB persistence fails, log a warning and continue realtime prediction delivery.
- EDGE1. Suppress alert escalation when recent classes are trending downward enough to indicate recovery.
- EDGE2. Apply cooldown so repeated identical alert levels do not create noisy intervention prompts every second.
- CONC1. SSE subscribers may receive the same public event; DB persistence must be performed once per prediction event at worker time, not once per subscriber.

## 11. Dependencies and Configuration

- DEP1. Add Bedrock runtime dependency to `apps/ai-server` through `boto3`.
- DEP2. Backend may use existing `sqlalchemy`/`asyncpg` dependencies already present.
- DEP3. New env/config keys: `ALERT_WINDOW_SIZE`, `ALERT_RECOMMEND_MIN`, `ALERT_REQUIRED_MIN`, `ALERT_HIGH_STREAK`, `ALERT_COOLDOWN_SECONDS`, `ALERT_DOWNTREND_DELTA`, `BEDROCK_MODEL_ID`, `AWS_REGION`.
- DEP4. No dependency lockfile churn is required.

## 12. Migration, Rollout, and Rollback

- MIG1. Update `infra/db/init.sql` with `CREATE TABLE IF NOT EXISTS` statements only; no destructive migrations.
- ROLL1. Feature is always available when new endpoints are present; alert thresholds can be neutralized by env values during rollback.
- BACK1. Roll back by deploying previous backend and LLM images; existing new tables are additive and may remain unused.

## 13. Implementation Boundaries

### 13.1 Expected Change Areas

- Backend: `apps/api/app/main.py`, `apps/api/app/inference.py`, new backend support modules/tests, `infra/db/init.sql`, backend README/env docs.
- LLM server: `apps/ai-server/app/main.py`, new LLM support modules/tests, `apps/ai-server/requirements.txt`, env docs.
- Android: phone and watch Kotlin files under `apps/mobile`, plus `SERVER_API_SPEC.md` and phone/watch docs when API fields or displayed alert states change.

### 13.2 Forbidden Changes

- Do not modify model feature extraction or RandomForest inference behavior except to consume its prediction events.
- Do not alter `POST /sensor-window` request contract.
- Do not remove class-only SSE compatibility.
- Do not implement STT or microphone UI.
- Do not port unrelated NeuroSync RAG, survey, OCR, temporal, or web code.
- No unrelated refactors, formatting churn, dependency upgrades, broad rewrites, or generated-file churn unless explicitly listed above.

### 13.3 Minimal-Change Guidance

- Reuse existing FastAPI route style and Android `StateFlow` patterns.
- Prefer small helper modules for alert rules, memory persistence, and Bedrock calls.
- Keep public interfaces backward-compatible unless explicitly listed as changed.

## 14. Acceptance Criteria

- AC1. Given prediction classes `[0,0,1,1,1,1,1,1,1,1]`, when evaluated with defaults, then alert metadata reports `recommend`.
- AC2. Given prediction classes averaging at least `1.5`, when not in cooldown or downtrend suppression, then alert metadata reports `required` and `alertRequired=true`.
- AC3. Given a class-only SSE event, when parsed by Android, then class display still works and alert fields default to none.
- AC4. Given Bedrock credentials and a valid chat request, `/ai/chat/respond` returns a Korean assistant response and does not diagnose or prescribe.
- AC5. Given conversation history, `/ai/slots/extract` returns craving-specific slot keys only.
- AC6. Given slots and history, `/ai/handoff/generate` returns Markdown with evidence/limitations and missing-slot information.
- AC7. Given a backend chat request, the backend persists the user turn and assistant turn when DB is available.
- AC8. Given Android phone receives `recommend` or `required`, phone and watch display the alert state.

## 15. Validation Plan

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Backend unit tests | `python -m pytest` from `neurotruth/apps/api` if pytest is available | Alert rules, DB schema idempotency, proxy behavior pass |
| LLM unit tests | `python -m pytest` from `neurotruth/apps/ai-server` if pytest is available | Mocked Bedrock chat/slots/handoff tests pass |
| Android build | `.\gradlew.bat assembleDebug` from `neurotruth/apps/mobile` | Debug APK build succeeds |
| Docker smoke | `docker compose -f infra/deploy/docker-compose.backend.yml config` and `docker compose -f infra/deploy/docker-compose.gpu.yml config` if Docker is available | Compose config is valid |
| Manual API smoke | Start backend and LLM server, call `/health`, `/prediction-stream`, `/api/intervention/chat` | Endpoints respond as specified |

## 16. Risks and Open Notes

- RISK1. Local environment may not have AWS credentials, Docker, Android SDK, or pytest; use mocked tests and report skipped integration checks.
- RISK2. Bedrock model IDs may vary by account/region; `BEDROCK_MODEL_ID` and `AWS_REGION` must remain overrideable.
- RISK3. Android build may require local SDK/Samsung dependencies outside this repository.

## 17. Implementation Checklist / Progress Record

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Finalize spec pair | Main | Complete | `docs/specs/...` | `validate_spec_pair.py`: PASS | English source and Korean mirror synchronized. |
| P2 | Backend alert rules, memory, proxy routes | Worker | Complete | `apps/api/app/alerts.py`, `apps/api/app/memory.py`, `apps/api/app/inference.py`, `apps/api/app/main.py`, `apps/api/tests/*`, `infra/db/init.sql`, `.env.example` | Historical `compileall`: PASS; current backend pytest after consolidation: PASS | Rule engine, SSE metadata, Postgres memory, LLM proxy routes, slot allowlist, and focused tests implemented before later backend consolidation. |
| P3 | LLM server Bedrock F1-style agents | Worker | Complete | `apps/ai-server/app/main.py`, `apps/ai-server/app/bedrock_agents.py`, `apps/ai-server/requirements.txt`, `apps/ai-server/tests/*`, `apps/ai-server/Dockerfile` | Historical `compileall`: PASS; superseded by backend-owned Bedrock tests after consolidation | Bedrock Sonnet 4.6 adapter and chat/slots/handoff endpoints implemented, then moved into `apps/backend/app/ai`. |
| P4 | Android phone/watch alert and text chat UI | Worker | Complete | `apps/mobile/...` Kotlin and docs | Source scan: PASS; `assembleDebug`: PASS; `testDebugUnitTest`: PASS | Phone/watch alert display and text chat/handoff flow implemented; no STT or microphone UI added. |
| P5 | Main verification and progress sync | Main | Complete | `docs/specs/...` | Spec validation PASS; `git diff --check` PASS; Docker config PASS; phone/watch install PASS | Final verification updated after backend consolidation and mobile reconstruction. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.0 | 2026-07-09 | Feature Planner | Initial finalized spec pair for requested implementation. |
| 1.1 | 2026-07-09 | Feature Planner | Recorded implementation completion and environment-limited verification results. |
| 1.2 | 2026-07-09 | Codex | Updated verification after unified backend consolidation, Android build/unit tests, Docker config, and device installs passed. |
