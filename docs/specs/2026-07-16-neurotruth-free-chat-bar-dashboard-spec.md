# NeuroTruth Free Chat and Bar Dashboard Spec

- **Language role:** English source spec
- **Spec status:** Finalized
- **Spec version:** 1.0
- **Last updated:** 2026-07-16
- **English source:** `docs/specs/2026-07-16-neurotruth-free-chat-bar-dashboard-spec.md`
- **Korean mirror:** `docs/specs/2026-07-16-neurotruth-free-chat-bar-dashboard-spec.ko.md`
- **Requester / owner:** NeuroTruth project team
- **Implementation status:** In implementation

## 0. Codex Implementation Handoff

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: docs/specs/2026-07-16-neurotruth-free-chat-bar-dashboard-spec.md
korean_mirror_spec: docs/specs/2026-07-16-neurotruth-free-chat-bar-dashboard-spec.ko.md
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

## 1. Summary

Replace the new-session structured intervention flow with neutral-entry, optional-AUQ, retry-safe free dialogue. New dialogue does not run the deterministic safety detector, mandatory first-intervention rules, question-topic coverage, or new intervention persistence. Each patient message performs at most one normal Bedrock dialogue call plus one validation repair; state-summary LLM calls move to session finish. The Android patient dashboard replaces the live probability line with Samsung Health-style bar summaries: hourly craving probability for the local calendar day, daily alert-event counts for 7 or 30 days, and hourly/daily AUQ averages. Existing administrator web, Wear OS, legacy sessions, session reports, and probability-series API remain compatible.

## 2. Goals

- G1. Make authenticated chat responsive, free-form, bounded in context, non-repetitive, and manually retryable without duplicate messages.
- G2. Show current craving probability and clear hourly/daily bar summaries on Android.
- G3. Rebuild and validate the local Docker deployment before GitHub handoff.
- G4. Preserve authentication, encryption, sensor upload, prediction SSE, reports, administrator web, and Watch behavior.

## 3. Non-Goals

- NG1. No periodic LLM report document, aggregation table, or scheduler is added.
- NG2. No new administrator probability chart or Wear OS chart.
- NG3. No new intervention row is created by free-chat sessions.
- NG4. No deletion, rewrite, or backfill of legacy sessions, interventions, inferences, or reports.
- NG5. No model retraining, prediction-policy change, rPPG change, dependency upgrade, or unrelated refactor.
- NG6. This release does not guarantee emergency detection or response; the user explicitly selected LLM-only safety handling.

## 4. Users and Use Cases

- UC1. A patient chooses to complete or skip AUQ, enters a neutral free-chat session, and receives one concise Korean response per turn.
- UC2. If Bedrock fails, the patient sees an error and retries the same message once without creating a duplicate user row.
- UC3. A patient sees today's hourly craving probability, 7/30-day alert-event counts, and today/7/30-day AUQ bars in device-local time.
- UC4. An operator verifies the flow locally through Docker and receives Git commit/push guidance after acceptance.

## 5. Final Decisions

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | Dialogue | New sessions use fully free dialogue after a neutral opening; no mandatory question sequence. | User decision |
| D2 | AUQ | Keep the existing optional complete/skip choice before chat. | User decision |
| D3 | Safety | Safety interpretation and crisis wording are LLM-only; the server does not run the deterministic risk detector for new free-chat sessions. | User decision |
| D4 | Intervention | The agent provides conversation only; it does not select or persist new intervention types. | User decision |
| D5 | Summary timing | Per-turn state-summary LLM calls are removed; summaries are generated only at session finish. | User decision |
| D6 | Failure UX | Bedrock failure returns an error and one manual retry, not a hidden deterministic fallback. | User decision |
| D7 | Probability chart | Replace the mobile live line with today's 24 local-hour bars. | User decision |
| D8 | Event chart | One event equals a persisted `recommend` or `required` craving alert; show daily counts for 7/30 days. | User decision |
| D9 | AUQ chart | Show average normalized AUQ per local hour today and per local day for 7/30 days, with response counts. | User decision |
| D10 | Empty buckets | Missing sensor/AUQ data is a gray no-data bucket, not zero and not omitted. | User decision |
| D11 | Surface | The new bars are Android-only; administrator web and Wear OS remain unchanged. | User decision |
| D12 | Retry limit | Total dialogue attempts per `clientMessageId` are two: initial attempt plus one manual retry. | Agent default |
| D13 | Context bound | Send the newest 20 persisted messages plus at most 50 ledgered prior assistant questions/refusals. | Agent default |
| D14 | DST buckets | Always render 24 wall-clock hour labels; merge repeated-hour samples and leave skipped hours as no-data. | Agent default |
| D15 | Retry concurrency | `0004` adds a conditional unique client-message index and message turns use a blocking per-session advisory lock so a response-loss retry observes the completed original result. | Agent default |
| D16 | Repeat threshold | Normalize Unicode/punctuation and reject prior-question similarity at `SequenceMatcher >= 0.82`; compare against all ledgered questions. | Agent default |

