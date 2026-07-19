# Report Agent

Last updated: 2026-07-19

## Endpoints

```http
POST /api/sessions/{sessionId}/reports
GET  /api/sessions/{sessionId}/reports
```

`REPORT_AI_ENABLED=false` is the default. Manual finish and inactivity timeout then skip automatic report creation, and `POST` returns HTTP `202` with exactly `{"reportId":null,"version":null,"status":"not_started"}`. No report Bedrock call or report model-version registration occurs. `GET` preserves historical metadata and returns `[]` when no report exists. Current patient and administrator dashboards never render report bodies.

When `REPORT_AI_ENABLED=true`, the preserved behavior is asynchronous and persistent: finish/timeout queues a consented report, and `POST` is an idempotent retry for a missing/failed report. `GET` returns only `reportId`, `version`, `status`, `createdAt`, and `generatedAt`.

## Rules

- Require `reportGeneration` consent.
- New reports use conversation messages, optional AUQ, trigger prediction, alerts, delivered interventions, and evidence-linked state inferences. They never read `session_slots` or update legacy memory.
- Both manually `completed` and timeout-`abandoned` new sessions may produce reports. Report failure remains independent from session terminal state and state inference.
- Separate patient-reported facts, AUQ, and prediction-derived context. Preserve uncertainty and evidence references.
- Do not claim intoxication, relapse, diagnosis, treatment success, human review, or emergency response.
- Persist AES-256-GCM encrypted content with actual report model/prompt version. Expose only sanitized failure state.
- No bulk report/dataset download API exists.

The legacy synchronous handoff and process-local handoff-job endpoints are not current APIs. Legacy slot reports remain immutable history. Korean STT is optional/default-off, camera rPPG is default-on but consent- and readiness-gated, and TTS is Android-local. Self-event capture and craving-model experiments remain deferred.
