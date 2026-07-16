# NeuroTruth Binary Craving Probability Model Transition Spec

- **Language role:** English source spec
- **Spec status:** Finalized
- **Spec version:** 1.1
- **Last updated:** 2026-07-16
- **English source:** `docs/specs/2026-07-16-neurotruth-binary-craving-probability-spec.md`
- **Korean mirror:** `docs/specs/2026-07-16-neurotruth-binary-craving-probability-spec.ko.md`
- **Requester / owner:** NeuroTruth team
- **Implementation status:** Implemented; live DGX CUDA acceptance pending

## 0. Codex Implementation Handoff

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: neurotruth/docs/specs/2026-07-16-neurotruth-binary-craving-probability-spec.md
korean_mirror_spec: neurotruth/docs/specs/2026-07-16-neurotruth-binary-craving-probability-spec.ko.md
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

Replace the active three-class RandomForest craving model with the two-class PyTorch `Conv1DNet` supplied in `../final_moving_average_k5_model`. Every second, the backend shall process the latest ten-second PPG/GSR window and expose the softmax probability for class 1 as a research-use “model craving likelihood” on the authenticated Android dashboard. Production on DGX Spark shall prefer CUDA and automatically fall back to CPU when CUDA cannot complete a smoke inference. Existing three-class derived data shall be removed only by an explicit audited maintenance command; raw sensors, user data, conversations, AUQ, sessions, interventions, reports, and rPPG captures/jobs remain intact.

## 2. Goals

- **G1.** Serve the supplied binary model with deterministic, training-aligned input and output contracts.
- **G2.** Persist and stream both class probabilities and show class-1 probability every second on Android.
- **G3.** Convert alerting from three-class thresholds to binary class-history rules.
- **G4.** Support CUDA-first DGX deployment with observable CPU fallback.
- **G5.** Provide a safe, explicit cleanup path for legacy three-class derived records.

## 3. Non-Goals

- **NG1.** Do not retrain, calibrate, benchmark, or change the supplied model weights.
- **NG2.** Do not present class-1 probability as a clinical score, diagnosis, treatment effect, or calibrated craving severity.
- **NG3.** Do not add the probability graph to the administrator web dashboard or Wear OS.
- **NG4.** Do not expose or enable hidden rPPG UI.
- **NG5.** Do not add an Alembic migration; current prediction/model-version columns are sufficient.
- **NG6.** Do not delete raw sensors, users, consent, AUQ, sessions, messages, interventions, reports, or rPPG captures/jobs.

## 4. Users and Use Cases

- **UC1. Patient:** While authenticated monitoring is active, view the latest class-1 likelihood and a live ten-minute history updated once per prediction.
- **UC2. Patient:** Switch between ten-minute, 24-hour, 7-day, and 30-day probability views.
- **UC3. Operator:** Confirm whether the runtime is using `cuda:0` or CPU fallback without exposing secret or host-path information.
- **UC4. Operator:** Preview and then explicitly remove legacy three-class derived records before binary deployment.

## 5. Final Decisions

| ID | Domain | Decision | Source |
| --- | --- | --- | --- |
| D1 | Runtime | PyTorch CUDA first, automatic CPU fallback. | User decision |
| D2 | Preprocessing | No signal filter; resample each channel to 512 and independently MinMax normalize. | User decision |
| D3 | Artifact | Commit the approximately 1 MiB state dict and metadata to the private repository. | User decision |
| D4 | Cleanup | One-time dry-run/confirm CLI; no automatic migration or admin button. | User decision |
| D5 | Alerts | Six class-1 predictions in ten recommends; three consecutive class-1 predictions requires intervention. | User decision |
| D6 | Graph | Android only: raw 1 Hz for ten minutes and server buckets for longer ranges. | User decision |
| D7 | Labeling | User-facing wording is “갈망 가능성(모델)” and includes a research-use limitation. | Conservative product default |

## 6. Functional Requirements

