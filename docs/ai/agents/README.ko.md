# NeuroTruth Agent 개요

최종 업데이트: 2026-07-13

## 공개 Backend Endpoint

| Agent 동작 | Backend endpoint |
|---|---|
| 대화 | `/api/intervention/chat` |
| Slot 추출 | `/api/intervention/slots` |
| Handoff 동기 호환 | `/api/intervention/handoff` |
| Handoff job 접수 | `/api/intervention/handoff/jobs` |
| Handoff job 상태 | `/api/intervention/handoff/jobs/{job_id}` |

Bedrock adapter와 prompt builder는 `apps/backend/app/ai/bedrock_agents.py`에 있습니다.

이 파일의 prompt constant:

```text
CHAT_SYSTEM_PROMPT
SLOTS_SYSTEM_PROMPT
HANDOFF_SYSTEM_PROMPT
```

## 공통 규칙

- LLM으로 alert decision을 만들지 않습니다.
- Sensor data만으로 음주 여부를 추론하지 않습니다.
- 민감한 맥락을 요약할 때 사용자의 표현을 최대한 보존합니다.
- 누락된 slot value를 지어내지 않습니다.
- 진단, 약물 조언, 임상적 확정 표현을 피합니다.
- 요청 session ID를 유지해 dialogue, slot, handoff가 backend memory에서 연결되게 합니다.
- 비동기 job status에 provider raw failure나 credential을 노출하지 않습니다.

영어 원본: [Agent overview](README.md)
