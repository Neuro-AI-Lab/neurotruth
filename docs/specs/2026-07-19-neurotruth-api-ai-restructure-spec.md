# NeuroTruth Layered Backend Restructure — Living Implementation Specification

<!-- feature-planner-control
{
  "workflow": "feature-planner/v7",
  "state": "complete",
  "source_spec": "docs/specs/2026-07-19-neurotruth-api-ai-restructure-spec.md",
  "korean_mirror": "docs/specs/2026-07-19-neurotruth-api-ai-restructure-spec.ko.md",
  "spec_revision": 9,
  "reviewed_revision": 9,
  "selected_strategy": "STRAT-1",
  "implementation_direction": "user-approved-divergence",
  "direction_decision_id": "D-002",
  "minimal_change_policy": "strict",
  "final_domain_gate": "confirmed_none",
  "open_question_ids": [],
  "active_slices": [],
  "next_action": "none"
}
-->

> The user supplied and explicitly authorized the complete implementation plan. This English file is authoritative; the Korean file is the synchronized review mirror.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `complete`, revision 9, post-restructure correctness, cleanup, API contract, and active documentation verified |
| Outcome | Reorganize the single FastAPI backend into NeuroSync-style layers while preserving public APIs, persistence, mobile/web behavior, and deployment boundaries. |
| Recommended implementation | `STRAT-1` — relocate existing owners into explicit layers, split the three AI responsibilities, and default state-summary/report AI off without adding a service boundary. |
| Planned production targets | `apps/backend/app/ai/__init__.py::layered module relocation`; `apps/backend/app/ai/bedrock_agents.py::layered module relocation`; `apps/backend/app/alerts.py::layered module relocation`; `apps/backend/app/inference.py::layered module relocation`; `apps/backend/app/inference_time.py::layered module relocation`; `apps/backend/app/main.py::layered module relocation`; `apps/backend/app/memory.py::layered module relocation`; `apps/backend/app/security/__init__.py::layered module relocation`; `apps/backend/app/security/crypto.py::layered module relocation`; `apps/backend/app/security/passwords.py::layered module relocation`; `apps/backend/app/security/tokens.py::layered module relocation`; `apps/backend/app/settings.py::layered module relocation`; `apps/backend/app/v25/__init__.py::layered module relocation`; `apps/backend/app/v25/admin_service.py::layered module relocation`; `apps/backend/app/v25/auth_service.py::layered module relocation`; `apps/backend/app/v25/dashboard_service.py::layered module relocation`; `apps/backend/app/v25/dependencies.py::layered module relocation`; `apps/backend/app/v25/models.py::layered module relocation`; `apps/backend/app/v25/repository.py::layered module relocation`; `apps/backend/app/v25/routes_admin.py::layered module relocation`; `apps/backend/app/v25/routes_auth.py::layered module relocation`; `apps/backend/app/v25/routes_dashboard.py::layered module relocation`; `apps/backend/app/v25/routes_rppg.py::layered module relocation`; `apps/backend/app/v25/routes_sensor.py::layered module relocation`; `apps/backend/app/v25/routes_sessions.py::layered module relocation`; `apps/backend/app/v25/routes_stt.py::layered module relocation`; `apps/backend/app/v25/rppg_dgx.py::layered module relocation`; `apps/backend/app/v25/rppg_media.py::layered module relocation`; `apps/backend/app/v25/rppg_repository.py::layered module relocation`; `apps/backend/app/v25/rppg_service.py::layered module relocation`; `apps/backend/app/v25/rppg_storage.py::layered module relocation`; `apps/backend/app/v25/runtime.py::layered module relocation`; `apps/backend/app/v25/sensor_service.py::layered module relocation`; `apps/backend/app/v25/sensor_storage.py::layered module relocation`; `apps/backend/app/v25/session_agents.py::layered module relocation`; `apps/backend/app/v25/session_service.py::layered module relocation`; `apps/backend/app/v25/stt_client.py::layered module relocation`; `apps/backend/app/adapters/rppg_dgx.py::layered module relocation`; `apps/backend/app/adapters/rppg_media.py::layered module relocation`; `apps/backend/app/adapters/stt_client.py::layered module relocation`; `apps/backend/app/agents/bedrock.py::layered module relocation`; `apps/backend/app/agents/intervention.py::layered module relocation`; `apps/backend/app/agents/report.py::layered module relocation`; `apps/backend/app/agents/state_summary.py::layered module relocation`; `apps/backend/app/api/v1/dependencies.py::layered module relocation`; `apps/backend/app/api/v1/router.py::layered module relocation`; `apps/backend/app/api/v1/routes/admin.py::layered module relocation`; `apps/backend/app/api/v1/routes/auth.py::layered module relocation`; `apps/backend/app/api/v1/routes/dashboard.py::layered module relocation`; `apps/backend/app/api/v1/routes/rppg.py::layered module relocation`; `apps/backend/app/api/v1/routes/sensor.py::layered module relocation`; `apps/backend/app/api/v1/routes/session.py::layered module relocation`; `apps/backend/app/api/v1/routes/stt.py::layered module relocation`; `apps/backend/app/api/v1/routes/system.py::layered module relocation`; `apps/backend/app/core/config.py::layered module relocation`; `apps/backend/app/core/runtime.py::layered module relocation`; `apps/backend/app/core/security/__init__.py::layered module relocation`; `apps/backend/app/core/security/crypto.py::layered module relocation`; `apps/backend/app/core/security/passwords.py::layered module relocation`; `apps/backend/app/core/security/tokens.py::layered module relocation`; `apps/backend/app/ml/craving/latency.py::layered module relocation`; `apps/backend/app/ml/craving/model.py::layered module relocation`; `apps/backend/app/ml/craving/pipeline.py::layered module relocation`; `apps/backend/app/models/records.py::layered module relocation`; `apps/backend/app/prompts/intervention.py::layered module relocation`; `apps/backend/app/prompts/report.py::layered module relocation`; `apps/backend/app/prompts/state_summary.py::layered module relocation`; `apps/backend/app/repositories/legacy_memory.py::layered module relocation`; `apps/backend/app/repositories/postgres.py::layered module relocation`; `apps/backend/app/repositories/rppg.py::layered module relocation`; `apps/backend/app/schemas/admin.py::layered module relocation`; `apps/backend/app/schemas/auth.py::layered module relocation`; `apps/backend/app/schemas/dashboard.py::layered module relocation`; `apps/backend/app/schemas/rppg.py::layered module relocation`; `apps/backend/app/schemas/sensor.py::layered module relocation`; `apps/backend/app/schemas/session.py::layered module relocation`; `apps/backend/app/schemas/stt.py::layered module relocation`; `apps/backend/app/services/admin.py::layered module relocation`; `apps/backend/app/services/auth.py::layered module relocation`; `apps/backend/app/services/dashboard.py::layered module relocation`; `apps/backend/app/services/prediction.py::layered module relocation`; `apps/backend/app/services/rppg.py::layered module relocation`; `apps/backend/app/services/sensor.py::layered module relocation`; `apps/backend/app/services/session.py::layered module relocation`; `apps/backend/app/services/stt.py::layered module relocation`; `apps/backend/app/storage/rppg.py::layered module relocation`; `apps/backend/app/storage/sensor.py::layered module relocation`; `apps/backend/app/inference_time.txt::latency artifact ownership`; `apps/backend/app/ml/craving/inference_time.txt::latency artifact ownership`; `apps/backend/app/core/config.py::AI feature flags`; `apps/backend/app/services/session.py::state/report call sites` |
| Expected additions | New production files: apps/backend/app/adapters/rppg_dgx.py, apps/backend/app/adapters/rppg_media.py, apps/backend/app/adapters/stt_client.py, apps/backend/app/agents/bedrock.py, apps/backend/app/agents/intervention.py, apps/backend/app/agents/report.py, apps/backend/app/agents/state_summary.py, apps/backend/app/api/v1/dependencies.py, apps/backend/app/api/v1/router.py, apps/backend/app/api/v1/routes/admin.py, apps/backend/app/api/v1/routes/auth.py, apps/backend/app/api/v1/routes/dashboard.py, apps/backend/app/api/v1/routes/rppg.py, apps/backend/app/api/v1/routes/sensor.py, apps/backend/app/api/v1/routes/session.py, apps/backend/app/api/v1/routes/stt.py, apps/backend/app/api/v1/routes/system.py, apps/backend/app/core/config.py, apps/backend/app/core/runtime.py, apps/backend/app/core/security/__init__.py, apps/backend/app/core/security/crypto.py, apps/backend/app/core/security/passwords.py, apps/backend/app/core/security/tokens.py, apps/backend/app/ml/craving/latency.py, apps/backend/app/ml/craving/model.py, apps/backend/app/ml/craving/pipeline.py, apps/backend/app/models/records.py, apps/backend/app/prompts/intervention.py, apps/backend/app/prompts/report.py, apps/backend/app/prompts/state_summary.py, apps/backend/app/repositories/legacy_memory.py, apps/backend/app/repositories/postgres.py, apps/backend/app/repositories/rppg.py, apps/backend/app/schemas/admin.py, apps/backend/app/schemas/auth.py, apps/backend/app/schemas/dashboard.py, apps/backend/app/schemas/rppg.py, apps/backend/app/schemas/sensor.py, apps/backend/app/schemas/session.py, apps/backend/app/schemas/stt.py, apps/backend/app/services/admin.py, apps/backend/app/services/auth.py, apps/backend/app/services/dashboard.py, apps/backend/app/services/prediction.py, apps/backend/app/services/rppg.py, apps/backend/app/services/sensor.py, apps/backend/app/services/session.py, apps/backend/app/services/stt.py, apps/backend/app/storage/rppg.py, apps/backend/app/storage/sensor.py, apps/backend/app/ml/craving/inference_time.txt; dependencies: None; shared abstractions: User-approved layered module layout only |
| Work plan | `WS1`, `WS2`, `WS3`, `WS4`, `WS5`, and `WS6` verified; serial `WS7` sensor contract/idempotency, `WS8` lifecycle/state masking, `WS9` dead-code/name cleanup, then `WS10` active API/documentation refresh |
| Open questions | None |
| Agent decisions to review | None; the user explicitly selected the service boundary, AI scope, folder style, compatibility posture, and disabled behavior. |
| Last material change | Revision 9 — the user requested a correctness/cleanliness/API-doc audit; repository review found five bounded runtime contract/lifecycle defects plus stale active documentation. The public route set, database, dependencies, and deployment direction remain unchanged. |

