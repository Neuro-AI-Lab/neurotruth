# 보고서 에이전트

최종 업데이트: 2026-07-15

## Endpoint

```http
POST /api/sessions/{sessionId}/reports
GET  /api/sessions/{sessionId}/reports
```

보고서 생성은 비동기이며 영구 저장됩니다. 수동 종료 또는 inactivity timeout은 동의한 report를 자동 queue합니다. `POST`는 누락/실패 report를 위한 idempotent retry입니다. `GET`은 `reportId`, `version`, `status`, `createdAt`, `generatedAt`만 반환하며 현재 patient/admin dashboard는 report body를 표시하지 않습니다.

## 규칙

- `reportGeneration` 동의를 요구합니다.
- 신규 report는 conversation message, 선택형 AUQ, trigger prediction, alert, 전달된 intervention, evidence-linked state inference를 사용합니다. `session_slots`를 읽거나 legacy memory를 갱신하지 않습니다.
- 수동 `completed`와 timeout `abandoned` 신규 session 모두 report를 만들 수 있습니다. Report 실패는 session terminal state와 state inference에 영향을 주지 않습니다.
- 환자 보고 사실, AUQ, prediction 기반 context를 구분하고 불확실성과 evidence reference를 보존합니다.
- 취함, 재발, 진단, 치료 성공, 사람의 검토 또는 긴급 대응을 주장하지 않습니다.
- 실제 report model/prompt version과 함께 AES-256-GCM 암호화 content를 저장하고 정제된 실패 상태만 노출합니다.
- Bulk report/dataset download API는 없습니다.

기존 synchronous handoff와 process-local handoff-job endpoint는 현재 API가 아닙니다. Legacy slot report는 immutable history로 남습니다. Voice, rPPG, self-event capture, craving-model experiment는 deferred입니다.