- **FR1. Model artifacts:** Copy `model.py`, `model_weights.pt`, and `model_metadata.json` into the backend model area. Verify the weights SHA-256 equals the computed source-artifact value `9fec80f7b2c42ba8a5bdb1d702a82eb5bd73542365c7ccb91db3d880b9f321c5` before loading. Load with `weights_only=True`, call `eval()`, and infer under `torch.inference_mode()`.
- **FR2. Input:** Use a ten-second sliding window with one-second stride. Select PPG in priority order `PPG_GREEN`, `PPG_IR`, `PPG_RED`; select GSR from `EDA`. Resample each selected channel linearly to 51.2 Hz / 512 values without filtering. Independently MinMax normalize to `[0,1]`; a constant channel becomes 512 zeros. Stack as `(1,2,512)` in `[PPG,GSR]` order.
- **FR3. Input failure:** A window with no finite PPG values is rejected and creates no prediction. Missing GSR becomes zeros and is marked in non-sensitive input-quality metadata.
- **FR4. Output:** Apply softmax to two logits. Index 0 is `low`; index 1 is `high`. `class` is `argmax`, with an exact tie resolving to class 0. `confidence=max(p0,p1)` and `cravingProbability=p1`.
- **FR5. Model persistence:** Register a new `binary_classification` model version with artifact SHA, preprocessing, input/output schema, PyTorch version, requested device, and actual device. Persist `predicted_class_probability=confidence`, `class_probabilities={"low":p0,"high":p1}`, and `continuous_value=p1` with scale `0.0..1.0`.
- **FR6. Alert rules:** Maintain ten binary classes per sensor session. A full window containing at least six ones produces `recommend`. A trailing streak of three ones produces `required`, including during warm-up, and takes precedence over downtrend suppression. A first-half to second-half mean decrease of at least `0.6` suppresses recommendation. Preserve separate 30-second cooldowns per non-none level.
- **FR7. Dashboard:** Android shows the latest `p1` as a one-decimal percentage and a fixed 0–100% line chart. It offers `10m`, `24h`, `7d`, and `30d`. It must not smooth or interpolate missing periods.
- **FR8. Compatibility surfaces:** Administrator web renders binary labels but no probability graph. Wear OS renders class 0/1 as low/high and trusts server `alertAction`; class alone must not trigger a local alert. The hidden rPPG workflow reuses the binary predictor with its existing zero-EDA adapter and remains hidden.
- **FR9. Legacy removal:** The maintenance command identifies legacy model versions by `multiclass_classification` or an output schema containing Low/Mid/High. It deletes their predictions, linked alerts, and state inferences directly referencing those predictions in one transaction under a PostgreSQL advisory lock. It preserves all non-goal data and inserts one aggregate system audit record after success.
- **FR10. Old implementation:** Remove the active RandomForest artifact, RF-only feature extraction files, and RF-only dependencies from the current source/image after binary tests pass. Repository history and the prior image are the rollback source.

## 7. User Experience / UI Requirements

- **UI1.** The Android card title is `갈망 가능성(모델)` and the latest value is shown as `0.0%` through `100.0%`.
- **UI2.** The card states that the value is a research model output, not a diagnosis or clinical severity. Supporting documentation states that the final weights used all available training data and have no independent final-weight test evaluation.
- **UI3.** The ten-minute view starts from API backfill and appends authenticated SSE points, deduplicated by prediction timestamp/ID. Keep at most 600 raw points.
- **UI4.** Missing buckets remain visual gaps. Loading, empty, and unavailable states retain the existing dashboard patterns.
- **UI5.** Existing AUQ, events, PPG preview, state summaries, and report status remain available.

## 8. API / Data / State Requirements

### 8.1 Prediction event

The existing authenticated SSE and sensor-result event is a deliberate breaking binary contract and adds:

```json
{
  "predictionSchema": "binary-craving-v1",
  "class": 1,
  "classCode": "high",
  "confidence": 0.812345,
  "cravingProbability": 0.812345,
  "classProbabilities": {"low": 0.187655, "high": 0.812345},
  "timestampMs": 1784160000000,
  "alertLevel": "recommend",
  "alertAction": "recommend_intervention",
  "windowMean": 0.7,
  "classOneRatio": 0.7
}
```

Probabilities are finite, clamped to `[0,1]`, rounded to six decimals at the wire boundary, and sum to approximately one. `windowMean` remains as a compatibility alias for `classOneRatio`.

### 8.2 Patient probability series

`GET /api/me/craving-probability-series?range=10m|24h|7d|30d` requires patient JWT ownership and returns UTC ISO-8601 times:

```json
{
  "range": "24h",
  "from": "2026-07-15T00:00:00Z",
  "to": "2026-07-16T00:00:00Z",
  "bucketSeconds": 60,
  "points": [
    {"at": "2026-07-15T00:01:00Z", "averageCravingProbability": 0.7132, "sampleCount": 57}
  ]
}
```

Bucket sizes are fixed: `10m=1s` (max 600), `24h=60s` (max 1,440), `7d=600s` (max 1,008), and `30d=1800s` (max 1,440). Empty buckets are omitted. Aggregation uses persisted `continuous_value` for the active binary model only. The existing patient dashboard prediction item adds `cravingProbability`; no administrator probability-series route is added.

### 8.3 Runtime status

Runtime status exposes model name, expected/actual checksum, requested device, actual device (`cuda:0|cpu`), fallback boolean/reason code, class labels, sample rate, and window length. It does not expose absolute host paths, credentials, stack traces, or raw inputs.

