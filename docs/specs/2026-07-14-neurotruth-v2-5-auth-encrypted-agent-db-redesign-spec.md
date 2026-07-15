# NeuroTruth V2.5 Authentication, Encrypted Data, Agent, and Database Redesign

- **Language role:** English source spec
- **Spec status:** Finalized
- **English source:** `neurotruth/docs/specs/2026-07-14-neurotruth-v2-5-auth-encrypted-agent-db-redesign-spec.md`
- **Korean mirror:** `neurotruth/docs/specs/2026-07-14-neurotruth-v2-5-auth-encrypted-agent-db-redesign-spec.ko.md`
- **Date:** 2026-07-14
- **Implementation mode:** `IMPLEMENTATION_ORCHESTRATION`

## 0. Codex Implementation Handoff

```text
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: neurotruth/docs/specs/2026-07-14-neurotruth-v2-5-auth-encrypted-agent-db-redesign-spec.md
korean_mirror_spec: neurotruth/docs/specs/2026-07-14-neurotruth-v2-5-auth-encrypted-agent-db-redesign-spec.ko.md
authoritative_spec: english_source_spec
worker_rule: source_code_changes_must_be_delegated_to_worker_subagents
</codex_feature_planner_handoff>
```

## 1. Summary

Replace NeuroTruth's temporary six-table persistence layer with a fresh V2.5 database and move the backend, Android phone, watch relay, administrator web, and LLM agents to authenticated patient ownership and encrypted, consent-aware persistence. Patients self-register and activate immediately. The system stores all consented sensor, prediction, alert, AUQ, chat, slot, intervention, memory, report, and audit data. The previous PostgreSQL volume remains backed up and untouched; no legacy data is migrated.

## 2. Goals

- Establish one authoritative 18-table V2.5 schema managed by Alembic.
- Add patient self-registration, administrator-code registration, role authorization, rotating refresh sessions, and secure mobile credential storage.
- Encrypt all sensitive content and retained raw sensor windows with backend AES-256-GCM.
- Collect complete consented experiment data with idempotent sensor ingestion and traceable model versions.
- Replace the current agent slots with the approved 13-slot, non-repeating conversation flow and deterministic intervention selection.
- Provide administrator controls for sensitive access, deletion, intervention enablement, and session timeout with complete audit trails.

## 3. Non-Goals

- Migrating or backfilling legacy runtime data into V2.5.
- Voice STT/TTS, audio capture, or audio-artifact implementation.
- rPPG capture or inference.
- Administrator live chat, an emergency-response queue, automatic emergency contact, or a claim that human connection is guaranteed.
- A bulk dataset-download API or automatic retention deletion.
- Backporting V2.5 data after rollback.

## 4. Users and Use Cases

- **Patient:** self-registers, signs in, manages consent, streams phone/watch sensor windows, receives predictions and alerts, completes AUQ and the 13-topic chat, receives an eligible intervention, views reports, and resumes an interrupted active session.
- **Administrator:** registers with a rotating signup code, signs in to the web console, reviews patient state and timelines, decrypts sensitive content only with a recorded reason, issues temporary passwords, changes global settings, and initiates audited patient deletion.
- **System operator:** supplies database, encryption, JWT, storage, and transport configuration; performs fresh deployment, readiness checks, backup, and rollback.

## 5. Final Decisions

- Use a fresh database; preserve but do not mutate or import the legacy volume.
- Use the 16 V2.5 business tables plus `auth_sessions` and `system_settings`.
- Patients self-register and become active immediately. Administrators require the current signup code.
- Encrypt sensitive database fields and raw sensor files in the backend with versioned AES-256-GCM keys; no plaintext fallback is allowed.
- Retain all consented raw sensor windows in an encrypted backend-only volume.
- Use 15-minute access JWTs and rotating 30-day opaque refresh tokens; store only refresh-token hashes.
- Replace unauthenticated string session identifiers with server-issued UUID sessions owned by the JWT patient.
- Require all 13 slots to reach `answered`, `unknown`, or `declined` before normal completion; completed topics are never re-asked.
- Apply no turn-count limit. Use manual termination and a configurable one-hour default timeout.
- Permit normal intervention only after all slots complete. A rule engine chooses the type and the LLM only phrases that selected type.
- Keep safety guidance independent of the global normal-intervention setting.
- Allow insecure HTTP only in explicit development mode.

