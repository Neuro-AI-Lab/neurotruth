# NeuroTruth LLM Repetition Control Spec

- **Language role:** English source spec
- **Spec status:** Finalized
- **Spec version:** 1.1
- **Last updated:** 2026-07-13
- **English source:** `neurotruth/docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.md`
- **Korean mirror:** `neurotruth/docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.ko.md`
- **Requester / owner:** NeuroTruth project operator
- **Implementation status:** Implemented

## 0. Codex Implementation Handoff

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: neurotruth/docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.md
korean_mirror_spec: neurotruth/docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.ko.md
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
$feature-planner Implement `neurotruth/docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.md` using IMPLEMENTATION_ORCHESTRATION. Use the English source spec as authoritative, keep `neurotruth/docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.ko.md` synchronized, spawn worker sub-agents for source-code edits, and have the main agent verify minimal diffs and update progress records. If the spec has a gap, use TARGETED_REFINEMENT for only that gap before continuing.
```

## 1. Summary

Reduce repeated questions in NeuroTruth's backend-owned Bedrock intervention dialogue without changing its public API or provider. The implementation combines completion-aware chat and slot prompts, correct missing-slot calculation across accumulated slots, and one deterministic repair attempt when a new non-safety question closely repeats recent assistant history. If repair cannot produce a distinct response, the backend removes the repeated question and returns a brief question-free acknowledgement. Changes remain limited to NeuroTruth backend AI/orchestration, focused tests, and matching AI documentation.

## 2. Goals

- G1. Stop the model from asking about a topic that the user already answered, denied, did not know, or declined.
- G2. Preserve explicit negative, unknown, and refusal answers as completed grounded craving-slot values.
- G3. Calculate missing slots from accumulated current plus newly extracted slots so previously completed topics do not become missing again.
- G4. Detect strong repeated-question candidates and repair them without changing the intervention API response schema.

## 3. Non-Goals

- NG1. Do not change Sonnet 4.6 provider selection, Bedrock credentials, request transport, alert policy, mobile UI, database schema, handoff prompt, or sensor inference.
- NG2. Do not add session termination, a turn limit, embeddings, external NLP services, or dependencies.
- NG3. Do not redesign clinical safety policy; safety/probe/crisis questions remain exempt from repetition suppression.
- NG4. Do not rewrite unrelated pre-existing NeuroTruth worktree changes or historical specs.

## 4. Users and Use Cases

### 4.1 Target Users

- A patient using NeuroTruth text intervention after a craving alert.
- A researcher or operator evaluating conversation quality and handoff readiness.

### 4.2 Primary Use Cases

- UC1. A user answers a craving question negatively or says they do not know; the assistant proceeds without asking the same topic again.
- UC2. Sonnet produces a close paraphrase of a recent non-safety question; the backend makes one repair call and returns a distinct response.
- UC3. Repair fails or remains repetitive; the backend returns the non-question portion or a safe acknowledgement instead of another repeated question.

## 5. Final Decisions

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | Chat prompt | Ask at most one question, never force empathy, prefer one genuinely missing slot, and treat answered/negative/unknown/refused topics as complete. | User request plus validated NeuroSync experiment finding |
| D2 | Slot prompt | Preserve explicit negative, unknown, and refusal answers as non-empty flat strings with a short verbatim user quote, mapped only to the immediately preceding single-topic assistant question. | Validated NeuroSync experiment finding |
| D3 | Missing state | Compute `missingSlots` from filtered `currentSlots` merged with non-empty newly extracted values. | Repository defect evidence |
| D4 | Detection | Compare a candidate question with the last three assistant questions after NFKC/case normalization, leading acknowledgement removal, and punctuation/whitespace removal. Use exact match or standard-library `SequenceMatcher` ratio at or above `0.86`. | Agent conservative default |
| D5 | Repair | For a repeated non-safety question, perform at most one additional Bedrock call with a precise non-repetition instruction and the same model settings. | Agent conservative default |
| D6 | Fallback | If repair fails or is still repetitive, remove repeated interrogative sentences; if nothing meaningful remains, return a fixed Korean acknowledgement without a question. | Agent conservative default |
| D7 | Safety | Content involving suicide, self-harm, harm to others, immediate danger, emergency help, or explicit safety/probe/crisis language bypasses detection and repair. | Safety-preserving default |
| D8 | Compatibility | Keep `/api/intervention/chat`, `/api/intervention/slots`, and `/api/llm/chat` request/response shapes unchanged. Do not expose repair metadata publicly. | Agent conservative default |

## 6. Functional Requirements

- FR1. `CHAT_SYSTEM_PROMPT` must prioritize current history, `currentSlots`, and `missingSlots`; ask at most one concise question and never re-ask a completed topic by paraphrase.
- FR2. Neutral factual responses must not receive invented or formulaic empathy solely to satisfy a template.
- FR3. `build_chat_messages` must include filtered current slots and their deterministic missing-slot list in the per-turn context.
- FR4. `SLOTS_SYSTEM_PROMPT` must use user-role content as factual evidence and assistant turns only to identify the immediately preceding question topic.
- FR5. Explicit negative, unknown, and refusal answers must become grounded non-empty flat values for only that topic; unrelated slots remain null or unchanged.
- FR6. Existing grounded `currentSlots` must not be overwritten by null, empty, or weaker inferred values.
- FR7. `_ai_slots_extract` must calculate `missingSlots` from the merged accumulated slot state while preserving existing route-level merging and allowlist filtering.
- FR8. Repetition detection must inspect only question-bearing assistant responses and the most recent three assistant turns.
- FR9. A repeated non-safety candidate triggers one repair call; no repair loop is permitted.
- FR10. A failed or repeated repair uses deterministic local question removal/fallback and still returns the existing successful chat response schema.
- FR11. Safety-exempt candidates are returned directly with no repair or suppression.

## 7. User Experience / UI Requirements

- UI1. No mobile or web UI change is required.
- UI2. Users should observe fewer repeated questions and uninterrupted API responses; no new status label or repair notice is shown.

## 8. API / Data / State Requirements

- API1. Public endpoint payload and response keys remain backward-compatible.
- DATA1. Slot values remain JSON-compatible existing `Any` values; completed negative/unknown/refusal states use flat strings and require no migration.
- STATE1. The client-provided `conversationHistory` and `currentSlots` remain the source of cross-turn state; no new server-side repetition store is added.

## 9. Permissions, Security, Privacy, and Audit

- SEC1. Existing Bedrock credential handling and secret redaction behavior remain unchanged.
- PRIV1. Do not log full prompts, user messages, or repair comparison text.
- AUDIT1. No new persistence or public audit field is added; focused tests provide implementation evidence.

## 10. Error, Edge-Case, and Concurrency Behavior

- ERR1. If the initial Bedrock call fails, preserve the existing HTTP 502 behavior.
- ERR2. If only the optional repair call fails, use the deterministic fallback rather than converting a successful initial response into HTTP 502.
- EDGE1. Responses without a question do not trigger repair.
- EDGE2. A distinct question, even within the same craving conversation, is returned unchanged.
- EDGE3. Malformed slot JSON retains the existing safe empty extraction behavior, but missing-slot calculation still includes valid `currentSlots`.
- CONC1. All detection state is local to the request payload; no shared mutable global session state is introduced.

## 11. Dependencies and Configuration

- DEP1. Reuse Python standard-library `unicodedata`, `re`, and `difflib.SequenceMatcher` only.
- DEP2. Add no package, environment variable, lockfile, or model configuration.

## 12. Migration, Rollout, and Rollback

- MIG1. No database or output migration is required.
- ROLL1. Deploy with the existing backend because public API contracts are unchanged; validate with mocked Bedrock calls before any live smoke.
- BACK1. Roll back the focused backend AI/orchestration/docs/test files; stored sessions remain compatible.

## 13. Implementation Boundaries

### 13.1 Expected Change Areas

- `neurotruth/apps/backend/app/ai/bedrock_agents.py`
- `neurotruth/apps/backend/app/main.py`
- `neurotruth/apps/backend/tests/test_bedrock_agents.py`
- `neurotruth/apps/backend/tests/test_intervention_routes.py`
- Relevant English/Korean files under `neurotruth/docs/ai/`

### 13.2 Forbidden Changes

- Do not modify mobile, database, inference, alert, credential, deployment, dependency, or handoff generation behavior.
- Do not modify `neurosync`, `neurosync_LLM_test`, or `neurosync_test`.
- Do not revert or reformat unrelated NeuroTruth worktree changes.
- No unrelated refactors, formatting churn, dependency upgrades, broad rewrites, or generated-file churn.

### 13.3 Minimal-Change Guidance

- Keep repetition helpers pure and unit-testable in the existing Bedrock helper module.
- Keep orchestration changes inside `_ai_chat_respond` and `_ai_slots_extract` unless a verified gap requires otherwise.
- Preserve existing function signatures and response shapes.

## 14. Acceptance Criteria

- AC1. Given a completed slot, when the chat context is built, then both `currentSlots` and an accurate `missingSlots` list are present.
- AC2. Given current slots plus a new extraction, when missing slots are returned, then previously completed keys are not listed missing.
- AC3. Given an explicit negative/unknown/refusal after a single-topic question, the slot prompt instructs the model to store one grounded non-empty value and not expand it to other slots.
- AC4. Given an exact or strongly similar recent non-safety question, the backend makes exactly one repair call.
- AC5. Given a distinct question, a question-free response, or a safety question, the backend makes no repair call.
- AC6. Given repair failure or a repeated repair, the response remains HTTP-success-compatible and contains no repeated question.
- AC7. Existing 24 backend tests plus focused new tests pass without external network calls.
- AC8. No file outside the implementation boundary is changed by this feature.

## 15. Validation Plan

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Spec pair | `python validate_spec_pair.py neurotruth/docs/specs/2026-07-13-neurotruth-llm-repetition-control-spec.md` | PASS |
| Backend tests | `apps/backend/.venv/Scripts/python.exe -B -m pytest -q` from `neurotruth/apps/backend` | Existing and focused tests pass; no network |
| Compile | `apps/backend/.venv/Scripts/python.exe -B -m compileall -q app tests` | Exit 0 |
| Scope | Compare changed-file list against section 13 | Only expected files plus this spec pair |
| Live provider | Not run automatically | User may run a later manual smoke |

## 16. Risks and Open Notes

- RISK1. Text similarity is a conservative heuristic; prompt rules handle semantic repeats while the repair guard targets exact and strongly similar wording.
- RISK2. Client history quality determines cross-turn effectiveness; omitted history cannot be reconstructed by this stateless change.
- RISK3. The NeuroTruth repository is already heavily restructured and dirty; workers must modify only the explicitly allowed current files.

## 17. Implementation Checklist / Progress Record

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Inspect current backend and establish baseline | Main | Completed | Spec pair only | Existing backend: 24 tests PASS | Repository has pre-existing unrelated changes. |
| P2 | Implement prompts, slot state, and repetition repair | Worker | Completed | `apps/backend/app/ai/bedrock_agents.py`, `apps/backend/app/main.py`, two focused test files, four AI agent docs | Worker: 38 tests PASS; compile exit 0 | Follow-up correction preserves explicit user slot updates while null/empty output cannot overwrite; no live Bedrock call. |
| P3 | Independent verification and progress sync | Main | Completed | Spec pair only | Main: 38 tests PASS; compile exit 0; spec validation PASS; changed-file window matches section 13 | Existing Starlette `python_multipart` pending-deprecation warning only; public API and handoff behavior preserved. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.0 | 2026-07-13 | Feature Planner | Initial finalized spec for NeuroTruth internal LLM repetition control. |
| 1.1 | 2026-07-13 | Codex | Recorded completed prompt, accumulated-slot with explicit correction support, one-repair, fallback, documentation, and independent validation work. |
