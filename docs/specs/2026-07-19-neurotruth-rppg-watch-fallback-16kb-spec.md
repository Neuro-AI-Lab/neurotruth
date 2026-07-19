# NeuroTruth Watch-Optional rPPG Fallback and Android 16KB Support — Living Implementation Specification

<!-- feature-planner-control
{
  "workflow": "feature-planner/v7",
  "state": "complete",
  "source_spec": "docs/specs/2026-07-19-neurotruth-rppg-watch-fallback-16kb-spec.md",
  "korean_mirror": "docs/specs/2026-07-19-neurotruth-rppg-watch-fallback-16kb-spec.ko.md",
  "spec_revision": 7,
  "reviewed_revision": 7,
  "selected_strategy": "STRAT-1",
  "implementation_direction": "preserve",
  "direction_decision_id": null,
  "minimal_change_policy": "strict",
  "final_domain_gate": "confirmed_none",
  "open_question_ids": [],
  "active_slices": [],
  "next_action": "none"
}
-->

> This English document is authoritative for implementation. The Korean file is the synchronized review mirror. The user reviewed the complete plan in chat and explicitly authorized implementation of this revision.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `complete`, revision 7, reviewed revision 7 |
| Outcome | A patient without a connected Wear OS node gets a primary, consent-gated 20-second camera-rPPG action; a connected patient keeps Watch monitoring and optional rPPG. Android native libraries pass 16KB compatibility checks. |
| Recommended implementation | `STRAT-1` — extend the existing Android ViewModel/Compose flow, reuse the current rPPG job pipeline, update build tooling, and change the existing rPPG flag default. |
| Planned production targets | `apps/mobile/app/src/main/java/com/example/healthsensor/WatchConnectionMonitor.kt::file-level`; `apps/mobile/app/src/main/java/com/example/healthsensor/WearDataListenerService.kt::onPeerConnected, onPeerDisconnected`; `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt::SensorViewModel`; `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt::UserHomeScreen, UserDashboardScreen`; `apps/mobile/app/src/main/java/com/example/healthsensor/RppgCameraScreen.kt::capture duration/countdown`; `apps/backend/app/core/config.py::SecuritySettings.rppg_enabled` |
| Expected additions | New production files: apps/mobile/app/src/main/java/com/example/healthsensor/WatchConnectionMonitor.kt; dependencies: None; shared abstractions: None |
| Work plan | `WS1`, `WS2`, reopened `WS3`, and final-correction `WS4` are verified. |
| Open questions | None |
| Agent decisions to review | None. |
| Last material change | Revision 7 — re-review and repository search disproved the premature WS3 parity claim by finding stale default-off wording in backend, development, and current AI documents; reopened the bounded docs slice. |

## 2. Outcome and Scope

### Outcome

Patients who do not own or currently connect a Wear OS watch can explicitly run the existing 20-second camera-rPPG measurement from a prominent Home action. Patients with a connected watch continue to receive Watch sensor predictions and may still run rPPG as an optional point-in-time measurement. The Phone APK no longer contains the known non-16KB-compatible CameraX or ML Kit native binaries.

### In Scope

- Determine Watch availability from an initial Wearable Data Layer connected-node query and existing listener-service peer events, independently of sensor sample freshness.
- Present distinct checking, connected, disconnected, and error states and make rPPG primary only when disconnected.
- Preserve all four existing rPPG consent checks and the current camera job/prediction routing.
- Enable rPPG by default while retaining readiness checks and the `RPPG_ENABLED` flag.
- Upgrade AGP, Gradle, ML Kit, and the existing CameraX family to the minimum verified 16KB-compatible versions.
- Update the active root/mobile/backend/web/development/API/product documentation and verify Android, backend, public API, APK alignment, and physical-device behavior.

### Out of Scope / Non-Goals

- No background camera capture, automatic repeated measurement, disconnect notification, or sensor-staleness timeout.
- No new backend endpoint, response field, database migration, Alembic revision, prediction model, or model input.
- No change to rPPG encryption, video-retention consent, Watch prediction relay policy, or latest-result ordering.
- No unrelated cleanup of the current backend restructure or existing dirty working tree.

### Users and Primary Flow

1. An authenticated patient grants biosignal, AI analysis, camera-rPPG, and face-video-retention consent.
2. The Phone queries and listens to the Wear OS connected-node list.
3. With no connected node, Home explains the fallback and presents a primary 20-second face measurement action; with a node, Watch monitoring remains primary and rPPG remains secondary.
4. A successful camera job follows the existing `/api/rppg/jobs` flow and publishes a `camera_rppg` Phone prediction. A later Watch prediction replaces it as the newest prediction.

### Current Assumptions and Constraints

- “Watch disconnected” means `connectedNodes.isEmpty()`, not absent sensor packets (D-004).
- rPPG is a foreground, user-initiated point measurement, not continuous detection (D-003).
- The existing backend/DGX service must report ready; otherwise the UI must state that fallback measurement is unavailable (D-006).
- The current uncommitted restructure is user-owned and must be preserved.

