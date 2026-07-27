# NeuroTruth VP-012 Four-Month Demo — Living Implementation Specification

<!-- feature-planner-control
{
  "workflow": "feature-planner/v7",
  "state": "complete",
  "source_spec": "docs/specs/2026-07-26-neurotruth-vp012-four-month-demo-spec.md",
  "korean_mirror": "docs/specs/2026-07-26-neurotruth-vp012-four-month-demo-spec.ko.md",
  "spec_revision": 9,
  "reviewed_revision": 9,
  "selected_strategy": "STRAT-2",
  "implementation_direction": "preserve",
  "direction_decision_id": null,
  "minimal_change_policy": "strict",
  "final_domain_gate": "confirmed_none",
  "open_question_ids": [],
  "active_slices": [],
  "next_action": "none"
}
-->

> The English file is authoritative. The Korean file is the synchronized review mirror.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `complete`, revision 9, implementation and verification complete |
| Outcome | A provisioned VP-012 account creates a fresh 120-day scenario on successful login and is fully removed when that account uses the existing app logout action |
| Recommended implementation | `STRAT-2` — extend the existing seeder and auth lifecycle; no demo API, migration, scheduler, or mobile-only data store |
| Planned production targets | `apps/backend/app/maintenance/seed_vp012_demo.py` — `Vp012DemoSeeder`, `run_cli`; `apps/backend/app/services/auth.py` — `AuthService.login`, `AuthService.logout`; `apps/backend/app/api/v1/routes/auth.py` — `_raise_service_error`, `login`, `logout`; `apps/backend/app/schemas/auth.py` — `LoginInput.email`; `apps/backend/app/core/runtime.py` — `initialize_runtime`; `apps/backend/app/core/config.py` — `SecuritySettings`; `apps/backend/app/repositories/postgres.py` — `refresh-token owner lookup / transaction boundary`; `apps/mobile/core/src/main/kotlin/com/neurotruth/mobile/core/net/AuthenticatedApiClient.kt` — `logout`; `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/settings/SettingsViewModel.kt` — `logout`, `tearDownAndSignOut` |
| Expected additions | New production files: `apps/backend/app/maintenance/seed_vp012_demo.py`; dependencies: None; shared abstractions: None |
| Work plan | WS1, WS2, WS3, and WS4 verified |
| Open questions | None |
| Agent decisions to review | None |
| Last material change | Revision 9 — verified retry-safe mobile logout and the complete repeated-take lifecycle |

## 2. Outcome and Scope

### Outcome

Demo operators can provision one clearly fictional VP-012 login without historical rows. The first successful password login while the account is armed creates 120 days of temporally coherent Watch craving predictions, bounded alert events, AUQ assessments, free-dialogue sessions, encrypted messages, state summaries, and reports relative to that login time. Existing app logout is the explicit demo-end action and removes the complete demo account and its data. The operator can provision it again for another take. A Korean production pack gives a 90–120 second shot list, narration, captions, app actions, and disclosure copy so real Phone/Watch footage can be recorded without presenting dummy data as research evidence.

### In Scope

- A `--dry-run`, confirmed provision, confirmed seed fallback, status, and confirmed delete CLI.
- Login-triggered seed creation guarded by an explicit disabled-by-default setting, the reserved demo identity, and an armed audit state.
- Logout-triggered full account deletion only for the reserved armed/started demo identity.
- Fixed seed and stable UUIDs so reruns are idempotent.
- 120 calendar days ending at an explicit `--anchor-date` or today in `Asia/Seoul`.
- VP-012 restaurant-work stress, evening, and weekend variability without a monotonic treatment-effect curve.
- A 360-point, 10-second recent-hour trace copied from the existing
  `Alcohol_Test/1_1_010_V1` demo scenario with value order preserved.
- Dashboard-compatible prediction, alert, AUQ, session, message, intervention, state-inference, and report rows.
- AES-256-GCM encryption for every sensitive seeded field using the deployed keyring and exact AAD.
- One active demo session near the anchor date may be excluded; all seeded historical sessions are completed so the user can still start a live demo session.
- A Korean storyboard/runbook with optional short rPPG coverage and a Watch-first primary flow.

### Out of Scope / Non-Goals

- No continuous four-month raw PPG/EDA files, fabricated face video, STT audio, or rPPG waveform.
- No calls to Bedrock, STT, FactorizePhys, or the craving model during seeding.
- No schema migration, production dependency, model setting change, or alteration of alert runtime policy.
- No automatic SSH deployment, Git push, or destructive operation against non-demo users.
- No app force-close, process death, access-token expiry, refresh, or network disconnect interpreted as demo completion.
- No claim that CBT, dialogue, or app use caused craving reduction, diagnosis, recovery, or treatment success.
- No synthetic footage represented as a real model inference; the final video uses actual app/device screens after seeding.

### Users and Primary Flow

