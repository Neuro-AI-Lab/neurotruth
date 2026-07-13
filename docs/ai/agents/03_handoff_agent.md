# Handoff Agent

Last updated: 2026-07-13

## Responsibility

The handoff behavior produces a concise Markdown report for clinician, supporter, or research follow-up review.

## Public Endpoints

```http
POST /api/intervention/handoff
POST /api/intervention/handoff/jobs
GET /api/intervention/handoff/jobs/{job_id}
```

The Android app uses the job endpoints so report generation does not depend on one long-lived HTTP request. The synchronous endpoint remains for compatibility. Job status exposes only queued/running/completed/failed, the completed result, or a sanitized failure.

## Report Rules

- Separate user-reported facts from prediction-derived context.
- Keep uncertainty visible.
- Do not claim intoxication, relapse, diagnosis, or treatment success.
- Include missing critical context when relevant.
- Keep the report compact enough for quick review.
- Persist the generated report under the same session as its conversation and slots.
- Use the same generation and persistence path for synchronous and asynchronous requests.