## 2. Outcome and Scope

### Outcome

Backend contributors can locate HTTP routes, schemas, services, AI agents, prompts, model inference, repositories, adapters, and encrypted storage by responsibility. Runtime behavior remains a single `apps/backend` FastAPI deployment plus the existing STT worker.

### In Scope

- Replace `app/v25` and the mixed root modules with the approved layered package tree.
- Keep all current public paths and request/response contracts.
- Keep intervention dialogue active for both text and voice-derived text.
- Keep STT as `route -> service -> adapter -> apps/stt-service`.
- Keep binary craving inference under `ml/craving` with orchestration in `services/prediction.py`.
- Preserve rPPG behavior while moving its client, service, repository, and storage owners.
- Default state-summary and report AI calls off with explicit environment flags and preserve their implementation for future enablement.
- Update tests, imports, OpenAPI organization, backend docs, mobile API docs, Compose/env examples, and the living spec pair.
- Enforce the already documented 20-second sensor-window contract, serialize concurrent retry handling, mask disabled state summaries on dashboard reads, and make partial startup/shutdown safe.
- Remove production-only dead legacy Bedrock helpers and narrow stale internal `v25` names without changing public compatibility keys.
- Refresh active API and operator Markdown against the live 42-operation route inventory; historical specifications remain historical.

### Out of Scope / Non-Goals

- No `apps/ai-server`, internal HTTP AI API, database migration, dependency addition, model change, prompt behavior redesign, mobile UI change, or web UI change.
- No change to authentication, consent, encryption, session idempotency, STT limits, rPPG quality rules, craving thresholds, or Alembic revision.
- No compatibility shim retaining `app/v25` or `app.inference` after all internal imports are migrated.
- Do not touch the user's unrelated handoff document deletions or new presentation artifact.

### Users and Primary Flow

1. Mobile/web clients continue calling the same `/api/...` endpoints.
2. Thin route modules validate requests and call typed service owners.
3. Session text and confirmed STT text reach the same intervention agent; sensor windows reach the same binary craving model.
4. Persistence and public responses remain compatible, while disabled state/report AI performs no Bedrock call.

### Current Assumptions and Constraints

- `apps/backend` remains the deployment and Docker build context (`D-001`).
- The structural layering is an explicit user-approved direction change (`D-002`), limited to the mapped backend modules.
- The pre-cleanup audit baseline was `162 passed, 1 skipped`. After removing nine tests that exercised deleted production-dead helpers and adding the new boundary tests, the final behavioral suite is `159 passed, 1 skipped`; compileall succeeds and the live OpenAPI route/method set exactly matches all 42 documented operations (`D-014`).

## 3. Repository Pattern Baseline

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Deployment | One FastAPI backend owns DB, Bedrock, craving inference, and provider proxies. | `apps/backend/app/main.py::lifespan`; `apps/db/docker-compose.yml` | Keep one backend process and existing STT container. |
| Public API | Routers expose unversioned `/api/...` URLs. | `apps/backend/app/v25/routes_*.py`; `apps/mobile/SERVER_API_SPEC.md` | Internal `api/v1` must not add `/v1` to URLs. |
| Dialogue | `SessionService.message` persists the user message, calls the dialogue agent, validates, then persists one reply. | `apps/backend/app/v25/session_service.py::SessionService.message` | Preserve consent, locking, retry/idempotency, modality metadata, encryption, and errors. |
| State/report | Deterministic inference is persisted independently from optional LLM text; reports are asynchronous jobs. | `SessionService._create_inference`; `SessionService._run_report` | Disabled AI must not change deterministic state or historical report reads. |
| STT | Backend validates/upload-copies audio to tmpfs and calls the separate STT worker. | `routes_stt.py::transcribe`; `stt_client.py::SttClient` | Preserve authentication, voice consent, types, limits, cleanup, and error codes. |
| Craving inference | A process-wide model is loaded once and consumed through `SensorService`. | `app/inference.py::RealtimePredictionService`; `main.py::RuntimePredictorAdapter` | Preserve model checksum/device fallback, output schema, persistence, alerts, and SSE. |
| Testing | Tests import production symbols directly and use fake repositories/providers. | `apps/backend/tests/test_*_v25.py` | Update imports and retain focused behavior assertions. |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | Existing public routers and request validation | `apps/backend/app/v25/routes_*.py` | Relocate and thin them without changing decorators or error mapping. |
| R-002 | Existing services and repository protocol | `apps/backend/app/v25/*_service.py`; `repository.py` | Move code with ownership intact; do not rewrite transactions. |
| R-003 | Existing Bedrock adapter and output validators | `app/ai/bedrock_agents.py`; `v25/session_agents.py` | Split by agent/prompt responsibility and call the same provider. |
| R-004 | Existing craving model and worker | `app/inference.py` | Split model/pipeline from service orchestration without changing math or artifacts. |
| R-005 | Existing STT client and tests | `v25/stt_client.py`; `tests/test_stt_routes_v25.py` | Reuse behind the new service layer. |
| R-006 | Existing backend test suite | `apps/backend/tests` | Treat 152 pass/1 skip as the regression floor. |