1. An operator enables `DEMO_SCENARIO_ENABLED`, sets `DEMO_PATIENT_PASSWORD`, and runs dry-run plus the exact provision command inside the backend container.
2. Provision creates only the stable fictional login, encrypted `"정우식"` profile, consent, and an armed audit record; it creates no historical scenario rows.
3. Successful password login for the exact reserved account atomically creates the 120-day scenario relative to the current `Asia/Seoul` login time before issuing tokens.
4. The operator records Watch measurement, alert, AUQ, dialogue, rPPG, and dashboard shots using the runbook.
5. The user taps the existing app logout action. The backend deletes the complete demo account and associated data before returning logout success; a deletion failure returns a retryable error and keeps the account available for another logout attempt.
6. The operator runs the provision command again before the next take. The confirmed delete CLI remains an operator recovery path.

### Current Assumptions and Constraints

- The persona is the fictional NeuroSync `VP-012` and is adapted only for alcohol-craving demo behavior.
- Existing uncommitted mobile and spec changes are unrelated and must be preserved.
- The active binary `craving_model` row must exist before seeding; the CLI does not change active model ownership.
- The password is read from `DEMO_PATIENT_PASSWORD`, never accepted as a command argument or stored in documentation.
- `DEMO_SCENARIO_ENABLED` defaults to `false`; ordinary users and the demo identity while disabled retain normal authentication behavior.
- The reserved profile display name is `"정우식"` without a demo suffix; fictional/demo disclosure remains in seeded metadata and the production runbook.
- The recent-hour trace is demo-only Alcohol_Test model output, not a VP-012
  participant measurement. Provenance is retained in each recent prediction.

## 3. Repository Pattern Baseline

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Maintenance safety | Destructive or transforming CLIs provide dry-run, exact confirmation, transaction, advisory lock, sanitized output, and audit | `apps/backend/app/maintenance/migrate_auq_zero_based.py::AuqZeroBasedMigrator` | Use the same CLI and transaction style |
| Encryption | Sensitive values use `AesGcmKeyring` and `aad_for(table,column,patient,record)` | `apps/backend/app/core/security/crypto.py`; `apps/backend/app/services/session.py::_encrypt_json` | No plaintext fallback and unique nonces |
| Dashboard source | Dashboard aggregates predictions, alerts, AUQ, sessions, interventions, inference and report rows | `apps/backend/app/repositories/postgres.py::craving_dashboard_rows`; `craving_calendar_rows` | Seed existing tables rather than add demo-only API/schema |
| Authentication | Patient passwords use Argon2id and profiles/consents use current V2.5 contracts | `apps/backend/app/core/security/passwords.py::hash_password`; `SqlAlchemyV25Repository.create_patient` | Produce a normal login-capable patient account |
| Persona | VP-012 is a fictional, SI-negative alcohol-use persona with restaurant/evening stress | `../neurosync/docs/ai/personas/VP-012_first_visit_alcohol.md` | Keep it fictional and non-diagnostic in video copy |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | Maintenance CLI transaction/confirmation pattern | `apps/backend/app/maintenance/migrate_auq_zero_based.py` | Mirror entrypoint, environment parsing, advisory lock, and report output |
| R-002 | AES-GCM keyring and AAD helpers | `apps/backend/app/core/security/crypto.py` | Encrypt name, AUQ answers, session state, messages, interventions, inference, and reports |
| R-003 | Argon2id password helper | `apps/backend/app/core/security/passwords.py::hash_password` | Hash the operator-supplied demo password |
| R-004 | Existing dashboard tables and aggregation | `apps/backend/app/repositories/postgres.py` | Insert only records already consumed by current APIs |
| R-005 | VP-012 persona facts and dialogue style | `../neurosync/docs/ai/personas/VP-012_first_visit_alcohol.md` | Drive deterministic scenario language and temporal variability |
| R-006 | Sustained-rebound recent-hour model trace | `../Alcohol_Test/demo_scenarios/1_1_010_V1.json`; `../Alcohol_Test/docs/ai/personas/NT-DP-002_1_1_010_V1_rebound.ko.md` | Preserve the existing 360-point value order and 60-minute normalization |

