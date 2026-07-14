# 대화 Agent

최종 업데이트: 2026-07-13

## 책임

대화 동작은 휴대폰 앱에서 text-first 갈망 중재를 지원합니다. 사용자의 현재 상태를 인정하고, 작고 실행 가능한 coping step 하나를 제안하며, follow-up question은 한 번에 하나만 묻습니다.

## 공개 Endpoint

```http
POST /api/intervention/chat
```

## 기대 동작

- 기본적으로 지지적이고 비판단적인 한국어를 사용합니다.
- 긍정, 부정, 모름, 답변 거부를 모두 완료된 주제로 처리합니다.
- 누적된 `currentSlots`와 `missingSlots`를 사용하며 완료된 주제를 표현만
  바꾸어 다시 묻지 않습니다.
- 간결한 질문은 최대 하나만 하며, 중립적인 사실 답변에 억지 공감이나
  질문을 붙이지 않습니다.
- Backend의 deterministic alert context를 존중하되 확실성을 과장하지 않습니다.
- 진단, 치료 효과 단정, 수치심을 유발하는 표현을 피합니다.
- 급성 safety risk가 드러나면 즉각적인 주변 도움 또는 긴급 도움을 권합니다.
- 비안전 질문은 최근 assistant 질문 세 개와 비교합니다. 동일하거나 매우
  유사하면 Bedrock repair를 한 번만 시도합니다. Repair가 실패하거나 다시
  반복되면 backend가 반복 질문을 제거하고 질문 없는 짧은 확인 응답을
  반환합니다. 안전, probe, 위기, 긴급 질문은 억제하지 않습니다.

## 구현

Backend는 `apps/backend/app/ai/bedrock_agents.py`에서 Bedrock prompt를 구성하고, user/assistant turn은 shared intervention session ID로 backend memory에 저장합니다.

반복 비교는 요청 내부에서만 상태 없이 수행합니다. NFKC 및 대소문자
정규화 후 앞부분의 확인 표현, 문장부호, 공백을 제거하고
`difflib.SequenceMatcher` 임계값 `0.86`을 적용합니다. Public response에는
repair metadata를 추가하지 않습니다.

영어 원본: [Dialogue Agent](01_dialogue_agent.md)