## 4. Decisions and Questions

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | Deployment | Keep `apps/backend` as one FastAPI process; do not create `apps/ai-server`. | user | The user selected folder-only separation. | No new service, network hop, or shared-contract package. | confirmed | resolved |
| D-002 | Architecture | Adopt the latest NeuroSync-style layered internal folder structure. | user | The user selected the NeuroSync layered option and supplied the exact target tree. | Authorizes removal of `app/v25` and relocation into explicit layers. | confirmed | resolved |
| D-003 | Compatibility | Preserve all public `/api/...` URLs and mobile/web contracts. | user | The user selected public API preservation. | Only internal imports/ownership and OpenAPI grouping change. | confirmed | resolved |
| D-004 | AI scope | Keep intervention dialogue, STT, and craving prediction active; default state-summary/report AI off. | user | Explicit implementation request. | Adds two typed flags and separates the agents. | confirmed | resolved |
| D-005 | Disabled behavior | Quietly skip disabled AI: unavailable/null summaries, no automatic report job, 202/not_started manual request, historical reads preserved. | user | The user selected “flag + quietly skip.” | No DB status or schema extension is needed. | confirmed | resolved |
| D-006 | Persistence | Make no database or Alembic change. | user | Public/persistence compatibility is required. | Existing revision `20260717_0005` remains required. | confirmed | resolved |
| D-007 | Baseline | Backend tests currently pass `152 passed, 1 skipped`. | repository | `.venv\\Scripts\\python.exe -m pytest -q --basetemp=.pytest-plan-basetemp` | Establishes the acceptance floor. | not-required | resolved |
| D-008 | Change budget | Count content-preserving relocation paths literally: 110 changed paths, 87 production paths, and 50 new production Python paths before rename detection. | repository | `git status --short --untracked-files=all -- apps/backend/app apps/backend/tests` | Corrects the WS2 expansion alarm without changing the approved tree, behavior, or direction. | not-required | resolved |
| D-009 | Change budget | Count 872 literal production additions across split/relocated files rather than the +148 net line delta. | repository | Temporary-index `git diff --cached --find-renames=40% --numstat -- apps/backend/app` | Raises only the WS2 expansion alarm to 900; implementation behavior and target tree are unchanged. | not-required | resolved |
| D-010 | Ownership | Relocate the tracked `app/inference_time.txt` artifact beside `ml/craving/latency.py`. | repository | Main and read-only review found the new default path is `ml/craving/inference_time.txt` while the tracked file remained at root. | Removes a stale mixed-root artifact and preserves the tracked latency record at its actual default location. | not-required | resolved |
| D-011 | Runtime composition | Pass typed state-summary/report flags from `core/config.py` through `core/runtime.py` into `SessionService`. | repository | `initialize_runtime` is the composition root and currently constructs the three agents and `SessionService`. | Keeps environment parsing typed and makes the zero-call branches explicit without service-local environment reads. | not-required | resolved |
| D-012 | Prediction ownership | Make `RealtimePredictionService` the one process-wide model queue and patient SSE-hub owner used by authenticated sensor ingestion; remove its inactive legacy persistence/broadcast path. | repository | Final read-only review found `RuntimePredictorAdapter.predict` called the model directly while `SensorService` owned a second hub, leaving the prediction queue, latency, and hub disconnected from `/api/sensor-windows`. | Restores the user-approved `services/prediction.py` loading/queue/SSE responsibility without changing model math, DB persistence, routes, or payloads. | not-required | resolved |
| D-013 | Prediction lifecycle | Reject new work while stopping, fail queued futures, stop rPPG before the shared prediction worker, and strip worker-only timing keys before rPPG persistence. | repository | WS5 correction review found shutdown could leave futures waiting after the worker exits and the shared adapter adds `_lat`/`_readyPerf` to rPPG results. | Preserves bounded shutdown and the existing rPPG DB/public payload contract without changing inference math or schema. | not-required | resolved |
| D-014 | Audit baseline | Preserve the original `152 passed, 1 skipped` acceptance floor, record the pre-cleanup `162 passed, 1 skipped`, and require the final behavioral suite plus exact equality between the 42 live OpenAPI method/path pairs and the active mobile inventory. | repository | Fresh read-only audit and final validation using the backend virtual environment and live `app.openapi()`; final `159 passed, 1 skipped` reflects deletion of nine tests for production-dead helpers plus new boundary coverage. | Keeps meaningful behavior coverage explicit without counting tests for deleted code. | not-required | resolved |
| D-015 | Correctness scope | Correct sensor duration/error/idempotency boundaries, disabled-summary dashboard masking, partial-startup cleanup, and in-flight prediction shutdown before declaring the restructure clean. | user | The user explicitly requested error validation and code cleanup; the audit reproduced contract gaps inside the approved backend scope. | Adds bounded tests and fixes without changing valid public calls, database schema, model math, or topology. | confirmed | resolved |
| D-016 | API authority | Keep the human `SERVER_API_SPEC.md` authoritative for detailed response payloads and describe `/openapi.json` precisely as the route/method/request-schema authority until response models are completed in a separately reviewed scope. | repository | All 42 routes are correct, but current JSON success schemas are generic objects; adding response validation across 35 operations would materially expand this corrective pass. | Prevents the docs from overstating the generated contract while preserving runtime payloads. | not-required | resolved |
| D-017 | Cleanup | Remove only production-unreferenced Bedrock slot/handoff/repetition helpers and narrow stale internal names; retain public `health.v25`, `v25_unavailable`, repository protocol/class names, and historical specs for compatibility and auditability. | repository | Import scan distinguishes dead helpers/internal labels from compatibility-visible fields and broad repository symbols. | Reduces dead code without a broad rename or historical-doc rewrite. | not-required | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | Deployment | Split services or folders only? | Changes runtime topology. | Keep one backend. | D-001 | answered | User selected folder-only separation. |
| Q-002 | AI | Which AI capabilities remain active? | Changes provider calls and product behavior. | Intervention, STT, and craving only. | D-004 | answered | User selected the three active capabilities. |
| Q-003 | Structure | Feature-first or NeuroSync layered folders? | Determines module ownership. | NeuroSync layered structure. | D-002 | answered | User selected layered folders. |
| Q-004 | Disabled behavior | Error or silently skip state/report AI? | Changes API behavior while flags are off. | Quietly skip with not_started/unavailable. | D-005 | answered | User selected quiet skipping. |

## 5. Requirements and Acceptance Criteria

### Functional Requirements

- **FR-001:** Production code must use the approved `api/v1`, `core`, `schemas`, `models`, `services`, `agents`, `prompts`, `ml/craving`, `repositories`, `adapters`, and `storage` owners, with no remaining `app/v25` package.
- **FR-002:** Every existing public route, HTTP method, authentication/consent gate, and compatible response field must remain available at the same URL.
- **FR-003:** Text and voice-derived messages must use the same intervention dialogue workflow and preserve `inputModality`, encryption, locking, and idempotency.
- **FR-004:** STT must retain its separate container and backend `route -> service -> adapter` flow with current limits, cleanup, and error codes.
- **FR-005:** Craving model/preprocessing must live under `ml/craving`; its process-wide orchestration must live in `services/prediction.py` with unchanged predictions, alerts, persistence, and SSE.
- **FR-006:** With `STATE_SUMMARY_AI_ENABLED=false`, deterministic inference must persist with `summaryStatus=unavailable`, `summary=null`, and zero state-summary provider/model-registration calls.
- **FR-007:** With `REPORT_AI_ENABLED=false`, automatic report creation must be skipped; manual creation must return HTTP 202 with `reportId/version=null` and `status=not_started`; historical GET results must remain readable.
- **FR-008:** Enabling either flag must restore its current Bedrock-backed behavior without another code change.
- **FR-009:** rPPG functionality and provider isolation must remain unchanged after relocation.
- **FR-010:** Active documentation and environment examples must describe the new structure and feature-flag defaults.
- **FR-011:** Sensor uploads must accept only internally consistent 20-second windows, return 422 for invalid timing, and serialize identical concurrent `(patient, clientWindowId)` retries so inference, alert evaluation, persistence, and SSE occur once.
- **FR-012:** With state-summary AI disabled, session and dashboard reads must both return unavailable/null without decrypting stored summary prose; enabling the flag restores current patient behavior.
- **FR-013:** Startup failure and prediction shutdown must close partial runtime resources and must not allow a previous synchronous model inference to overlap a restart.
- **FR-014:** Active API, architecture, AI, database, mobile, deployment, and development Markdown must reflect current paths, defaults, migration head `20260717_0005`, 20-second/1,024-point signals, and implemented feature boundaries.
- **FR-015:** Active production modules must not retain pre-free-dialogue Bedrock slot/handoff/repetition helpers or stale ownership documentation that has no production caller.

### Non-Functional Requirements

- **NFR-001:** Add no dependency, database migration, internal HTTP service, or unrelated abstraction.
- **NFR-002:** Preserve encryption AAD, security fail-closed behavior, sanitized provider errors, retry/concurrency behavior, and model checksum/device fallback.
- **NFR-003:** Prefer content-preserving moves; business logic edits are limited to routing ownership, schema extraction, flags, and the approved quiet-skip behavior.
- **NFR-004:** Preserve unrelated working-tree changes exactly.
- **NFR-005:** Do not add dependencies, migrations, public routes, response fields, or model transformations while correcting the audited edges.

### Acceptance Criteria

- **AC-001:** `rg` finds no production/test imports of `app.v25`, `app.inference`, `app.settings`, `app.security`, or `app.ai` and no tracked `apps/backend/app/v25` files.
- **AC-002:** OpenAPI contains the same existing path/method set; internal `api/v1` introduces no `/v1` URL prefix.
- **AC-003:** Backend compile succeeds and pytest passes at least 152 tests with only the established skip or fewer skips.
- **AC-004:** Focused dialogue tests prove text/voice use one intervention agent and retry/idempotency behavior remains intact.
- **AC-005:** Focused flag tests prove zero disabled provider calls and the exact unavailable/not_started contracts.
- **AC-006:** Existing STT, craving, rPPG, auth, dashboard, schema, encryption, and repository tests pass.
- **AC-007:** Compose config remains valid and, when Docker is available, rebuilt `/health`, `/ready`, `/model/status`, `/api/stt/status`, authenticated dialogue, and sensor prediction succeed.
- **AC-008:** No Alembic revision, SQL schema, dependency lock, mobile production code, or web production code changes.
- **AC-009:** Focused sensor tests prove 10-second, 1 ms, reversed, mismatched, and overflow timestamp inputs fail with 422 before storage/inference; concurrent identical retries yield one predictor, alert, persistence, and SSE execution.
- **AC-010:** Focused dashboard tests prove disabled stored summaries are unavailable/null for patient reads and unavailable for admin metadata without decryption, while enabled patient reads retain the historical prose.
- **AC-011:** Lifecycle tests prove rPPG/runtime startup failure closes every partial resource once and `stop()` does not return while the model thread can overlap a subsequent `start()`.
- **AC-012:** Active documentation has no removed `app/v25`/`app/ai` ownership paths, old current-head `0004`, current 10-second rPPG/sensor wording, or default-pending report example; the 42-operation inventory remains exact.