## 4. Decisions and Questions

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | Persona | Use fictional VP-012 as the single demo user. | user | The user accepted the recommended VP-012 default. | Alcohol-craving content aligns with NeuroTruth. | confirmed | resolved |
| D-002 | Duration | Generate 120 days ending on a configurable anchor date. | user | The user accepted the proposed four-month bundle. | Day/week/month dashboards have stable historical coverage. | confirmed | resolved |
| D-003 | Narrative | Use variable restaurant/evening/weekend patterns and avoid monotonic improvement. | user | The accepted proposal explicitly excluded immediate treatment-effect claims. | The video remains descriptive rather than causal. | confirmed | resolved |
| D-004 | Video | Use a 90–120 second Watch-first device-recording script with a short optional rPPG shot. | user | The user accepted the recommended video bundle. | Production pack prioritizes the core product flow. | confirmed | resolved |
| D-005 | Safety | Seed only a stable fictional account and require exact confirmation for create/delete. | repository | R-001 and existing audit/security conventions. | Research records and other users remain untouched. | not-required | resolved |
| D-006 | Raw signals | Do not fabricate four months of raw sensor files; create dashboard rows only. | user | The accepted proposal limited raw signals to representative live footage. | Smaller dataset and no misleading physiological data. | confirmed | resolved |
| D-007 | Change budget | Raise the WS1 production-line expansion alarm from 650 to 850 without changing files, behavior, dependencies, abstractions, or direction. | repository | The clean CH-001 implementation measured 778 lines because nine existing-schema record groups and exact per-field AES-GCM AAD remain explicit. | Avoids readability/security compression while preserving the reviewed design. | not-required | resolved |
| D-008 | Profile | Display the fictional account name as `"정우식"` without a visible demo suffix. | user | The user explicitly removed the “가상 데모” suffix. | App profile looks natural; seeded metadata and runbook retain fictional disclosure. | confirmed | resolved |
| D-009 | Live alert isolation | Keep the final three recent-hour seeded predictions below danger. | user | A live Watch take must establish its own three consecutive danger results. | Historical seed cannot immediately trigger or contaminate the live danger streak. | confirmed | resolved |
| D-010 | Demo start | Start the 120-day scenario on successful password login of a pre-provisioned, armed reserved demo account. | user | The user wants the scenario clock to start when connecting with the demo account. | Historical timestamps stay aligned with the actual recording start. | confirmed | resolved |
| D-011 | Repeat takes | Remove the complete demo account and scenario after each completed take, then require reprovisioning. | user | Failed takes must be repeatable without accumulating previous data. | Every take begins from a clean identity and dataset. | confirmed | resolved |
| D-012 | Demo end | Treat the existing app logout action as the exact demo-end boundary. Delete first and return logout success only after the full demo deletion commits; deletion failure is retryable. | user | The user answered “로그아웃 버튼으로 보고 싶다.” | No new mobile button or API route; force-close and disconnect never delete data. | confirmed | resolved |
| D-013 | Error mapping | Extend the existing auth route error mapper for sanitized retryable demo lifecycle `503` responses. | repository | `routes/auth.py` currently maps only authentication/authorization/conflict errors and logout has no service-error mapping. | Keeps existing endpoints while meeting FR-011 and FR-013; no route is added. | not-required | resolved |
| D-014 | Login validation | Use the valid, ordinary-looking fictional login `woosik.jeong@neurotruth.kr` and keep `LoginInput.email` as strict `EmailStr`. | user | The reserved `.invalid` login looked visibly like a demo account in the recorded app. | Keeps normal email validation while presenting the fictional profile consistently in the demo. | not-required | resolved |
| D-015 | Mobile logout | Clear local credentials and per-user caches only after backend logout succeeds; on transport or non-2xx failure retain the session and show a retryable error. | repository | Current `AuthenticatedApiClient.logout` clears in `finally` and `SettingsViewModel` ignores the result, contradicting D-012 retry semantics. | The existing logout button becomes a reliable demo-end boundary without a new screen or API. | not-required | resolved |
| D-016 | Recent-hour source | Replace the artificial seven-episode waveform with the existing `Alcohol_Test/1_1_010_V1` 360-point trace; use MA10 where present and source softmax during MA10 warm-up. | user | VP-012 has no numeric craving series, and the user directed the implementation to the Alcohol_Test data when absent. | The chart now follows the established sustained-rebound demo curve while retaining explicit demo-only provenance. | confirmed | resolved |
| D-017 | Live danger take | When the demo flag is enabled, overlay only the reserved patient's first five Watch uploads after a fresh login with `0.38, 0.62, 0.82, 0.86, 0.89`; persist and evaluate them through the production alert transaction. | user | Historical seed rows are not replayed through SSE, so waiting for the real model cannot guarantee a recordable danger-to-chat transition. | The third danger result creates one normal Phone/Watch alert at roughly 60 seconds without adding a public demo endpoint or changing non-demo predictions. | confirmed | resolved |
| D-018 | Historical dashboard consistency | Represent only deterministic morning, midday, and evening Watch-worn periods with weighted 15-minute rows. Leave all other hours unmeasured, distribute 59 historical event days across 120 days, and back every event with a real three-danger sequence. | user | The user rejected a calendar that looked continuously measured for all 24 hours and asked twice for more visible event history. | A measured hour still totals 360 ten-second samples, but daily totals remain below the 8,640 maximum and unmeasured hours stay empty. | superseded | D-020 |
| D-019 | Login-day visibility | At successful demo login, create elapsed current-day Watch-worn periods before the recent-hour trace plus one current-day event, event-linked AUQ, completed dialogue session, inference, and report; use event-linked AUQ scores in the visible 39..41 range of the valid 0..48 scale. | user | The current day lacked a visible stacked history and the event/AUQ histories were still too sparse for recording. | The day stacked dashboard, event, and AUQ views are populated immediately at the login-relative current time without creating future rows or changing clinical interpretation. | superseded | D-020 |
| D-020 | Event density, AUQ linkage, sleep wear, and stable-stage balance | Replace the one-event-per-event-day pattern with deterministic pseudo-random 2–10 events per completed day, enforce the production 15-minute cooldown and ten-event daily cap, and link one AUQ/session/report to every event. Add six overnight Watch-worn days across 120 days without overnight synthetic alerts and rebalance non-event daytime baselines so `low`/안정 remains visibly represented. | user | One event per day did not reflect a policy that can notify again after 15 minutes; the user also requested matching AUQ history, rare sleep wear, and more stable-stage data. | Calendar event/AUQ bars become varied but bounded, no day exceeds ten events, the seed remains reproducible, sleep wear remains exceptional, and the stable stage occupies 20–30% of measured display weight in automated validation. | confirmed | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | Demo defaults | Confirm VP-012, 120 days, 90–120 seconds, Watch-first flow, and short optional rPPG. | Determines persona, data horizon, and recording scope. | Use the proposed bundle. | D-001 | answered | User answered “엉 부탁할게,” accepting D-001, D-002, and D-004. |
| Q-002 | Demo end boundary | Decide whether demo completion means app logout, an operator CLI, or a new explicit end action. | Whole-account deletion is destructive and must have one unambiguous trigger. | Use existing app logout; never use force-close or timeout. | D-012 | answered | User selected the logout button. |