## 6. Functional Requirements

- FR1. New sessions start in `free_dialogue` and persist the neutral assistant message `지금 상황이나 원하는 도움을 편하게 말씀해 주세요.`.
- FR2. New-session dialogue state version 2 stores prior assistant question texts, refused question texts, and the latest question; legacy state remains readable.
- FR3. The free-chat prompt uses recent history and the ledger, asks at most one question, avoids repeated/refused questions, and prohibits diagnosis, prescription, treatment-effect, certainty, and causal claims.
- FR4. New free-chat turns do not run deterministic safety classification, deterministic first-intervention selection, or intervention persistence. The prompt instructs the LLM to provide 119/109 guidance when it judges an immediate-risk context, but the product must state that this is not guaranteed emergency handling.
- FR5. A normal turn makes one Bedrock dialogue call. One structured repair is permitted only when output is invalid or repeats a prior question.
- FR6. Session creation and messages do not run state-summary LLM calls. Manual finish/timeout retains final realtime/longitudinal summary and existing asynchronous session-report behavior.
- FR7. Dialogue history sent to Bedrock is bounded to the newest 20 messages. All messages remain encrypted and retained in the database.
- FR8. Android generates one UUID `clientMessageId` per typed message and reuses it for the single manual retry.
- FR9. A retry reuses the existing persisted user message, increments `dialogueAttempts`, and never inserts a second user message or two assistant replies.
- FR10. The dashboard derives all bars from current indexed prediction, alert, and assessment rows; no periodic aggregation job is created.
- FR11. Current craving probability is the latest valid active binary model `continuous_value`.
- FR12. Hourly craving buckets cover the device-local calendar day, 00:00 through 23:59, and return all 24 hours.
- FR13. Daily event buckets count persisted `recommend|required` alerts. A bucket with predictions and no alerts is zero; a bucket with no predictions is no-data.
- FR14. AUQ values are normalized per assessment as `(rawScore-scaleMin)/(scaleMax-scaleMin)`, averaged per bucket, clamped to `[0,1]`, and returned with `sampleCount`.
- FR15. Current/hourly craving and event availability use only quality-passed predictions from the active binary craving model. AUQ bars use only `instrument_code='AUQ'`.
- FR16. Assessment submission also performs no state-summary LLM call; creation, message, and assessment paths are summary-free until finish.

## 7. User Experience / UI Requirements

- UI1. Preserve the existing optional AUQ choice and replace the safety-first phase label with `자유 대화`.
- UI2. Provider failure leaves the user's bubble visible and shows an error plus `다시 시도`; the button disappears after the retry is exhausted.
- UI3. The craving card shows the latest percentage and a 24-bar 0–100% hourly chart for today. The existing ten-minute line is removed from the screen.
- UI4. The event card has `7일` and `30일` controls and daily bars. `recommend` and `required` counts are distinguishable in the selected-bar detail.
- UI5. The AUQ card has `오늘`, `7일`, and `30일` controls; today is hourly and longer ranges are daily.
- UI6. Tapping a bar shows its local period, average/min/max and sample count for craving, counts for events, or average percentage and response count for AUQ.
- UI7. No-data bars are gray. Event zero with available predictions is a valid zero-height bar.
- UI8. Keep live Watch PPG, owned historical PPG preview, state/report status, accessibility labels, and local-time display.

## 8. API / Data / State Requirements

### 8.1 Free dialogue

`POST /api/sessions/{sessionId}/messages` accepts:

```json
{"clientMessageId":"uuid-or-null","content":"1..10000 characters"}
```

New Android always supplies the UUID. Omitted IDs remain accepted for one-shot legacy compatibility but cannot be retried.