### Edge and Failure Cases

- Disabled state AI -> deterministic state commits; absence of prose is not audited as a provider failure.
- Disabled report AI -> no job row; manual POST is a successful no-op; existing rows remain visible through GET.
- STT unavailable -> typed chat remains usable and current sanitized STT error is returned.
- Craving model unavailable -> auth/chat remain usable and sensor prediction retains the current 503 behavior.
- Provider/rPPG failures -> current timeout, cleanup, and sanitized error behavior remains unchanged.
- Invalid sensor timing -> FastAPI 422 before encrypted storage or model access; consent failures alone map to 403.
- Simultaneous identical sensor retries -> one keyed critical section and one published prediction; conflicting content still returns 409.
- Shutdown during synchronous model inference -> request fails as unavailable, but service shutdown waits for the thread boundary before restart.

## 6. Implementation Strategy and Direction

### STRAT-1 — Content-Preserving Layered Relocation

- **Direction:** `user-approved-divergence`
- **Current approach:** Move existing owners into the exact approved layers, update imports/tests, split the mixed agent/model modules at their current responsibility boundaries, then add the two flags at composition/service call sites.
- **Existing flow to reuse:** R-001 through R-006.
- **Why this is minimal:** The user explicitly requested a new layered direction; content-preserving moves plus two narrow flags avoid a new service, dependency, DB state, compatibility shim, or business rewrite.
- **Behavior-preserving limitations:** `api/v1` is an internal package name only; the STT worker remains separately deployed; state/report code remains present but disabled by default.
- **Explicit exclusions:** No endpoint family redesign, shared-contract package, model/prompt redesign, DB migration, client rewrite, or adjacent cleanup.
- **Compatibility and migration posture:** One deployable refactor with no data migration. Roll back by reverting the source move/config commit; flags can independently restore the existing optional AI behavior.
- **Direction approval:** D-002 authorizes only the module-layer relocation rows marked `approved-divergence`.
- **Open-question sensitivity:** None.

### Material Alternatives Considered

| Strategy | Direction | Benefit | Additional code or risk | Decision |
| --- | --- | --- | --- | --- |
| Separate `apps/ai-server` | user-approved-divergence | Independent AI deployment | Network contracts, operations, latency, and a new service | rejected by D-001 |
| Feature-first vertical slices | user-approved-divergence | Each domain is self-contained | Does not match the selected latest NeuroSync style | rejected by D-002 |
| Keep `app/v25` wrappers | preserve | Lower immediate import churn | Leaves the duplicate/confusing structure the user asked to remove | rejected |

## 7. Modification Map and Change Budget