## 3. Repository Pattern Baseline

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Wear connectivity | Phone-to-Watch delivery already queries `Wearable.getNodeClient(context).connectedNodes`. | `apps/mobile/app/src/main/java/com/example/healthsensor/PhonePredictionSender.kt::sendPrediction` | Reuse Play Services Wearable and add no connectivity dependency. |
| Phone state | `SensorViewModel` exposes `StateFlow` values consumed by Compose. | `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt` | Keep Android state ownership in the ViewModel and UI rendering in Compose. |
| rPPG routing | Home opens the camera; completed jobs convert to `CravingPrediction`; camera predictions stay on Phone. | `MainActivity.kt::UserHomeScreen`; `RppgJobResult.toPrediction`; `SensorViewModel.handleCameraPrediction` | Reuse the job, persistence, alert, and latest-prediction flow without a second prediction path. |
| Consent/readiness | `MobileAuthState.canCaptureRppg` combines four consents and `RppgServiceStatus.canStart` gates capture. | `MobileAuthRuntime.kt`; `RppgModels.kt` | Do not weaken privacy or provider-readiness gates. |
| Android build | AGP 8.2.2, Gradle 8.2, CameraX 1.3.2, and bundled ML Kit face detection 16.1.6 produce the observed 16KB warnings. | `apps/mobile/build.gradle`; `gradle-wrapper.properties`; `apps/mobile/app/build.gradle`; final APK ELF inspection | Preserve SDK 34 and dependency families while upgrading only required versions. |
| Backend flag | rPPG startup is controlled by `SecuritySettings.rppg_enabled`. | `apps/backend/app/core/config.py`; `apps/backend/app/core/runtime.py` | Change only the default; retain validation and readiness behavior. |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | Wearable `NodeClient` dependency | `apps/mobile/app/build.gradle`; `PhonePredictionSender.kt` | Query initial nodes and subscribe to node-list changes. |
| R-002 | `SensorViewModel` StateFlow pattern | `SensorViewModel.kt` | Expose `WatchConnectionState` and clear sensor-receiving state on confirmed disconnect. |
| R-003 | Existing Home rPPG action | `MainActivity.kt::UserDashboardScreen` | Change emphasis and supporting copy without adding navigation. |
| R-004 | Existing consent and readiness gates | `MobileAuthState.canCaptureRppg`; `RppgServiceStatus.canStart` | Determine whether the CTA is actionable and explain unavailable states. |
| R-005 | Existing camera prediction route | `SensorViewModel.handleCameraPrediction`; `RppgPredictionRoutingPolicy` | Keep Phone-only `camera_rppg` publication and latest-result behavior. |
| R-006 | Existing Android contract test style | `apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt` | Add focused pure-state and monitor lifecycle tests. |