## 6. Functional Requirements

### 6.1 Accounts, authentication, and consent

- Patient signup creates an `active` user. Administrator signup validates the current administrator signup-code hash. Users support `active`, `disabled`, and `pending_deletion`, plus `must_change_password`.
- Passwords use Argon2id. Login issues a signed 15-minute access JWT and a 30-day opaque refresh token. Every refresh rotates the token; replay revokes its token family. Logout, password change, disablement, and deletion revoke applicable sessions.
- Consent snapshots are append-only. Terms, privacy, and sensitive-data consent are mandatory. Biosignal collection, AI analysis, notifications, and reports are optional. Voice is displayed as unavailable and disabled. Withdrawal blocks new feature use without deleting prior data.

### 6.2 Complete collection and prediction traceability

- An accepted sensor upload requires `clientWindowId`, authenticates the patient, stores the encrypted raw window, creates or reuses its `sensor_recordings` row, and links subsequent prediction and alert records.
- `(patient_id, client_window_id)` is unique. A retry returns the prior accepted resource/result rather than duplicating storage or inference.
- AUQ score and searchable prediction metadata remain plaintext; raw AUQ answers and sensitive supporting content are encrypted.
- Prediction, dialogue, slot, intervention, report, and memory operations reference their actual `model_versions` row and prompt version.

### 6.3 Sessions and agent behavior

- A patient has at most one `created` or `in_progress` session. The app resumes it within `chat_timeout_seconds`; manual early finish or timeout sets `abandoned`.
- The 13 slot keys are `episode_trigger`, `current_context`, `alcohol_context`, `drinking_status`, `habit_pattern`, `emotional_context`, `physical_context`, `alcohol_expectancy`, `coping_context`, `support_context`, `user_goal`, `safety_context`, and `additional_context`.
- Each encrypted slot payload contains `status: answered|unknown|declined` and `data`. Every status counts as complete. A message may update multiple slots. The dialogue agent asks at most one question, targets only an incomplete slot, and never revisits completed topics directly or indirectly.
- Question priority is safety, current drinking, current environment, alcohol access, trigger, emotional/physical context, expectancy, habit, coping, support, goal, then additional context. `handoffReady=true` and normal completion require all 13 slots.
- Early termination creates an asynchronous partial report only when report consent is active; it explicitly records completed and incomplete topics. Longitudinal memory updates only after normal completion.

### 6.4 Safety and interventions

- On safety-risk detection, ask once in chat whether administrator involvement is wanted. Record acceptance or refusal and continue remaining slot collection. Never promise connection. Immediate-risk content includes configured emergency information; the Korean default is suicide-prevention line 109.
- After all slots complete, select a normal intervention deterministically: alcohol access/refusal need maps to `leave_location|refusal_practice`; tension/physical arousal to `breathing|grounding`; craving expectancy/habit to `urge_surfing|attention_shift`; an available supporter to `social_support`; otherwise `self_monitoring|hydration`.
- The LLM may phrase only the selected type. When `interventions_enabled=false`, emit no normal intervention wording and create no `interventions` row. Safety checking and emergency guidance remain enabled.

## 7. User Experience / UI Requirements

- Mobile provides patient signup, login, mandatory temporary-password change, consent settings, monitoring state, UUID session resume, chat manual-finish control, and report status. Logout stops monitoring and prediction streaming.
- The watch continues to relay through the phone and neither stores nor sends backend credentials directly.
- The administrator web provides signup-code authentication, patient summaries/timelines, reason-gated sensitive-content viewing, temporary-password assignment, manual deletion, intervention ON/OFF, and chat-timeout configuration.
- Disabled or withdrawn-consent features explain why they are unavailable without exposing sensitive backend details.
- The chat must not display a separate safety banner; its one-time administrator-involvement question and emergency guidance appear within the conversation.