Success adds `userMessageId`, `assistantMessageId`, and `phase:"free_dialogue"` to the current response. Provider/validation failure returns HTTP 502:

```json
{
  "detail": {
    "code": "dialogue_provider_error|dialogue_output_rejected",
    "clientMessageId": "uuid",
    "userMessageId": "uuid",
    "retryable": true,
    "attemptsRemaining": 1
  }
}
```

Retrying the same request after the second failure returns `retryable:false` and `attemptsRemaining:0`.

- DATA1. User message `generation_metadata` stores `clientMessageId` and `dialogueAttempts`; assistant metadata stores `replyToUserMessageId`.
- STATE1. Add `free_dialogue` to `sessions.interaction_phase` through additive Alembic `0004`. The same migration adds a conditional unique index on `(session_id, generation_metadata->>'clientMessageId')` for user messages whose client ID is present. Existing values are unchanged.

### 8.2 Mobile bar dashboard

`GET /api/me/craving-dashboard?timezone={ianaTimezone}&eventRange=7d|30d&auqRange=today|7d|30d`

returns:

```json
{
  "timezone": "Asia/Seoul",
  "generatedAt": "UTC ISO-8601",
  "currentCraving": {"probability": 0.81, "at": "UTC ISO-8601"},
  "hourlyCraving": {
    "buckets": [{
      "localStart": "ISO-8601 with offset",
      "averageProbability": 0.72,
      "minimumProbability": 0.41,
      "maximumProbability": 0.91,
      "sampleCount": 3590
    }]
  },
  "dailyEvents": {
    "range": "7d",
    "buckets": [{
      "localDate": "2026-07-16",
      "hasPredictionData": true,
      "recommendCount": 2,
      "requiredCount": 1,
      "totalCount": 3
    }]
  },
  "auq": {
    "range": "today",
    "bucketUnit": "hour",
    "buckets": [{
      "localStart": "ISO-8601 with offset",
      "averageNormalizedScore": 0.63,
      "sampleCount": 2
    }]
  }
}
```

- API1. Valid IANA zones are accepted; invalid zones return 422 `invalid_timezone`.
- API2. Missing value fields are JSON null with `sampleCount:0`; expected hour/day buckets are never omitted.
- API3. Existing `/api/me/dashboard` and `/api/me/craving-probability-series` remain available.
- API4. Android URL-encodes every query parameter; `Etc/GMT+5` is included in contract tests.

## 9. Permissions, Security, Privacy, and Audit

- SEC1. Both endpoints retain patient JWT ownership and existing AI/report consent rules.
- PRIV1. Messages, ledger, summaries, and reports remain AES-256-GCM encrypted. Bar data contains only existing non-sensitive aggregate metadata.
- AUDIT1. Dialogue failures record code, stage, exception class, user message ID, and attempt number without raw text, provider payload, stack trace, or credentials.
- AUDIT2. LLM-only safety behavior must be documented as research/demo-only and not represented as reliable emergency response.

## 10. Error, Edge-Case, and Concurrency Behavior

- ERR1. A failed Bedrock call stores no assistant message and returns retry metadata.
- ERR2. Output rejection uses the same retry contract with `dialogue_output_rejected`.
- EDGE1. Reusing a completed `clientMessageId` returns the existing assistant response idempotently.
- EDGE2. A retry with the same ID but different content returns 409 `client_message_conflict`.
- EDGE3. DST days still return hour labels `00` through `23`; a repeated local hour is merged into its label and a skipped local hour is a no-data bucket.
- CONC1. Message turns use a blocking transaction-scoped per-session advisory lock. After waiting, the request rechecks `clientMessageId` and returns the existing assistant result or continues the allowed retry.

## 11. Dependencies and Configuration

- DEP1. No new runtime dependency.
- DEP2. Retain `BEDROCK_TIMEOUT_SECONDS`, 3600-second default session inactivity, current Docker ports, model configuration, and encryption settings.

## 12. Migration, Rollout, and Rollback

- MIG1. Add `0004` only to allow `free_dialogue` and add the conditional retry-idempotency index; do not rewrite existing session rows or baseline SQL.
- ROLL1. Back up the local PostgreSQL volume, rebuild backend/mobile together, apply `0004`, and validate locally before Git handoff.
- BACK1. Roll back to the previous image/commit. The additive constraint remains harmless; new free-dialogue rows are not readable by the older app and therefore require restoring the DB backup for full rollback.

## 13. Implementation Boundaries