## 5. Requirements and Acceptance Criteria

### Functional Requirements

- **FR-001:** The CLI shall support `--dry-run`, `--confirm SEED-VP012-120D-DEMO`, `--status`, and `--delete-confirm DELETE-VP012-DEMO`.
- **FR-002:** The seed shall create one stable login-capable fictional patient and current consent snapshot without changing any non-demo account.
- **FR-003:** The seed shall create 120 days of deterministic, non-future,
  dashboard-compatible prediction rows with four-stage variability. The recent
  hour shall contain the exact 360-value `Alcohol_Test/1_1_010_V1` trace at
  10-second spacing with source provenance and no interpolation or invented
  stage episodes. Historical prediction rows shall exist only in explicit
  Watch-worn periods and shall leave unmeasured hours empty.
- **FR-004:** Alerts shall be tied to danger predictions, at least 15 minutes apart, and labeled as demo records. Every completed historical day shall contain a deterministic pseudo-random 2–10 alerts and no day may exceed 10; login-day rows shall never extend into the future.
- **FR-005:** AUQ rows shall use version `2.0`, eight `0..6` responses, and total `0..48`. Every seeded event shall have one linked AUQ so the login day and calendar views are populated.
- **FR-006:** Completed free-dialogue sessions shall contain encrypted user/assistant turns, optional delivered interventions, state inference, and ready report metadata with persona-consistent non-diagnostic text.
- **FR-007:** Re-running the confirmed seed shall produce the same account and record identities without duplication.
- **FR-008:** Confirmed deletion shall remove only the stable demo user and cascading demo records, then add a system audit event without affecting other users.
- **FR-009:** The production pack shall specify timed shots, on-device actions, Korean narration/captions, persona responses, data disclosures, rPPG inclusion, and final export checklist.
- **FR-010:** `--provision-confirm PROVISION-VP012-DEMO` shall create only the reserved login, encrypted `"정우식"` profile, consent snapshot, and armed audit state; it shall create no historical scenario rows.
- **FR-011:** When `DEMO_SCENARIO_ENABLED=true`, successful password login for the exact reserved armed identity shall seed the deterministic 120-day scenario relative to the current Seoul login time in one advisory-locked transaction before tokens are issued.
- **FR-012:** Refresh, repeated login after the armed state is consumed, ordinary users, and all authentication while `DEMO_SCENARIO_ENABLED=false` shall not seed or delete demo data.
- **FR-013:** Existing app logout for the started reserved demo account shall atomically remove auth sessions, profile, consents, predictions, alerts, AUQ, sessions, messages, interventions, state inferences, reports, sensor recordings/files, and rPPG captures/jobs before returning success; only a sanitized system audit record may remain.
- **FR-014:** A new take shall require the operator to provision the reserved account again; no automatic background resurrection or scheduled seed is allowed.
- **FR-015:** Mobile logout shall retain credentials, active-session state, caches, and signed-in UI when backend logout fails; it shall show a retryable error and perform local teardown only after a successful backend response.

### Non-Functional Requirements

- **NFR-001:** Preserve the existing architecture and add no schema migration, dependency, shared abstraction, or runtime behavior change.
- **NFR-002:** Seed execution shall be one PostgreSQL transaction under an advisory lock; any validation or write failure shall roll back the whole operation.
- **NFR-003:** Sensitive content shall be AES-256-GCM encrypted with the current key and exact AAD; logs and reports shall not expose the password or encryption keys.
- **NFR-004:** All randomness shall derive from a fixed documented seed and all record IDs from a stable namespace.
- **NFR-005:** User-facing demo language shall not claim diagnosis, clinical severity, treatment efficacy, immediate reduction, or causal improvement.
- **NFR-006:** Preserve every unrelated dirty-worktree change.
- **NFR-007:** Add no migration, public demo API, mobile-only branch, scheduler, dependency, or shared abstraction.
- **NFR-008:** Demo lifecycle behavior shall default disabled and require exact reserved user ID/email plus the expected audit state.

### Acceptance Criteria

