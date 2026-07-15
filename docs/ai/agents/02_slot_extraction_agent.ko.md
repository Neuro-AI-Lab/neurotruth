# Legacy Slot 추출 에이전트

최종 업데이트: 2026-07-15

이 문서는 redesign 이전 이력을 설명합니다. 신규 intervention-first session은 slot extraction, `session_slots` 생성, legacy memory 갱신, `slots`, `missingSlots`, `handoffReady` 반환을 수행하지 않습니다. 기존 slot session은 `legacy=true`로 읽을 수 있지만 모든 mutation은 `legacy_session_read_only`를 반환하며 backfill하지 않습니다.

## 정확한 Slot Key

```text
episode_trigger
current_context
alcohol_context
drinking_status
habit_pattern
emotional_context
physical_context
alcohol_expectancy
coping_context
support_context
user_goal
safety_context
additional_context
```

## 과거 값 계약

보존된 각 slot은 `{"status":"answered|unknown|declined","data":...}`입니다. 이 암호화 값은 과거 환자 record이며 신규 dialogue agent가 다시 쓰지 않습니다.

기존 `handoffReady` 규칙은 현재 product contract가 아닙니다. 신규 session은 대화 연속성을 위해 encrypted asked/refused topic ledger만 사용하며 replacement questionnaire나 clinical fact store로 취급하지 않습니다.