### 13.1 Expected Change Areas

- Backend session agents/service/routes/repository, dashboard aggregation/routes, additive migration, and focused tests.
- Android authenticated session API/view-model/chat UI, patient dashboard/parser/charts, and focused tests.
- Spec/progress and relevant README/API documentation.

### 13.2 Forbidden Changes

- Administrator web, Wear OS, model inference, alert rules, rPPG, authentication, encryption primitives, and existing session-report format.
- No unrelated refactors, dependency upgrades, generated churn, or destructive data cleanup.

## 14. Acceptance Criteria

- AC1. New sessions open directly in `free_dialogue` with the neutral prompt, optional AUQ remains, and no new intervention row is created.
- AC2. Ten or more free-chat turns use bounded context, allow at most one question, and reject/repair normalized prior-question similarity at the specified threshold.
- AC3. A failed message can be retried once with the same ID; exactly one user row and at most one assistant row exist.
- AC4. No state-summary LLM call occurs during session creation, messages, or AUQ assessment; final summaries and existing reports are produced at finish.
- AC5. Android shows today's 24 craving bars, 7/30-day event bars, and today/7/30-day AUQ bars with correct local boundaries.
- AC6. Missing sensor/AUQ data is distinct from a valid zero-event bucket.
- AC7. Existing auth, encrypted persistence, sensor/SSE, Watch, PPG preview, administrator web, and session reports pass regression tests.
- AC8. Local Docker reports healthy/ready on the updated image and completes an authenticated real-Bedrock dialogue smoke test.

## 15. Validation Plan

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Backend | `apps/backend/.venv/Scripts/python.exe -m pytest apps/backend/tests -q` | Free chat, retry, aggregation, migration, and regressions pass. |
| Android | `apps/mobile/gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` | Parser, bars, retry UI, and build/lint pass. |
| Docker | Rebuild `apps/db/docker-compose.yml`, then check `/health`, `/ready`, Alembic revision, authenticated chat, and dashboard API. | Updated code is running and DB is at `0004`. |
| Manual | AUQ complete/skip, 10+ turns, forced provider failure/retry, finish, and seeded hourly/daily data. | AC1–AC8 are observable. |

## 16. Risks and Open Notes

- RISK1. LLM-only safety can miss urgent contexts during provider or model failure. This is an explicit user decision and prevents release claims about dependable crisis response.
- RISK2. Large prediction histories make aggregation expensive; reuse the existing `(patient_id,predicted_at)` indexes and query only the requested local periods.
- RISK3. The currently running local container predates the latest source fixes, so Docker rebuild verification is mandatory.

## 17. Implementation Checklist / Progress Record

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Finalize and validate spec pair | Main | Completed | Spec pair | `validate_spec_pair.py`: PASS | Source edits ready for delegation. |
| P2 | Backend free chat, retry, aggregation API, migration, tests | Backend worker | Completed | Session agent/service/routes/repository, dashboard service/routes, `20260716_0004`, backend tests and README files | Backend suite: `150 passed, 1 skipped`; Alembic head `20260716_0004`; spec diff checks pass | New sessions use encrypted-ledger free dialogue; legacy session data remains preserved. |
| P3 | Android retry flow and bar dashboard, tests | Android worker | Completed | Authenticated API/session client, `SensorViewModel`, `MainActivity`, `PatientDashboard`, focused tests and mobile docs | `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`: BUILD SUCCESSFUL | Phone-only bars and retry UI added; administrator web and Wear OS were not changed. |
| P4 | Main verification, Docker rebuild, documentation/progress | Main | Completed | Spec progress plus local runtime verification | Spec validator PASS; DB custom-format backup verified; `/health` and `/ready` 200 at schema `20260716_0004`; real Bedrock 10-turn free chat, AUQ submit/skip, finish/report, idempotency and dashboard smoke passed; invalid timezone returned 422 | Backup: `champion/neurotruth-local-backups/neurotruth-pre-0004-20260716.dump`. The live 10-turn session stored exactly 10 user and 11 assistant messages including the neutral opener, created no intervention rows, and completed its report. Automated tests cover forced failure/retry paths. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.0 | 2026-07-16 | Feature Planner | Finalized free-chat and Android bar-dashboard specification. |
| 1.1 | 2026-07-16 | Feature Planner | Recorded delegated implementation, automated validation, Docker migration, and real Bedrock smoke-test completion. |