- **AC-001:** Network-free tests prove deterministic counts/IDs, four-month date bounds, no future timestamps, AUQ validity, alert spacing, and non-monotonic stage distribution.
- **AC-002:** Tests prove encryption round trips with correct AAD and rejection with wrong AAD.
- **AC-003:** Tests prove dry-run has no writes, wrong confirmations do not open a DB, seed rerun is idempotent, and delete targets only the stable demo user.
- **AC-004:** Status output reports patient ID, date range, and table counts without sensitive plaintext.
- **AC-005:** The Korean production pack can be followed from login through Watch, alert, AUQ, dialogue, dashboard, optional rPPG, and export without inventing a missing app screen.
- **AC-006:** Focused backend tests pass, the spec pair validates, and the implementation diff contains no unrelated mobile edits.
- **AC-007:** Provision tests prove the account can authenticate but has zero historical demo rows and one armed audit state.
- **AC-008:** Login tests prove scenario creation commits before token issuance, uses login-relative timestamps, runs once per provision, and rejects login without issuing tokens when seeding fails.
- **AC-009:** Logout tests prove complete demo-account deletion, retained sanitized system audit, rollback on any deletion failure, and retry success without affecting an ordinary user.
- **AC-010:** An end-to-end test proves provision → login seed → logout delete → reprovision → second login seed is repeatable without stale rows.
- **AC-011:** Mobile core tests prove non-2xx/transport logout retains the refresh token and user, while 204 clears them; Android compilation proves Settings logout only enters signed-out state after success.

### Edge and Failure Cases

- Missing or weak `DEMO_PATIENT_PASSWORD` → fail before opening the database.
- Missing active binary craving model → rollback with `demo_model_unavailable`.
- Existing non-demo user with the reserved email or stable ID → rollback with `demo_identity_conflict`.
- Existing demo dataset with a different anchor/seed contract → require delete then seed; do not partially reconcile incompatible data.
- Anchor date after the current Seoul date → reject.
- Encryption, constraint, or transaction failure → rollback all seeded rows.
- Demo login seed failure → return sanitized `503 demo_seed_failed`, issue no access/refresh token, and leave the provisioned account armed for retry.
- Demo logout deletion failure → return a retryable error, do not report logout success, and roll back the full deletion so the user can press logout again.
- App force-close, process death, network loss, access-token expiry, or refresh → leave the demo account and scenario unchanged.
- Repeated logout/delete after a committed deletion → return the existing unauthenticated/idempotent result without touching any other account.
- Mobile receives demo logout `503` or a transport failure → keep the current session and show an error so the same logout button can retry.

## 6. Implementation Strategy and Direction

### STRAT-2 — Armed Account Auth-Lifecycle Demo Seed

- **Direction:** `preserve`
- **Current approach:** Extend the existing maintenance seeder with an identity-only provision command and reusable transaction methods. Wire an optional auth lifecycle hook through existing runtime construction. A successful armed demo login seeds before token issuance; successful demo logout deletes the complete account before returning. Stable UUID5 identities, audit states, exact reserved identity checks, and one advisory lock make retries deterministic and isolated.
- **Existing flow to reuse:** R-001 through R-005.
- **Why this is minimal:** Current dashboards and login/logout endpoints already provide the required boundaries. No new API route, mobile action, migration, scheduler, replay service, or continuous raw-signal generator is needed.
- **Behavior-preserving limitations:** Historical raw PPG/EDA previews are not synthesized. Live Watch and rPPG shots are recorded from the actual devices and services.
- **Explicit exclusions:** Mobile UI edits, runtime alert changes, model calls, provider calls, schema edits, Compose topology edits, and unrelated documentation cleanup.
- **Compatibility and migration posture:** Disabled by default. Rollback sets `DEMO_SCENARIO_ENABLED=false`; the confirmed delete CLI removes any remaining demo account, and prior images restore the original auth lifecycle.
- **Direction approval:** None.
- **Open-question sensitivity:** None.

### Material Alternatives Considered

| Strategy | Direction | Benefit | Additional code or risk | Decision |
| --- | --- | --- | --- | --- |
| Seed continuous raw 20-second sensor files for 120 days | preserve | Historical PPG preview could be shown | Millions of samples, encrypted storage cost, misleading synthetic physiology | rejected |
| Add a demo replay API/service or explicit demo-end button | user-approved-divergence | Fully automated live-looking demo | New public/runtime contract and mobile change, risk of shipping demo behavior | rejected |
| Direct one-off SQL script | preserve | Short initial implementation | Cannot safely encrypt content, validate AAD, hash password, or test behavior | rejected |
| Delete/reseed on every login | preserve | No provisioning step | A reconnect or login retry can erase live take data | rejected |
| Treat app force-close as demo end | preserve | No explicit logout required | Process death is not reliably observable and could destroy a recoverable take | rejected |
| Keep operator CLI as the only end boundary | preserve | Lowest accidental-delete risk | Does not meet the requested app-driven repeated-take workflow | rejected |

## 7. Modification Map and Change Budget

