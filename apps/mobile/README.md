# NeuroTruth Mobile (신규 앱)

환자용 Android 앱을 새로 구현하는 위치입니다. 아직 코드가 없습니다.

## 개발 기준 문서

| 문서 | 역할 |
|---|---|
| [`docs/prd/PRD_neurotruth_mobile.md`](../../docs/prd/PRD_neurotruth_mobile.md) | 이 앱의 PRD (영어 원본) — 범위, 화면별 요구사항, 우선순위, 인수 기준 |
| [`docs/prd/PRD_neurotruth_mobile.ko.md`](../../docs/prd/PRD_neurotruth_mobile.ko.md) | 위 PRD의 한국어 동기화본 |
| [`neurotruth_frontend_handoff_final.md`](../../neurotruth_frontend_handoff_final/neurotruth_frontend_handoff_final.md) | 화면 동작·문구 계약 |
| [`apps/test_mobile_app/SERVER_API_SPEC.md`](../test_mobile_app/SERVER_API_SPEC.md) | 응답 payload 계약 |
| 실행 중인 백엔드의 `/openapi.json` | route·요청 스키마의 최종 기준 |

문서 간 충돌 시 우선순위는 `/openapi.json` → `SERVER_API_SPEC.md` → 핸드오프 Markdown → PRD → PPT 순입니다.

`apps/test_mobile_app`은 폐기 대상이 아니라 계약 검증용 참조 구현입니다. 신규 앱은 그 앱이 이미 통과시킨 계약(인증 재시도, 센서 idempotency, rPPG job 복구, AUQ 0–48 스케일)을 동일하게 만족해야 합니다.