## 8. API / Data / State Requirements

### 8.1 Schema authority and state

The repository-contained authoritative sources are `neurotruth_schema_definition_v2_5.md` and `neurotruth_schema_v2_5.sql` at the Git repository root. Alembic and Compose must resolve this in-repository SQL and must not depend on a parent workspace. The V2.5 schema is the existing 16-table design plus:

- `auth_sessions`: refresh hash, family, rotation lineage, revocation, expiry, and device metadata.
- `system_settings`: at minimum `interventions_enabled`, `chat_timeout_seconds=3600`, and administrator signup-code hash.

`init.sql` installs extensions only. The SQL reference, schema-definition document, and baseline Alembic migration must express the same constraints, indexes, foreign keys, enums/checks, and session lifecycle.

### 8.2 Public authenticated API

- `POST /api/auth/patient/signup`
- `POST /api/auth/admin/signup`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `POST /api/auth/change-password`
- `GET /api/me`
- `PATCH /api/me`
- `POST /api/me/consents`
- `POST /api/sensor-windows`
- `GET /api/predictions/stream`
- `POST /api/sessions`
- `GET /api/sessions/{id}`
- `POST /api/sessions/{id}/messages`
- `POST /api/sessions/{id}/assessments`
- `POST /api/sessions/{id}/finish`
- `POST /api/sessions/{id}/reports`
- `GET /api/sessions/{id}/reports`

The unauthenticated legacy `/sensor-window`, `/prediction-stream`, and `/api/intervention/*` string-session contracts are removed. JWT subject and role establish ownership and authorization. The server issues UUID chat sessions.

### 8.3 Encrypted field and file format

- Database envelopes store ciphertext, fresh 96-bit nonce, 128-bit tag, and key version. AAD binds table, column, patient UUID, and record UUID.
- Raw sensor storage is `canonical JSON -> gzip -> AES-256-GCM`. Database metadata contains only relative encrypted path, modality, sampling rate, time bounds, byte size, pre-encryption SHA-256 checksum, and encryption metadata.
- File and database persistence is coordinated so errors leave neither plaintext files nor permanent orphan encrypted files.

## 9. Permissions, Security, Privacy, and Audit

- `DATA_ENCRYPTION_KEYS_B64` is a versioned key ring and `DATA_ENCRYPTION_CURRENT_KEY_ID` selects the write key. Reads select the stored version. Missing/unknown keys and failed authentication are hard failures.
- Administrators receive summaries by default. Sensitive decryption requires a non-empty reason and writes actor, target, reason, action, and timestamp to `audit_logs`.
- Do not log passwords, encryption keys, access/refresh tokens, decrypted content, raw sensor paths, ciphertext payloads, or provider internals. Model failures are sanitized codes.
- Patient deletion requires role authorization, a reason, and explicit confirmation. It locks the account as `pending_deletion`, revokes sessions, removes encrypted files and linked data, tombstones the account, and preserves only a non-sensitive audit record. Partial failure remains retryable.
- No bulk dataset-download endpoint exists. Optional-consent withdrawal does not erase historical data.
- Production startup requires HTTPS. `ALLOW_INSECURE_HTTP=true` is development-only.

## 10. Error, Edge-Case, and Concurrency Behavior

- Database, required Alembic revision, or encryption-key unavailability fails readiness; the backend never silently continues without persistence.
- Refresh-token reuse revokes the full family even when the presented token is otherwise well formed.
- Concurrent creation of a second active session is rejected or returns the existing active session without creating a duplicate.
- Duplicate `clientWindowId` upload is idempotent. A conflicting payload using the same ID is rejected and audited rather than overwriting prior data.
- Encryption envelope corruption, AAD mismatch, unknown key version, or tag failure returns a sanitized failure and never partial plaintext.
- Interrupted sensor writes clean temporary artifacts. Failed deletion remains `pending_deletion` and can be retried.
- Agent/provider failure preserves the session and completed slots, returns a safe retry response, and stores only a sanitized failure code.
- An early-finish report job is idempotent per session/report version. Safety response remains available even if normal interventions are disabled.

