# NeuroTruth Session, Alert, and UI Stabilization Spec

- **Language role:** English source spec
- **Spec status:** Finalized
- **Spec version:** 1.1
- **Last updated:** 2026-07-10
- **English source:** `docs/specs/2026-07-10-neurotruth-session-alert-ui-stabilization-spec.md`
- **Korean mirror:** `docs/specs/2026-07-10-neurotruth-session-alert-ui-stabilization-spec.ko.md`
- **Requester / owner:** NeuroTruth team
- **Implementation status:** Implemented

## 0. Codex Implementation Handoff

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: docs/specs/2026-07-10-neurotruth-session-alert-ui-stabilization-spec.md
korean_mirror_spec: docs/specs/2026-07-10-neurotruth-session-alert-ui-stabilization-spec.ko.md
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

NeuroTruth currently runs end to end, but alert history and cooldown state are shared across sensor sessions, the phone uses different identifiers for sensor and intervention memory, and phone/watch notifications act on raw prediction classes instead of the backend rule decision. This change isolates alert evaluation per session, introduces a single mobile session identity, makes backend alert metadata authoritative, and aligns the existing `watch_test`-based UI with staged recommendation and required-intervention behavior. It also fixes the Wear OS packaging lint error and the sensor simulator's unstable session and console behavior. Public endpoints, database schema, Bedrock prompts, and RandomForest behavior remain compatible.

## 2. Goals

- G1. Prevent prediction windows, cooldowns, and downtrend state from crossing session boundaries.
- G2. Join prediction, conversation, slot, and handoff records under one mobile/backend session ID.
- G3. Make phone and watch intervention actions match backend `alertAction` decisions.
- G4. Preserve the current dashboard and eight-question state-check experience.
- G5. Restore clean backend, Docker, Android build, unit-test, and Wear lint validation.

## 3. Non-Goals

- NG1. No API authentication, CORS redesign, or external-network production hardening.
- NG2. No database migration, model retraining, weight-format change, or Bedrock prompt redesign.
- NG3. No web UI redesign, dependency upgrades, broad Android refactor, or generated-file churn.
- NG4. No STT or microphone flow.

## 4. Users and Use Cases

- UC1. A phone starts sensor monitoring and all prediction and intervention data remains in one session.
- UC2. A recommendation updates the dashboard and creates a non-blocking phone notification plus a short watch vibration.
- UC3. A required action opens the phone state check, creates a stronger watch alert, and transitions to text chat after the eight questions.
- UC4. A cooldown or suppressed backend decision updates displayed data without creating a notification, vibration, or automatic navigation.
- UC5. An operator runs the simulator and receives stable same-session SSE events without a console crash.

## 5. Final Decisions

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | Backend state | Keep one `AlertEvaluator` per session in an LRU registry capped at 256 sessions. | Approved plan |
| D2 | Warm-up | Mean thresholds require a full configured window; `ALERT_HIGH_STREAK` may trigger early required intervention. | Approved plan |
| D3 | Session identity | `PhoneMonitoringState` owns the active session; sensor, chat, slots, and handoff share it. | Approved plan |
| D4 | Action precedence | Resolve `alertAction`, then `alertLevel`, then raw `class` only for legacy payloads without alert metadata. | Approved plan |
| D5 | Phone UI | Recommendation is non-blocking; required opens the eight-question state check and then chat. | User decision |
| D6 | Watch UI | Recommendation uses a short vibration; required uses a strong pattern and phone-check guidance. | User decision |
| D7 | Compatibility | Preserve endpoint paths and existing class/SSE fields; formalize optional `sessionId`. | Approved plan |

## 6. Functional Requirements

- FR1. Backend must resolve a session ID before evaluating an alert and must use only that session's evaluator.
- FR2. The evaluator registry must refresh LRU order on use and evict only the least recently used session above 256 entries.
- FR3. Before `ALERT_WINDOW_SIZE` values exist, mean-based alerts must return `none` with `triggerReason=window_warming_up`; a configured class-2 high streak may still return required.
- FR4. Cooldown must be session-local and keep `alertLevel` while returning `alertAction=cooldown` and `alertRequired=false`.
- FR5. Mobile sensor payloads must send both `sessionId` and the backward-compatible `sessionStartedAtMs`.
- FR6. SSE parsing must retain `sessionId`; intervention requests must prefer the latest server-echoed session ID and otherwise use the shared local ID.
- FR7. Clearing mobile data must begin a new shared session and reset alert gates, chat, state-check, handoff, and latency UI state.
- FR8. Phone and watch must suppress user-facing actions for `none` and `cooldown` while still updating the visible prediction state.
- FR9. Phone recommendation must show a non-blocking banner/notification; required must open state check once and proceed to chat only after submission.
- FR10. Watch recommendation and required must use distinct vibration/notification copy, with existing local cooldown used only to prevent duplicate rendering of the same authoritative action.
- FR11. Legacy SSE without alert metadata must retain class mapping: 0 none, 1 recommend, 2 required.
- FR12. The simulator must reuse one session start value, use a physiological synthetic PPG waveform, and finish with ASCII-only console copy.

## 7. User Experience / UI Requirements

- UI1. Preserve the existing user dashboard, developer dashboard, charts, state-check questions, and text chat layout.
- UI2. Recommendation must not navigate away from the current phone screen.
- UI3. Required must request the existing state check once per authoritative action and must not reopen during cooldown.
- UI4. The watch must continue showing class and alert level even when an action is suppressed.
- UI5. Existing Korean copy remains the default; only alert-stage copy needed to distinguish recommendation and required may change.

## 8. API / Data / State Requirements