## 9. Permissions, Security, Privacy, and Audit

- **SEC1.** Probability history is patient-authenticated and ownership-scoped through the existing JWT dependency.
- **SEC2.** Only `weights_only=True` state-dict loading is allowed; do not unpickle the full checkpoint.
- **PRIV1.** Logs and runtime status exclude raw samples, credentials, absolute host paths, and patient identifiers beyond existing structured persistence requirements.
- **AUDIT1.** Successful legacy cleanup writes a system audit event with selected model IDs/artifact hashes and per-table deletion counts. A dry run is read-only and prints counts without inserting audit data.

## 10. Error, Edge-Case, and Concurrency Behavior

- **ERR1.** Missing artifact, checksum mismatch, incompatible state dict, or failed CPU load leaves auth/chat available but prediction submissions return `503 model_unavailable`.
- **ERR2.** `CRAVING_INFERENCE_DEVICE=auto` tries CUDA only after `torch.cuda.is_available()` and a real smoke inference succeed. Any CUDA initialization/smoke failure logs a code and reloads the same weights on CPU. A CPU load failure makes the model unavailable.
- **EDGE1.** Invalid/unsupported dashboard range returns the existing structured `422` style; no predictions returns an empty points array.
- **EDGE2.** Class 2 received by updated Android/Wear parsers is rejected as an incompatible prediction, not mapped to high.
- **CONC1.** Keep the existing bounded inference queue/drop-stale behavior. Dashboard history queries are read-only. Cleanup requires the backend prediction worker to be stopped and also obtains an advisory lock.

## 11. Dependencies and Configuration

- **DEP1.** Upgrade the backend base runtime to Python 3.12 and pin `torch==2.12.0`. Keep only non-RF scientific dependencies still used elsewhere.
- **DEP2.** Add a DGX Compose override that grants the backend `gpus: all`; the base Compose remains runnable without GPU.
- **CONF1.** Add:

```dotenv
CRAVING_MODEL_PATH=/app/model/weights/final_moving_average_k5/model_weights.pt
CRAVING_MODEL_METADATA_PATH=/app/model/weights/final_moving_average_k5/model_metadata.json
CRAVING_MODEL_SHA256=9fec80f7b2c42ba8a5bdb1d702a82eb5bd73542365c7ccb91db3d880b9f321c5
CRAVING_INFERENCE_DEVICE=auto
ALERT_WINDOW_SIZE=10
ALERT_RECOMMEND_COUNT=6
ALERT_HIGH_STREAK=3
ALERT_COOLDOWN_SECONDS=30
ALERT_DOWNTREND_DELTA=0.6
```

Remove `ALERT_REQUIRED_MIN`. Existing deployment ports, database credentials, encryption keys, Bedrock settings, and rPPG settings are not changed.

## 12. Migration, Rollout, and Rollback

- **MIG1.** No Alembic revision. Before binary rollout, back up PostgreSQL, stop the prediction worker, and run:

```powershell
docker compose run --rm backend python -m app.maintenance.purge_legacy_predictions --dry-run
docker compose run --rm backend python -m app.maintenance.purge_legacy_predictions --confirm DELETE-LEGACY-3CLASS-PREDICTIONS
```

- **ROLL1.** Deploy backend and Android together because the class contract is breaking. On DGX, start with the GPU Compose override and verify `actualDevice=cuda:0` before device acceptance.
- **BACK1.** Roll back to the prior Git commit/image. Deleted legacy derived predictions are not restored automatically; recovery requires the pre-deployment DB backup. Raw sensor and user data remain available.

## 13. Implementation Boundaries

### 13.1 Expected Change Areas

- Backend inference, alerts, V25 model-version persistence/dashboard repository/routes, maintenance CLI, Docker/requirements/config, focused tests, and model artifacts.
- Android dashboard/SSE prediction contracts and tests.
- Wear OS binary label/alert compatibility and tests.
- Relevant README, API, and deployment documentation.

### 13.2 Forbidden Changes

- No Alembic or baseline SQL changes.
- No administrator probability graph.
- No rPPG UI enablement or DGX rPPG service changes.
- No conversation-agent, AUQ, authentication, encryption, port, or database topology redesign.
- No unrelated refactors, formatting churn, mass renames, generated-file churn, or reversion of user changes.

## 14. Acceptance Criteria