## 11. Dependencies and Configuration

- PostgreSQL 16+, Alembic, an Argon2id implementation, JWT signing/verification, a cryptography library supporting AES-256-GCM, and existing backend/mobile/web stacks.
- Required secrets/configuration include database URL, JWT signing material, `DATA_ENCRYPTION_KEYS_B64`, `DATA_ENCRYPTION_CURRENT_KEY_ID`, administrator signup code/hash configuration, encrypted sensor-volume root, and production HTTPS settings.
- Configurable values include `chat_timeout_seconds` (default `3600`), `interventions_enabled`, access TTL (15 minutes), refresh TTL (30 days), Korean emergency-resource text, and Argon2id cost parameters validated on deployment hardware.
- Android refresh credentials use Keystore-backed encrypted storage. The watch depends on the authenticated phone relay.
- Automated LLM tests use a fake Bedrock adapter; real-model quality evaluation is manual.

## 12. Migration, Rollout, and Rollback

- Back up and preserve legacy `postgres_data`; never run the V2.5 baseline against it.
- Create `postgres_data_v25` and a backend-only encrypted sensor volume, apply the fresh Alembic baseline, seed settings/model versions, and verify readiness before switching clients.
- Deploy compatible backend, mobile, watch relay, and administrator web together because legacy unauthenticated contracts are intentionally removed.
- Run fresh-stack schema, auth, encryption, client, and Compose smoke tests before enabling collection.
- Rollback stops V2.5 services and reconnects the previous image and preserved legacy volume. No V2.5 data is converted back.

## 13. Implementation Boundaries

- Source-code changes must be performed by worker sub-agents. The main agent owns spec synchronization, minimal-diff review, validation, and final reporting.
- Keep implementation limited to authentication, consent, V2.5 persistence, encryption, complete sensor retention, approved agent behavior, administrator controls, and compatible client changes.
- Do not implement voice, rPPG, live administrator intervention, emergency dispatch, dataset export, legacy data conversion, or automatic retention deletion.
- Preserve unrelated user changes and avoid generated secrets, credentials, databases, encrypted data, and build artifacts in Git.

## 14. Acceptance Criteria

- **AC-01 Fresh schema:** A blank PostgreSQL 16+ database reaches the required Alembic revision and contains the consistent 18-table contract, checks, foreign keys, indexes, and one-active-session constraint.
- **AC-02 Mandatory readiness:** Missing DB, migration, or encryption-key configuration makes readiness fail; the API never continues in unpersisted mode.
- **AC-03 Encryption:** Protected fields and sensor files contain no plaintext; unique nonces, AAD binding, mixed key versions, and successful round trips are verified; ciphertext, AAD, or tag tampering is rejected.
- **AC-04 Self-registration and roles:** A patient can self-register and log in immediately; administrator signup fails without the active signup code; patient/admin authorization boundaries are enforced.
- **AC-05 Token security:** Refresh rotation works, replay revokes the family, and logout/password change/disable/delete revoke the correct sessions.
- **AC-06 Consent gating:** Required consent is enforced, optional features are independently gated, changes create immutable snapshots, and withdrawal stops only new processing.
- **AC-07 Complete collection:** An authenticated and consented sensor window is encrypted and retained, deduplicated by `clientWindowId`, linked to its prediction and alert, and remains attributable to the authenticated patient.
- **AC-08 Agent completion:** All 13 slots support `answered`, `unknown`, and `declined`; multi-slot extraction works; no completed topic is repeated; normal handoff and intervention are impossible before all 13 complete.
- **AC-09 Session termination:** Interrupted sessions resume within the configured timeout; manual early finish and timeout become `abandoned`; consented partial reports list missing topics; memory updates only on normal completion.
- **AC-10 Safety behavior:** The involvement offer is asked once, acceptance/refusal is recorded without a false promise, slot dialogue continues, and emergency guidance remains available while interventions are globally off.
- **AC-11 Deterministic interventions:** The rule engine selects the type, the LLM cannot change it, and the global setting suppresses both normal wording and persistence.
- **AC-12 Administrative controls:** Reason-gated decryption, setting changes, temporary passwords, and deletion are role-protected and audited; deletion failures remain retryable; no dataset-download endpoint exists.
- **AC-13 Client transition:** Mobile signup/login/consent/token recovery/logout and UUID session resume work; watch relay uses phone credentials; the admin web exposes only approved controls.
- **AC-14 Transport:** Insecure HTTP starts only in explicit development mode and is rejected in production mode.
- **AC-15 Rollback:** The legacy DB volume remains restorable and no implementation step mutates or backfills it.