## 4. Decisions and Questions

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | Existing flow | Camera rPPG already feeds the Phone’s main prediction path and never relays to Watch. | repository | `MainActivity.kt`; `SensorViewModel.handleCameraPrediction`; `RppgPredictionRoutingPolicy.relayToWatch=false` | The feature needs UI/connectivity work, not a new backend prediction path. | not-required | resolved |
| D-002 | 16KB | Upgrade AGP to 8.5.2, Gradle to 8.7, and ML Kit face detection to 16.1.7 without a legacy-packaging workaround. | user | The reviewed plan follows Android and ML Kit 16KB guidance. | Removes the known incompatible face-detection binaries while keeping SDK 34. | confirmed | resolved |
| D-003 | Fallback UX | On confirmed disconnect, show an immediate primary CTA; capture remains a user-started 20-second foreground action with no notification. | user | Selected during refinement. | No surprise camera launch or background capture. | confirmed | resolved |
| D-004 | Connection definition | Use an empty Wear connected-node list; do not use a sensor freshness timeout. | user | The user clarified that the concern is patients without a Watch, not transient packet gaps. | Separates hardware connection from `isReceiving`. | confirmed | resolved |
| D-005 | Consent | Keep biosignal, AI, camera-rPPG, and face-video-retention consent requirements. | user | Selected during refinement. | Preserves current privacy and API contracts. | confirmed | resolved |
| D-006 | Configuration | Keep the flag/readiness checks but change the rPPG default to enabled. | user | Selected during refinement so the fallback is available by default in ready deployments. | Updates code/default documentation without removing operational shutdown. | confirmed | resolved |
| D-007 | Internal state | Use a nonexistent NodeClient connected-node listener for live updates. | agent | The initial plan assumed NodeClient exposed list-change registration. | This target is invalid for `play-services-wearable:18.1.0`. | overridden | superseded |
| D-008 | Implementation authorization | Implement this exact reviewed plan on top of the current working tree. | user | The user explicitly requested “PLEASE IMPLEMENT THIS PLAN”. | Sets revision 1 to ready and authorizes all mapped slices. | confirmed | resolved |
| D-009 | Live connection events | Keep `NodeClient.connectedNodes` for the initial/refresh query and reuse `WearDataListenerService.onPeerConnected/onPeerDisconnected` to trigger refreshes; the monitor unregisters its local app listener at ViewModel teardown. | agent | The installed Wearable 18.1.0 `NodeClient` has no connected-list listener, while the existing system-managed `WearableListenerService` owns peer callbacks. | Adds the existing service and manifest to WS1 instead of polling, sensor timeouts, CapabilityClient resources, or a new dependency. | confirmed | resolved |
| D-010 | Settings test | Extend the existing generic `SecuritySettings` test target because default-enabled rPPG now requires the fixture to declare storage or opt out, and add explicit default/override evidence. | repository | `tests/test_security_v25.py::test_settings_require_secure_material_and_guard_http` fails after D-006 because it omits `RPPG_STORAGE_ROOT`. | Adds one test file to WS2 without changing production behavior. | not-required | resolved |
| D-011 | Wear listener registration | Keep the existing filtered `MESSAGE_RECEIVED` service registration and do not add deprecated `BIND_LISTENER`; the registered `WearableListenerService` still owns peer callbacks. | repository | AGP 8.5.2 lint rejects `BIND_LISTENER` because it wakes the app for every Wear event; official listener-service documentation includes connected/disconnected lifecycle events. | Removes `AndroidManifest.xml` from WS1 and preserves the current filtered service registration. | not-required | resolved |
| D-012 | rPPG status retry | A non-ready or unconfirmed service state must show a status-retry action, not a measurement-start action; consent-required remains disabled. | repository | Existing status-fetch failure collapses to `RppgServiceStatus(false,false,false)`, which is indistinguishable from an explicit disabled response in the dashboard policy. | Avoids a false “server switched off” claim and lets the existing `prepareCapture()` path refresh status without opening the camera. | not-required | resolved |
| D-013 | CameraX 16KB dependency | Upgrade the existing CameraX family from 1.3.2 to 1.4.0 while preserving SDK 34 and the same four declared CameraX artifacts. | repository | Final APK inspection shows ML Kit 16.1.7 `libface_detector_v2_jni.so` at `p_align=2**14`, but CameraX 1.3.2 `libimage_processing_util_jni.so` remains `p_align=2**12` on ARM64. | Extends the existing version edit by one line; no dependency family, API, or packaging workaround is added. | not-required | resolved |
| D-014 | Active documentation parity | Change current operational docs that still call rPPG default-off; initially treat PRDs and prior specifications as historical records. | repository | Repository-wide search found stale statements in `apps/backend/README.md`, `docs/dev-environment.md`, and `apps/web/README.md` after the code and deployment default changed to true. | This initial boundary was too narrow because the bilingual PRD is a current product contract. | overridden | superseded |
| D-015 | Capture timing | Use one internal 20-second capture constant for both the visible countdown and controller stop deadline. | repository | Final review found the UI counted down 10 seconds while `RECORDING_MS` remained 20,000 ms. | The user sees an accurate countdown and a focused contract test prevents duration drift. | not-required | resolved |
| D-016 | Product-document parity | Treat the bilingual PRD as current and align its Watch-optional flow, point-in-time limit, default-on flag, off switch, and fail-closed readiness wording; keep prior dated implementation specs historical. | repository | The PRD is the active product summary and still described Watch as the only flow and rPPG as default-off. | Removes contradictory current product guidance without rewriting prior implementation records. | not-required | resolved |
| D-017 | Repository-wide active docs | Reopen WS3 and align all current backend/development/AI rPPG summaries; retain prior dated feature specifications and planning snapshots as historical evidence. | repository | Re-review found two stale statements in backend README and one in development docs; repository search found the same combined STT/rPPG default-off wording in current AI docs. | Removes remaining contradictions while preserving intentionally historical records. | not-required | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | Fallback trigger | Choose manual CTA, CTA plus notification, or automatic foreground entry. | Changes camera/privacy behavior. | Manual immediate CTA. | D-003 | answered | User selected immediate CTA. |
| Q-002 | Connection definition | Choose sensor timeout or actual Wear node connection. | Changes false-disconnect behavior and the target user population. | Actual connected-node list. | D-004 | answered | User corrected the timeout assumption and selected Watch connection. |
| Q-003 | Consent | Preserve retention consent or redesign for transient video. | Changes privacy, storage, and API scope. | Preserve existing consent. | D-005 | answered | User selected the existing consent contract. |
| Q-004 | Feature default | Enable rPPG by default or require deployment opt-in. | Determines whether watchless fallback is usable out of the box. | Default enabled with flag/readiness retained. | D-006 | answered | User selected default ON. |
| Q-005 | Live connection events | Choose the existing listener-service peer callbacks or an app-capability declaration for live refreshes. | The reviewed NodeClient-listener API does not exist; the choice changes exact Android targets and connection semantics. | Reuse `WearDataListenerService` peer callbacks and preserve generic connected-node semantics. | D-009 | answered | User approved the existing-service recommendation. |

## 5. Requirements and Acceptance Criteria

