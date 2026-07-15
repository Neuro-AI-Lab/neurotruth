# Report Agent

Last updated: 2026-07-15

## Endpoints

```http
POST /api/sessions/{sessionId}/reports
GET  /api/sessions/{sessionId}/reports
```

Report creation is asynchronous and persistent. Manual finish or inactivity timeout automatically queues a consented report. `POST` is an idempotent retry for a missing/failed report. `GET` returns only `reportId`, `version`, `status`, `createdAt`, and `generatedAt`; current patient and administrator dashboards never render report bodies.

## Rules

- Require `reportGeneration` consent.
- New reports use conversation messages, optional AUQ, trigger prediction, alerts, delivered interventions, and evidence-linked state inferences. They never read `session_slots` or update legacy memory.
- Both manually `completed` and timeout-`abandoned` new sessions may produce reports. Report failure remains independent from session terminal state and state inference.
- Separate patient-reported facts, AUQ, and prediction-derived context. Preserve uncertainty and evidence references.
- Do not claim intoxication, relapse, diagnosis, treatment success, human review, or emergency response.
- Persist AES-256-GCM encrypted content with actual report model/prompt version. Expose only sanitized failure state.
- No bulk report/dataset download API exists.

The legacy synchronous handoff and process-local handoff-job endpoints are not current APIs. Legacy slot reports remain immutable history. Voice, rPPG, self-event capture, and craving-model experiments are deferred.