### Modification Map

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | docs | `docs/specs/2026-07-19-neurotruth-api-ai-restructure-spec.md` | living spec | add | approved user plan | Record reviewed design/progress. | Implements FR-010 and NFR-004. | WS1 | preserve |
| CH-002 | docs | `docs/specs/2026-07-19-neurotruth-api-ai-restructure-spec.ko.md` | Korean mirror | add | approved user plan | Synchronize user-review mirror. | Implements FR-010 and NFR-004. | WS1 | preserve |
| CH-100 | production | `apps/backend/app/ai/__init__.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-101 | production | `apps/backend/app/ai/bedrock_agents.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-102 | production | `apps/backend/app/alerts.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-103 | production | `apps/backend/app/inference.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-104 | production | `apps/backend/app/inference_time.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-105 | production | `apps/backend/app/main.py` | layered module relocation | edit | current app composition | Update central composition for the layered owners. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-106 | production | `apps/backend/app/memory.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-107 | production | `apps/backend/app/security/__init__.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-108 | production | `apps/backend/app/security/crypto.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-109 | production | `apps/backend/app/security/passwords.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-110 | production | `apps/backend/app/security/tokens.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-111 | production | `apps/backend/app/settings.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-112 | production | `apps/backend/app/v25/__init__.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-113 | production | `apps/backend/app/v25/admin_service.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-114 | production | `apps/backend/app/v25/auth_service.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-115 | production | `apps/backend/app/v25/dashboard_service.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-116 | production | `apps/backend/app/v25/dependencies.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-117 | production | `apps/backend/app/v25/models.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-118 | production | `apps/backend/app/v25/repository.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-119 | production | `apps/backend/app/v25/routes_admin.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-120 | production | `apps/backend/app/v25/routes_auth.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-121 | production | `apps/backend/app/v25/routes_dashboard.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-122 | production | `apps/backend/app/v25/routes_rppg.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-123 | production | `apps/backend/app/v25/routes_sensor.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-124 | production | `apps/backend/app/v25/routes_sessions.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-125 | production | `apps/backend/app/v25/routes_stt.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-126 | production | `apps/backend/app/v25/rppg_dgx.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-127 | production | `apps/backend/app/v25/rppg_media.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-128 | production | `apps/backend/app/v25/rppg_repository.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-129 | production | `apps/backend/app/v25/rppg_service.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-130 | production | `apps/backend/app/v25/rppg_storage.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-131 | production | `apps/backend/app/v25/runtime.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-132 | production | `apps/backend/app/v25/sensor_service.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-133 | production | `apps/backend/app/v25/sensor_storage.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-134 | production | `apps/backend/app/v25/session_agents.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-135 | production | `apps/backend/app/v25/session_service.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-136 | production | `apps/backend/app/v25/stt_client.py` | layered module relocation | remove | approved relocation destination | Remove the legacy path after its owner is relocated. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-137 | test | `apps/backend/tests/test_admin_routes_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-138 | test | `apps/backend/tests/test_alerts.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-139 | test | `apps/backend/tests/test_auth_routes_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-140 | test | `apps/backend/tests/test_auth_service_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-141 | test | `apps/backend/tests/test_bedrock_agents.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-142 | test | `apps/backend/tests/test_binary_craving_model.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-143 | test | `apps/backend/tests/test_craving_bar_dashboard_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-144 | test | `apps/backend/tests/test_inference_alert_sessions.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-145 | test | `apps/backend/tests/test_intervention_dashboard_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-146 | test | `apps/backend/tests/test_intervention_schema_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-147 | test | `apps/backend/tests/test_memory.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-148 | test | `apps/backend/tests/test_probability_series_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-149 | test | `apps/backend/tests/test_repository_model_version_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-150 | test | `apps/backend/tests/test_rppg_routes_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-151 | test | `apps/backend/tests/test_rppg_runtime_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-152 | test | `apps/backend/tests/test_rppg_schema_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-153 | test | `apps/backend/tests/test_rppg_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-154 | test | `apps/backend/tests/test_security_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-155 | test | `apps/backend/tests/test_sensor_routes_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-156 | test | `apps/backend/tests/test_sensor_storage_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-157 | test | `apps/backend/tests/test_sessions_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-158 | test | `apps/backend/tests/test_sse_payload.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-159 | test | `apps/backend/tests/test_stt_routes_v25.py` | layered import/contract coverage | edit | existing backend test | Update imports and preserve or extend the mapped regression assertion. | Proves AC-001, AC-002, AC-003, AC-004, AC-006, and AC-008. | WS2 | preserve |
| CH-160 | production | `apps/backend/app/adapters/rppg_dgx.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-161 | production | `apps/backend/app/adapters/rppg_media.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-162 | production | `apps/backend/app/adapters/stt_client.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-163 | production | `apps/backend/app/agents/bedrock.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-164 | production | `apps/backend/app/agents/intervention.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-165 | production | `apps/backend/app/agents/report.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-166 | production | `apps/backend/app/agents/state_summary.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-167 | production | `apps/backend/app/api/v1/dependencies.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-168 | production | `apps/backend/app/api/v1/router.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-169 | production | `apps/backend/app/api/v1/routes/admin.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-170 | production | `apps/backend/app/api/v1/routes/auth.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-171 | production | `apps/backend/app/api/v1/routes/dashboard.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-172 | production | `apps/backend/app/api/v1/routes/rppg.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-173 | production | `apps/backend/app/api/v1/routes/sensor.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-174 | production | `apps/backend/app/api/v1/routes/session.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-175 | production | `apps/backend/app/api/v1/routes/stt.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-176 | production | `apps/backend/app/api/v1/routes/system.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-177 | production | `apps/backend/app/core/config.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-178 | production | `apps/backend/app/core/runtime.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-179 | production | `apps/backend/app/core/security/__init__.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-180 | production | `apps/backend/app/core/security/crypto.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-181 | production | `apps/backend/app/core/security/passwords.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-182 | production | `apps/backend/app/core/security/tokens.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-183 | production | `apps/backend/app/ml/craving/latency.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-184 | production | `apps/backend/app/ml/craving/model.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-185 | production | `apps/backend/app/ml/craving/pipeline.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-186 | production | `apps/backend/app/models/records.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-187 | production | `apps/backend/app/prompts/intervention.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-188 | production | `apps/backend/app/prompts/report.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-189 | production | `apps/backend/app/prompts/state_summary.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-190 | production | `apps/backend/app/repositories/legacy_memory.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-191 | production | `apps/backend/app/repositories/postgres.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-192 | production | `apps/backend/app/repositories/rppg.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-193 | production | `apps/backend/app/schemas/admin.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-194 | production | `apps/backend/app/schemas/auth.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-195 | production | `apps/backend/app/schemas/dashboard.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-196 | production | `apps/backend/app/schemas/rppg.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-197 | production | `apps/backend/app/schemas/sensor.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-198 | production | `apps/backend/app/schemas/session.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-199 | production | `apps/backend/app/schemas/stt.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-200 | production | `apps/backend/app/services/admin.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-201 | production | `apps/backend/app/services/auth.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-202 | production | `apps/backend/app/services/dashboard.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-203 | production | `apps/backend/app/services/prediction.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-204 | production | `apps/backend/app/services/rppg.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-205 | production | `apps/backend/app/services/sensor.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-206 | production | `apps/backend/app/services/session.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-207 | production | `apps/backend/app/services/stt.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-208 | production | `apps/backend/app/storage/rppg.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-209 | production | `apps/backend/app/storage/sensor.py` | layered module relocation | add | approved legacy owner | Add the approved layered destination with relocated/split content. | Implements FR-001 and AC-001. | WS2 | approved-divergence |
| CH-210 | production | `apps/backend/app/inference_time.txt` | latency artifact ownership | remove | `apps/backend/app/ml/craving/latency.py` | Remove the stale root artifact after relocation. | Implements FR-001, FR-005, and AC-001. | WS2 | approved-divergence |
| CH-211 | production | `apps/backend/app/ml/craving/inference_time.txt` | latency artifact ownership | add | `apps/backend/app/inference_time.txt` | Move the tracked latency record beside its writer without content changes. | Implements FR-001, FR-005, and AC-001. | WS2 | approved-divergence |
| CH-005 | config | `apps/backend/app/core/config.py` | AI feature flags | extend | current environment-backed settings | Add typed default-false state-summary and report settings. | Implements FR-006 through FR-008 and AC-005. | WS3 | preserve |
| CH-013 | production | `apps/backend/app/core/runtime.py` | layered module relocation | extend | current `initialize_runtime` composition root | Pass the two typed flags into `SessionService` while keeping intervention active. | Implements FR-006 through FR-008 and AC-005. | WS3 | preserve |
| CH-006 | production | `apps/backend/app/services/session.py` | state/report call sites | extend | current `_create_inference`, `_queue_report`, `_run_report` | Add exact quiet-skip branches while preserving enabled behavior. | Implements FR-006 through FR-008 and AC-005. | WS3 | preserve |
| CH-007 | test | `apps/backend/tests/test_sessions.py` | disabled/enabled AI behavior tests | add | relocated current session tests | Prove zero disabled provider calls and preserved enabled behavior. | Proves AC-003 through AC-006. | WS3 | preserve |
| CH-008 | test | `apps/backend/tests/test_api_structure.py` | public route and layer-boundary tests | add | current app/OpenAPI and import paths | Prove unchanged public path/methods and absent legacy imports. | Proves AC-001, AC-002, and AC-005. | WS3 | preserve |
| CH-009 | config | `.env.example` | AI feature flag defaults | edit | existing environment example | Document the two default-off settings. | Implements FR-006, FR-007, FR-010. | WS4 | preserve |
| CH-010 | config | `apps/db/docker-compose.yml` | backend environment | edit | existing backend environment block | Pass the two default-off settings without changing services. | Implements FR-006, FR-007, FR-010. | WS4 | preserve |
| CH-011 | docs | `apps/backend/README.md` | active backend architecture | edit | current backend README | Describe layers, active AI, and disabled state/report behavior. | Implements FR-002 and FR-010. | WS4 | preserve |
| CH-012 | docs | `apps/mobile/SERVER_API_SPEC.md` | report API contract | edit | current mobile API contract | Document successful no-op report creation while disabled. | Implements FR-002, FR-007, FR-010. | WS4 | preserve |
| CH-212 | production | `apps/backend/app/services/prediction.py` | layered module relocation | extend | current queue/model adapter plus inactive legacy worker path | Route authenticated prediction requests through one request/response queue and one patient-scoped hub; remove inactive legacy persistence/broadcast ownership. | Implements FR-001, FR-005, NFR-004, AC-003, and AC-006. | WS5 | preserve |
| CH-213 | production | `apps/backend/app/services/sensor.py` | layered module relocation | extend | current locally constructed patient hub | Inject/use the prediction service hub and publish internal timing only to SSE while preserving public/DB responses. | Implements FR-005, NFR-001, and AC-003. | WS5 | preserve |
| CH-214 | production | `apps/backend/app/main.py` | layered module relocation | extend | current lifespan composition | Remove the legacy-memory disable workaround and inject the prediction-owned hub into `SensorService`. | Implements FR-001 and FR-005. | WS5 | preserve |
| CH-215 | production | `apps/backend/app/api/v1/routes/sensor.py` | layered module relocation | extend | current authenticated SSE generator | Tie latency recording to the active authenticated SSE path and strip all worker-only keys. | Implements FR-002, FR-005, and AC-002. | WS5 | preserve |
| CH-216 | test | `apps/backend/tests/test_inference_alert_sessions.py` | prediction orchestration tests | edit | obsolete direct/legacy orchestration assertions | Prove adapter queue routing, patient hub isolation, and absence of direct model bypass. | Proves AC-003 and AC-006. | WS5 | preserve |
| CH-217 | test | `apps/backend/tests/test_sensor_routes_v25.py` | sensor SSE ownership tests | edit | current sensor service/SSE tests | Prove injected hub publication remains patient-isolated and public responses hide timing keys. | Proves AC-002, AC-003, and AC-006. | WS5 | preserve |
| CH-218 | docs | `apps/backend/README.md` | active backend architecture | edit | current prediction-flow and validation wording | Describe the consolidated queue/SSE flow and replace stale Docker rerun text with final acceptance evidence. | Implements FR-005 and FR-010. | WS5 | preserve |
| CH-219 | production | `apps/backend/app/services/prediction.py` | layered module relocation | extend | current request/response worker queue | Track running/stopping state, reject work without an active worker, and fail queued/in-flight futures during shutdown. | Implements FR-005, NFR-002, AC-003, and AC-006. | WS6 | preserve |
| CH-220 | production | `apps/backend/app/main.py` | layered module relocation | edit | current prediction-before-runtime shutdown | Stop rPPG/runtime consumers before stopping the shared prediction worker. | Implements FR-005, FR-009, and NFR-002. | WS6 | preserve |
| CH-221 | production | `apps/backend/app/services/rppg.py` | layered module relocation | edit | current merged predictor result | Strip worker-only underscore-prefixed timing fields before rPPG success persistence. | Implements FR-005, FR-009, NFR-002, and AC-006. | WS6 | preserve |
| CH-222 | test | `apps/backend/tests/test_inference_alert_sessions.py` | prediction shutdown tests | edit | current queue routing assertions | Prove stopping rejects new work and fails queued futures rather than hanging. | Proves AC-003 and AC-006. | WS6 | preserve |
| CH-223 | test | `apps/backend/tests/test_rppg_v25.py` | rPPG persistence tests | edit | current successful prediction assertions | Prove rPPG persistence removes worker-only timing fields from shared-adapter results. | Proves AC-006 and FR-009. | WS6 | preserve |
| CH-224 | production | `apps/backend/app/schemas/sensor.py` | layered module relocation | edit | current permissive timing fields | Enforce the documented 20-second duration and internal timestamp consistency before route execution. | Implements FR-011 and AC-009. | WS7 | preserve |
| CH-225 | production | `apps/backend/app/services/sensor.py` | layered module relocation | edit | current lookup/predict/persist flow | Serialize per-patient/window retries and classify invalid timestamps, including overflow, separately from consent failures. | Implements FR-011, NFR-002, and AC-009. | WS7 | preserve |
| CH-226 | production | `apps/backend/app/api/v1/routes/sensor.py` | layered module relocation | edit | current broad `ValueError` mapping and stale internal handler name | Map authorization to 403 and sensor payload errors to 422; rename the private handler without changing its route. | Implements FR-011. | WS7 | preserve |
| CH-227 | test | `apps/backend/tests/test_sensor_routes_v25.py` | sensor validation/concurrency tests | edit | sequential 10-second fixture | Use the current 20-second contract and prove invalid timing plus concurrent idempotency boundaries. | Proves AC-009. | WS7 | preserve |
| CH-228 | production | `apps/backend/app/services/dashboard.py` | layered module relocation | edit | current unconditional stored-summary status/decryption | Inject the typed flag and mask/decryption-skip disabled summaries on dashboard reads. | Implements FR-012 and AC-010. | WS8 | preserve |
| CH-229 | production | `apps/backend/app/core/runtime.py` | layered module relocation | edit | current partial cleanup and dashboard composition | Compose the dashboard flag and route partial initialization failures through common cleanup. | Implements FR-012, FR-013, AC-010, and AC-011. | WS8 | preserve |
| CH-230 | production | `apps/backend/app/main.py` | layered module relocation | edit | current post-setup `try/finally` | Establish cleanup before any startup action so prediction/rPPG/runtime resources close on setup failures. | Implements FR-013 and AC-011. | WS8 | preserve |
| CH-231 | production | `apps/backend/app/services/prediction.py` | layered module relocation | edit | current cancellable wrapper around a continuing `to_thread` call | Track and join the in-flight model thread boundary before stop returns or restart can load/use the model. | Implements FR-013 and AC-011. | WS8 | preserve |
| CH-232 | test | `apps/backend/tests/test_intervention_dashboard_v25.py` | state-summary dashboard tests | edit | current always-enabled fixture expectation | Prove disabled masking/decryption skip and enabled compatibility. | Proves AC-010. | WS8 | preserve |
| CH-233 | test | `apps/backend/tests/test_inference_alert_sessions.py` | prediction restart lifecycle tests | edit | current stop test releases the thread after stop | Prove stop waits for the thread boundary and restart never overlaps model execution. | Proves AC-011. | WS8 | preserve |
| CH-234 | test | `apps/backend/tests/test_rppg_runtime_v25.py` | partial runtime cleanup tests | edit | current successful initialization/shutdown coverage | Force startup failures and assert partial resources close exactly once. | Proves AC-011. | WS8 | preserve |
| CH-235 | test | `apps/backend/tests/test_api_structure.py` | runtime flag composition assertion | edit | session-only forwarding assertion | Prove the typed state-summary flag also reaches `DashboardService`. | Proves AC-010. | WS8 | preserve |
| CH-236 | production | `apps/backend/app/agents/bedrock.py` | layered module relocation | edit | production-unreferenced slot/handoff/repetition utilities | Remove unused pre-free-dialogue helpers while retaining the provider adapter and JSON parser used by active agents. | Implements FR-015. | WS9 | preserve |
| CH-237 | test | `apps/backend/tests/test_bedrock_agents.py` | active Bedrock adapter coverage | edit | tests for production-unreferenced helpers | Retain only provider/parser behavior used by active agents. | Proves D-017 and AC-003. | WS9 | preserve |
| CH-238 | production | `apps/backend/app/ml/craving/latency.py` | layered module relocation | edit | stale `app/main.py` generator ownership text | Point latency ownership to the active sensor route. | Implements FR-015. | WS9 | preserve |
| CH-239 | docs | `README.md` | active project overview | edit | current restructure/config/API summary | Refresh layered backend, default-off flags, current endpoints, and exact validation commands. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-240 | docs | `apps/backend/README.md` | backend architecture/runbook | edit | current mostly-correct layered guide | Add omitted layers, exact venv commands, current STT engine, default report status, and final validation evidence. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-241 | docs | `apps/mobile/SERVER_API_SPEC.md` | human API contract | edit | current generic OpenAPI-authority wording and stale response/consent/readiness details | Clarify authority, 20-second validation/idempotency, `not_started`, consent gates, response timing, and readiness/model separation. | Implements FR-011, FR-012, FR-014, and AC-012. | WS10 | preserve |
| CH-242 | docs | `apps/mobile/README.md` | mobile integration overview | edit | stale 10-second/default-report wording | Describe 20-second capture and unified confirmed-STT/text message flow with default-off summary/report behavior. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-243 | docs | `apps/mobile/docs/PHONE_APP.md` | phone behavior guide | edit | stale 10-second capture wording | Align camera/sensor timing and current server boundaries. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-244 | docs | `apps/db/README.md` | database/Compose runbook | edit | stale `0004` head and pending evidence | Document `20260717_0005`, the STT service, exact encrypted rPPG table, and evidence actually rerun. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-245 | docs | `docs/dev-environment.md` | development environment | edit | stale flags, limits, 10-second signal, and deferred-feature text | Align current environment flags, 40 MiB limit, head, free dialogue, 20-second/1,024 inputs, STT, and local TTS. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-246 | docs | `docs/ai/README.md` | active AI architecture | edit | deleted module paths and old prompt/default behavior | Point to layered owners and document active/default-off behavior. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-247 | docs | `docs/ai/README.ko.md` | Korean AI architecture mirror | edit | deleted module paths and old prompt/default behavior | Synchronize CH-246 in Korean. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-248 | docs | `docs/ai/agents/README.md` | active agent overview | edit | old deterministic/deferred boundaries | Describe current LLM-only dialogue safety and implemented feature-gated STT/rPPG boundaries. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-249 | docs | `docs/ai/agents/README.ko.md` | Korean agent overview mirror | edit | old deterministic/deferred boundaries | Synchronize CH-248 in Korean. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-250 | docs | `docs/ai/agents/03_handoff_agent.md` | report-agent behavior | edit | default-active automatic report wording | Lead with exact default-off 202/no-op/history behavior and preserve enabled behavior. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-251 | docs | `docs/ai/agents/03_handoff_agent.ko.md` | Korean report-agent mirror | edit | default-active automatic report wording | Synchronize CH-250 in Korean. | Implements FR-014 and AC-012. | WS10 | preserve |
| CH-252 | docs | `docs/deployment/DGX_STT_TTS_SETUP.ko.md` | DGX STT deployment paths | edit | deleted `app/v25` STT paths | Reference route/service/adapter owners in the layered backend. | Implements FR-014 and AC-012. | WS10 | preserve |

