# PRD: NeuroTruth

최종 업데이트: 2026-07-13

영어 원본: [PRD: NeuroTruth](PRD_neurotruth.md)

## 제품 요약

NeuroTruth는 wearable sensor를 활용한 알코올 갈망 중재 prototype입니다. Watch sensor window에서 갈망 위험을 예측하고, deterministic alert rule을 적용하며, Bedrock GPT-5.5 기반 text intervention을 제공하고, 갈망 slot을 추출해 비동기 job 흐름으로 handoff report를 생성합니다.

현재 demo는 mobile-first입니다. Galaxy Watch가 sensor data를 수집하고 Android phone이 monitoring/intervention UI를 제공하며, backend가 prediction과 Bedrock intervention을 처리하고 Postgres가 session memory를 저장합니다.

## Service 소유권

| 영역 | 경로 | 책임 |
|---|---|---|
| Backend | `apps/backend` | Prediction, alert, memory, Bedrock intervention, handoff |
| Web | `apps/web` | Web deployment surface |
| Mobile | `apps/mobile` | Phone UI, Wear OS app, sensor upload, SSE, chat |
| DB | `apps/db` | Postgres schema 및 Docker stack |

## 목표

- 갈망 alert를 deterministic하고 설명 가능하게 유지합니다.
- 로컬 갈망 모델을 backend 내부에 유지합니다.
- Bedrock은 conversation, slot extraction, handoff drafting에만 사용합니다.
- Mobile API 호환성을 유지합니다.
- 검증된 `watch_test` 환경을 기반으로 사용할 수 있는 phone/watch demo flow를 제공합니다.
- Laptop LAN Docker backend로 실제 기기 테스트가 가능하게 합니다.
- Prediction history, cooldown, downtrend 상태를 session별로 격리합니다.
- Sensor, prediction, conversation, slot, handoff record를 하나의 session identity로 연결합니다.
- 활성 상담 chat이 뒤이은 AUQ/state-check 실행으로 교체되지 않게 합니다.
- 관리자가 채팅 응답 제한시간을 설정하고 sensor history를 지우지 않은 채 상담 상태만 초기화할 수 있게 합니다.

## 비목표

- 이번 버전에 microphone 또는 STT를 추가하지 않습니다.
- 진단, 약물 안내, 치료 지시를 제공하지 않습니다.
- 이번 변경에서 모델을 재학습하지 않습니다.
- Android가 LLM을 직접 호출하지 않습니다.
- 별도의 로컬 AI server 또는 GPU service를 사용하지 않습니다.

## 핵심 흐름

```text
sensor stream -> backend prediction -> rule alert -> text chat -> slot extraction -> handoff report
```

```text
Galaxy Watch sensors
  -> Wearable Data Layer
  -> Android phone dashboard
  -> POST /sensor-window
  -> backend RF prediction
  -> deterministic alert rule
  -> GET /prediction-stream
  -> phone/watch recommendation 또는 required 상태
  -> text intervention chat
  -> slot extraction
  -> Markdown handoff report
```

## 현재 Mobile 경험

| Surface | 요구 동작 |
|---|---|
| Phone user dashboard | 현재 상태, 최신 craving class, alert level, 중재 진입점, monitoring 상태 표시 |
| Phone developer view | Live chart, upload/SSE 상태, CSV export, 채팅 제한시간 설정, 상담 초기화 control 표시 |
| Phone alert flow | Backend `alertAction`, `alertLevel`, legacy class fallback 순서로 사용하고 `none`, `cooldown` 동작 억제 |
| Phone intervention chat | Required intervention에서 기존 8문항 상태 확인 후 chat 중 AUQ 재실행을 막고, 비동기 handoff job 중에도 text turn을 계속 사용 |
| Watch app | 최신 class/alert 표시, recommendation 짧은 진동, required 강한 진동과 phone 확인 안내 |

## Alert 및 Session 규칙

- Alert history는 최대 256개 session을 보관하는 LRU evaluator registry에서 session별로 격리합니다.
- Mean-based recommend/required는 기본 10-window warm-up이 끝난 후 평가합니다.
- Class-2 high streak는 기본 3회 연속일 때 조기 required intervention을 허용합니다.
- Sensor upload는 `sessionId`와 호환용 `sessionStartedAtMs`를 함께 보냅니다.
- Chat, slot extraction, handoff 저장에는 server가 반환한 session ID를 우선 사용합니다.
- Phone data 초기화 시 새 session을 시작하고 alert, 상태 확인, chat, handoff 상태를 함께 초기화합니다.
- 상태 확인 제출 또는 기존 chat 다시 열기는 상담 latch를 활성화합니다. 활성 중 새 alert는 기록되고 watch 상태는 계속 갱신되지만 AUQ와 반복 watch 진동/notification은 표시하지 않으며, chat을 닫은 뒤의 새로운 required alert부터 다시 허용합니다.
- Chat 응답 제한시간은 기본 60분이며 관리자 모드에서 1~1,440분 범위로 저장합니다.
- Handoff 생성은 HTTP 202 job 접수와 status polling을 사용하고 기존 동기 endpoint는 호환성을 위해 유지합니다.

## 검증 상태

| 영역 | 결과 |
|---|---|
| Backend | Compile 통과, pytest 50개 통과 |
| Docker | DB healthy, backend/web 실행 중 |
| Bedrock | GPT-5.5 Mantle adapter 실제 호출 통과, 이전에 검증한 Claude Converse 경로는 rollback용으로 유지 |
| GPT-5.5 intervention | 알려진 문제: 최신 전체 chat smoke가 slot extraction에서 HTTP 502 반환 |
| Persistence | Prediction, alert, conversation, slot, handoff가 하나의 session으로 연결됨 |
| Android | AUQ/비동기 handoff 변경 후 Phone/Wear build, unit test, 최신 lint 통과 |
| 실제 기기 | 최신 phone/watch APK 설치 및 실행 성공, 전체 live chat과 watch 차단 관찰은 남아 있음 |

## 안전 원칙

- Alert decision은 LLM 판단이 아니라 최근 prediction class 기반 rule로 결정합니다.
- Bedrock 응답은 지지적이고 CBT-style이어야 하지만 임상적 확실성을 표현하지 않습니다.
- Handoff report는 user-reported fact와 model/alert context를 분리합니다.
- 급성 safety concern이 있으면 즉각적인 주변 도움 또는 긴급 지원을 안내합니다.