- **AC1.** Given a valid ten-second PPG/GSR fixture, service preprocessing produces `(1,2,512)` and logits/softmax match the supplied model implementation within `1e-5` on CPU.
- **AC2.** Every accepted one-second-stride window emits/stores `p0`, `p1`, binary class, predicted-class confidence, and `p1` continuous value under the new model version.
- **AC3.** Six ones in a complete ten-window history recommends; three trailing ones requires even during warm-up; downtrend never suppresses the three-one rule; cooldown prevents repeated actions.
- **AC4.** Android shows the latest `p1` percentage and correct 10m/24h/7d/30d series with gaps and bounds; administrator web and watch have no probability graph.
- **AC5.** DGX reports CUDA after a successful CUDA smoke inference; simulated CUDA failure selects CPU and still predicts.
- **AC6.** The cleanup dry run changes no data; incorrect confirmation changes no data; confirmed cleanup removes only the specified legacy derived records and records aggregate audit counts.
- **AC7.** Existing authentication, encrypted sensor storage, AUQ, conversation, report, PPG preview, and hidden rPPG workflows pass regression tests.
- **AC8.** The active source/image no longer contains or loads the RandomForest artifact or RF-only inference path.

## 15. Validation Plan

| Check | Command or Method | Expected Result |
| --- | --- | --- |
| Backend unit/integration | `python -m pytest apps/backend/tests` from repository environment | Binary inference, alerts, persistence, dashboard, cleanup, and regressions pass. |
| Android unit tests | `apps/mobile/gradlew.bat :app:testDebugUnitTest` | Prediction parsing, probability series, graph state, and alert policies pass. |
| Wear OS unit/build | `apps/mobile/gradlew.bat :wearos:testDebugUnitTest :wearos:assembleDebug` | Binary labels and server-alert precedence compile and pass. |
| Android build | `apps/mobile/gradlew.bat :app:assembleDebug` | Debug APK builds. |
| Docker CPU smoke | Build/start base Compose and inspect model status/prediction fixture. | `actualDevice=cpu`, prediction succeeds. |
| DGX CUDA smoke | Start with GPU override and submit repeated fixture/windows. | `actualDevice=cuda:0`, 1 Hz processing remains stable. |
| Data cleanup | Run dry-run, wrong confirm, then confirmed command against disposable PostgreSQL fixture. | Counts, transactionality, audit, and preservation match AC6. |

## 16. Risks and Open Notes

- **RISK1.** The final weights used all retained training data and lack an independent final-weight test set; mitigate with explicit research-use wording and no clinical interpretation.
- **RISK2.** CUDA/PyTorch image size and DGX driver compatibility can affect deployment time; pin the runtime, verify `nvidia-smi`, and retain CPU fallback.
- **RISK3.** One-second overlapping predictions are autocorrelated. The UI displays raw model probability without claiming independent samples or smoothing.
- **RISK4.** Legacy cleanup is irreversible without the DB backup; it is never executed automatically by application startup or migration.

## 17. Implementation Checklist / Progress Record

| ID | Task / Scope | Owner | Status | Changed Files | Validation | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Finalize and validate English/Korean spec pair | Main | Completed | Spec pair | `validate_spec_pair.py`: PASS | Source edits not started. |
| P2 | Backend model, alerts, API/data, CUDA packaging, cleanup CLI | Worker | Completed | Backend inference/alerts/V25 routes and repositories, model assets, maintenance CLI, Docker/Compose, tests | Backend `pytest`: 176 passed, 1 skipped; Docker CPU model parity smoke: PASS; Compose base+DGX config: PASS | The supplied artifact computes to SHA `9fec…c7ccb91…`; the originally written value omitted one character and was corrected in code, configuration, tests, and this spec. Live DGX CUDA was not available locally. |
| P3 | Android dashboard and prediction contract | Worker | Completed | Phone prediction parser, API client, dashboard/chart, ViewModel/uploader, tests | `:app:testDebugUnitTest` and `:app:assembleDebug`: PASS | Binary-only parsing, p1 history/backfill, gap rendering, and server-owned alert actions implemented. |
| P4 | Wear OS compatibility and relevant documentation | Worker | Completed | Wear prediction/alert UI and tests; root/mobile/API/Wear docs | `:wearos:testDebugUnitTest`, `:wearos:assembleDebug`, combined Android build: PASS | Watch retains no probability graph and does not infer alerts from class 1. |
| P5 | Main-agent diff review and full validation | Main | Completed | Full worker diff and synchronized spec pair | `git diff --check`: PASS; spec pair validator: PASS; model/source softmax max error `2.17e-7` | Real DGX `cuda:0`, 1 Hz soak, real phone/watch end-to-end, and destructive cleanup against a disposable PostgreSQL instance remain deployment acceptance checks. |

## 18. Revision History

| Version | Date | Author | Changes |
| --- | --- | --- | --- |
| 1.0 | 2026-07-16 | Feature Planner | Initial finalized specification for binary PyTorch craving probability serving. |
| 1.1 | 2026-07-16 | Feature Planner | Recorded implementation and validation results and corrected the checkpoint SHA to the value computed from the supplied artifact. |
