# PRD: NeuroTruth Intervention Support Platform

Last updated: 2026-07-19

Korean mirror: [PRD_neurotruth.ko.md](PRD_neurotruth.ko.md)

## Product Summary

NeuroTruth is an authenticated mobile-first supportive-intervention research prototype for people receiving CBT or willing to seek treatment. A patient self-registers on Android, grants feature-specific consent, and may use a connected Galaxy Watch whose sensor batches are relayed through the phone. Without a connected Watch, the patient can explicitly run a consent- and readiness-gated 20-second face rPPG measurement; connected users may also use it as an optional point-in-time measurement. The backend retains consented raw windows encrypted, runs deterministic prediction/alert/safety/first-intervention logic, supports autonomous dialogue through Bedrock, and persists evidence-linked state inference, report status, and audit records. It does not replace medical care, diagnosis, treatment, or emergency response. The web surface is administrator-only.

## Goals

- Immediate patient self-registration with role-based authentication and rotating refresh sessions.
- Complete, consent-aware collection of sensor, prediction, alert, AUQ, conversation, intervention, state inference, report, and audit data.
- AES-256-GCM encryption for sensitive content and raw sensor files, with model/prompt traceability.
- Deterministic alert, safety, and first-intervention rules; LLMs only continue allowlisted intervention dialogue and summarize supplied evidence.
- Structured autonomous dialogue with at most one short question, an encrypted asked/refused topic ledger, and no mandatory questionnaire coverage.
- Phone-owned backend authentication and optional Watch relay without Watch credentials; a connected Watch is not required for the manual rPPG path.
- Patient and administrator `24h|7d|30d` dashboards, reason-gated reveal, temporary password, confirmed deletion, intervention toggle, and timeout controls.

## Non-Goals

- Background or continuous camera measurement, automatic repeated rPPG, and treating a point-in-time camera result as continuous Watch coverage. Camera rPPG is enabled by default but remains gated on consent, server readiness, and controlled real-device validation.
- Diagnosis, medication advice, clinical certainty, or treatment claims.
- Immediate craving-reduction demonstrations, CBT-efficacy claims, or causal interpretation of intervention timing.
- Craving-model retraining, class balancing, threshold experiments, and moving-average label experiments.
- Direct LLM access from Android or Watch.
- Live administrator chat, emergency queue/dispatch, automatic contact, or guaranteed human connection.
- Bulk dataset/report download, legacy data backfill, or new-schema-to-legacy conversion.

## Core Flow

```text
Patient signup/login/consent on Phone
  <- Watch sensor batches
  -> authenticated encrypted sensor ingestion
  <- prediction SSE + deterministic alert
  -> user approves conversation and optionally submits AUQ
  -> safety check + deterministic first intervention
  -> structured autonomous dialogue without slot completion
  -> state inference + encrypted asynchronous report status
  -> patient/admin dashboards
```

The Watch never connects to backend directly. The phone stores refresh credentials in Android Keystore-backed storage, rotates once after `401`, and logs out if recovery fails.

The Phone determines Watch availability from the Wear OS connected-node list. A confirmed empty list promotes the manual 20-second face measurement; connected, checking, and error states retain accurate non-fallback wording. Camera results remain Phone-only with source `camera_rppg`, while a later Watch prediction may naturally become the latest result.

## Consent and Data

Terms, privacy, and sensitive-data consent are mandatory. Biosignal, AI analysis, notification, and report generation are independent optional gates. Optional Korean STT produces editable text that the patient must confirm before it enters the normal message path; AI speech output is Android-local TTS. Consent changes append immutable snapshots. Withdrawal blocks new processing but does not automatically erase historical records.

Accepted sensor windows require UUID `clientWindowId`. Identical retries are idempotent and conflicting reuse is rejected. Backend stores canonical JSON → gzip → AES-256-GCM under a backend-only volume and links each recording to prediction and alert rows.

## Session and Agent Behavior

A patient has at most one active UUID session. New sessions transition through `safety_check` and `intervention_dialogue`; manual finish is `completed`, while the configurable default 3,600-second inactivity timeout is `abandoned`. AUQ is optional and skipping it never blocks dialogue, state inference, finish, or consented report generation.

New sessions do not create `session_slots`, update legacy memory, or return `slots`, `missingSlots`, or `handoffReady`. An encrypted dialogue ledger prevents paraphrased repeated questions while allowing relevant topic order to remain flexible. Historical 13-slot sessions remain readable but immutable and are not backfilled.

Deterministic rules choose the first allowlisted intervention from current evidence. The LLM may later suggest only allowlisted types, and each delivered suggestion is versioned and stored. `interventionsEnabled=false` suppresses ordinary intervention wording and rows while preserving safety guidance and state inference.

State inference copies the latest valid model class as `low|mid|high|unknown` and records concrete evidence IDs. AUQ, dialogue, and intervention events remain separate evidence and never create a synthesized clinical score. LLM summaries cannot change the class and may fail independently.

## Safety

Immediate-risk dialogue may show 119 and Korean suicide-prevention line 109, then ask once whether to record requested administrator involvement. Acceptance/refusal is audited and the conversation continues. The product must state that this record is not live monitoring and does not guarantee contact or response.

## Administrator Experience

The web console supports code-gated admin signup/login, patient summaries/timelines, `24h|7d|30d` class/AUQ/event/intervention/report-status dashboards, nonblank-reason sensitive reveal, reasoned temporary-password assignment, exact-UUID confirmed deletion, and global `interventionsEnabled`/`chatTimeoutSeconds` settings. It never exposes raw PPG or report bodies in dashboard views and has no patient UI or export endpoint.

The optional camera-rPPG extension adds capture metadata summaries, reason-gated audited inline video playback, and reason plus exact-UUID deletion. It has no video download button or endpoint. A privileged viewer can technically preserve rendered bytes, so least privilege, policy, and audit remain required.

## Security and Deployment

The authenticated stack requires PostgreSQL 16+, Alembic head, versioned AES keyring, JWT signing key, admin signup code, encrypted sensor volume, and HTTPS. Explicit insecure HTTP is limited to development/test. Deployment creates fresh `postgres_data_v25` and `encrypted_sensor_data`; the legacy volume is backed up and preserved for rollback without migration.

Camera rPPG retains every accepted success/failure face video AES-256-GCM encrypted until audited deletion. Only the backend calls the private DGX service. Co-deployment keeps DGX internal-only, uses tmpfs for uploads, and requires `DELETE_UPLOADED_VIDEO=true`. `RPPG_ENABLED=true` is the default, `false` remains the deployment off switch, and enabled deployments still fail closed until storage, DGX, model, and runtime readiness checks pass.

## Acceptance Summary

- Auth/role/refresh replay, consent gates, encrypted round trips/tamper failures, sensor idempotency, and ownership are tested.
- Slot-free new sessions, legacy read-only behavior, repetition prevention, safety continuation, optional AUQ, deterministic first intervention, state inference, and report-status isolation are tested with fake adapters.
- Phone auth recovery, consent, authenticated upload/SSE, UUID resume, logout cleanup, and Watch relay are tested.
- Patient/admin dashboards, PPG ownership denial, admin no-PPG output, reason audit, settings, temporary password, and deletion retry are tested.
