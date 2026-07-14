# NeuroTruth Bedrock GPT-5.5 Migration Spec

- **Language role:** English source spec
- **Spec status:** Finalized
- **Spec version:** 1.1
- **Last updated:** 2026-07-13
- **English source:** `neurotruth/docs/specs/2026-07-13-neurotruth-bedrock-gpt-55-migration-spec.md`
- **Korean mirror:** `neurotruth/docs/specs/2026-07-13-neurotruth-bedrock-gpt-55-migration-spec.ko.md`
- **Requester / owner:** NeuroTruth project requester
- **Implementation status:** Implemented

## 0. Codex Implementation Handoff

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: neurotruth/docs/specs/2026-07-13-neurotruth-bedrock-gpt-55-migration-spec.md
korean_mirror_spec: neurotruth/docs/specs/2026-07-13-neurotruth-bedrock-gpt-55-migration-spec.ko.md
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

NeuroTruth will use OpenAI GPT-5.5 through Amazon Bedrock for chat, slot extraction, repair, and handoff generation. GPT-5.6 was checked first and returned `not_found_error`; GPT-5.5 was then verified by a successful live request in `us-east-1`. Because GPT-5.5 is available only through the `bedrock-mantle` OpenAI Responses API, the existing adapter must select the Mantle protocol for `openai.*` model IDs while preserving the current Converse path for rollback.

## 2. Goals

- G1. Make `openai.gpt-5.5` the configured NeuroTruth model.
- G2. Support Bedrock Mantle Responses API payload and response parsing without changing intervention routes.
- G3. Preserve the existing Claude Converse implementation as a configuration-only rollback path.

## 3. Non-Goals

- NG1. Do not add GPT-5.6 because AWS reports that the model does not exist.
- NG2. Do not change prompts, repetition policy, slot schema, mobile UI, database schema, or public intervention APIs.
- NG3. Do not add the OpenAI Python package; use the existing standard-library HTTP pattern.

## 4. Users and Use Cases

### 4.1 Target Users

- NeuroTruth operators and mobile intervention users.

### 4.2 Primary Use Cases

- UC1. Backend chat, slots, and handoff calls use GPT-5.5 without mobile changes.
- UC2. Operators can restore a Claude model ID and retain the previous Converse behavior.

## 5. Final Decisions

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | Model | Use `openai.gpt-5.5`; GPT-5.6 is unavailable. | User priority plus live verification |
| D2 | API | Route `openai.*` IDs to `https://bedrock-mantle.{region}.api.aws/openai/v1/responses`. | AWS model contract |
| D3 | Authentication | Reuse `AWS_BEARER_TOKEN_BEDROCK`; fail clearly if it is missing for Mantle. | Existing project credential |
| D4 | Compatibility | Keep non-OpenAI model IDs on the existing bearer/IAM Converse paths. | Agent default |
| D5 | Configuration | Set defaults and local `.env` model ID to `openai.gpt-5.5`; retain `AWS_REGION=us-east-1`. | User request and verified region |
| D6 | Inference parameters | Preserve the adapter's `temperature` argument but omit it from GPT-5.5 requests because the live API rejects it as unsupported. | Live protocol verification |

## 6. Functional Requirements

- FR1. The adapter must select Mantle when `model_id` begins with `openai.`.
- FR2. Mantle requests must send the system prompt and ordered conversation messages through the Responses API.
- FR3. The adapter must pass `max_output_tokens` but must not send `temperature` to GPT-5.5; non-OpenAI Converse calls retain their current temperature behavior.
- FR4. Response parsing must join textual `output[].content[]` items and reject empty output.
- FR5. Provider errors must not expose credentials in public API responses or logs.
- FR6. Existing non-OpenAI Converse behavior must remain covered by tests.

## 7. User Experience / UI Requirements

- UI1. No mobile or web UI changes are required.
- UI2. Existing loading, timeout, retry, and asynchronous handoff behavior remains unchanged.

## 8. API / Data / State Requirements

- API1. Existing `/api/intervention/chat`, `/api/intervention/slots`, and handoff contracts remain unchanged.
- DATA1. No database or stored transcript migration is required.
- STATE1. API responses continue to report the adapter's configured `model` string.

## 9. Permissions, Security, Privacy, and Audit

- SEC1. Never print or persist `AWS_BEARER_TOKEN_BEDROCK`.
- PRIV1. Mantle receives the same prompt and conversation content already sent to Claude; no additional data is added.
- AUDIT1. Existing application logging and model response metadata remain unchanged.

## 10. Error, Edge-Case, and Concurrency Behavior

- ERR1. Missing Mantle bearer credentials must raise a sanitized configuration error.
- ERR2. HTTP and malformed/empty response failures must propagate through the existing sanitized backend error path.
- EDGE1. A Claude or other non-OpenAI model ID must continue using Converse.
- CONC1. Calls remain isolated per request and use the existing `asyncio.to_thread` boundary.

