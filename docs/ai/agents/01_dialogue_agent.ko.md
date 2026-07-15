# 대화 에이전트

최종 업데이트: 2026-07-15

## Endpoint와 책임

`POST /api/sessions/{sessionId}/messages`는 인증된 환자 메시지 하나를 받아 암호화된 user/assistant turn과 conversation ledger를 저장하고 `assistantText`, `phase`, `safety`, `activeInterventions`, `stateSnapshot`, `reportStatus`를 반환합니다. 신규 session은 slot을 쓰거나 slot coverage field를 반환하지 않습니다.

## 대화 정책

- 간결하고 지지적이며 비판단적인 한국어를 사용합니다.
- 짧은 질문은 최대 하나입니다. Topic 순서는 선택형 guide이며 questionnaire 또는 completion target이 아닙니다.
- 이미 물었거나 답변을 거부한 topic은 환자가 명시적으로 정정하지 않는 한 우회 표현까지 다시 묻지 않습니다. 암호화 topic ledger는 앱 재시작 이후에도 유지됩니다.
- 정정 내용을 반영하고 명시적인 부정, 불확실성, 답변 거부를 보존합니다.
- 진단, 처방, 수치심 유발, 임상 결과 약속을 하지 않으며 prediction data로 음주를 추론하지 않습니다.
- Deterministic rule이 첫 intervention을 선택합니다. 이후 LLM 제안은 approved intervention allowlist로 제한되며 전달된 제안마다 order와 version evidence를 저장합니다.
- `interventionsEnabled=false`이면 safety handling과 state inference는 계속하지만 일반 intervention 문구/row는 만들지 않습니다.
- 즉각적 갈망 감소, CBT 효과, 진단, 치료 성공, 확실성 또는 인과 효과를 주장하지 않습니다. 검증 repair는 한 번만 허용하고 실패하면 deterministic supportive fallback을 사용합니다.

선택형 한국어 question guide는 `niaaa-samhsa-who-ko-v1`로 versioning합니다. [NIAAA brief intervention](https://www.niaaa.nih.gov/health-professionals-communities/core-resource-on-alcohol/conduct-brief-intervention-build-motivation-and-plan-change), [SAMHSA TIP 35](https://library.samhsa.gov/product/tip-35-enhancing-motivation-change-substance-use-disorder-treatment/pep19-02-01-003), [WHO mhGAP alcohol guidance](https://www.who.int/teams/mental-health-and-substance-use/treatment-care/mental-health-gap-action-programme/evidence-centre/alcohol-use-disorders)를 참고한 독자적인 비임상 paraphrase이며 clinical script가 아닙니다.

## 안전

즉각적 위험 내용은 provider 실패로 지원이 지연되지 않도록 일반 LLM 순서를 우회합니다. 응답은 119와 자살예방 상담전화 109를 안내하고 관리자 도움 요청 사실을 기록할지 한 번 물을 수 있습니다. 이 기록이 실시간 연결이나 즉각적 연락을 보장하지 않는다고 명시합니다. 수락 또는 거절을 감사 기록에 남기고 남은 대화를 계속합니다.

Provider 실패 시 patient message를 유지하고 새 질문이나 미승인 intervention이 없는 deterministic supportive response를 반환합니다. Voice, rPPG, self-event capture, wearable-absent AUQ automation은 이번 release의 대화 입력이 아닙니다.