## 15. Validation Plan

- Backend network-free tests: fresh migration, constraints, lifecycle transitions, authentication/roles, token rotation/replay, consent, AES-GCM round trip/key rotation/tampering, encrypted file consistency, sensor idempotency, prediction/alert links, audit, and deletion retry.
- Fake-Bedrock agent tests: 13-slot extraction, multi-slot messages, unknown/declined completion, repetition prevention, safety acceptance/refusal, partial reports, intervention mapping/toggle, model-version links, and sanitized failures.
- Android tests: signup/login, Keystore credential recovery, forced password change, consent gating, authenticated upload/SSE, logout cleanup, and UUID session resume. Verify watch relay without watch credentials.
- Web tests: administrator signup-code authentication, authorization, timelines, reason-gated decryption, settings, temporary passwords, deletion, and audit creation.
- Compose smoke: new V2.5 and encrypted sensor volumes, readiness, end-to-end authenticated collection, and restoration of the preserved legacy volume. Real LLM quality remains a manual evaluation.

## 16. Risks and Open Notes

- There are no unresolved product decisions in this specification.
- Argon2id cost parameters must be benchmarked on deployment hardware without weakening the selected algorithm.
- Full raw-data retention increases storage and breach impact; encrypted-volume capacity and operator backup security require monitoring.
- A single compromised backend process can access active decryption keys; least-privilege deployment and secret rotation remain operational requirements.
- The safety flow records a request but does not deliver human intervention; UI and prompts must avoid implying otherwise.
- The hard API cut requires coordinated client deployment and makes the preserved legacy stack the only rollback path.
- **Operational caveat:** the user's current `.env` does not contain the new V2.5 key names. The implementation and isolated verification are complete, but a real deployment is not release-ready until the required V2.5 secrets and configuration are supplied. This is an operational readiness requirement, not an implementation blocker.

## 17. Implementation Checklist / Progress Record

| Phase | Status | Record |
| --- | --- | --- |
| P1 | Complete | Approved scope and final decisions captured. |
| P2 | Complete | English authoritative specification finalized. |
| P3 | Complete | Korean mirror synchronized; backend/schema, Android, administrator web, and documentation source implementation completed through delegated worker sub-agents. |
| P4 | Complete | Main-agent verification completed: backend `115 passed`; Android `50 passed`; React production build compiled; spec-pair validator PASS; independent consent gates were verified for biosignal raw-only collection, AI prediction, notification alert presentation/persistence, and reports; an actual isolated PostgreSQL 16 instance reached Alembic revision `20260715_0001` with the exact 18-table/index contract; runtime readiness was `true`; live dummy patient signup, JWT issuance, and consent restoration succeeded; encrypted-name storage contained no plaintext and refresh credentials were stored as SHA-256 hashes; legacy routes were absent; `.env` was unchanged. Docker smoke used uniquely named temporary resources and cleaned them after verification; the legacy volume was untouched. Real deployment remains subject to the `.env` operational caveat in Section 16. |

## 18. Revision History

| Date | Revision | Description |
| --- | --- | --- |
| 2026-07-14 | 1.0 | Finalized English authoritative specification from the approved V2.5 redesign plan. |
| 2026-07-15 | 1.1 | Recorded synchronized worker implementation and final verification evidence; documented the remaining deployment-configuration caveat. |