## 11. Dependencies and Configuration

- DEP1. No new dependency is allowed.
- DEP2. `BEDROCK_MODEL_ID` defaults to `openai.gpt-5.5`.
- DEP3. GPT-5.5 requires `AWS_BEARER_TOKEN_BEDROCK` and a Mantle-supported region; current verified region is `us-east-1`.

## 12. Migration, Rollout, and Rollback

- MIG1. No data migration is required.
- ROLL1. Update the adapter, tests, `.env.example`, local `.env` model ID, and active documentation, then rebuild/restart backend after network-free tests pass.
- BACK1. Set `BEDROCK_MODEL_ID` back to `us.anthropic.claude-sonnet-4-6` and restart backend to use the preserved Converse path.

## 13. Implementation Boundaries

### 13.1 Expected Change Areas

- `apps/backend/app/ai/bedrock_agents.py`
- `apps/backend/tests/test_bedrock_agents.py`
- `.env.example` and the non-secret `BEDROCK_MODEL_ID` value in `.env`
- Active Markdown documentation that names the provider/model/protocol

### 13.2 Forbidden Changes

- Mobile, watch, database, prompts, slot schema, route payloads, and intervention state behavior.
- No unrelated refactors, formatting churn, dependency upgrades, broad rewrites, or generated-file churn.

### 13.3 Minimal-Change Guidance

- Add a protocol branch inside the existing adapter.
- Reuse `urllib.request` and the current timeout/environment conventions.
- Preserve current public interfaces and Claude rollback behavior.

## 14. Acceptance Criteria

- AC1. Given `BEDROCK_MODEL_ID=openai.gpt-5.5`, a completion uses the Mantle Responses endpoint and extracts text.
- AC2. Given a non-OpenAI model ID, the existing Converse test still passes.
- AC3. Missing token, HTTP failure, malformed output, empty output, and omission of unsupported `temperature` are safely handled and tested.
- AC4. All backend tests and compile checks pass without adding dependencies.
- AC5. A live minimal GPT-5.5 call succeeds and backend health remains available after restart.

## 15. Validation Plan

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Spec pair | `validate_spec_pair.py docs/specs/2026-07-13-neurotruth-bedrock-gpt-55-migration-spec.md` | PASS |
| Unit tests | `apps/backend/.venv/Scripts/python.exe -m pytest` | All tests PASS |
| Static check | `apps/backend/.venv/Scripts/python.exe -m compileall app tests` | Exit 0 |
| Live provider | Minimal `openai.gpt-5.5` Responses request in `us-east-1` | Success without printing credentials |
| Runtime smoke | Rebuild/restart backend and call `/health` | HTTP 200, model ready |

## 16. Risks and Open Notes

- RISK1. GPT-5.5 uses a different endpoint and schema from Claude; focused adapter tests mitigate protocol drift.
- RISK2. First access can return a temporary subscription setup error; live access was verified after automatic setup completed.
- RISK3. Model cost and response style may differ; quality evaluation remains an operator responsibility.
- RISK4. GPT-5.5 currently rejects `temperature`; the public adapter argument remains only for Converse compatibility.
- RISK5. The minimal Mantle request passes, but the latest full `/api/intervention/chat` smoke failed during slot extraction with HTTP 502 (`Bedrock slot request failed`). This integration path requires follow-up before live intervention is considered release-ready.

## 17. Implementation Checklist / Progress Record

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Verify AWS model availability and account access | Main | Completed | None | GPT-5.6: 404 not found; GPT-5.5: live success | Credentials were not printed. |
| P2 | Implement Mantle adapter/config/tests/docs | Worker | Completed | `apps/backend/app/ai/bedrock_agents.py`, `apps/backend/tests/test_bedrock_agents.py`, `.env`, `.env.example`, active Markdown docs | Focused adapter tests: 22 passed; compileall exit 0 | Preserved Converse rollback and omitted unsupported `temperature`. |
| P3 | Verify full suite and runtime | Main | Completed | Spec progress only | 50 tests passed; compileall exit 0; live adapter call succeeded; Docker rebuild and `/health` passed | Container model ID verified as `openai.gpt-5.5`; one existing Starlette deprecation warning remains. |
| P4 | Run a full intervention-route smoke | Main | Follow-up required | Documentation only | Synthetic `POST /api/intervention/chat` returned HTTP 502 | Minimal GPT-5.5 access is verified, but slot extraction failed with `Bedrock slot request failed`; no credentials or identifying data were logged. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.0 | 2026-07-13 | Feature Planner | Finalized GPT-5.5 migration decisions after live model verification. |
| 1.0 | 2026-07-13 | Main verifier | Recorded completed implementation, tests, live call, and runtime smoke. |
| 1.1 | 2026-07-13 | Main verifier | Recorded the full intervention-route slot-extraction failure separately from the successful minimal provider call. |