### Change Budget

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 2 | 0 | 0 | 0 | None | None |
| WS2 | 115 | 89 | 51 | 900 | None | User-approved layered module layout only |
| WS3 | 8 | 3 | 0 | 120 | None | None |
| WS4 | 4 | 0 | 0 | 0 | None | None |
| WS5 | 7 | 4 | 0 | 140 | None | None |
| WS6 | 5 | 3 | 0 | 100 | None | None |
| WS7 | 4 | 3 | 0 | 140 | None | None |
| WS8 | 8 | 4 | 0 | 180 | None | None |
| WS9 | 3 | 2 | 0 | 20 | None | None |
| WS10 | 14 | 0 | 0 | 0 | None | None |

The WS2 budget treats relocated target files as new paths and old/new rename sides as separate changed paths; review must distinguish content-preserving moves from genuinely new logic.

## 8. Work Plan

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | Create the reviewed bilingual implementation contract. | None | Serial | CH-001, CH-002 | `docs/specs/2026-07-19-neurotruth-api-ai-restructure-spec*.md` | All production/test files | FR-010, NFR-004, AC-008 | `specctl.py validate` | verified |
| WS2 | Relocate backend code into the approved layers, split current owners, update imports/tests, and remove old modules with behavior preserved. | WS1 | Serial | CH-100, CH-101, CH-102, CH-103, CH-104, CH-105, CH-106, CH-107, CH-108, CH-109, CH-110, CH-111, CH-112, CH-113, CH-114, CH-115, CH-116, CH-117, CH-118, CH-119, CH-120, CH-121, CH-122, CH-123, CH-124, CH-125, CH-126, CH-127, CH-128, CH-129, CH-130, CH-131, CH-132, CH-133, CH-134, CH-135, CH-136, CH-137, CH-138, CH-139, CH-140, CH-141, CH-142, CH-143, CH-144, CH-145, CH-146, CH-147, CH-148, CH-149, CH-150, CH-151, CH-152, CH-153, CH-154, CH-155, CH-156, CH-157, CH-158, CH-159, CH-160, CH-161, CH-162, CH-163, CH-164, CH-165, CH-166, CH-167, CH-168, CH-169, CH-170, CH-171, CH-172, CH-173, CH-174, CH-175, CH-176, CH-177, CH-178, CH-179, CH-180, CH-181, CH-182, CH-183, CH-184, CH-185, CH-186, CH-187, CH-188, CH-189, CH-190, CH-191, CH-192, CH-193, CH-194, CH-195, CH-196, CH-197, CH-198, CH-199, CH-200, CH-201, CH-202, CH-203, CH-204, CH-205, CH-206, CH-207, CH-208, CH-209, CH-210, CH-211 | `apps/backend/app/**`, `apps/backend/tests/**` | DB/Alembic, mobile/web production code, handoff docs | FR-001, FR-002, FR-003, FR-004, FR-005, FR-008, FR-009, NFR-001, NFR-002, NFR-003, NFR-004, AC-001, AC-002, AC-003, AC-004, AC-006, AC-008 | compileall; full backend pytest | verified |
| WS3 | Add default-off state-summary/report flags and exact quiet-skip contracts. | WS2 | Serial | CH-005, CH-013, CH-006, CH-007, CH-008 | `apps/backend/app/**`, `apps/backend/tests/**` | Public URLs, DB schema, intervention/STT/craving behavior | FR-006, FR-007, FR-008, NFR-001, NFR-002, NFR-003, AC-001, AC-002, AC-003, AC-004, AC-005, AC-006 | focused session/API tests; full backend pytest | verified |
| WS4 | Update active docs/config and run integration acceptance. | WS3 | Serial | CH-009, CH-010, CH-011, CH-012 | `.env.example`, `apps/db/docker-compose.yml`, `apps/backend/README.md`, `apps/mobile/SERVER_API_SPEC.md` | User handoff docs, mobile/web source, DB schema | FR-002, FR-006, FR-007, FR-010, NFR-004, AC-002, AC-007, AC-008 | spec validation; compose config; full backend pytest; status/smoke when available | verified |
| WS5 | Consolidate authenticated craving prediction on the planned queue/SSE owner and correct final validation docs. | WS4 | Serial | CH-212, CH-213, CH-214, CH-215, CH-216, CH-217, CH-218 | `apps/backend/app/services/prediction.py`, `apps/backend/app/services/sensor.py`, `apps/backend/app/main.py`, `apps/backend/app/api/v1/routes/sensor.py`, `apps/backend/tests/test_inference_alert_sessions.py`, `apps/backend/tests/test_sensor_routes_v25.py`, `apps/backend/README.md` | Public URLs/payloads, model math, PostgreSQL/Alembic, other docs/tests | FR-001, FR-002, FR-005, FR-010, NFR-001, NFR-004, AC-002, AC-003, AC-006, AC-008 | focused prediction/sensor tests; compileall; full pytest; Docker sensor/SSE smoke | verified |
| WS6 | Close shared prediction shutdown and rPPG serialization regressions found in correction review. | WS5 | Serial | CH-219, CH-220, CH-221, CH-222, CH-223 | `apps/backend/app/services/prediction.py`, `apps/backend/app/main.py`, `apps/backend/app/services/rppg.py`, `apps/backend/tests/test_inference_alert_sessions.py`, `apps/backend/tests/test_rppg_v25.py` | Public URLs/payloads, model math, PostgreSQL/Alembic, other files | FR-005, FR-009, NFR-001, NFR-002, AC-003, AC-006, AC-008 | focused prediction/rPPG tests; compileall; full pytest; Docker shutdown and sensor/SSE smoke | verified |
| WS7 | Correct the current 20-second sensor contract, error mapping, and concurrent retry idempotency. | WS6 | Serial | CH-224, CH-225, CH-226, CH-227 | `apps/backend/app/schemas/sensor.py`, `apps/backend/app/services/sensor.py`, `apps/backend/app/api/v1/routes/sensor.py`, `apps/backend/tests/test_sensor_routes_v25.py` | Model math, DB/Alembic, other routes/tests | FR-011, NFR-002, NFR-005, AC-009 | focused sensor route/service tests; compileall; full pytest | verified |
| WS8 | Close disabled-summary read leakage and partial-startup/in-flight-thread lifecycle gaps. | WS7 | Serial | CH-228, CH-229, CH-230, CH-231, CH-232, CH-233, CH-234, CH-235 | `apps/backend/app/services/dashboard.py`, `apps/backend/app/core/runtime.py`, `apps/backend/app/main.py`, `apps/backend/app/services/prediction.py`, `apps/backend/tests/test_intervention_dashboard_v25.py`, `apps/backend/tests/test_inference_alert_sessions.py`, `apps/backend/tests/test_rppg_runtime_v25.py`, `apps/backend/tests/test_api_structure.py` | Public routes/payloads, model math, DB/Alembic | FR-012, FR-013, NFR-002, NFR-005, AC-010, AC-011 | focused dashboard/runtime/prediction tests; compileall; full pytest | verified |
| WS9 | Remove audited dead Bedrock helpers and stale ownership text without a broad compatibility rename. | WS8 | Serial | CH-236, CH-237, CH-238 | `apps/backend/app/agents/bedrock.py`, `apps/backend/app/ml/craving/latency.py`, `apps/backend/tests/test_bedrock_agents.py` | Provider adapter/parser, prompts, public compatibility names, historical specs | FR-015, NFR-005, AC-003 | focused agent tests; dead-symbol import scan; compileall; full pytest | verified |
| WS10 | Refresh every mapped active API/architecture/AI/DB/mobile/deployment Markdown file against verified runtime behavior. | WS9 | Serial | CH-239, CH-240, CH-241, CH-242, CH-243, CH-244, CH-245, CH-246, CH-247, CH-248, CH-249, CH-250, CH-251, CH-252 | `README.md`, `apps/backend/README.md`, `apps/mobile/README.md`, `apps/mobile/SERVER_API_SPEC.md`, `apps/mobile/docs/PHONE_APP.md`, `apps/db/README.md`, `docs/dev-environment.md`, `docs/ai/README.md`, `docs/ai/README.ko.md`, `docs/ai/agents/README.md`, `docs/ai/agents/README.ko.md`, `docs/ai/agents/03_handoff_agent.md`, `docs/ai/agents/03_handoff_agent.ko.md`, `docs/deployment/DGX_STT_TTS_SETUP.ko.md` | Production code, historical specs/handoff artifacts, DB/Alembic | FR-011, FR-012, FR-014, NFR-004, AC-012 | stale-path/default scan; 42-operation parity; Markdown diff review; full validation evidence update | verified |

