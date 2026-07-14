# Handoff Agent

최종 업데이트: 2026-07-13

## 책임

Handoff 동작은 clinician, supporter, research follow-up 검토를 위한 간결한 Markdown report를 생성합니다.

## 공개 Endpoint

```http
POST /api/intervention/handoff
POST /api/intervention/handoff/jobs
GET /api/intervention/handoff/jobs/{job_id}
```

Android 앱은 report 생성이 하나의 장시간 HTTP request에 의존하지 않도록 job endpoint를 사용합니다. 기존 동기 endpoint는 호환성을 위해 유지합니다. Job status는 queued/running/completed/failed, 완료 result 또는 정제된 failure만 노출합니다.

## Report 규칙

- 사용자가 직접 보고한 사실과 prediction-derived context를 분리합니다.
- 불확실성을 드러냅니다.
- 음주, relapse, 진단, 치료 성공을 단정하지 않습니다.
- 필요한 경우 누락된 핵심 맥락을 포함합니다.
- 빠르게 검토할 수 있도록 compact하게 유지합니다.
- 생성된 report를 conversation과 slot이 속한 동일 session 아래에 저장합니다.
- 동기/비동기 request가 같은 generation 및 persistence 경로를 사용합니다.

영어 원본: [Handoff Agent](03_handoff_agent.md)