### Functional Requirements

- **FR-001:** Android must expose checking, connected, disconnected, and error Watch states from an initial connected-node query and refreshes triggered by existing `WearDataListenerService` peer callbacks.
- **FR-002:** Home must show a primary rPPG CTA only for confirmed disconnect; connected users retain an optional rPPG action, and checking/error states must not falsely claim disconnection.
- **FR-003:** Consent failure must remain non-actionable; provider-readiness or unconfirmed status must show an accurate status-retry action rather than a measurement-start action or a false fallback claim.
- **FR-004:** Camera results must continue through the current `camera_rppg` Phone prediction path, remain off Watch, and yield to later Watch predictions.
- **FR-005:** `RPPG_ENABLED` must default to `true` while retaining the environment override, validation, status endpoint, and runtime readiness checks.
- **FR-006:** The Phone build must use AGP 8.5.2, Gradle 8.7, CameraX 1.4.0, and ML Kit face detection 16.1.7 with compile/target SDK 34 unchanged.
- **FR-007:** Active root/mobile/backend/web/development/API/product documentation must describe Watch-optional behavior where relevant and consistently describe point-in-time limits, consent/readiness, source routing, default enablement, and unchanged public endpoints.
- **FR-008:** The visible rPPG countdown and camera controller stop deadline must derive from the same 20-second capture contract.

### Non-Functional Requirements

- **NFR-001:** Preserve current public URLs, methods, response shapes, model behavior, database schema, Alembic history, encryption, and video retention.
- **NFR-002:** Remove the monitor’s app-local event subscription when the owning ViewModel is cleared, leave the Wearable listener service lifecycle system-managed, and do not infer disconnect from sensor timing.
- **NFR-003:** Add no production dependency, compatibility bypass, broad refactor, or unrelated cleanup.
- **NFR-004:** Preserve existing user changes and keep Android connection errors distinct from confirmed disconnection.

### Acceptance Criteria

- **AC-001:** Unit tests prove zero nodes, one-or-more nodes, query failure, listener updates, listener removal, and the CTA/consent/readiness state matrix.
- **AC-002:** Phone unit tests and lint plus Phone/Wear debug builds pass without reducing the current baseline.
- **AC-003:** The Phone APK passes 16KB zip/ELF alignment checks and installs on SM-S926N without the prior compatibility warning.
- **AC-004:** On physical devices, disconnecting SM-L320 changes Home without an app restart, permits a ready/consented 20-second rPPG job, and reconnecting restores Watch monitoring while retaining optional rPPG.
- **AC-005:** Backend compile/pytest and the existing API-structure/OpenAPI checks pass with no API or migration change.
- **AC-006:** The English/Korean specs and root/mobile/API documentation agree on the final behavior and limitations.
- **AC-007:** Backend tests prove default-enabled rPPG, explicit `RPPG_ENABLED=false`, and unrelated secure-settings construction without relying on ambient `.env` values.

### Edge and Failure Cases

- Initial query pending → show “checking” rather than connected/disconnected; keep rPPG availability visible but not promoted as a confirmed fallback.
- Node query/listener error → show connection-check failure; allow rPPG only when consent and service readiness independently permit it.
- Empty node list → clear `isReceiving`, show Watch absent/disconnected, and promote rPPG.
- Consent missing → explain all required consent categories and do not open the camera.
- rPPG disabled/unavailable/model not loaded → explain service unavailability and keep Watch behavior unaffected.
- Watch reconnects during or after camera work → do not cancel the accepted camera job; the next successful Watch prediction may become latest.

## 6. Implementation Strategy and Direction

### STRAT-1 — Extend the Existing Phone State and Listener Service

- **Direction:** `preserve`
- **Current approach:** Add one local monitor for initial `NodeClient` queries, reuse the existing `WearDataListenerService` peer callbacks as refresh events, expose the state from `SensorViewModel`, and let the existing Home composable choose rPPG copy/emphasis. Update only the existing build/config defaults and documentation.
- **Existing flow to reuse:** R-001 through R-006.
- **Why this is minimal:** The backend rPPG path already satisfies the data flow and the Phone already has a manifest-declared Wearable listener service. Extending that owner avoids polling, CapabilityClient resources, or a new dependency.
- **Behavior-preserving limitations:** The fallback is a user-triggered point measurement and is unavailable when consent or the rPPG service is not ready.
- **Explicit exclusions:** No new endpoint, storage path, local rPPG model, background camera, timeout detector, notification, connection framework, or backend refactor.
- **Compatibility and migration posture:** No DB migration or public API change. Rollback restores the prior build versions, flag default, and Android UI/monitor files; persisted data is unaffected.
- **Direction approval:** None required; all changes preserve the nearest project owners and dependencies.
- **Open-question sensitivity:** None.

### Material Alternatives Considered

