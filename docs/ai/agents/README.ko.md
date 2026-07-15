# NeuroTruth 에이전트 개요

최종 업데이트: 2026-07-15

Backend가 모든 에이전트 prompt, model-version 참조, 암호화된 결과와 안전 규칙을 소유합니다. Mobile은 인증된 환자 메시지를 server가 발급한 UUID session으로 전송하며 prompt를 포함하거나 Bedrock을 직접 호출하지 않습니다.

## 현재 API

| 동작 | Endpoint |
|---|---|
| Session 생성/재개 | `POST /api/sessions`, `GET /api/sessions/{sessionId}` |
| Safety-aware intervention dialogue | `POST /api/sessions/{sessionId}/messages` |
| AUQ | `POST /api/sessions/{sessionId}/assessments` |
| 수동 종료 | `POST /api/sessions/{sessionId}/finish` |
| 보고서 생성/상태 | `POST/GET /api/sessions/{sessionId}/reports` |

모든 route는 인증된 환자와 소유권을 요구합니다. 기존 `/api/llm/chat`과 `/api/intervention/*`는 현재 에이전트 API가 아닙니다.

## 공통 규칙

- Alert 판단, safety handling, first intervention은 deterministic backend rule이며 LLM이 선택하지 않습니다.
- Sensor data만으로 음주, 취함, 재발, 진단 또는 치료 성공을 추론하지 않습니다.
- 신규 session에는 slot coverage 또는 `handoffReady`가 없습니다. 선택형 question bank는 completion target 없이 context-sensitive dialogue만 안내합니다.
- Assistant turn마다 짧은 질문은 최대 하나입니다. 환자가 명시적으로 정정하지 않으면 이미 물었거나 거부한 topic을 직접 또는 우회적으로 다시 묻지 않습니다.
- 불확실성과 사용자 표현을 유지하고 누락 사실을 만들지 않습니다.
- Dialogue, intervention, state inference, report를 실제 `model_versions`/`prompt_version` 및 UUID session에 연결합니다.
- 민감정보는 AES-256-GCM으로 암호화합니다. Public error에는 provider payload, credential, token, key, path 또는 복호화 원문을 노출하지 않습니다.
- 즉각적 위험 안내에는 119와 자살예방 상담전화 109를 포함할 수 있습니다. 관리자 도움 요청 사실을 기록할지 한 번 묻고 수락/거절 후 계속하며 실시간 연결이나 즉각적 연락을 약속하지 않습니다.
- `interventionsEnabled=false`는 일반 intervention 문구/저장만 차단하며 안전 안내는 유지합니다.

## State inference 경계

Deterministic code는 최신 유효 persisted model class를 `low|mid|high|unknown`으로 복사하고 구체적인 prediction, AUQ, alert, session, intervention evidence reference를 저장합니다. AUQ와 dialogue는 이 class를 수학적으로 변경하지 않습니다. LLM은 제공된 evidence만 요약하며 summary 실패 시 deterministic inference는 `unavailable` 상태로 유지됩니다. Realtime/longitudinal view는 descriptive record이며 diagnosis, prognosis, treatment outcome 또는 causal analysis가 아닙니다.

첫 일반 intervention은 deterministic safety handling 후 선택되며 AUQ는 선택이고 questionnaire completion이 필요하지 않습니다. 기존 13-slot session은 backfill 없이 read-only로 남습니다. Voice/STT/TTS, rPPG, self-event capture, wearable-absent AUQ automation, craving-model experiment, 관리자 실시간 chat, 긴급 출동, bulk data download는 core release 범위 밖입니다.

English source: [README.md](README.md).