### Modification Map

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | production | `apps/backend/app/maintenance/seed_vp012_demo.py` | `Vp012DemoSeeder`, `run_cli` | add | `apps/backend/app/maintenance/migrate_auq_zero_based.py` | Add deterministic prepare/validate/seed/status/delete logic | Implements FR-001 through FR-008 and NFR-001 through NFR-004. | WS1 | preserve |
| CH-002 | test | `apps/backend/tests/test_seed_vp012_demo.py` | file-level | add | `apps/backend/tests/test_auq_zero_based_migration.py` | Add focused pure-data, encryption, CLI, idempotency, and delete-scope tests | Proves AC-001 through AC-004 and AC-006. | WS1 | preserve |
| CH-003 | docs | `apps/backend/README.md` | Maintenance commands | extend | Existing maintenance command documentation | Add container commands, environment, status, seed, and delete examples | Supports FR-001, FR-002, and AC-005. | WS2 | preserve |
| CH-004 | docs | `docs/demo/VP-012_120day_demo_video.ko.md` | file-level | add | `docs/deployment/MOBILE_DEVICE_TEST_GUIDE.ko.md` | Add persona disclosure, dataset narrative, shot list, narration, captions, and export checklist | Implements FR-009 and NFR-005. | WS2 | preserve |
| CH-005 | production | `apps/backend/app/maintenance/seed_vp012_demo.py` | `Vp012DemoSeeder`, `run_cli` | extend | Existing WS1 seeder | Add identity-only provision, armed/started audit-state checks, login-relative seed entry, and complete delete reuse | Implements FR-010, FR-011, FR-013, and FR-014. | WS3 | preserve |
| CH-006 | production | `apps/backend/app/services/auth.py` | `AuthService.login`, `AuthService.logout` | extend | Existing login/refresh/logout flow | Invoke the guarded demo lifecycle before token issue and before logout success | Implements FR-011 through FR-013. | WS3 | preserve |
| CH-007 | production | `apps/backend/app/core/config.py` | `SecuritySettings` | extend | Existing security environment parsing | Add disabled-by-default `DEMO_SCENARIO_ENABLED` | Implements NFR-008. | WS3 | preserve |
| CH-008 | production | `apps/backend/app/core/runtime.py` | `initialize_runtime` | extend | Existing service wiring | Inject the optional demo lifecycle into `AuthService` without a global or new service layer | Supports CH-006 and NFR-007. | WS3 | preserve |
| CH-009 | production | `apps/backend/app/repositories/postgres.py` | refresh-token owner lookup / transaction boundary | extend | Existing refresh revoke and user deletion helpers | Resolve the logout token owner and execute demo-only deletion in one transaction; omit the edit if current repository primitives suffice | Supports FR-013 with the smallest repository change. | WS3 | preserve |
| CH-010 | test | `apps/backend/tests/test_seed_vp012_demo.py` | focused seeder lifecycle tests | extend | Existing seeder tests | Prove provision, login-relative seed, complete delete, and repeat take | Proves AC-007 and AC-010. | WS3 | preserve |
| CH-011 | docs | `.env.example` | demo configuration | extend | Existing environment example | Document disabled flag and demo password input without a secret value | Supports FR-010 through FR-014. | WS3 | preserve |
| CH-012 | docs | `apps/backend/README.md` | demo lifecycle commands | extend | Existing maintenance documentation | Document provision → login → logout-delete → reprovision commands | Supports FR-010 through FR-014. | WS3 | preserve |
| CH-013 | docs | `docs/demo/VP-012_120day_demo_video.ko.md` | demo operator lifecycle | extend | Existing VP-012 production runbook | Align repeated-take steps and logout deletion warning | Supports FR-010 through FR-014. | WS3 | preserve |
| CH-014 | test | `apps/backend/tests/test_auth_service_v25.py` | auth lifecycle unit tests | extend | Existing authentication service tests | Prove guarded login seed, logout delete/rollback, ordinary-user isolation, and retry behavior | Proves AC-008 and AC-009. | WS3 | preserve |
| CH-015 | test | `apps/backend/tests/test_auth_routes_v25.py` | auth route lifecycle tests | extend | Existing authentication route tests | Prove sanitized login/logout failures and unchanged public route contract | Proves AC-008 and AC-009. | WS3 | preserve |
| CH-016 | production | `apps/backend/app/api/v1/routes/auth.py` | `_raise_service_error`, `login`, `logout` | extend | Existing authentication route error mapping | Map sanitized retryable demo lifecycle failures to HTTP 503 without adding a route | Implements FR-011 and FR-013. | WS3 | preserve |
| CH-017 | production | `apps/backend/app/schemas/auth.py` | `LoginInput.email` | extend | Existing strict login input schema | Accept `EmailStr` or the exact reserved demo literal; leave patient/admin signup unchanged | Implements FR-011 without broadening signup validation. | WS3 | preserve |
| CH-018 | production | `apps/mobile/core/src/main/kotlin/com/neurotruth/mobile/core/net/AuthenticatedApiClient.kt` | `logout` | extend | Existing authenticated logout client | Validate the backend response and clear credentials only after success | Implements FR-015. | WS4 | preserve |
| CH-019 | production | `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/settings/SettingsViewModel.kt` | `logout`, `tearDownAndSignOut` | extend | Existing settings logout flow | Keep monitoring/session/cache state on backend failure and expose a retryable error | Implements FR-015. | WS4 | preserve |
| CH-020 | test | `apps/mobile/core/src/test/kotlin/com/neurotruth/mobile/core/net/AuthenticatedApiClientTest.kt` | logout tests | extend | Existing logout credential test | Prove failure retention and success cleanup | Proves AC-011. | WS4 | preserve |

### Change Budget

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 2 | 1 | 1 | 850 | None | None |
| WS2 | 2 | 0 | 0 | 0 | None | None |
| WS3 | 13 | 7 | 0 | 290 | None | None |
| WS4 | 3 | 2 | 0 | 35 | None | None |