### Parallelization Rationale

All slices remain serial because sensor contracts feed lifecycle tests, documentation must describe the final corrected behavior, and the dirty worktree already contains the user's restructure and unrelated handoff changes. This avoids workers modifying unstable interfaces or active docs concurrently.

### Final Integration

Run strict spec validation, import/path scans, Python compile, full backend pytest, OpenAPI path comparison, Compose config, and available Docker/mobile contract checks. Review the final diff for unrelated changes and excessive rewritten logic.

## 9. Validation, Rollout, and Risk

### Validation Plan

- `& '.\\.venv\\Scripts\\python.exe' -m compileall app tests`
- `& '.\\.venv\\Scripts\\python.exe' -m pytest -q --basetemp=.pytest-restructure`
- `rg -n "app\\.(v25|inference|settings|security|ai)" apps/backend/app apps/backend/tests`
- Compare pre/post OpenAPI path and method sets; validate response examples for message, STT, prediction, state, and reports.
- Run focused STT, craving, rPPG, sessions, auth/security, repository/schema, dashboard, and SSE tests.
- Run `docker compose config`; when Docker is available rebuild and smoke the listed status and authenticated flows.
- Run the existing mobile session API contract test when the Android toolchain is available.
- Exercise invalid and concurrent sensor windows, disabled-summary dashboard reads, partial-startup cleanup, and stop/restart during a blocked model call.
- Scan only active Markdown (not historical specs) for removed module paths, stale migration head/defaults, and current 10-second signal claims.

### Minimality and Style-Fidelity Review

- Classify every changed production line as a move, import/composition update, schema extraction, approved flag branch, or required testability adjustment.
- Reject compatibility shims, generic registries, new HTTP boundaries, dependencies, business rewrites, or unrelated formatting.
- Confirm the final tree has one owner for every moved responsibility and no duplicate old module.

### Rollout and Rollback

