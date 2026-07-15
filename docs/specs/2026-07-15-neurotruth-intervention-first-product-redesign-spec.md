# NeuroTruth Intervention-First Product Redesign Spec

- **Language role:** English source spec
- **Spec status:** Finalized
- **Spec version:** 1.2
- **Last updated:** 2026-07-15
- **English source:** `docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.md`
- **Korean mirror:** `docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.ko.md`
- **Requester / owner:** NeuroTruth project team
- **Implementation status:** Implemented

## 0. Codex Implementation Handoff

This section preserves the Feature Planner workflow when moving from the finalized design to implementation. The English file is authoritative.

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: neurotruth/docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.md
korean_mirror_spec: neurotruth/docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.ko.md
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
$feature-planner Implement `neurotruth/docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.md` using IMPLEMENTATION_ORCHESTRATION. Use the English source spec as authoritative, keep `neurotruth/docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.ko.md` synchronized, spawn worker sub-agents for source-code edits, and have the main agent verify minimal diffs and update progress records. If the spec has a gap, use TARGETED_REFINEMENT for only that gap before continuing.
```

## 1. Summary

NeuroTruth becomes a research-use supportive intervention system that detects a possible craving situation and, with the user's approval, offers a structured but autonomous conversation. It is not a treatment app and must not present an immediate post-CBT decrease, diagnosis, treatment success, or causal treatment effect. New sessions stop collecting the V2.5 13-slot questionnaire and stop exposing `handoffReady`; instead they move through optional AUQ, deterministic safety handling, a deterministic first intervention, free follow-up dialogue, state inference, encrypted persistence, and status-only report presentation. Existing 13-slot sessions and reports remain readable historical records without backfill or new slot writes. The implementation spans the authenticated backend, Android patient experience, and administrator web dashboard, with one additive `0003` migration.

## 2. Goals

- G1. Demonstrate `PPG/GSR -> Low/Mid/High estimate -> possible-rise notification -> user-approved dialogue -> optional AUQ -> intervention -> DB -> dashboards` without claiming treatment effect.
- G2. Replace mandatory slot completion in new sessions with concise, nonjudgmental, history-aware intervention dialogue that respects refusal and avoids repeated questions.
- G3. Separate deterministic state classification and evidence collection from LLM-authored Korean summaries.
- G4. Provide patient and administrator dashboards for craving class trends, AUQ, alerts, sessions, interventions, and report status while enforcing raw-signal and sensitive-text access rules.
- G5. Preserve V2.5 authentication, consent, AES-256-GCM encryption, auditing, one-active-session behavior, alert cooldown, and the existing rPPG implementation behind its disabled feature flag.

## 3. Non-Goals

- NG1. Proving that CBT or an intervention immediately reduces craving, or making diagnostic, treatment, prognostic, or causal claims.
- NG2. Retraining the craving model, class balancing, threshold experiments, or moving-average label experiments; these remain research-team work documented outside runtime behavior.
- NG3. STT, self-event capture, wearable-absent AUQ automation, and production activation of rPPG.
- NG4. Importing or calling NeuroSync code at runtime; only its dialogue principles are reimplemented locally in NeuroTruth.
- NG5. Backfilling, deleting, rewriting, or continuing the legacy `session_slots` workflow or legacy `memory_snapshots` for new sessions.
- NG6. Showing report bodies in the current patient or administrator dashboard, exposing raw PPG to administrators, or creating a dataset download API.
- NG7. Adding a demo signal-replay mode; a recorded demo scenario may be specified separately when assets and timing are supplied.

## 4. Users and Use Cases

### 4.1 Target Users

- People currently receiving CBT or willing to engage in treatment who need ongoing records and dialogue-based support during craving situations.
- Authorized administrators who review patient-level trends, event timing, intervention use, and report generation state.

### 4.2 Primary Use Cases

- UC1. A patient receives a possible craving-rise alert, chooses `Talk now`, optionally completes AUQ, and continues through safety-aware intervention dialogue until manual finish or inactivity timeout.
- UC2. A patient reviews 24-hour, 7-day, or 30-day Low/Mid/High trends, separate AUQ values, event markers, current local Watch PPG, and an owned historical 10-second PPG preview.
- UC3. An administrator reviews patient trends and event metadata without raw PPG and supplies a reason before revealing encrypted message, state-summary, intervention, or report text.
- UC4. A legacy 13-slot session remains available as read-only history but cannot receive messages, assessments, report regeneration, or slot changes.

## 5. Final Decisions

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | Positioning | Describe NeuroTruth as a research-use supportive intervention system, never a treatment or immediate-effect demonstration. | User decision |
| D2 | Session flow | New sessions have no slot coverage, `missingSlots`, or `handoffReady`; manual finish is `completed`, and 1-hour inactivity is `abandoned`. | User decision |
| D3 | AUQ | AUQ is optional. Skipping creates no assessment row and never blocks safety checking, dialogue, intervention, state inference, or finish. | User decision |
| D4 | Safety | Deterministic safety checks take priority; urgent contexts show 119/109 guidance and never promise live contact. Dialogue may continue if the user chooses. | User decision |
| D5 | First intervention | Server rules, not the LLM, select the first intervention from the approved allowlist. | User decision |
| D6 | Later interventions | The LLM may suggest only allowlisted intervention types after the first one; the server validates and persists every delivered suggestion. | User decision |
| D7 | Question guide | A versioned Korean question bank derived as original paraphrases from NIAAA, SAMHSA TIP 35, and WHO mhGAP is optional guidance, not a questionnaire or coverage target. | User decision |
| D8 | State inference | Deterministic evidence produces `low|mid|high|unknown`; AUQ and dialogue evidence never mathematically alter the model class. LLM summary failure leaves the deterministic record intact. | User decision |
| D9 | Legacy compatibility | Existing slot sessions have `interaction_phase=NULL`, remain read-only, and are not backfilled. New-session code never writes `session_slots` or legacy `memory_snapshots`. | Agent default |
| D10 | Dialogue continuity | Encrypted session dialogue state stores asked/refused topic IDs and safety state, preventing repeated questions across app restarts without creating replacement slots. | Agent default |
| D11 | Dashboard range | `24h`, `7d`, and `30d` are the only ranges; arrays are returned chronologically in one response because 30 days is the maximum. | User decision |
| D12 | Raw PPG | Live PPG is rendered only from the Android Watch stream. Historical 10-second PPG is available only to its owning patient through a separate maximum-512-point endpoint. | User decision |
| D13 | Reports | Finish automatically queues a consent-gated report from messages, AUQ, prediction, interventions, and state inference. Patient/admin dashboards expose only `not_started|generating|ready|failed`. | User decision |
| D14 | rPPG | Preserve rPPG code and data. Hide patient and admin rPPG UI unless the existing backend rPPG feature is explicitly enabled and ready. | User decision |
| D15 | Time | Store and return timestamps as UTC ISO-8601; Android and web render them in the viewer's local timezone. | User decision |
| D16 | API rollout | Session response contracts break intentionally and backend plus Android deploy together; no compatibility shim is added for the removed slot fields. | User decision |
| D17 | Inference cadence | Create a realtime state snapshot for an alert-backed session start and after each accepted patient message, assessment, or delivered intervention; create one longitudinal snapshot at session finish using the prior 30 days. | Agent default |
| D18 | Provider failures | Dialogue failure uses a safe deterministic response and coded audit entry; summary/report failure is isolated to its own status and never rolls back deterministic data or session finish. | Agent default |

## 6. Functional Requirements

- FR1. At first authenticated patient use, Android must show the target-user and limitation notice and store its version locally; a changed notice version requires acknowledgement again, and the same notice remains accessible from Home.
- FR2. A qualifying alert keeps the existing cooldown/warm-up rules and offers `Talk now` and `Later`. Only `Talk now` creates/resumes an authenticated session.
- FR3. `POST /api/sessions` starts an `in_progress` new-style session in `safety_check`, persists a deterministic assistant safety prompt, and returns that prompt plus the effective `system_settings.chat_timeout_seconds` as `inactivityTimeoutSeconds`. The app may show the optional AUQ choice before revealing the buffered prompt.
- FR4. Submitting AUQ uses the existing assessment endpoint. Choosing `Skip and talk` makes no assessment request and proceeds immediately.
- FR5. The safety engine must detect at least immediate self-harm, impaired driving, dangerous alcohol/medicine combinations, severe acute symptoms, and severe withdrawal wording. It provides 119 for immediate medical danger and 109 for suicide/self-harm crisis context, records coded evidence and any requested/declined administrator involvement, and does not claim that contact will occur.
- FR6. Once safety handling allows continuation, deterministic context flags choose the first intervention in this priority: refusal need -> `refusal_practice`; accessible alcohol -> `leave_location`; respiratory arousal -> `breathing`; other tension/arousal -> `grounding`; repetitive craving thoughts -> `urge_surfing`; habitual cue -> `attention_shift`; available supporter -> `social_support`; dehydration wording -> `hydration`; otherwise -> `self_monitoring`.
- FR7. The approved intervention allowlist is `breathing`, `urge_surfing`, `attention_shift`, `leave_location`, `refusal_practice`, `social_support`, `grounding`, `hydration`, and `self_monitoring`. `other` remains legacy-only and must not be created by new sessions.
- FR8. After the first intervention, the dialogue agent receives full decrypted-in-memory history, encrypted dialogue-ledger state after decryption, active interventions, latest prediction, optional AUQ, and safety state. It returns one concise Korean response with at most one short question and an optional allowlisted intervention suggestion.
- FR9. Question topic IDs are limited to `safety`, `current_environment`, `alcohol_access`, `trigger`, `emotion_body`, `past_coping`, `support`, and `desired_help`. The agent may omit questions, change order, or revisit only when the user explicitly corrects prior information. Refused or already asked topic IDs are not asked again by paraphrase.
- FR10. The question-bank artifact must use original Korean paraphrases, identify source URLs and version `niaaa-samhsa-who-ko-v1`, and cite [NIAAA brief intervention](https://www.niaaa.nih.gov/health-professionals-communities/core-resource-on-alcohol/conduct-brief-intervention-build-motivation-and-plan-change), [SAMHSA TIP 35](https://library.samhsa.gov/product/tip-35-enhancing-motivation-change-substance-use-disorder-treatment/pep19-02-01-003), and [WHO mhGAP alcohol guidance](https://www.who.int/teams/mental-health-and-substance-use/treatment-care/mental-health-gap-action-programme/evidence-centre/alcohol-use-disorders). It must not reproduce source wording as a clinical script.
- FR11. Server validation rejects or repairs agent output containing more than one question, an unapproved intervention, diagnosis/prescription, treatment success, immediate reduction, certainty, causal language, or unsupported emergency promises. One structured repair is allowed; otherwise use a deterministic non-clinical fallback.
- FR12. Every user and assistant message is AES-256-GCM encrypted before persistence. Every delivered intervention is separately encrypted and stored with order, status, evidence references, and actual model/prompt version.
- FR13. Deterministic state inference copies the latest valid model class and probability, or uses `unknown` when unavailable. It stores evidence IDs and a payload containing class distribution, AUQ observations, alerts, session events, and intervention events without synthesizing a new clinical score.
- FR14. The LLM may write a Korean state summary only from the supplied evidence payload. It cannot change the class. Provider failure stores `summary_status=unavailable`, a coded audit event, and no fabricated summary.
- FR15. Manual finish closes a new session as `completed`; inactivity after the configured timeout (default 3600 seconds) closes it as `abandoned`. Both create the final realtime snapshot, one 30-day longitudinal snapshot, and a report job when report consent is active.
- FR16. New reports use messages, AUQ, trigger prediction, alerts, interventions, and state inferences. They do not read `session_slots`; report failure sets `failed` without reopening or rolling back the session.
- FR17. New sessions never update `memory_snapshots`; longitudinal `state_inferences` are the new evidence-linked longitudinal record. Existing memory remains retained and reason-gated.
- FR18. Android must display a separate patient dashboard and the administrator web must display its restricted dashboard according to Section 7 and Section 8.
- FR19. Existing rPPG routes, encrypted data, and jobs remain intact. No rPPG runtime call is introduced into the core demo flow.
- FR20. All model, prompt, rule, and question-bank versions used for agent output or state inference must be persisted or referenced by the generated record.

## 7. User Experience / UI Requirements

- UI1. The first-use notice states: intended for people receiving CBT or willing to seek treatment; intended for ongoing recording and dialogue support in craving situations; research-use support that does not replace medical care, diagnosis, or emergency response. The primary action is `확인했어요`; Home exposes `NeuroTruth 사용 안내` afterward.
- UI2. The alert sheet uses possibility language such as `갈망이 높아졌을 가능성이 있어요` and actions `지금 대화하기` and `나중에`. It must not say that craving definitely increased.
- UI3. After session creation, show AUQ actions `작성하기` and `건너뛰고 대화하기`. A skip immediately opens the buffered safety prompt and has no warning or reduced-function state.
- UI4. The chat displays safety guidance inline, not as a separate emergency banner. It provides a persistent `대화 종료` action and shows that inactivity ends the session after the administrator-configured `inactivityTimeoutSeconds`, default one hour. Android treats the server value as authoritative and never hardcodes one hour.
- UI5. Chat progress must use phase labels such as `안전 확인`, `대화형 중재`, and `마무리`; it must not show slot completion, missing information, questionnaire progress, or handoff readiness.
- UI6. Report UI shows only `생성 전`, `생성 중`, `준비됨`, or `실패`. It never renders report body text in this release.
- UI7. The Android dashboard defaults to `24시간` and switches to `7일` or `30일`. It renders a categorical Low/Mid/High step chart, AUQ on a separate chart/axis, and markers for detection, AUQ, notification, dialogue approval/session start, each intervention, and session finish.
- UI8. Android renders current Watch PPG from the existing local stream and offers a historical `10초 PPG 보기` action only where `ppgPreviewAvailable=true`. It shows an unavailable state rather than substituting another prediction's data.
- UI9. The latest realtime state and the latest longitudinal summary are shown with evidence timestamps and neutral language. Empty states distinguish no prediction, no AUQ, no sessions, and unavailable summary.
- UI10. The administrator dashboard selects a patient and `24h|7d|30d`, then shows class trend, AUQ, alerts, sessions, intervention metadata, and report status. It has no raw-PPG control or raw-PPG response data.
- UI11. Administrator reveal of message, state summary, intervention, or report content uses the existing reason-entry flow and audit behavior. Dashboard list/trend views never decrypt these bodies.
- UI12. Patient camera-rPPG controls and administrator capture sections are absent unless `GET /api/rppg/status` reports both enabled and ready. STT and self-event controls remain absent.
- UI13. Graphs provide text labels/legends in addition to color, maintain readable Korean labels at Android font scaling, and use keyboard-accessible controls and visible focus in web.

## 8. API / Data / State Requirements

### 8.1 Breaking session API

- API1. `POST /api/sessions` keeps this request:

```json
{"sessionType":"alert_checkin|manual_checkin|scheduled_checkin","triggerAlertId":"uuid|null"}
```

For a new-style session it returns HTTP 200:

```json
{
  "sessionId":"uuid",
  "sessionType":"alert_checkin",
  "status":"in_progress",
  "interactionPhase":"safety_check",
  "assistantText":"string",
  "safety":{"status":"awaiting_response","riskCodes":[],"supportResources":[]},
  "activeInterventions":[],
  "stateSnapshot":null,
  "reportStatus":"not_started",
  "inactivityTimeoutSeconds":3600,
  "legacy":false,
  "createdAt":"UTC ISO-8601",
  "startedAt":"UTC ISO-8601",
  "endedAt":null
}
```

- API2. `GET /api/sessions/{sessionId}` returns the same common new-session state fields, including required integer `inactivityTimeoutSeconds`, without requiring `assistantText`. It never returns `slots`, `missingSlots`, or `handoffReady`. A historical slot session returns `legacy=true`, `interactionPhase=null`, and its immutable status/timestamps/report status; mutating calls against it return HTTP 409 `legacy_session_read_only`.
- API3. `POST /api/sessions/{sessionId}/messages` keeps request `{ "content": "1..10000 characters" }` and returns:

```json
{
  "assistantText":"string",
  "phase":"safety_check|intervention_dialogue|completed|abandoned",
  "safety":{"status":"awaiting_response|clear|concern|urgent","riskCodes":["string"],"supportResources":[{"label":"string","contact":"string"}]},
  "activeInterventions":[{"id":"uuid","type":"approved type","status":"recommended|delivered|started|completed|skipped|failed","presentationOrder":1,"content":"string","createdAt":"UTC ISO-8601"}],
  "stateSnapshot":{"inferenceId":"uuid","scope":"realtime","state":"low|mid|high|unknown","confidence":null,"summaryStatus":"pending|ready|unavailable","summary":"string|null","createdAt":"UTC ISO-8601"},
  "reportStatus":"not_started|generating|ready|failed",
  "inactivityTimeoutSeconds":3600
}
```

`inactivityTimeoutSeconds` is required on every successful new-session create, get, message, and finish response. It is the current effective `system_settings.chat_timeout_seconds` in the existing 60..86400 range; if an administrator changes the setting during an active session, the next response carries the new effective value.

- API4. `POST /api/sessions/{sessionId}/assessments` retains its current request and HTTP 201 response `{ "assessmentId": "uuid" }`. It is optional; skipping sends no placeholder or zero score.
- API5. `POST /api/sessions/{sessionId}/finish` returns `{ "sessionId":"uuid", "status":"completed", "interactionPhase":"completed", "stateSnapshot":{...}, "reportStatus":"not_started|generating", "inactivityTimeoutSeconds":3600 }`. Repeating finish is idempotent and returns the stored terminal state with the effective timeout. Timeout produces `status=abandoned`, `interactionPhase=abandoned`, and `completion_reason=timeout`.
- API6. Automatic report creation makes `POST /api/sessions/{sessionId}/reports` an idempotent retry for a missing/failed consented report. `GET /api/sessions/{sessionId}/reports` returns only `reportId`, `version`, `status`, `createdAt`, and `generatedAt`; it never returns decrypted `content`.

### 8.2 Dashboard API

- API7. `GET /api/me/dashboard?range=24h|7d|30d` returns:

```json
{
  "range":"24h",
  "from":"UTC ISO-8601",
  "to":"UTC ISO-8601",
  "predictions":[{"predictionId":"uuid","at":"UTC ISO-8601","class":"low|mid|high|unknown","probability":0.0,"ppgPreviewAvailable":true}],
  "assessments":[{"assessmentId":"uuid","sessionId":"uuid","at":"UTC ISO-8601","instrumentCode":"AUQ","rawScore":0.0,"scaleMin":0.0,"scaleMax":56.0}],
  "events":[{"eventId":"uuid-or-stable-composite","type":"detection|notification|session_started|intervention|session_finished","at":"UTC ISO-8601","sessionId":"uuid|null","predictionId":"uuid|null","label":"string"}],
  "latestState":{"inferenceId":"uuid","scope":"realtime","state":"low|mid|high|unknown","confidence":0.0,"summaryStatus":"pending|ready|unavailable","summary":"string|null","createdAt":"UTC ISO-8601"},
  "longitudinalState":{"inferenceId":"uuid","scope":"longitudinal","state":"low|mid|high|unknown","confidence":0.0,"summaryStatus":"pending|ready|unavailable","summary":"string|null","createdAt":"UTC ISO-8601"},
  "reports":[{"sessionId":"uuid","reportId":"uuid","status":"generating|ready|failed","updatedAt":"UTC ISO-8601"}]
}
```

Nullable objects and probabilities are JSON `null`; empty series are `[]`. Class codes are mapped from each prediction's persisted `model_versions.output_schema`, not inferred from index alone.

- API8. `GET /api/me/predictions/{predictionId}/ppg-preview` requires ownership and a linked sensor recording with PPG. It returns at most 512 chronological points:

```json
{"predictionId":"uuid","windowStartedAt":"UTC ISO-8601","windowEndedAt":"UTC ISO-8601","samplingHz":51.2,"samples":[{"at":"UTC ISO-8601","value":0.0}]}
```

The backend decrypts/decompresses in memory, validates finite values and the prediction window, downsamples deterministically when needed, and returns no storage path or non-PPG modality.

- API9. `GET /api/admin/patients/{patientId}/dashboard?range=24h|7d|30d` returns the same trend, assessment, event, and report-status shapes plus state class/status metadata, but excludes `ppgPreviewAvailable`, raw PPG, and decrypted state `summary`. Administrator sensitive reveal adds `state_inference` to the existing `POST /api/admin/resources/{resourceType}/{resourceId}/reveal` allowlist and requires a nonblank reason.
- API10. Unsupported range returns HTTP 422 `invalid_dashboard_range`; unowned or unavailable prediction preview returns HTTP 404 `ppg_preview_not_found` without revealing whether another patient owns it.

### 8.3 Additive `0003` data contract

- DATA1. Add only revision `20260715_0003` with `down_revision="20260715_0002"`. Do not edit `0001`, `0002`, or `neurotruth_schema_v2_5.sql`, and do not backfill legacy rows.
- DATA2. Add `state_inferences`:
  - `id uuid PRIMARY KEY`
  - `patient_id uuid NOT NULL` -> `patient_profiles(user_id) ON DELETE RESTRICT`
  - `session_id uuid NULL`, with same-patient composite FK to `sessions(id,patient_id) ON DELETE SET NULL (session_id)`
  - `trigger_prediction_id uuid NULL`, with same-patient composite FK to `craving_predictions(id,patient_id) ON DELETE SET NULL (trigger_prediction_id)`
  - `inference_scope varchar(16) NOT NULL CHECK IN ('realtime','longitudinal')`
  - `state_class varchar(16) NOT NULL CHECK IN ('low','mid','high','unknown')`
  - `confidence numeric(6,5) NULL CHECK 0..1`
  - `evidence_refs jsonb NOT NULL DEFAULT '{}' CHECK object`
  - `payload_encrypted bytea NOT NULL`
  - `summary_encrypted bytea NULL`
  - `encryption_key_version varchar(32) NOT NULL`
  - `rule_version varchar(64) NOT NULL`
  - `summary_model_version_id uuid NULL REFERENCES model_versions(id) ON DELETE RESTRICT`
  - `summary_status varchar(16) NOT NULL CHECK IN ('pending','ready','unavailable')`
  - `created_at timestamptz NOT NULL DEFAULT now()`
  - indexes `(patient_id,created_at DESC)`, `(session_id,created_at)`, and `(trigger_prediction_id)`.
- DATA3. Rebuild `ck_model_versions_component` to retain every value accepted after `0002` and add `state_inference_agent`. Rule-only records use `model_name=deterministic-state-inference`, their code version in `model_version`, and no provider artifact.
- DATA4. Add nullable `sessions.interaction_phase varchar(32)` checked as NULL or `safety_check|intervention_dialogue|completed|abandoned`; existing rows remain NULL. Add nullable `dialogue_state_encrypted bytea` and `dialogue_state_key_version varchar(32)` with an all-null-or-all-present pair constraint. Every new session sets these fields and never sets them back to NULL.
- DATA5. The encrypted dialogue-state JSON schema is `{ "version":1, "questionBankVersion":"niaaa-samhsa-who-ko-v1", "askedTopicIds":[], "declinedTopicIds":[], "safety":{ "status":"awaiting_response|clear|concern|urgent", "riskCodes":[], "adminInvolvement":"not_offered|offered|accepted|declined" }, "firstInterventionSelected":false }`. It is non-clinical conversation control state, not a replacement patient questionnaire.
- DATA6. Add nullable `interventions.presentation_order smallint CHECK > 0` and `interventions.evidence_refs jsonb CHECK object`; add a partial unique index on `(session_id,presentation_order) WHERE presentation_order IS NOT NULL`. Existing intervention rows remain NULL. New rows require order and evidence references.
- DATA7. AES-GCM AAD follows existing `table/column/patient/record` construction for `state_inferences.payload_encrypted`, `state_inferences.summary_encrypted`, and `sessions.dialogue_state_encrypted`. The current keyring key ID is stored; decryption/authentication failure never falls back to plaintext.
- DATA8. `evidence_refs` contains only UUIDs/type tags and search-safe timestamps. Patient utterances, question answers, intervention rationale text, state payloads, and summaries remain encrypted.
- STATE1. New session transitions are `in_progress/safety_check -> in_progress/intervention_dialogue -> completed/completed` for manual finish and `in_progress/* -> abandoned/abandoned` for timeout. Safety concern does not force termination. Legacy rows with `interaction_phase=NULL` are immutable.
- STATE2. One active session per patient remains enforced. Session message handling must serialize per session; a concurrent second message returns HTTP 409 `message_in_progress` and the client does not auto-submit it.

## 9. Permissions, Security, Privacy, and Audit

- SEC1. All changed patient APIs require the existing active patient access JWT and ownership checks; administrator dashboard/reveal APIs require the administrator role and completed password change.
- SEC2. `ai_analysis` consent gates session creation and agent/state-summary calls. `notification` gates alert/AUQ/chat launch notifications but not persistence of a valid craving prediction. `report_generation` gates automatic report creation. Consent withdrawal blocks future activity and does not delete retained history.
- PRIV1. Messages, dialogue control state, intervention basis/content, state payload/summary, AUQ answers, and report content remain encrypted with AES-256-GCM. Only graph metadata and search/index fields defined in Section 8 remain plaintext.
- PRIV2. Patient dashboard state summaries may be decrypted for their owner. Administrator dashboard responses never decrypt sensitive text; administrator reveal requires a reason and uses `Cache-Control: no-store`.
- PRIV3. PPG preview is patient-only, contains at most the requested prediction window and 512 points, and is not cached by the backend. No administrator PPG endpoint is created.
- AUDIT1. Audit coded safety detections and involvement choice, dialogue/provider validation failure, state-summary failure, legacy mutation attempts, administrator state/report/message/intervention reveal reason, and forbidden cross-owner preview attempts without recording sensitive content or credentials.
- AUDIT2. Model provider errors are reduced to stable codes such as `dialogue_provider_error`, `dialogue_output_rejected`, `state_summary_provider_error`, and `report_generation_failed`; raw provider bodies and credentials never enter responses or audit metadata.

## 10. Error, Edge-Case, and Concurrency Behavior

- ERR1. Changed/new endpoints use `{"detail":{"code":"stable_code","message":"safe message"}}`. Authentication behavior remains the existing 401/403 contract.
- ERR2. Dialogue-provider or invalid-output failure persists the patient message once, audits a stable code, and returns a deterministic supportive response without a new question or unapproved intervention. Urgent deterministic safety content is never suppressed by provider failure.
- ERR3. State-summary failure commits the deterministic inference with `summary_status=unavailable`; report failure commits `status=failed`. Neither failure changes prediction class or session terminal state.
- ERR4. AES authentication failure, DB unavailability, or unavailable sensor ciphertext fails closed with 503 and no partial plaintext response. PPG preview uses 404 for missing/unowned/unavailable data.
- EDGE1. With no valid prediction, state is `unknown`, confidence is NULL, and AUQ is shown separately. Quality-gate failure is not displayed as low craving.
- EDGE2. Empty dashboard ranges return valid empty arrays and NULL latest states. Multiple predictions at the same timestamp order by `(timestamp,id)`; all event arrays use the same stable ordering.
- EDGE3. A corrected user statement may remove a topic ID from `declinedTopicIds` only after explicit correction, but it does not create a slot or a clinical fact not supported by messages.
- EDGE4. A manually finished session is idempotently `completed` even when AUQ was skipped or no intervention beyond the deterministic fallback was accepted. Timeout is always `abandoned`.
- CONC1. The repository uses a transaction/advisory row lock for message processing and intervention order. Only one message turn and one presentation order can commit at a time per session.
- CONC2. Report creation is unique per `(session_id,version)` and auto-queue/retry is idempotent. State inference creation records the concrete triggering evidence IDs so retry cannot duplicate the same scope/trigger event.

## 11. Dependencies and Configuration

- DEP1. Reuse FastAPI, PostgreSQL/Alembic, the existing AES-GCM keyring, Bedrock adapter, Android Compose/network/auth stack, and React web stack. No dependency upgrade is authorized.
- DEP2. Add local, versioned prompt/question-bank configuration for `intervention-dialogue-v1`, `state-summary-v1`, `state-rule-v1`, and `niaaa-samhsa-who-ko-v1`; register actual model and prompt versions through `model_versions`.
- DEP3. Continue using `system_settings.chat_timeout_seconds`, default 3600 and administrator configurable within the existing 60..86400 range, plus `system_settings.interventions_enabled`. When interventions are disabled, safety content and state inference continue, but no ordinary intervention row/text is produced.
- DEP4. Reuse `RPPG_ENABLED=false` as the backend capability flag. UI checks enabled-and-ready status; no second rPPG flag or new checkpoint is added.
- DEP5. The notice acknowledgement version is an Android constant `INTERVENTION_PRODUCT_NOTICE_VERSION=2026-07-15-v1` stored in Keystore-backed app preferences; it contains no health data.

## 12. Migration, Rollout, and Rollback

- MIG1. Apply additive Alembic `20260715_0003` only. Existing session, slot, memory, intervention, report, and rPPG rows are retained unchanged; no backfill runs.
- MIG2. `0003` downgrade must fail closed with a clear runtime error while new intervention-first data may exist. Operational rollback uses the previous application images against the forward-compatible additive schema.
- ROLL1. Deploy backend migration/backend and Android as one coordinated breaking release. Deploy administrator web immediately after backend. Keep rPPG disabled unless separately validated.
- ROLL2. Pre-release readiness requires fresh `0001 -> 0002 -> 0003` migration, upgrade of a populated `0002` database, backend tests, Android tests/build/lint, web build, and a full manual demo flow with fake/saved sensor input but no treatment-effect claim.
- BACK1. Roll back application images together to the prior backend/Android/web versions. Leave `0003` tables/columns in place; prior code ignores them. Do not delete or reverse-convert new encrypted data.
- BACK2. If the new dialogue flow must be stopped without image rollback, disable ordinary interventions in existing administrator settings and stop alert-driven session entry operationally; safety and retained records remain available.

## 13. Implementation Boundaries

### 13.1 Expected Change Areas

- Backend V2.5 session agent/service/routes/repository/runtime, a focused state-inference/dashboard service, administrator sensitive-resource support, and additive Alembic `0003`.
- Android authenticated session contract/view-model/chat flow, first-use notice, patient dashboard, and tests; Watch sensor collection itself remains unchanged.
- Administrator React dashboard and status-only report presentation, plus focused documentation/tests for the new public contract.

### 13.2 Forbidden Changes

- Do not edit `apps/backend/alembic/versions/20260715_0001_v25_baseline.py`, `apps/backend/alembic/versions/20260715_0002_dgx_rppg.py`, `neurotruth_schema_v2_5.sql`, `.env`, or `.gitignore`.
- Do not delete rPPG code/data, legacy slots/memory/reports, or the old model artifacts.
- Do not add slot writes, runtime NeuroSync imports, craving-model experiments, STT, self-event capture, demo replay, administrator raw PPG, or report-body UI.
- Do not change Watch firmware/data collection or the craving model's thresholds, labels, class mapping, cooldown, or warm-up behavior.
- No unrelated refactors, formatting churn, dependency upgrades, broad rewrites, or generated-file churn.

### 13.3 Minimal-Change Guidance

- Reuse authentication, consent, AES-GCM AAD, report queue, system settings, model registry, alert, and dashboard styling patterns.
- Preserve existing endpoints unless this spec explicitly changes their response or adds a route.
- Isolate legacy read paths from new-session write paths instead of rewriting legacy data.
- Split delegated workers by backend, Android, and administrator web ownership so they do not edit the same files.

## 14. Acceptance Criteria

- AC1. Given an acknowledged patient and a qualifying alert, when the patient selects `Talk now`, then a new authenticated session starts in `safety_check`, optional AUQ may be completed or skipped, and no slot row or slot response field is created.
- AC2. Given skipped AUQ, when the patient continues, then safety checking, first intervention, free dialogue, finish, state inference, and consented report generation remain available with no zero/placeholder assessment.
- AC3. Given immediate risk wording, when a message is handled, then deterministic 119/109 guidance appears inline before ordinary dialogue, a coded audit exists, no live contact is promised, and the user may continue.
- AC4. Given contextual evidence, when the first ordinary intervention is selected, then the deterministic priority in FR6 chooses one allowlisted type and the persisted row has presentation order, encrypted text/basis, evidence IDs, and actual version references.
- AC5. Given previously asked or declined topic guidance, when later turns are generated, then no paraphrased repeat question appears unless the patient explicitly corrects that topic; each assistant turn has at most one question.
- AC6. Given LLM output with diagnosis, treatment-effect/causal language, multiple questions, or an unapproved intervention, when validation/one repair fails, then a safe deterministic fallback is used and rejected text is not persisted as assistant content.
- AC7. Given any realtime or longitudinal inference, then its class is the latest valid persisted model class or `unknown`, evidence IDs are stored, encrypted payload survives summary failure, and AUQ/dialogue do not create a synthesized clinical score.
- AC8. Given a manual finish or one-hour default inactivity, then the session becomes respectively `completed/completed` or `abandoned/abandoned`, final state snapshots persist, and a consented report moves independently through `generating|ready|failed` using no slot evidence.
- AC9. Given a patient dashboard range, then Low/Mid/High, AUQ, event markers, report state, latest realtime state, and longitudinal state match persisted UTC evidence and render in local time; empty data has explicit empty states.
- AC10. Given an owned prediction with linked PPG, when the patient requests preview, then only that 10-second window and at most 512 finite points are returned; another patient and every administrator receive no PPG access.
- AC11. Given administrator dashboard use, then trend/status metadata is visible without decryption; message/state/intervention/report reveal requires a reason, returns no-store data, and creates an audit record.
- AC12. Given a pre-`0003` slot session, then it remains readable with `legacy=true`, has no backfill, and every mutation attempt returns `legacy_session_read_only`; new sessions never write slots or legacy memory.
- AC13. Given rPPG/STT/self-event features are not explicitly active, then their controls are absent and core PPG/GSR -> intervention -> DB -> dashboard behavior does not depend on them.
- AC14. Given the demo and all generated text, then no user-facing copy claims immediate craving reduction, CBT efficacy, diagnosis, treatment success, or causal effect.
- AC15. Given fresh and populated databases, when `0003` is applied, then constraints/indexes/FKs pass, `0001`/`0002`/baseline SQL hashes are unchanged, and previous rPPG plus V2.5 data remain readable.
- AC16. Backend, Android, and web validations in Section 15 pass without live LLM calls by using fake adapters; the manual acceptance flow completes from saved/live PPG/GSR prediction through both dashboards.
- AC17. Given any successful new-session create, get, message, or finish response, then required `inactivityTimeoutSeconds` equals the effective administrator setting and Android displays that value without a hardcoded one-hour assumption.

## 15. Validation Plan

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Spec pair | `python C:/Users/NeuroAI-Laptop/.codex/skills/feature-planner/scripts/validate_spec_pair.py docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.md` | English/Korean pair, IDs, handoff, and progress records match. |
| Backend unit/integration | From `apps/backend`: `.\.venv\Scripts\python.exe -m pytest -q` | Slot-free session, safety, interventions, state inference, dashboard, encryption, legacy, and regressions pass with fake Bedrock. |
| Fresh/populated migration | Run `alembic upgrade head` against isolated fresh DB and a `20260715_0002` fixture DB; inspect `alembic_version` and constraints. | Revision is `20260715_0003`; old data remains; no baseline file changed. |
| Android | From `apps/mobile`: `.\gradlew.bat testDebugUnitTest assembleDebug lintDebug` | Unit tests, debug APK, and lint pass for notice, optional AUQ, chat, dashboards, and hidden deferred UI. |
| Administrator web | From `apps/web`: `npm.cmd run build` plus focused component/API tests if the repository test harness exists. | Production build passes; no administrator PPG or report body is rendered. |
| Static contract scan | Search source/UI output for `slots`, `missingSlots`, `handoffReady`, treatment claims, and rPPG visibility in new-session paths. | Removed fields are absent from new contracts; legacy reads are isolated; prohibited claims are absent. |
| Security | Fake ciphertext/AAD tamper, cross-owner PPG, reasonless reveal, and provider-error tests. | Fail closed, no plaintext/path/credential leak, and required audit records exist. |
| Manual demo | Use a test patient and saved/live PPG/GSR input: alert -> approval -> skip/submit AUQ -> safety -> intervention -> finish -> patient/admin dashboards. | End-to-end flow completes; class trend is descriptive and no immediate reduction or treatment-effect claim appears. |
| Diff integrity | `git diff --check` and before/after SHA-256 for `.env`, `.gitignore`, `0001`, `0002`, and baseline SQL. | No whitespace errors and all forbidden-file hashes are unchanged. |

## 16. Risks and Open Notes

- RISK1. Free dialogue can still repeat semantic content despite a ledger. Mitigate with topic IDs, full history, server validation, one repair, and focused Korean paraphrase tests.
- RISK2. A categorical model class and AUQ may disagree. Present both as separate evidence and record the mismatch implicitly in the state payload; never average them into a new score.
- RISK3. Android charts may become dense at 30 days. Use step aggregation only for rendering while preserving every server event and allow accessible labels.
- RISK4. Historical sensor files may not contain usable PPG or may use older shapes. Return `ppg_preview_not_found` after safe validation; do not infer or substitute data.
- RISK5. The research question-bank sources guide content but do not make the agent a clinician. Keep paraphrases non-diagnostic, versioned, and reviewed before demo use.
- RISK6. This release intentionally breaks session response fields. Coordinated backend/Android deployment and rollback are mandatory.

## 17. Implementation Checklist / Progress Record

This section is updated by the main orchestrating agent during implementation. Update the English source first and the Korean mirror second.

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Finalize English source and synchronized Korean mirror | Spec worker | Complete | `docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.md`; `.ko.md` | `validate_spec_pair.py` | Decision-complete implementation handoff. |
| P2 | Add `0003`, intervention-first backend, state inference, dashboards, security, and tests | Backend worker | Complete | `apps/backend/alembic/versions/20260715_0003_intervention_first.py`; `apps/backend/app/v25/`; focused backend tests | Full backend: 162 passed; focused suites: 70 passed and 43 passed; compile check passed; actual PostgreSQL fresh `0001 -> 0003` and populated `0002 -> 0003` migrations passed with legacy `interaction_phase=NULL` | `0001`, `0002`, and baseline SQL were not edited. |
| P3 | Implement Android notice, optional AUQ/chat contract, patient dashboard, and tests | Android worker | Complete | `AuthenticatedSessionApi.kt`; `InterventionNotice.kt`; `PatientDashboard.kt`; related view-model/UI/tests | Independent Android run: 61/61 unit tests passed; assemble passed; lint passed with 0 errors; debug APK produced | Preserves Watch collection; live PPG remains local. |
| P4 | Implement restricted administrator dashboard, hidden deferred UI, and build checks | Web worker | Complete | `apps/web/src/App.js`; `apps/web/src/api.js`; `apps/web/src/styles.css` | Production web build passed | No administrator raw PPG or report body. |
| P5 | Inspect minimal diffs, run final validations/migrations, check prohibited claims and forbidden-file hashes, synchronize progress | Main agent | Complete | Verification only; spec progress updates | Backend full/focused/compile, actual fresh and populated PostgreSQL migrations, Android tests/assemble/lint/APK, web production build, final spec validator, and protected-hash checks passed | Second and final read-only re-review found no unresolved issues; protected `.env`, `.gitignore`, `0001`, `0002`, and baseline SQL hashes were unchanged. No live paid LLM call was required. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.2 | 2026-07-15 | Feature Planner | Marked implementation complete after final backend, PostgreSQL, Android, web, integrity, read-only review, and spec-pair validation. |
| 1.1 | 2026-07-15 | Feature Planner | Added server-authoritative `inactivityTimeoutSeconds` response contract and recorded implementation/validation progress pending final review. |
| 1.0 | 2026-07-15 | Feature Planner | Initial finalized intervention-first redesign spec pair. |