| Strategy | Direction | Benefit | Additional code or risk | Decision |
| --- | --- | --- | --- | --- |
| Sensor staleness timeout | preserve | Detects a connected Watch that stopped streaming. | Does not match the user’s watchless-user requirement and can false-trigger during radio gaps. | rejected |
| Automatic/background camera fallback | user-approved-divergence | Could appear more continuous. | Privacy, permission, battery, foreground-service, and UX expansion; camera still needs face positioning. | rejected |
| New backend fallback endpoint/model | user-approved-divergence | Separate orchestration. | Duplicates the existing rPPG job/prediction path and changes public contracts. | rejected |

## 7. Modification Map and Change Budget

### Modification Map

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/WatchConnectionMonitor.kt` | file-level | add | `PhonePredictionSender.kt::sendPrediction` | Wrap initial/refresh connected-node queries, app-local peer-event subscription, four-state mapping, close cleanup, and local presentation policy/test seam. | Implements FR-001, FR-002, NFR-002, and AC-001. | WS1 | preserve |
| CH-002 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt` | `SensorViewModel` | extend | existing `StateFlow` ownership and `onCleared` | Own/expose the monitor state, clear `isReceiving` on disconnect, and close the monitor. | Implements FR-001, NFR-002, and AC-004. | WS1 | preserve |
| CH-003 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt` | `UserHomeScreen, UserDashboardScreen` | extend | existing Home rPPG button and `UserStatusTile` | Render accurate connection state, fallback explanation, primary/secondary CTA, and unavailable guidance. | Implements FR-002, FR-003, FR-004, and AC-004. | WS1 | preserve |
| CH-004 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt` | `RppgContractsTest` | extend | existing rPPG policy/contract tests | Add monitor lifecycle/state and CTA gating cases. | Proves AC-001 and FR-004. | WS1 | preserve |
| CH-005 | config | `apps/mobile/build.gradle` | Android plugin version | edit | existing plugins block | Change AGP 8.2.2 to 8.5.2. | Implements FR-006 and AC-003. | WS2 | preserve |
| CH-006 | config | `apps/mobile/gradle/wrapper/gradle-wrapper.properties` | `distributionUrl` | edit | existing Gradle wrapper | Change Gradle 8.2 to 8.7. | Implements FR-006 and AC-003. | WS2 | preserve |
| CH-007 | config | `apps/mobile/app/build.gradle` | CameraX and ML Kit dependency versions | edit | existing camera dependencies | Change CameraX 1.3.2 to 1.4.0 and face detection 16.1.6 to 16.1.7. | Implements FR-006 and AC-003. | WS2 | preserve |
| CH-008 | production | `apps/backend/app/core/config.py` | `SecuritySettings.rppg_enabled` | edit | existing feature flag | Change only the default from false to true. | Implements FR-005 and NFR-001. | WS2 | preserve |
| CH-009 | config | `.env.example` | `RPPG_ENABLED` | edit | existing environment template | Publish `RPPG_ENABLED=true`. | Implements FR-005 and AC-006. | WS2 | preserve |
| CH-010 | docs | `README.md` | rPPG overview/configuration | edit | existing feature/config sections | Document default-on Watch-optional rPPG and readiness limitation. | Implements FR-007 and AC-006. | WS3 | preserve |
| CH-011 | docs | `apps/mobile/README.md` | Phone feature/validation sections | edit | existing rPPG and Watch descriptions | Document connection-state fallback and 16KB prerequisites. | Implements FR-007 and AC-006. | WS3 | preserve |
| CH-012 | docs | `apps/mobile/docs/PHONE_APP.md` | Home/rPPG/Watch flow | edit | existing Phone flow | Document UI state matrix, consent/readiness, and point-in-time behavior. | Implements FR-007 and AC-006. | WS3 | preserve |
| CH-013 | docs | `apps/mobile/SERVER_API_SPEC.md` | patient rPPG section | edit | existing endpoint contract | State that Android chooses Watch/rPPG presentation client-side and endpoints remain unchanged. | Implements FR-007, NFR-001, and AC-006. | WS3 | preserve |
| CH-017 | docs | `apps/backend/README.md` | optional rPPG configuration | edit | existing rPPG default paragraph | Replace the stale default-off statement with default-on, explicit off-switch, and readiness-gate wording. | Implements FR-007 and AC-006. | WS3 | preserve |
| CH-018 | docs | `docs/dev-environment.md` | rPPG environment and deployment guidance | edit | existing sample environment and rPPG section | Publish the true default and preserve explicit disable/readiness guidance. | Implements FR-007 and AC-006. | WS3 | preserve |
| CH-019 | docs | `apps/web/README.md` | camera rPPG administration | edit | existing retention/default bullet | Align the active administrator documentation with default-on plus fail-closed readiness behavior. | Implements FR-007 and AC-006. | WS3 | preserve |
| CH-020 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgCameraScreen.kt` | capture duration/countdown | edit | existing countdown and `RECORDING_MS` | Derive UI countdown and stop deadline from one 20-second constant. | Implements FR-008 and resolves the final review finding. | WS4 | preserve |
| CH-021 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt` | capture duration contract | extend | existing rPPG contract tests | Assert the shared capture contract remains 20 seconds. | Proves FR-008. | WS4 | preserve |
| CH-022 | docs | `docs/prd/PRD_neurotruth.md` | product summary/non-goals/flow/deployment | edit | current English PRD | Align Watch-optional behavior and default/readiness policy. | Implements FR-007 and AC-006. | WS4 | preserve |
| CH-023 | docs | `docs/prd/PRD_neurotruth.ko.md` | product summary/non-goals/flow/deployment | edit | current Korean PRD mirror | Mirror the English Watch-optional and default/readiness policy. | Implements FR-007 and AC-006. | WS4 | preserve |
| CH-024 | docs | `docs/ai/README.md` | implemented optional capabilities | edit | current AI architecture summary | Separate default-off STT from default-on readiness-gated rPPG. | Implements FR-007 and AC-006. | WS3 | preserve |
| CH-025 | docs | `docs/ai/README.ko.md` | implemented optional capabilities | edit | current Korean AI architecture summary | Mirror CH-024. | Implements FR-007 and AC-006. | WS3 | preserve |
| CH-026 | docs | `docs/ai/agents/README.md` | capability status summary | edit | current agent overview | Separate STT and rPPG defaults while preserving TTS/deferred scope. | Implements FR-007 and AC-006. | WS3 | preserve |
| CH-027 | docs | `docs/ai/agents/README.ko.md` | capability status summary | edit | current Korean agent overview | Mirror CH-026. | Implements FR-007 and AC-006. | WS3 | preserve |
| CH-028 | docs | `docs/ai/agents/03_handoff_agent.md` | current capability note | edit | current handoff-agent page | Correct the combined default-off capability statement. | Implements FR-007 and AC-006. | WS3 | preserve |
| CH-029 | docs | `docs/ai/agents/03_handoff_agent.ko.md` | current capability note | edit | current Korean handoff-agent page | Mirror CH-028. | Implements FR-007 and AC-006. | WS3 | preserve |
| CH-014 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/WearDataListenerService.kt` | `onPeerConnected, onPeerDisconnected` | extend | `WearDataListenerService.onMessageReceived` | Publish peer connection changes to the monitor’s local refresh event owner without changing sensor parsing. | Implements FR-001, NFR-002, and AC-004. | WS1 | preserve |
| CH-016 | test | `apps/backend/tests/test_security_v25.py` | `SecuritySettings` tests | extend | `test_settings_require_secure_material_and_guard_http` | Isolate the unrelated security test from the new rPPG default and prove default true plus explicit false. | Proves FR-005, AC-005, and AC-007. | WS2 | preserve |

### Change Budget

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 5 | 4 | 1 | 230 | None | None |
| WS2 | 6 | 1 | 0 | 2 | None | None |
| WS3 | 13 | 0 | 0 | 0 | None | None |
| WS4 | 4 | 1 | 0 | 4 | None | None |

The production-line budget is an expansion alarm, not a compression target. `WatchConnectionMonitor` may contain a local injectable source seam required by AC-001; it must not become a shared connectivity framework.

## 8. Work Plan

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | Add lifecycle-safe Watch state and rPPG fallback presentation with focused tests. | None | PG1 | CH-001, CH-002, CH-003, CH-004, CH-014 | `apps/mobile/app/src/main/java/com/example/healthsensor/WatchConnectionMonitor.kt`, `apps/mobile/app/src/main/java/com/example/healthsensor/WearDataListenerService.kt`, `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt`, `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt`, `apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt` | backend, Gradle/config, docs, Wear OS source, Android manifest | FR-001, FR-002, FR-003, FR-004, NFR-002, NFR-003, NFR-004, AC-001, AC-004 | `apps/mobile/gradlew.bat :app:testDebugUnitTest` | verified |
| WS2 | Apply reviewed 16KB versions and default-enable rPPG. | None | PG1 | CH-005, CH-006, CH-007, CH-008, CH-009, CH-016 | `apps/mobile/build.gradle`, `apps/mobile/gradle/wrapper/gradle-wrapper.properties`, `apps/mobile/app/build.gradle`, `apps/backend/app/core/config.py`, `.env.example`, `apps/backend/tests/test_security_v25.py` | Android Kotlin/UI/tests, docs, runtime behavior | FR-005, FR-006, NFR-001, NFR-003, AC-002, AC-003, AC-005, AC-006, AC-007 | Android assemble plus focused backend settings tests | verified |
| WS3 | Synchronize active root/mobile/backend/web/development/API/AI documentation with the reviewed behavior. | None | PG1 | CH-010, CH-011, CH-012, CH-013, CH-017, CH-018, CH-019, CH-024, CH-025, CH-026, CH-027, CH-028, CH-029 | `README.md`, `apps/mobile/README.md`, `apps/mobile/docs/PHONE_APP.md`, `apps/mobile/SERVER_API_SPEC.md`, `apps/backend/README.md`, `docs/dev-environment.md`, `apps/web/README.md`, `docs/ai/README.md`, `docs/ai/README.ko.md`, `docs/ai/agents/README.md`, `docs/ai/agents/README.ko.md`, `docs/ai/agents/03_handoff_agent.md`, `docs/ai/agents/03_handoff_agent.ko.md` | production/test/config files, PRDs owned by WS4, historical dated specs, planning snapshots, and spec pair | FR-007, NFR-001, AC-006 | `git diff --check` and repository-wide active-default/endpoint cross-check | verified |
| WS4 | Resolve final countdown and current-PRD parity findings. | WS1, WS3 | PG2 | CH-020, CH-021, CH-022, CH-023 | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgCameraScreen.kt`, `apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt`, `docs/prd/PRD_neurotruth.md`, `docs/prd/PRD_neurotruth.ko.md` | backend, build/config, other docs, prior implementation specs | FR-007, FR-008, AC-002, AC-006 | focused Phone unit tests and `git diff --check` | verified |