The production-line budget is an expansion alarm, not a compression target.

## 8. Work Plan

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | Add reversible deterministic VP-012 120-day seed tooling | None | DemoParallel | CH-001, CH-002 | `apps/backend/app/maintenance/seed_vp012_demo.py`, `apps/backend/tests/test_seed_vp012_demo.py` | `apps/mobile/**`, migrations, schema, Compose, existing maintenance files | FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-007, FR-008, NFR-001, NFR-002, NFR-003, NFR-004, NFR-006, AC-001, AC-002, AC-003, AC-004, AC-006 | `pytest -q apps/backend/tests/test_seed_vp012_demo.py` from repository root with backend path configured | verified |
| WS2 | Add operator commands and Korean demo-video production pack | None | DemoParallel | CH-003, CH-004 | `apps/backend/README.md`, `docs/demo/VP-012_120day_demo_video.ko.md` | `apps/mobile/**`, code, schema, existing design handoff files | FR-001, FR-002, FR-009, NFR-005, NFR-006, AC-005 | Manual link/command/copy review and forbidden-claim search | verified |
| WS3 | Add repeatable provision → login seed → logout delete lifecycle | WS1 | SerialAuthLifecycle | CH-005, CH-006, CH-007, CH-008, CH-009, CH-010, CH-011, CH-012, CH-013, CH-014, CH-015, CH-016, CH-017 | `apps/backend/app/maintenance/seed_vp012_demo.py`, `apps/backend/app/services/auth.py`, `apps/backend/app/api/v1/routes/auth.py`, `apps/backend/app/schemas/auth.py`, `apps/backend/app/core/config.py`, `apps/backend/app/core/runtime.py`, `apps/backend/app/repositories/postgres.py`, `apps/backend/tests/**`, `.env.example`, `apps/backend/README.md`, `docs/demo/**` | `apps/mobile/**`, migrations, schemas, model/STT/rPPG code, public route additions | FR-010, FR-011, FR-012, FR-013, FR-014, NFR-002, NFR-003, NFR-006, NFR-007, NFR-008, AC-007, AC-008, AC-009, AC-010 | Focused seeder/auth tests, backend regression, disposable PostgreSQL lifecycle, strict spec/scope/patch checks | verified |
| WS4 | Make the existing mobile logout button retry-safe | WS3 | SerialMobileLogout | CH-018, CH-019, CH-020 | `apps/mobile/core/src/main/kotlin/com/neurotruth/mobile/core/net/AuthenticatedApiClient.kt`, `apps/mobile/app/src/main/kotlin/com/neurotruth/mobile/ui/settings/SettingsViewModel.kt`, `apps/mobile/core/src/test/kotlin/com/neurotruth/mobile/core/net/AuthenticatedApiClientTest.kt` | Backend, Wear OS, other Phone screens, navigation, new UI | FR-015, NFR-006, NFR-007, AC-011 | Core unit test plus app unit/compile check and scope/patch/spec validation | verified |

### Parallelization Rationale

WS1 owns the verified backend maintenance foundation and WS2 owns verified documentation. WS3 is intentionally serial because auth token issuance and destructive logout share one lifecycle boundary and must be reviewed as one atomic behavior.

### Final Integration

- Run focused seed tests and relevant maintenance CLI tests.
- Run `specctl.py check-scope`, `check-patch`, and strict spec validation.
- Inspect the complete diff against the dirty-worktree baseline.
- If Docker is available, run dry-run/status against a disposable or explicitly approved database only; never seed an existing database without the confirmation command.
- Record actual Phone/Watch footage only after the operator has seeded the intended demo database.
- Verify the disabled flag on ordinary login/logout, then run provision → login → account-data count → logout → zero-account count → reprovision on a disposable database.

## 9. Validation, Rollout, and Risk

### Validation Plan

- Pure dataset invariants: 120 dates, recent one-hour points, four-stage boundaries, no future values, non-monotonic monthly distribution.
- Persistence safety: transaction, advisory lock, reserved identity, idempotency manifest, exact deletion scope.
- Security: password environment only, AES-GCM AAD round trips, no plaintext sensitive fields in serialized database parameters.
- UI compatibility: current dashboard queries can consume seeded predictions/alerts/AUQ/sessions without schema or API changes.
- Video review: captions match current labels (`안정·관찰·주의·위험`), AUQ 0–48, 3-danger/15-minute policy, and research-use disclosures.
- Auth lifecycle: disabled flag and ordinary users are unchanged; provision creates no history; login creates history before tokens; logout commits full deletion before success; the complete cycle is repeatable.

### Minimality and Style-Fidelity Review

- Reject any migration, public API route, new runtime service layer, dependency, raw-signal generator, or mobile edit.
- Compare CLI parsing, transaction handling, sanitized error output, and tests with R-001.
- Remove any seeded table not visible or necessary in the approved demo flow.

### Rollout and Rollback

The auth lifecycle ships disabled with `DEMO_SCENARIO_ENABLED=false`. For a take, operators enable it, set the password environment, run dry-run and exact provision, then log in. Login creates the scenario; the existing app logout deletes the account and scenario. The exact delete CLI remains a recovery path. Before another take, provision the identity again. Setting the flag back to `false` immediately disables automatic start/end behavior without deleting existing records.

