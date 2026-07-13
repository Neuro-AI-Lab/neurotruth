# Slot 추출 Agent

최종 업데이트: 2026-07-13

## 책임

Slot extraction 동작은 중재 대화 맥락을 구조화된 갈망 slot으로 변환합니다. 이 결과는 handoff readiness와 backend memory를 지원합니다. Alert level 결정은 담당하지 않습니다.

## 공개 Endpoint

```http
POST /api/intervention/slots
```

## Slot Key

```text
trigger
duration
intensity
recent_alcohol_use
physiological_context
coping_attempt
safety_concern
support_context
intervention_summary
```

## 규칙

- 누락된 값을 지어내지 않습니다.
- Sensor prediction만으로 음주 여부를 추론하지 않습니다.
- 앱으로 반환하기 전에 알 수 없는 key를 제거합니다.
- 사용자가 보고한 맥락의 불확실성을 보존합니다.
- 사실 근거로는 user role 내용만 사용합니다. Assistant turn은 직전의
  단일 주제 질문이 어떤 slot인지 식별할 때만 사용합니다.
- 명시적 부정, 모름, 답변 거부는 해당 주제 하나에만 짧은 사용자 원문
  인용을 포함한 비어 있지 않은 평면 문자열로 저장합니다.
- 기존의 비어 있지 않은 `currentSlots`를 보존하며 null, 빈 값, 더 약한
  추출값으로 덮어쓰지 않습니다. User role 근거가 같은 slot을 명시적으로
  정정하거나 갱신한 경우에만 비어 있지 않은 새 값으로 교체합니다.
- 현재 slot과 새로 수용한 값을 병합한 뒤 `missingSlots`를 계산합니다.
- 허용된 slot key를 요청 session ID 아래에 저장합니다.

영어 원본: [Slot Extraction Agent](02_slot_extraction_agent.md)