- API1. Keep `POST /sensor-window`, `GET /prediction-stream`, `POST /api/intervention/chat`, `POST /api/intervention/slots`, and `POST /api/intervention/handoff` unchanged.
- API2. Add optional `sessionId` to the documented sensor-window body while continuing to accept `sessionStartedAtMs`-only clients.
- API3. Preserve SSE `class`, `alertLevel`, `alertAction`, `windowMean`, `triggerReason`, `alertRequired`, and `sessionId`.
- DATA1. No schema migration or historical-data rewrite is required.
- STATE1. Alert registry state is in-process and bounded to 256 sessions; database persistence remains best effort.

## 9. Permissions, Security, Privacy, and Audit

- SEC1. Keep the internal-LAN demo security model and current Android permissions unchanged except for required Wear packaging metadata.
- PRIV1. Do not print bearer tokens or `.env` values in test output, logs, or docs.
- AUDIT1. Existing prediction, alert, conversation, slot, and handoff database records remain the audit trail.

## 10. Error, Edge-Case, and Concurrency Behavior

- ERR1. Unknown `alertAction` values fall through to valid `alertLevel`; unknown levels fall back to class only when metadata is absent.
- ERR2. A cooldown action never vibrates, notifies, or navigates, even if `alertLevel=required`.
- EDGE1. A missing server session ID uses the shared local session without breaking legacy servers.
- EDGE2. A service restart reads the current shared session instead of creating an independent private ID.
- CONC1. Concurrent sessions use independent evaluators; LRU eviction affects only future history for the evicted session and never another active evaluator.

## 11. Dependencies and Configuration

- DEP1. Reuse current Python, Android, Wear OS, Samsung SDK, and Bedrock dependencies.
- DEP2. Keep existing alert environment variables and do not add a new dependency or environment key.

## 12. Migration, Rollout, and Rollback

- MIG1. No database or stored-data migration is required.
- ROLL1. Rebuild backend and Android apps together; legacy clients remain compatible through class and `sessionStartedAtMs` fallback.
- BACK1. Rollback is source-level: restore the previous backend/mobile images and APKs; persisted records require no rollback.

## 13. Implementation Boundaries

- Backend worker may change alert evaluation/registry, inference wiring, simulator, and focused backend tests.
- Mobile worker may change shared session state, payload/SSE models, alert policy, phone/watch action handling, Wear manifest, and focused Android tests.
- Main agent may update this spec pair and active Markdown documentation.
- Do not change web source, DB schema, model code/weights, Bedrock prompts, dependency versions, public endpoint names, or unrelated UI.

## 14. Acceptance Criteria

- AC1. Two interleaved sessions produce independent means, streaks, downtrend decisions, and cooldown actions.
- AC2. Mean-based alerting remains in warm-up until ten default samples; three consecutive class-2 samples can trigger required early.
- AC3. Sensor, chat, and handoff requests from one mobile monitoring run use the same session ID and DB handoff context includes that session's alerts.
- AC4. `cooldown` and `none` update phone/watch display state without notification, vibration, or navigation.
- AC5. Recommendation is non-blocking and required opens state check before chat.
- AC6. Legacy class-only payloads retain their prior phone/watch behavior.
- AC7. Wear lint has zero errors, simulator exits zero, and existing public API fields remain compatible.

## 15. Validation Plan

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Backend compile | `python -m compileall app tests` from `apps/backend` | PASS |
| Backend tests | `.venv/Scripts/python.exe -m pytest -q` from `apps/backend` | PASS |
| Docker | `docker compose -f apps/db/docker-compose.yml up -d --build` | DB healthy; backend/web up |
| Integration | Sensor POST, SSE, DB query, Bedrock chat/slots/handoff | Same session and HTTP 200 |
| Android | `apps/mobile/gradlew.bat clean assembleDebug testDebugUnitTest lintDebug` | PASS, zero lint errors |
| Web | Browser and nginx proxy check | Connected, no console error |
| Hygiene | `git diff --check` and stale-path scan | No errors; historical refs only |

## 16. Risks and Open Notes

- RISK1. LRU eviction intentionally drops in-memory alert history for an inactive session but does not delete persisted records.
- RISK2. Physical phone/watch vibration and automatic navigation require connected devices; report skipped validation when ADB has no devices.
- RISK3. `.env`, model weights, Samsung AAR, and `local.properties` remain trackable by prior user decision; implementation must not stage, commit, or push automatically.

## 17. Implementation Checklist / Progress Record

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Finalize synchronized spec pair | Main | Complete | This spec pair | Pair self-review | Decisions from approved plan and UI refinement recorded. |
| P2 | Backend session alert isolation and simulator | Backend worker | Complete | `apps/backend/app/alerts.py`, `apps/backend/app/inference.py`, `apps/backend/tools/sim_app.py`, focused backend tests | Compileall; pytest 24 passed; Docker sensor/SSE/Postgres integration | Added bounded per-session LRU evaluators, warm-up, early high-streak required, and stable simulator sessions. |
| P3 | Mobile shared session and authoritative alert UI | Mobile worker | Complete | Phone and Wear alert policies, monitoring state, uploader/sender, manifest, focused unit tests | `assembleDebug`, `testDebugUnitTest`, and `lintDebug` passed | Shared session identity and authoritative action precedence are implemented; physical-device behavior remains to be observed with ADB devices connected. |
| P4 | Documentation and end-to-end verification | Main | Complete | Active README, API, mobile readiness, upload notes, plan, and this spec pair | Spec pair validation; live Bedrock chat/handoff; same-session DB linkage; `git diff --check` | Docker stack remains running; physical phone/watch verification was skipped because ADB had no connected devices. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.1 | 2026-07-10 | Feature Planner | Recorded completed backend/mobile implementation, documentation updates, and verified results. |
| 1.0 | 2026-07-10 | Feature Planner | Initial finalized stabilization spec. |