### Risks and Mitigations

| Risk | Impact | Mitigation or Evidence |
| --- | --- | --- |
| Dummy data is mistaken for research evidence | Invalid interpretation | Reserved `.invalid` identity, `DEMO/FICTIONAL` metadata, explicit on-video disclosure |
| Treatment-effect narrative | Misleading product claim | Non-monotonic pattern and forbidden-claim review |
| Seed affects real records | Data integrity/privacy harm | Stable reserved identity, advisory lock, one transaction, exact confirmations, delete scope tests |
| Four-month volume slows dashboard | Poor demo performance | Sparse representative measurement sessions rather than continuous raw signals |
| Final footage cannot be automated safely | Incomplete video | Deliver recording-ready script; use actual device screens after operator login and seed |
| Accidental account deletion | Lost demo take | Only exact reserved identity plus started audit state and explicit app logout can delete; force-close, refresh, and disconnect do not |
| Logout deletion partially fails | Inconsistent cleanup | One transaction, rollback, retryable error, no successful logout response |
| Login seeding delays or fails | Cannot start take | Seed before tokens, sanitized 503, armed state preserved for retry; no partially authenticated session |

## 10. Revision and Progress

### Design Revision History

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-26T15:00:00+09:00 | user-confirmation | Created the reviewed VP-012 120-day seed and video production design from the accepted defaults. | D-001, D-002, D-003, D-004, D-005, D-006 | Q-001 |
| 2 | 2026-07-26T15:45:00+09:00 | implementation-evidence | Corrected only the WS1 line-budget alarm from 650 to 850 after the minimal same-direction patch measured 778 lines; no behavior, target, dependency, abstraction, or strategy changed. | D-007 | None |
| 3 | 2026-07-26T17:10:00+09:00 | user-refinement | Changed the repeat-take lifecycle to identity-only provision, scenario creation on successful reserved-account login, and complete account removal at an explicit end boundary. | D-008, D-009, D-010, D-011 | Q-002 |
| 4 | 2026-07-26T17:25:00+09:00 | user-confirmation | Fixed the existing app logout action as the demo-end boundary; deletion commits before logout success and failures remain retryable. | D-012 | Q-002 |
| 5 | 2026-07-26T17:35:00+09:00 | repository-evidence | Added the existing auth route error mapper to the minimal target set because the current route cannot emit the specified sanitized retryable 503 lifecycle failure. | D-013 | None |
| 6 | 2026-07-26T17:45:00+09:00 | test-evidence | Added only `LoginInput.email` because the existing EmailStr validator rejects the exact reserved `.invalid` demo address before AuthService; signup remains strict. | D-014 | None |
| 7 | 2026-07-26T18:05:00+09:00 | implementation-evidence | Completed and verified the guarded auth lifecycle, exact reserved login validation, repeatable full-account cleanup, configuration, tests, and operator documentation within budget. | D-010, D-011, D-012, D-013, D-014 | None |
| 8 | 2026-07-26T18:15:00+09:00 | deletion-path-review | Added the existing mobile logout client and SettingsViewModel because they currently discard credentials and report signed-out even when backend demo deletion fails. | D-015 | None |
| 9 | 2026-07-26T18:30:00+09:00 | implementation-evidence | Verified server-success-only local logout, failure retention/retry, password-change local teardown, Android core tests, app compilation, and WS4 scope/budget. | D-015 | None |

### Implementation Progress Record

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-26T15:00:00+09:00 | 1 | — | ready | The user accepted the concrete default bundle before implementation; pair created and ready for worker orchestration. |
| 2026-07-26T15:35:00+09:00 | 1 | WS2 | verified | Scope and patch-budget checks passed; command documentation and the Korean 110-second actual-device production pack were reviewed for current-screen fidelity and forbidden claims. |
| 2026-07-26T15:45:00+09:00 | 2 | WS1 | pending | Repository evidence corrected the expansion alarm to 850; correctness review requested full recent-hour coverage and true consecutive-danger alert evidence before acceptance. |
| 2026-07-26T16:35:00+09:00 | 2 | WS1 | verified | Focused and maintenance regression tests passed (17 total). Disposable PostgreSQL head migration, dry-run, 3,216-prediction seed, idempotent reseed, status, exact-scope delete, zero-row post-delete status, and retained seed/delete audits all passed. |
| 2026-07-26T17:25:00+09:00 | 4 | WS3 | pending | User confirmed existing app logout as the destructive end boundary; implementation may begin after strict spec validation. |
| 2026-07-26T18:05:00+09:00 | 7 | WS3 | verified | Focused 26 tests and full backend 189 tests passed (1 skipped); disposable PostgreSQL proved migration 0001→0005, zero-history provision, 3,216-prediction login seed, full logout deletion, zero-history reprovision, and recovery delete. Scope/patch/spec/compile checks passed. |
| 2026-07-26T18:30:00+09:00 | 9 | WS4 | verified | Forced Android core test rerun passed, app debug Kotlin compilation passed, and scope/patch checks passed at 3/3 files and 33/35 production lines. |