### Parallelization Rationale

PG1 targets are disjoint: WS1 owns Android Kotlin/tests, WS2 owns build/config and one isolated backend default, and WS3 owns documentation. Public endpoint and state contracts are already fixed in this reviewed revision. The main agent integrates and runs build/API/device checks after all three are reviewed.

### Final Integration

Run spec scope/budget checks per slice, Android unit/lint/build tasks, backend compile/pytest and API-structure tests, APK 16KB alignment inspection, then Phone/Watch disconnect–rPPG–reconnect acceptance on SM-S926N and SM-L320.

## 9. Validation, Rollout, and Risk

### Validation Plan

- WS1: focused JUnit tests for query/listener lifecycle, state mapping, CTA emphasis, consent/readiness, and existing camera Phone-only routing.
- WS2: Gradle wrapper/version resolution, Phone and Wear debug assembly, backend settings/runtime tests, and config documentation cross-check.
- Integration: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:wearos:assembleDebug`; backend `compileall` and full pytest including `test_api_structure.py`.
- Binary: `zipalign -c -P 16 4` plus ELF segment alignment inspection of the built APK.
- Device: install on 16KB SM-S926N; verify no compatibility dialog; test SM-L320 connected, disconnected, rPPG completion, and reconnection states.

### Final Validation Record

| Check | Result |
| --- | --- |
| Android | 87 Phone unit tests, `lintDebug`, Phone `assembleDebug`, and Wear `assembleDebug` passed. |
| 16KB artifact | `zipalign -c -P 16 4` passed; all PT_LOAD segments in the four ARM64/x86_64 native libraries report `p_align=0x4000`. |
| Backend | Compileall passed; full pytest passed with `159 passed, 1 skipped`; OpenAPI contains 39 paths/42 operations and no `/v1` or `/api/v1` public prefix. |
| Watch | Final Wear APK installed successfully on SM-L320 and launched as process PID 15509. |
| Phone limitation | ADB exposed only SM-L320. SM-S926N installation/compatibility-dialog inspection and the physical disconnect → primary CTA → 20-second job → reconnect flow were not run and remain a live-device acceptance item. |

### Minimality and Style-Fidelity Review

- Classify every changed file against its mapped CH entry and reject unrelated edits.
- Confirm the new monitor only wraps the existing NodeClient and does not duplicate rPPG, prediction, authentication, or provider logic.
- Confirm no timeout, notification, background capture, new dependency, endpoint, migration, or broad formatting appears.
- Run `specctl.py check-scope` and `check-patch` for every worker slice.

### Rollout and Rollback

Build and deploy the updated backend/Phone image with a ready rPPG storage root and DGX endpoint. `RPPG_ENABLED=false` remains the emergency rollback switch. Code rollback does not require a data migration; reverting the Android APK and flag default restores prior presentation while preserving existing rows and encrypted videos.

### Risks and Mitigations

| Risk | Impact | Mitigation or Evidence |
| --- | --- | --- |
| A connected generic Wear node is treated as a Watch. | rPPG stays secondary although sensor data may not arrive. | This is the user-selected connection definition; peer callbacks only trigger an exact NodeClient refresh and rPPG remains available. |
| Node query fails. | The app cannot confirm connection. | Use explicit `ERROR`, never claim disconnected, and independently allow ready/consented rPPG. |
| Default-enabled rPPG lacks storage/DGX configuration. | Backend startup validation or status fails. | Keep current fail-closed validation, readiness status, UI explanation, and environment override. |
| Build upgrade exposes plugin incompatibility. | Android build failure. | Use the minimum reviewed AGP 8.5.2/Gradle 8.7 pair, retain Kotlin/SDK versions, and run both modules’ builds. |
| Camera rPPG is mistaken for continuous Watch coverage. | User overestimates monitoring. | UI/docs explicitly call it a 20-second user-initiated point measurement. |

## 10. Revision and Progress

### Design Revision History

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-19T17:48:11+09:00 | reviewed-chat-plan | Created the implementation-ready bilingual design from repository evidence, recorded all user choices, and accepted the explicit implementation authorization. | D-001, D-002, D-003, D-004, D-005, D-006, D-007, D-008 | Q-001, Q-002, Q-003, Q-004 |
| 2 | 2026-07-19T18:18:00+09:00 | implementation-spec-gap | Replaced the unavailable NodeClient-listener assumption with the existing listener-service callback strategy and mapped the stale settings test exposed by default-enabled rPPG. | D-007, D-009, D-010 | Q-005 |
| 3 | 2026-07-19T18:45:00+09:00 | validation-correction | Removed the lint-rejected broad Wear binding and specified a truthful retry-only presentation for unconfirmed/non-ready rPPG status. | D-011, D-012 | — |
| 4 | 2026-07-19T19:00:00+09:00 | binary-validation-correction | Traced the remaining 4KB ARM64 ELF to CameraX 1.3.2 and mapped the minimum same-family 1.4.0 upgrade plus documentation correction. | D-013 | — |
| 5 | 2026-07-19T19:20:00+09:00 | active-document-parity | Found three stale default-off statements in current operational documentation and mapped their exact parity edits. | D-014 | — |
| 6 | 2026-07-19T19:30:00+09:00 | final-review-correction | Mapped the 10/20-second capture timing mismatch and recognized the bilingual PRD as a current contract requiring Watch/default/readiness parity. | D-015, D-016 | — |
| 7 | 2026-07-19T19:40:00+09:00 | active-docs-reopen | Re-review and repository search found remaining contradictory current docs, so WS3 was reopened and expanded without changing historical dated specifications. | D-017 | — |

### Implementation Progress Record

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-19T17:48:11+09:00 | 1 | — | ready | User explicitly authorized implementation of the reviewed plan; no open questions remain. |
| 2026-07-19T18:05:00+09:00 | 1 | WS1, WS2, WS3 | in_progress | Strict validation passed and the three disjoint PG1 slices were assigned for bounded implementation. |
| 2026-07-19T18:18:00+09:00 | 2 | WS1, WS2 | blocked | Targeted refinement opened for missing NodeClient listener and the required settings-test target; independently reviewed WS3 is verified. |
| 2026-07-19T18:31:00+09:00 | 2 | WS1, WS2 | in_progress | User approved D-009; targeted revision 2 is reviewed and both blocked slices resumed. |
| 2026-07-19T18:45:00+09:00 | 3 | WS2 | verified | Build/config/default changes passed focused settings/rPPG tests, ambient-environment regression review, full backend compile, and `159 passed, 1 skipped`. |
| 2026-07-19T18:45:00+09:00 | 3 | WS1 | in_progress | Full Android validation rejected deprecated `BIND_LISTENER`; the manifest change was removed and read-only review opened the bounded service-status retry correction. |
| 2026-07-19T19:00:00+09:00 | 4 | WS1 | verified | Android unit tests and lint passed after both bounded corrections; read-only reviewer finding was resolved and full Phone/Wear assembly passed. |
| 2026-07-19T19:00:00+09:00 | 4 | WS2, WS3 | in_progress | APK ZIP alignment passed, but ELF inspection traced the sole remaining ARM64 4KB library to CameraX 1.3.2; version and documentation corrections were opened. |
| 2026-07-19T19:20:00+09:00 | 5 | WS2 | verified | CameraX 1.4.0 passed full Android unit/lint/Phone/Wear build and final 64-bit ELF plus APK ZIP alignment checks. |
| 2026-07-19T19:20:00+09:00 | 5 | WS3 | in_progress | Active-document parity review opened exact default/readiness wording corrections in backend, development-environment, and web documentation. |
| 2026-07-19T19:30:00+09:00 | 6 | WS3 | superseded | An initial seven-document check appeared complete, but revision 7 re-review found additional contradictory lines and reopened the slice. |
| 2026-07-19T19:30:00+09:00 | 6 | WS4 | in_progress | Final review opened bounded capture-countdown and bilingual current-PRD corrections. |
| 2026-07-19T19:40:00+09:00 | 7 | WS3 | in_progress | Re-review invalidated the premature parity record; exact backend/development/AI corrections were mapped, while historical planning snapshots remain untouched and WS4 code/PRD corrections await final verification. |
| 2026-07-19T19:50:00+09:00 | 7 | WS3 | verified | All 13 active docs passed whitespace and repository-wide default/readiness cross-checks after correcting backend, development, and AI summary wording. |
| 2026-07-19T20:05:00+09:00 | 7 | WS4 | verified | Scope/patch budgets passed at 4/4 files and 4/4 production added lines; the shared 20-second contract passed 87 Phone tests, lint, and Phone/Wear builds; bilingual PRDs had no P0–P2 findings. |
| 2026-07-19T20:05:00+09:00 | 7 | integration | complete | Backend compile and `159 passed, 1 skipped`; 39-path/42-operation OpenAPI with no public v1 prefix; APK ZIP/64-bit ELF 16KB checks; SM-L320 install/launch passed. SM-S926N was absent, so the exact remaining physical acceptance is recorded rather than claimed. |