No data migration. Deploy the rebuilt backend/STT images with the two flags defaulting false. Roll back by reverting the source/config change; historical DB rows remain compatible. Enabling a flag restores its preserved provider-backed behavior.

### Risks and Mitigations

| Risk | Impact | Mitigation or Evidence |
| --- | --- | --- |
| Import omissions during large move | Startup/test collection failure | Full compile, import scan, and complete pytest after each structural slice. |
| OpenAPI drift from schema extraction | Mobile/web breakage | Preserve decorators/aliases and compare exact path/method plus representative payloads. |
| Disabled report changes terminal flow | Unexpected pending report UI | Exact 202/not_started and empty GET tests; no DB job. |
| Model behavior changes while splitting | Prediction regression | Move math unchanged and retain binary model/sensor/SSE tests. |
| Unrelated dirty files overwritten | User work loss | Exclude handoff paths and inspect scoped diff before acceptance. |
| Stricter sensor validation rejects obsolete clients | Upload rejected with 422 | Contract, current Android code, and model metadata already use 20 seconds; retain explicit 19.5–20.5-second tolerance and test it. |
| Waiting for a synchronous model call delays shutdown | Slower controlled shutdown | Stop accepting work immediately, fail request futures, join only the existing CPU/GPU inference boundary, and prove no restart overlap. |
| Documentation overstates generated OpenAPI | False client confidence | State that OpenAPI owns routes/methods/request schemas and this human contract owns detailed responses until response-model expansion is separately reviewed. |

## 10. Revision and Progress

### Design Revision History

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-19T00:00:00+09:00 | user-authorized-plan | Created the complete reviewed implementation contract from the user's final plan and prior answered decisions. | D-001, D-002, D-003, D-004, D-005, D-006, D-007 | Q-001, Q-002, Q-003, Q-004 |
| 2 | 2026-07-19T00:10:00+09:00 | repository-budget-evidence | Corrected WS2 changed/production/new-path budgets to literal Git path counts required by the exact approved target tree; no behavior or direction changed. | D-008 | None |
| 3 | 2026-07-19T00:15:00+09:00 | repository-budget-evidence | Corrected WS2 production-added-line budget from net delta to 872 literal additions caused by the approved file splits; no behavior or direction changed. | D-009 | None |
| 4 | 2026-07-19T00:20:00+09:00 | exact-scope-evidence | Expanded aggregate WS2 rows into exact old/new/test paths required by scope validation; no behavior or direction changed. | D-008, D-009 | None |
| 5 | 2026-07-19T00:25:00+09:00 | readonly-review-finding | Added the missing tracked latency artifact relocation and adjusted literal production/new-path budgets. | D-010 | None |
| 6 | 2026-07-19T00:30:00+09:00 | repository-composition-evidence | Added `core/runtime.py` to WS3 so typed feature flags flow through the existing composition root instead of being read inside the service. | D-011 | None |
| 7 | 2026-07-19T00:50:00+09:00 | final-readonly-review | Added a bounded correction slice to connect authenticated sensor inference to the planned process-wide prediction queue/SSE owner and correct stale final validation wording. | D-012 | None |
| 8 | 2026-07-19T01:10:00+09:00 | correction-readonly-review | Added a bounded lifecycle/serialization correction after review found stranded prediction futures during shutdown and worker-only timing fields in rPPG persistence. | D-013 | None |
| 9 | 2026-07-19T01:35:00+09:00 | user-requested-post-refactor-audit | Reopened the completed plan for bounded sensor, state-read, startup/thread lifecycle, dead-code, API-contract, and active-documentation corrections after a fresh compile/test/OpenAPI/read-only audit. | D-014, D-015, D-016, D-017 | None |

### Implementation Progress Record

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-19T00:00:00+09:00 | 1 | WS1 | verified | User supplied the reviewed plan and explicitly requested implementation; bilingual pair created before source edits. |
| 2026-07-19T00:05:00+09:00 | 1 | WS2 | in_progress | Structural relocation worker assigned after strict spec validation and ready-slice check passed. |
| 2026-07-19T00:10:00+09:00 | 2 | WS2 | in_progress | Repository evidence corrected the move-oriented path budget to 115/87/50; implementation direction and scope remain unchanged. |
| 2026-07-19T00:15:00+09:00 | 3 | WS2 | in_progress | Main review corrected production additions to 872/900 using a temporary Git index and rename-aware numstat. |
| 2026-07-19T00:20:00+09:00 | 4 | WS2 | in_progress | Exact Git status paths synchronized into the Modification Map before repeating scope and patch checks. |
| 2026-07-19T00:25:00+09:00 | 5 | WS2 | in_progress | Main and read-only review agreed on one P2 ownership correction for `inference_time.txt`. |
| 2026-07-19T00:29:00+09:00 | 5 | WS2 | verified | Content-preserving latency artifact relocation completed; compile, 153 passed/1 skipped, OpenAPI 42/no-v1, legacy scan, scope, and patch checks passed independently. |
| 2026-07-19T00:30:00+09:00 | 6 | WS3 | in_progress | Runtime composition target synchronized before assigning the default-off AI worker. |
| 2026-07-19T00:40:00+09:00 | 6 | WS3 | verified | Focused 7 passed, full 160 passed/1 skipped, scope/patch checks passed, and read-only review found no actionable issues. |
| 2026-07-19T00:41:00+09:00 | 6 | WS4 | in_progress | Documentation and Compose configuration slice opened after WS3 verification. |
| 2026-07-19T00:48:00+09:00 | 6 | WS4 | verified | Four-file scope/patch checks, Compose config, Docker rebuild/live authenticated smoke, and mobile session contract test passed. |
| 2026-07-19T00:50:00+09:00 | 7 | WS5 | in_progress | Final read-only review found the authenticated model call bypassed the planned prediction queue/SSE owner; correction slice opened before acceptance. |
| 2026-07-19T01:05:00+09:00 | 7 | WS5 | verified | Queue/SSE ownership, patient isolation, public timing-key filtering, README evidence, 16 focused tests, compileall, 161 passed/1 skipped, and WS5 scope/patch checks passed. |
| 2026-07-19T01:10:00+09:00 | 8 | WS6 | in_progress | Correction review found shutdown-future and rPPG timing-key persistence regressions; bounded lifecycle/serialization slice opened before Docker acceptance. |
| 2026-07-19T01:20:00+09:00 | 8 | WS6 | verified | Scope/patch checks, read-only re-review, 12 focused tests, compileall, 162 passed/1 skipped, Compose, rebuilt Docker readiness, authenticated sensor/SSE identity and filtering, and graceful restart passed. |
| 2026-07-19T01:35:00+09:00 | 9 | WS7 | in_progress | User requested correctness/cleanliness/API-doc refresh; fresh baseline remained 162 passed/1 skipped with exact 42-operation parity, and read-only audits identified bounded uncovered edge cases. |
| 2026-07-19T01:48:00+09:00 | 9 | WS7 | verified | Scope/patch budgets passed; focused 13 tests and compileall passed; independent review found and then cleared non-finite-value and pre-storage-overflow P2 edges. |
| 2026-07-19T01:49:00+09:00 | 9 | WS8 | in_progress | Sensor contract/idempotency corrections verified; lifecycle and disabled-summary masking slice opened. |
| 2026-07-19T02:02:00+09:00 | 9 | WS8 | verified | Scope/patch budgets, focused 17 tests, full 168 passed/1 skipped, compileall, and cancellation correction re-review passed after shared shielded stop/runtime cleanup tasks closed two P1/P2 findings. |
| 2026-07-19T02:03:00+09:00 | 9 | WS9 | in_progress | Lifecycle/state masking verified; bounded dead-helper and stale ownership-text cleanup opened. |
| 2026-07-19T02:10:00+09:00 | 9 | WS9 | verified | Scope/patch budgets, dead-symbol scan, focused 22-test review, compileall, and full 159 passed/1 skipped completed with no review findings; 302 dead production lines were removed. |
| 2026-07-19T02:11:00+09:00 | 9 | WS10 | in_progress | Code correctness/cleanup slices verified; active API and Markdown refresh opened. |
| 2026-07-19T02:50:00+09:00 | 9 | WS10 | verified | Fourteen-file scope/patch check, stale scan, 42/42 OpenAPI parity, independent review/correction, and diff check passed; rebuilt Docker returned health/ready/model success at head `0005` and a graceful restart preserved readiness. |
| 2026-07-19T02:51:00+09:00 | 9 | integration | verified | Final compileall and backend `159 passed, 1 skipped` passed; Compose config, rebuilt service status, live STT-auth 401 contract, spec validation, forbidden-import scan, and temporary-artifact cleanup passed. Android contract tests were attempted but could not start because the local Android SDK path is not configured. |
