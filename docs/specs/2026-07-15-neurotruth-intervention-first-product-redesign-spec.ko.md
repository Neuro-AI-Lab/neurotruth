# NeuroTruth 중재 중심 제품 재설계 명세

- **Language role:** Korean mirror spec
- **Spec status:** Finalized
- **Spec version:** 1.2
- **Last updated:** 2026-07-15
- **English source:** `docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.md`
- **Korean mirror:** `docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.ko.md`
- **Requester / owner:** NeuroTruth 프로젝트 팀
- **Implementation status:** Implemented

## 0. Codex 구현 인계

이 절은 확정 설계에서 구현으로 전환할 때 Feature Planner 워크플로를 보존한다. 영문 파일이 기준 문서다.

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: neurotruth/docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.md
korean_mirror_spec: neurotruth/docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.ko.md
authoritative_spec: english_source_spec
implementation_requires:
  - use_feature_planner_skill
  - read_english_source_spec_first
  - keep_korean_mirror_synchronized
  - spawn_worker_subagents_for_source_code_edits
  - main_agent_only_orchestrates_and_verifies
  - enforce_minimal_source_changes
  - return_to_targeted_refinement_on_spec_gap
worker_rule: source_code_changes_must_be_delegated_to_worker_subagents
main_agent_allowed:
  - read_files
  - search_repository
  - inspect_diffs
  - run_validation
  - update_spec_progress_records
  - prepare_worker_prompts
main_agent_forbidden:
  - direct_feature_source_code_edits
  - unrelated_refactors
  - formatting_only_churn
  - dependency_upgrades_unless_spec_requires
  - generated_file_churn_unless_spec_requires
</codex_feature_planner_handoff>
```

복사하여 사용할 구현 프롬프트:

```text
$feature-planner Implement `neurotruth/docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.md` using IMPLEMENTATION_ORCHESTRATION. Use the English source spec as authoritative, keep `neurotruth/docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.ko.md` synchronized, spawn worker sub-agents for source-code edits, and have the main agent verify minimal diffs and update progress records. If the spec has a gap, use TARGETED_REFINEMENT for only that gap before continuing.
```

## 1. 요약

NeuroTruth는 갈망 가능 상황을 탐지하고 사용자 승인하에 구조화되었지만 자율적인 대화를 제공하는 연구용 보조 중재 시스템으로 전환한다. 치료 앱이 아니며 CBT 직후 감소, 진단, 치료 성공 또는 치료의 인과 효과를 표현해서는 안 된다. 신규 세션은 V2.5 13-slot 문진을 중단하고 `handoffReady`를 노출하지 않으며, 대신 선택형 AUQ, 결정론적 안전 처리, 결정론적 첫 중재, 자유 후속 대화, 상태 추론, 암호화 저장, 리포트 상태만 표시하는 흐름을 사용한다. 기존 13-slot 세션과 리포트는 backfill이나 신규 slot 쓰기 없이 읽기 전용 이력으로 보존한다. 구현 범위는 인증 백엔드, Android 환자 경험, 관리자 웹 대시보드와 additive `0003` migration 하나다.

## 2. 목표

- G1. 치료 효과를 주장하지 않으면서 `PPG/GSR -> Low/Mid/High 추정 -> 상승 가능성 알림 -> 사용자 승인 대화 -> 선택형 AUQ -> 중재 -> DB -> 대시보드`를 시연한다.
- G2. 신규 세션의 필수 slot 완료를 거절을 존중하고 반복 질문을 피하는 간결하고 비판단적이며 이력 기반인 중재 대화로 교체한다.
- G3. 결정론적 상태 분류와 근거 수집을 LLM 작성 한국어 요약과 분리한다.
- G4. 원시 신호와 민감 텍스트 접근 규칙을 지키며 환자와 관리자에게 갈망 class 추세, AUQ, 알림, 세션, 중재, 리포트 상태 대시보드를 제공한다.
- G5. V2.5 인증, 동의, AES-256-GCM 암호화, 감사, 환자당 단일 활성 세션, alert cooldown과 기존 비활성 feature flag 뒤의 rPPG 구현을 보존한다.

## 3. 비목표

- NG1. CBT나 중재가 갈망을 즉시 감소시킨다는 점을 입증하거나 진단, 치료, 예후 또는 인과 주장을 하는 것.
- NG2. 갈망 모델 재학습, class balancing, threshold 실험 또는 moving-average label 실험. 이는 런타임 동작 밖에서 연구팀 작업으로 유지한다.
- NG3. STT, self-event 수집, 웨어러블 미착용 시 AUQ 자동화와 rPPG 운영 활성화.
- NG4. 런타임에서 NeuroSync 코드를 import하거나 호출하는 것. 대화 설계 원칙만 NeuroTruth 내부에서 다시 구현한다.
- NG5. 기존 `session_slots` 흐름 또는 legacy `memory_snapshots`를 backfill, 삭제, 재작성하거나 신규 세션에서 계속 사용하는 것.
- NG6. 현재 환자 또는 관리자 대시보드에서 리포트 본문을 표시하거나 관리자에게 원시 PPG를 노출하거나 dataset download API를 만드는 것.
- NG7. 데모 signal-replay 모드 추가. 저장된 데모 시나리오는 자산과 타이밍이 제공되면 별도로 명세할 수 있다.

## 4. 사용자와 사용 사례

### 4.1 대상 사용자

- CBT 치료를 받고 있거나 치료에 참여할 의지가 있고 갈망 상황에서 지속적인 기록과 대화 기반 지원이 필요한 사람.
- 환자별 추세, 이벤트 시점, 중재 사용과 리포트 생성 상태를 검토하는 권한 있는 관리자.

### 4.2 주요 사용 사례

- UC1. 환자가 갈망 상승 가능성 알림을 받고 `지금 대화하기`를 선택하고, AUQ를 선택적으로 수행한 뒤 수동 종료 또는 inactivity timeout까지 안전을 고려한 중재 대화를 지속한다.
- UC2. 환자가 24시간, 7일 또는 30일 Low/Mid/High 추세, 별도 AUQ 값, event marker, 현재 로컬 Watch PPG와 자신에게 속한 과거 10초 PPG preview를 검토한다.
- UC3. 관리자가 원시 PPG 없이 환자 추세와 event metadata를 검토하고, 암호화된 message, state summary, intervention 또는 report text를 열람하기 전에 사유를 입력한다.
- UC4. 기존 13-slot 세션은 읽기 전용 이력으로 유지되지만 message, assessment, report 재생성 또는 slot 변경을 받을 수 없다.

## 5. 최종 결정

| ID | 영역 | 결정 | 출처 |
| --- | --- | --- | --- |
| D1 | 제품 정의 | NeuroTruth를 연구용 보조 중재 시스템으로 설명하고 치료 또는 즉시 효과 데모로 표현하지 않는다. | 사용자 결정 |
| D2 | 세션 흐름 | 신규 세션에는 slot coverage, `missingSlots`, `handoffReady`가 없다. 수동 종료는 `completed`, 1시간 inactivity는 `abandoned`다. | 사용자 결정 |
| D3 | AUQ | AUQ는 선택 사항이다. 건너뛰면 assessment row를 만들지 않으며 안전 확인, 대화, 중재, 상태 추론, 종료를 차단하지 않는다. | 사용자 결정 |
| D4 | 안전 | 결정론적 안전 확인이 우선한다. 긴급 문맥에서는 119/109를 안내하고 실시간 연락을 약속하지 않는다. 사용자가 원하면 대화를 계속할 수 있다. | 사용자 결정 |
| D5 | 첫 중재 | LLM이 아니라 서버 규칙이 승인된 allowlist에서 첫 중재를 선택한다. | 사용자 결정 |
| D6 | 이후 중재 | 첫 중재 이후 LLM은 allowlist에 있는 중재 유형만 제안할 수 있고 서버가 모든 전달 제안을 검증·저장한다. | 사용자 결정 |
| D7 | 질문 가이드 | NIAAA, SAMHSA TIP 35, WHO mhGAP을 바탕으로 새로 표현한 버전 관리 한국어 질문은행은 문진이나 coverage 목표가 아닌 선택적 가이드다. | 사용자 결정 |
| D8 | 상태 추론 | 결정론적 근거가 `low|mid|high|unknown`을 생성한다. AUQ와 대화 근거는 모델 class를 수학적으로 바꾸지 않는다. LLM 요약 실패 시에도 결정론적 기록을 보존한다. | 사용자 결정 |
| D9 | Legacy 호환성 | 기존 slot 세션은 `interaction_phase=NULL`이며 읽기 전용으로 남고 backfill하지 않는다. 신규 세션 코드는 `session_slots` 또는 legacy `memory_snapshots`를 쓰지 않는다. | Agent default |
| D10 | 대화 연속성 | 암호화된 session dialogue state에 질문·거절 topic ID와 safety state를 저장해 대체 slot을 만들지 않고 앱 재시작 후 반복 질문을 방지한다. | Agent default |
| D11 | Dashboard 범위 | `24h`, `7d`, `30d`만 허용하며 30일이 최대이므로 배열은 한 응답에서 시간순으로 반환한다. | 사용자 결정 |
| D12 | 원시 PPG | 실시간 PPG는 Android Watch stream에서만 렌더링한다. 과거 10초 PPG는 별도 최대 512-point endpoint로 소유 환자에게만 제공한다. | 사용자 결정 |
| D13 | 리포트 | 종료 시 messages, AUQ, prediction, interventions, state inference로 동의 기반 리포트를 자동 생성한다. 환자/관리자 대시보드는 `not_started|generating|ready|failed`만 노출한다. | 사용자 결정 |
| D14 | rPPG | rPPG 코드와 데이터를 보존한다. 기존 backend rPPG feature가 명시적으로 활성·준비된 경우가 아니면 환자와 관리자 rPPG UI를 숨긴다. | 사용자 결정 |
| D15 | 시간 | 시각은 UTC ISO-8601로 저장·반환하고 Android와 web이 viewer의 local timezone으로 표시한다. | 사용자 결정 |
| D16 | API rollout | Session response 계약은 의도적으로 breaking하며 backend와 Android를 함께 배포한다. 제거된 slot field를 위한 compatibility shim은 추가하지 않는다. | 사용자 결정 |
| D17 | 추론 시점 | Alert-backed session 시작과 수락된 각 patient message, assessment 또는 전달된 intervention 후 realtime state snapshot을 생성하고, session 종료 시 과거 30일 기반 longitudinal snapshot 하나를 생성한다. | Agent default |
| D18 | Provider 실패 | Dialogue 실패는 안전한 결정론적 응답과 coded audit를 사용한다. Summary/report 실패는 자체 status로 격리하고 결정론적 데이터나 session finish를 rollback하지 않는다. | Agent default |

## 6. 기능 요구사항

- FR1. 인증된 환자의 첫 사용 시 Android는 대상 사용자와 한계 안내를 표시하고 version을 로컬에 저장해야 한다. 안내 version이 바뀌면 다시 확인해야 하며, Home에서 동일한 안내를 계속 볼 수 있어야 한다.
- FR2. 조건을 충족한 alert는 기존 cooldown/warm-up 규칙을 유지하고 `지금 대화하기`와 `나중에`를 제공한다. `지금 대화하기`만 인증 session을 생성하거나 재개한다.
- FR3. `POST /api/sessions`는 `safety_check`의 `in_progress` 신규 방식 session을 시작하고, 결정론적 assistant safety prompt를 저장·반환하며 유효한 `system_settings.chat_timeout_seconds`를 `inactivityTimeoutSeconds`로 함께 반환한다. App은 buffer된 prompt를 보여주기 전에 선택형 AUQ 선택지를 표시할 수 있다.
- FR4. AUQ 제출은 기존 assessment endpoint를 사용한다. `건너뛰고 대화하기`를 선택하면 assessment request 없이 즉시 진행한다.
- FR5. Safety engine은 최소한 즉각적 자해, 음주 운전, 위험한 알코올/약물 병용, 심각한 급성 증상, 심한 금단 표현을 탐지해야 한다. 즉각적 의학 위험에는 119, 자살/자해 위기 문맥에는 109를 안내하고 coded evidence 및 관리자 관여 요청/거절을 기록하며 연락이 이뤄질 것이라고 말하지 않는다.
- FR6. 안전 처리 후 계속할 수 있으면 결정론적 context flag로 다음 우선순위에 따라 첫 중재를 선택한다: 거절 필요 -> `refusal_practice`, 접근 가능한 술 -> `leave_location`, 호흡성 각성 -> `breathing`, 기타 긴장/각성 -> `grounding`, 반복 갈망 사고 -> `urge_surfing`, 습관성 단서 -> `attention_shift`, 사용 가능한 지지자 -> `social_support`, 탈수 표현 -> `hydration`, 그 외 -> `self_monitoring`.
- FR7. 승인된 intervention allowlist는 `breathing`, `urge_surfing`, `attention_shift`, `leave_location`, `refusal_practice`, `social_support`, `grounding`, `hydration`, `self_monitoring`이다. `other`는 legacy 전용으로 남고 신규 세션에서 생성해서는 안 된다.
- FR8. 첫 중재 이후 dialogue agent는 memory에서 복호화한 전체 이력, 복호화한 암호화 dialogue ledger state, active interventions, 최신 prediction, 선택적 AUQ, safety state를 받는다. 짧은 질문 최대 하나와 optional allowlisted intervention suggestion이 있는 간결한 한국어 응답 하나를 반환한다.
- FR9. Question topic ID는 `safety`, `current_environment`, `alcohol_access`, `trigger`, `emotion_body`, `past_coping`, `support`, `desired_help`로 제한한다. Agent는 질문을 생략하거나 순서를 바꿀 수 있고 사용자가 이전 정보를 명시적으로 수정할 때만 다시 다룰 수 있다. 거절되거나 이미 질문한 topic ID는 바꿔 말해 다시 묻지 않는다.
- FR10. Question-bank artifact는 새로 작성한 한국어 표현을 사용하고 source URL과 version `niaaa-samhsa-who-ko-v1`을 표시하며 [NIAAA brief intervention](https://www.niaaa.nih.gov/health-professionals-communities/core-resource-on-alcohol/conduct-brief-intervention-build-motivation-and-plan-change), [SAMHSA TIP 35](https://library.samhsa.gov/product/tip-35-enhancing-motivation-change-substance-use-disorder-treatment/pep19-02-01-003), [WHO mhGAP alcohol guidance](https://www.who.int/teams/mental-health-and-substance-use/treatment-care/mental-health-gap-action-programme/evidence-centre/alcohol-use-disorders)를 인용해야 한다. Source wording을 임상 script처럼 복제해서는 안 된다.
- FR11. Server validation은 질문이 둘 이상이거나, 승인되지 않은 intervention, 진단/처방, 치료 성공, 즉시 감소, 확정적 표현, 인과 표현 또는 근거 없는 긴급 대응 약속이 있는 agent output을 거부하거나 repair한다. Structured repair는 한 번 허용하며, 실패하면 결정론적 비임상 fallback을 사용한다.
- FR12. 모든 user/assistant message는 저장 전 AES-256-GCM으로 암호화한다. 전달된 각 intervention은 별도로 암호화하고 순서, status, evidence reference, 실제 model/prompt version과 함께 저장한다.
- FR13. 결정론적 state inference는 최신 유효 model class와 probability를 복사하고 사용할 수 없으면 `unknown`을 사용한다. Class distribution, AUQ observation, alert, session event, intervention event를 포함한 payload와 evidence ID를 저장하되 새 clinical score를 합성하지 않는다.
- FR14. LLM은 제공된 evidence payload만으로 한국어 state summary를 작성할 수 있다. Class를 변경할 수 없다. Provider 실패 시 `summary_status=unavailable`, coded audit event, fabricated summary 없음으로 저장한다.
- FR15. 수동 종료는 신규 session을 `completed`로 닫고, 설정된 timeout(기본 3600초) 이후 inactivity는 `abandoned`로 닫는다. 둘 다 마지막 realtime snapshot, 30일 longitudinal snapshot 하나, report 동의가 활성화된 경우 report job을 생성한다.
- FR16. 신규 report는 messages, AUQ, trigger prediction, alerts, interventions, state inferences를 사용한다. `session_slots`를 읽지 않으며 report 실패는 session을 다시 열거나 rollback하지 않고 `failed`를 설정한다.
- FR17. 신규 session은 `memory_snapshots`를 갱신하지 않는다. Longitudinal `state_inferences`가 새로운 evidence-linked 종단 기록이다. 기존 memory는 보존하고 사유 기반 열람을 유지한다.
- FR18. Android와 관리자 web은 Section 7과 Section 8에 따른 별도 patient dashboard와 제한된 dashboard를 표시해야 한다.
- FR19. 기존 rPPG route, encrypted data, job을 그대로 유지한다. Core demo flow에 rPPG runtime call을 새로 넣지 않는다.
- FR20. Agent output 또는 state inference에 사용된 모든 model, prompt, rule, question-bank version은 생성 record에 저장하거나 참조해야 한다.

## 7. 사용자 경험 / UI 요구사항

- UI1. 최초 안내는 CBT 치료 중이거나 치료를 받을 의지가 있는 사용자를 위한 것, 갈망 상황에서 지속적인 기록과 대화 지원을 위한 것, 의료행위·진단·응급 대응을 대체하지 않는 연구용 보조 시스템임을 설명한다. Primary action은 `확인했어요`이며 이후 Home에 `NeuroTruth 사용 안내`를 제공한다.
- UI2. Alert sheet는 `갈망이 높아졌을 가능성이 있어요` 같은 가능성 표현과 `지금 대화하기`, `나중에` action을 사용한다. 갈망이 확실히 상승했다고 말해서는 안 된다.
- UI3. Session 생성 후 AUQ action `작성하기`와 `건너뛰고 대화하기`를 표시한다. Skip은 즉시 buffer된 safety prompt를 열고 경고나 기능 제한 상태를 만들지 않는다.
- UI4. Chat은 safety guidance를 별도 emergency banner가 아닌 대화 안에 표시한다. 지속적인 `대화 종료` action을 제공하고 관리자 설정 `inactivityTimeoutSeconds`, 기본 한 시간의 inactivity 이후 종료됨을 표시한다. Android는 server 값을 authoritative하게 사용하고 한 시간을 hardcode하지 않는다.
- UI5. Chat progress는 `안전 확인`, `대화형 중재`, `마무리` 같은 phase label을 사용한다. Slot completion, missing information, questionnaire progress 또는 handoff readiness를 표시해서는 안 된다.
- UI6. Report UI는 `생성 전`, `생성 중`, `준비됨`, `실패`만 보여준다. 이번 release에서 report body text를 렌더링하지 않는다.
- UI7. Android dashboard는 `24시간`을 기본으로 하고 `7일`, `30일`로 전환한다. 범주형 Low/Mid/High step chart, 별도 chart/axis의 AUQ, detection, AUQ, notification, dialogue approval/session start, 각 intervention, session finish marker를 렌더링한다.
- UI8. Android는 기존 local stream의 현재 Watch PPG를 렌더링하고 `ppgPreviewAvailable=true`인 경우에만 과거 `10초 PPG 보기` action을 제공한다. 다른 prediction data를 대신 표시하지 않고 unavailable state를 보여준다.
- UI9. 최신 realtime state와 최신 longitudinal summary를 evidence timestamp 및 중립적 표현과 함께 표시한다. Empty state는 prediction 없음, AUQ 없음, session 없음, summary unavailable을 구분한다.
- UI10. Administrator dashboard는 patient와 `24h|7d|30d`를 선택하고 class trend, AUQ, alerts, sessions, intervention metadata, report status를 표시한다. Raw-PPG control이나 raw-PPG response data가 없어야 한다.
- UI11. Message, state summary, intervention 또는 report content의 administrator reveal은 기존 reason-entry flow와 audit behavior를 사용한다. Dashboard list/trend view는 이 body를 복호화하지 않는다.
- UI12. Patient camera-rPPG control과 administrator capture section은 `GET /api/rppg/status`가 enabled와 ready 모두를 보고할 때만 표시한다. STT와 self-event control은 계속 숨긴다.
- UI13. Graph는 색상 외에 text label/legend를 제공하고 Android font scaling에서 읽을 수 있는 한국어 label을 유지하며 web에서 keyboard-accessible control과 visible focus를 제공한다.

## 8. API / 데이터 / 상태 요구사항

### 8.1 Breaking session API

- API1. `POST /api/sessions`는 다음 request를 유지한다.

```json
{"sessionType":"alert_checkin|manual_checkin|scheduled_checkin","triggerAlertId":"uuid|null"}
```

신규 방식 session에 대해 HTTP 200으로 다음을 반환한다.

```json
{
  "sessionId":"uuid",
  "sessionType":"alert_checkin",
  "status":"in_progress",
  "interactionPhase":"safety_check",
  "assistantText":"string",
  "safety":{"status":"awaiting_response","riskCodes":[],"supportResources":[]},
  "activeInterventions":[],
  "stateSnapshot":null,
  "reportStatus":"not_started",
  "inactivityTimeoutSeconds":3600,
  "legacy":false,
  "createdAt":"UTC ISO-8601",
  "startedAt":"UTC ISO-8601",
  "endedAt":null
}
```

- API2. `GET /api/sessions/{sessionId}`는 `assistantText`를 요구하지 않고 required integer `inactivityTimeoutSeconds`를 포함한 같은 common new-session state field를 반환한다. `slots`, `missingSlots`, `handoffReady`를 반환하지 않는다. 과거 slot session은 `legacy=true`, `interactionPhase=null`, 불변 status/timestamp/report status를 반환하며 이에 대한 mutation call은 HTTP 409 `legacy_session_read_only`를 반환한다.
- API3. `POST /api/sessions/{sessionId}/messages`는 request `{ "content": "1..10000 characters" }`를 유지하고 다음을 반환한다.

```json
{
  "assistantText":"string",
  "phase":"safety_check|intervention_dialogue|completed|abandoned",
  "safety":{"status":"awaiting_response|clear|concern|urgent","riskCodes":["string"],"supportResources":[{"label":"string","contact":"string"}]},
  "activeInterventions":[{"id":"uuid","type":"approved type","status":"recommended|delivered|started|completed|skipped|failed","presentationOrder":1,"content":"string","createdAt":"UTC ISO-8601"}],
  "stateSnapshot":{"inferenceId":"uuid","scope":"realtime","state":"low|mid|high|unknown","confidence":null,"summaryStatus":"pending|ready|unavailable","summary":"string|null","createdAt":"UTC ISO-8601"},
  "reportStatus":"not_started|generating|ready|failed",
  "inactivityTimeoutSeconds":3600
}
```

`inactivityTimeoutSeconds`는 성공한 모든 신규 session create, get, message, finish response에서 required다. 기존 60..86400 범위의 현재 유효 `system_settings.chat_timeout_seconds`이며, 관리자가 active session 중 설정을 변경하면 다음 response가 새 유효 값을 전달한다.

- API4. `POST /api/sessions/{sessionId}/assessments`는 현재 request와 HTTP 201 response `{ "assessmentId": "uuid" }`를 유지한다. 선택 사항이며 skip 시 placeholder 또는 zero score를 보내지 않는다.
- API5. `POST /api/sessions/{sessionId}/finish`는 `{ "sessionId":"uuid", "status":"completed", "interactionPhase":"completed", "stateSnapshot":{...}, "reportStatus":"not_started|generating", "inactivityTimeoutSeconds":3600 }`를 반환한다. Finish 반복은 idempotent하고 유효 timeout과 함께 저장된 terminal state를 반환한다. Timeout은 `status=abandoned`, `interactionPhase=abandoned`, `completion_reason=timeout`을 만든다.
- API6. 자동 report 생성으로 `POST /api/sessions/{sessionId}/reports`는 누락되었거나 실패한 consented report에 대한 idempotent retry가 된다. `GET /api/sessions/{sessionId}/reports`는 `reportId`, `version`, `status`, `createdAt`, `generatedAt`만 반환하며 decrypted `content`를 반환하지 않는다.

### 8.2 Dashboard API

- API7. `GET /api/me/dashboard?range=24h|7d|30d`는 다음을 반환한다.

```json
{
  "range":"24h",
  "from":"UTC ISO-8601",
  "to":"UTC ISO-8601",
  "predictions":[{"predictionId":"uuid","at":"UTC ISO-8601","class":"low|mid|high|unknown","probability":0.0,"ppgPreviewAvailable":true}],
  "assessments":[{"assessmentId":"uuid","sessionId":"uuid","at":"UTC ISO-8601","instrumentCode":"AUQ","rawScore":0.0,"scaleMin":0.0,"scaleMax":56.0}],
  "events":[{"eventId":"uuid-or-stable-composite","type":"detection|notification|session_started|intervention|session_finished","at":"UTC ISO-8601","sessionId":"uuid|null","predictionId":"uuid|null","label":"string"}],
  "latestState":{"inferenceId":"uuid","scope":"realtime","state":"low|mid|high|unknown","confidence":0.0,"summaryStatus":"pending|ready|unavailable","summary":"string|null","createdAt":"UTC ISO-8601"},
  "longitudinalState":{"inferenceId":"uuid","scope":"longitudinal","state":"low|mid|high|unknown","confidence":0.0,"summaryStatus":"pending|ready|unavailable","summary":"string|null","createdAt":"UTC ISO-8601"},
  "reports":[{"sessionId":"uuid","reportId":"uuid","status":"generating|ready|failed","updatedAt":"UTC ISO-8601"}]
}
```

Nullable object와 probability는 JSON `null`이고 empty series는 `[]`다. Class code는 index만으로 추정하지 않고 각 prediction에 저장된 `model_versions.output_schema`에서 mapping한다.

- API8. `GET /api/me/predictions/{predictionId}/ppg-preview`는 ownership과 PPG가 있는 연결 sensor recording을 요구한다. 최대 512개 point를 시간순으로 반환한다.

```json
{"predictionId":"uuid","windowStartedAt":"UTC ISO-8601","windowEndedAt":"UTC ISO-8601","samplingHz":51.2,"samples":[{"at":"UTC ISO-8601","value":0.0}]}
```

Backend는 memory에서 decrypt/decompress하고 finite value와 prediction window를 검증하며 필요 시 결정론적으로 downsample하고 storage path나 PPG 외 modality를 반환하지 않는다.

- API9. `GET /api/admin/patients/{patientId}/dashboard?range=24h|7d|30d`는 같은 trend, assessment, event, report-status shape와 state class/status metadata를 반환하지만 `ppgPreviewAvailable`, raw PPG, decrypted state `summary`를 제외한다. Administrator sensitive reveal은 기존 `POST /api/admin/resources/{resourceType}/{resourceId}/reveal` allowlist에 `state_inference`를 추가하고 nonblank reason을 요구한다.
- API10. 지원하지 않는 range는 HTTP 422 `invalid_dashboard_range`를 반환한다. 소유하지 않았거나 사용할 수 없는 prediction preview는 다른 환자 ownership 여부를 드러내지 않고 HTTP 404 `ppg_preview_not_found`를 반환한다.

### 8.3 Additive `0003` 데이터 계약

- DATA1. `down_revision="20260715_0002"`인 revision `20260715_0003`만 추가한다. `0001`, `0002`, `neurotruth_schema_v2_5.sql`을 수정하지 않고 legacy row를 backfill하지 않는다.
- DATA2. `state_inferences`를 추가한다.
  - `id uuid PRIMARY KEY`
  - `patient_id uuid NOT NULL` -> `patient_profiles(user_id) ON DELETE RESTRICT`
  - `session_id uuid NULL`, `sessions(id,patient_id) ON DELETE SET NULL (session_id)`에 대한 동일 환자 composite FK
  - `trigger_prediction_id uuid NULL`, `craving_predictions(id,patient_id) ON DELETE SET NULL (trigger_prediction_id)`에 대한 동일 환자 composite FK
  - `inference_scope varchar(16) NOT NULL CHECK IN ('realtime','longitudinal')`
  - `state_class varchar(16) NOT NULL CHECK IN ('low','mid','high','unknown')`
  - `confidence numeric(6,5) NULL CHECK 0..1`
  - `evidence_refs jsonb NOT NULL DEFAULT '{}' CHECK object`
  - `payload_encrypted bytea NOT NULL`
  - `summary_encrypted bytea NULL`
  - `encryption_key_version varchar(32) NOT NULL`
  - `rule_version varchar(64) NOT NULL`
  - `summary_model_version_id uuid NULL REFERENCES model_versions(id) ON DELETE RESTRICT`
  - `summary_status varchar(16) NOT NULL CHECK IN ('pending','ready','unavailable')`
  - `created_at timestamptz NOT NULL DEFAULT now()`
  - index `(patient_id,created_at DESC)`, `(session_id,created_at)`, `(trigger_prediction_id)`.
- DATA3. `ck_model_versions_component`를 재구성해 `0002` 이후 허용되는 모든 값을 보존하고 `state_inference_agent`를 추가한다. Rule-only record는 `model_name=deterministic-state-inference`, code version을 `model_version`으로 사용하고 provider artifact는 사용하지 않는다.
- DATA4. NULL 또는 `safety_check|intervention_dialogue|completed|abandoned`로 검사되는 nullable `sessions.interaction_phase varchar(32)`를 추가하고 기존 row를 NULL로 유지한다. Nullable `dialogue_state_encrypted bytea`와 `dialogue_state_key_version varchar(32)` 및 all-null-or-all-present pair constraint를 추가한다. 모든 신규 session은 이 field를 설정하고 다시 NULL로 만들지 않는다.
- DATA5. 암호화된 dialogue-state JSON schema는 `{ "version":1, "questionBankVersion":"niaaa-samhsa-who-ko-v1", "askedTopicIds":[], "declinedTopicIds":[], "safety":{ "status":"awaiting_response|clear|concern|urgent", "riskCodes":[], "adminInvolvement":"not_offered|offered|accepted|declined" }, "firstInterventionSelected":false }`이다. 이는 대체 환자 문진이 아니라 비임상 대화 제어 상태다.
- DATA6. Nullable `interventions.presentation_order smallint CHECK > 0`와 `interventions.evidence_refs jsonb CHECK object`를 추가한다. `(session_id,presentation_order) WHERE presentation_order IS NOT NULL` partial unique index를 추가한다. 기존 intervention row는 NULL로 유지하고 신규 row는 order와 evidence reference를 요구한다.
- DATA7. AES-GCM AAD는 `state_inferences.payload_encrypted`, `state_inferences.summary_encrypted`, `sessions.dialogue_state_encrypted`에 대해 기존 `table/column/patient/record` 구성을 따른다. 현재 keyring key ID를 저장하고 decrypt/authentication 실패 시 plaintext fallback을 하지 않는다.
- DATA8. `evidence_refs`에는 UUID/type tag와 검색 가능한 안전한 timestamp만 포함한다. Patient utterance, question answer, intervention rationale text, state payload, summary는 암호화된 상태로 유지한다.
- STATE1. 신규 session transition은 수동 종료의 경우 `in_progress/safety_check -> in_progress/intervention_dialogue -> completed/completed`, timeout의 경우 `in_progress/* -> abandoned/abandoned`다. Safety concern은 강제 종료하지 않는다. `interaction_phase=NULL`인 legacy row는 immutable이다.
- STATE2. 환자당 활성 session 하나 제약을 유지한다. Session message 처리는 session별로 serialize해야 하며 concurrent second message에는 HTTP 409 `message_in_progress`를 반환하고 client는 자동 제출하지 않는다.

## 9. 권한, 보안, 개인정보, 감사

- SEC1. 변경된 모든 patient API는 기존 active patient access JWT와 ownership check를 요구한다. Administrator dashboard/reveal API는 administrator role과 완료된 password change를 요구한다.
- SEC2. `ai_analysis` 동의는 session 생성과 agent/state-summary call을 제한한다. `notification`은 alert/AUQ/chat launch notification을 제한하지만 유효한 craving prediction 저장에는 영향을 주지 않는다. `report_generation`은 자동 report 생성을 제한한다. 동의 철회는 향후 activity를 차단하고 보존 이력을 삭제하지 않는다.
- PRIV1. Messages, dialogue control state, intervention basis/content, state payload/summary, AUQ answers, report content는 AES-256-GCM 암호화를 유지한다. Section 8에서 정의한 graph metadata와 search/index field만 plaintext로 둔다.
- PRIV2. Patient dashboard state summary는 소유 환자를 위해 복호화할 수 있다. Administrator dashboard response는 sensitive text를 복호화하지 않으며 administrator reveal은 reason을 요구하고 `Cache-Control: no-store`를 사용한다.
- PRIV3. PPG preview는 환자 전용이며 요청 prediction window와 최대 512 point만 포함하고 backend가 cache하지 않는다. Administrator PPG endpoint는 만들지 않는다.
- AUDIT1. Coded safety detection 및 involvement choice, dialogue/provider validation failure, state-summary failure, legacy mutation attempt, administrator state/report/message/intervention reveal reason, 금지된 cross-owner preview attempt를 민감 content나 credential 없이 audit한다.
- AUDIT2. Model provider error는 `dialogue_provider_error`, `dialogue_output_rejected`, `state_summary_provider_error`, `report_generation_failed` 같은 stable code로 축약한다. Raw provider body와 credential은 response나 audit metadata에 넣지 않는다.

## 10. 오류, edge case, 동시성 동작

- ERR1. 변경/신규 endpoint는 `{"detail":{"code":"stable_code","message":"safe message"}}`를 사용한다. Authentication behavior는 기존 401/403 contract를 유지한다.
- ERR2. Dialogue-provider 또는 invalid-output failure는 patient message를 한 번 저장하고 stable code를 audit하며 새 질문이나 승인되지 않은 intervention이 없는 결정론적 supportive response를 반환한다. 긴급 결정론적 safety content는 provider failure로 억제되지 않는다.
- ERR3. State-summary failure는 `summary_status=unavailable`인 결정론적 inference를 commit하고 report failure는 `status=failed`를 commit한다. 어느 실패도 prediction class나 session terminal state를 변경하지 않는다.
- ERR4. AES authentication failure, DB unavailable 또는 sensor ciphertext unavailable은 503으로 fail closed하고 partial plaintext response를 반환하지 않는다. PPG preview는 missing/unowned/unavailable data에 404를 사용한다.
- EDGE1. 유효한 prediction이 없으면 state는 `unknown`, confidence는 NULL이며 AUQ를 별도로 표시한다. Quality-gate failure를 low craving으로 표시하지 않는다.
- EDGE2. Empty dashboard range는 valid empty array와 NULL latest state를 반환한다. 같은 timestamp의 여러 prediction은 `(timestamp,id)`로 정렬하며 모든 event array는 같은 stable ordering을 사용한다.
- EDGE3. Corrected user statement는 explicit correction 뒤에만 `declinedTopicIds`에서 topic ID를 제거할 수 있지만 slot이나 message가 뒷받침하지 않는 clinical fact를 만들지 않는다.
- EDGE4. Manual finish session은 AUQ를 건너뛰었거나 결정론적 fallback 외 intervention을 수락하지 않았어도 idempotently `completed`다. Timeout은 항상 `abandoned`다.
- CONC1. Repository는 message processing과 intervention order에 transaction/advisory row lock을 사용한다. Session별로 한 번에 하나의 message turn과 presentation order만 commit할 수 있다.
- CONC2. Report 생성은 `(session_id,version)`별 unique이고 auto-queue/retry는 idempotent하다. State inference 생성은 구체적인 triggering evidence ID를 기록해 retry가 같은 scope/trigger event를 중복 생성하지 않게 한다.

## 11. 의존성과 설정

- DEP1. FastAPI, PostgreSQL/Alembic, 기존 AES-GCM keyring, Bedrock adapter, Android Compose/network/auth stack, React web stack을 재사용한다. Dependency upgrade는 허용하지 않는다.
- DEP2. `intervention-dialogue-v1`, `state-summary-v1`, `state-rule-v1`, `niaaa-samhsa-who-ko-v1`에 대한 local versioned prompt/question-bank configuration을 추가하고 실제 model/prompt version을 `model_versions`로 등록한다.
- DEP3. `system_settings.chat_timeout_seconds`를 계속 사용하며 기본값은 3600이고 기존 60..86400 범위에서 관리자가 설정할 수 있다. `system_settings.interventions_enabled`도 계속 사용한다. Interventions가 disabled이면 safety content와 state inference는 계속하지만 ordinary intervention row/text는 생성하지 않는다.
- DEP4. `RPPG_ENABLED=false`를 backend capability flag로 재사용한다. UI는 enabled-and-ready status를 확인하며 두 번째 rPPG flag나 새 checkpoint를 추가하지 않는다.
- DEP5. Notice acknowledgement version은 Keystore-backed app preferences에 저장하는 Android constant `INTERVENTION_PRODUCT_NOTICE_VERSION=2026-07-15-v1`이다. Health data를 포함하지 않는다.

## 12. Migration, rollout, rollback

- MIG1. Additive Alembic `20260715_0003`만 적용한다. 기존 session, slot, memory, intervention, report, rPPG row를 변경 없이 보존하고 backfill을 실행하지 않는다.
- MIG2. 새 intervention-first data가 존재할 수 있으므로 `0003` downgrade는 명확한 runtime error로 fail closed해야 한다. 운영 rollback은 forward-compatible additive schema에 이전 application image를 사용한다.
- ROLL1. Backend migration/backend와 Android를 하나의 조율된 breaking release로 배포한다. Administrator web은 backend 직후 배포한다. 별도 검증 전까지 rPPG는 disabled로 유지한다.
- ROLL2. Pre-release readiness는 fresh `0001 -> 0002 -> 0003` migration, populated `0002` database upgrade, backend test, Android test/build/lint, web build와 treatment-effect claim 없는 fake/saved sensor input 기반 전체 manual demo flow를 요구한다.
- BACK1. Application image를 이전 backend/Android/web version으로 함께 rollback한다. `0003` table/column은 유지하며 이전 code는 이를 무시한다. 신규 encrypted data를 삭제하거나 역변환하지 않는다.
- BACK2. Image rollback 없이 신규 dialogue flow를 중지해야 하면 기존 administrator settings에서 ordinary interventions를 disable하고 alert-driven session entry를 운영상 중단한다. Safety와 retained record는 계속 사용할 수 있다.

## 13. 구현 경계

### 13.1 예상 변경 영역

- Backend V2.5 session agent/service/routes/repository/runtime, 집중된 state-inference/dashboard service, administrator sensitive-resource 지원, additive Alembic `0003`.
- Android authenticated session contract/view-model/chat flow, first-use notice, patient dashboard와 test. Watch sensor collection 자체는 변경하지 않는다.
- Administrator React dashboard와 status-only report 표시 및 새 public contract를 위한 집중 문서/test.

### 13.2 금지 변경

- `apps/backend/alembic/versions/20260715_0001_v25_baseline.py`, `apps/backend/alembic/versions/20260715_0002_dgx_rppg.py`, `neurotruth_schema_v2_5.sql`, `.env`, `.gitignore`를 수정하지 않는다.
- rPPG code/data, legacy slots/memory/reports 또는 기존 model artifact를 삭제하지 않는다.
- Slot write, runtime NeuroSync import, craving-model experiment, STT, self-event capture, demo replay, administrator raw PPG 또는 report-body UI를 추가하지 않는다.
- Watch firmware/data collection이나 craving model threshold, label, class mapping, cooldown, warm-up behavior를 변경하지 않는다.
- 관련 없는 refactor, formatting churn, dependency upgrade, broad rewrite 또는 generated-file churn을 하지 않는다.

### 13.3 최소 변경 지침

- Authentication, consent, AES-GCM AAD, report queue, system settings, model registry, alert, dashboard styling pattern을 재사용한다.
- 이 명세에서 response 변경 또는 route 추가를 명시하지 않은 기존 endpoint를 보존한다.
- Legacy data를 재작성하지 말고 legacy read path를 new-session write path와 격리한다.
- 위임 worker는 backend, Android, administrator web ownership으로 나눠 같은 file을 수정하지 않게 한다.

## 14. 인수 기준

- AC1. 안내를 확인한 환자와 조건을 충족한 alert가 있을 때 환자가 `지금 대화하기`를 선택하면 신규 인증 session이 `safety_check`로 시작하고 AUQ를 수행하거나 건너뛸 수 있으며 slot row 또는 slot response field가 생성되지 않는다.
- AC2. AUQ를 건너뛴 상태에서 환자가 계속하면 zero/placeholder assessment 없이 safety checking, first intervention, free dialogue, finish, state inference, consented report generation을 계속 사용할 수 있다.
- AC3. 즉각적 위험 표현이 주어져 message를 처리할 때 ordinary dialogue 전에 결정론적 119/109 guidance가 inline으로 나타나고 coded audit가 존재하며 live contact를 약속하지 않고 사용자는 계속할 수 있다.
- AC4. Contextual evidence가 있을 때 첫 ordinary intervention을 선택하면 FR6의 결정론적 우선순위가 allowlisted type 하나를 선택하고 저장 row가 presentation order, encrypted text/basis, evidence ID, actual version reference를 가진다.
- AC5. 이전에 질문했거나 거절한 topic guide가 있을 때 이후 turn을 생성하면 환자가 해당 topic을 명시적으로 수정하지 않는 한 paraphrased repeat question이 나타나지 않으며 각 assistant turn에는 질문이 최대 하나다.
- AC6. LLM output에 진단, 치료효과/인과 표현, 여러 질문 또는 승인되지 않은 intervention이 있을 때 validation/one repair가 실패하면 safe deterministic fallback을 사용하고 rejected text를 assistant content로 저장하지 않는다.
- AC7. Realtime 또는 longitudinal inference가 있으면 class는 최신 유효 persisted model class 또는 `unknown`이고 evidence ID가 저장되며 encrypted payload가 summary failure에도 남고 AUQ/dialogue가 synthesized clinical score를 만들지 않는다.
- AC8. Manual finish 또는 기본 한 시간 inactivity가 있을 때 session은 각각 `completed/completed` 또는 `abandoned/abandoned`가 되고 final state snapshot이 저장되며 consented report는 slot evidence 없이 독립적으로 `generating|ready|failed`를 거친다.
- AC9. Patient dashboard range가 주어지면 Low/Mid/High, AUQ, event marker, report state, latest realtime state, longitudinal state가 persisted UTC evidence와 일치하고 local time으로 렌더링되며 empty data에 명시적 empty state가 있다.
- AC10. Linked PPG가 있는 owned prediction에 대해 patient가 preview를 요청하면 해당 10초 window와 최대 512 finite point만 반환한다. 다른 환자와 모든 administrator는 PPG에 접근할 수 없다.
- AC11. Administrator dashboard 사용 시 trend/status metadata는 decryption 없이 보인다. Message/state/intervention/report reveal은 reason을 요구하고 no-store data를 반환하며 audit record를 만든다.
- AC12. `0003` 이전 slot session은 `legacy=true`로 읽을 수 있고 backfill되지 않으며 모든 mutation attempt가 `legacy_session_read_only`를 반환한다. 신규 session은 slot이나 legacy memory를 쓰지 않는다.
- AC13. rPPG/STT/self-event feature가 명시적으로 active가 아니면 control이 없고 core PPG/GSR -> intervention -> DB -> dashboard behavior는 이에 의존하지 않는다.
- AC14. Demo와 모든 generated text에는 즉각적 craving reduction, CBT efficacy, diagnosis, treatment success 또는 causal effect 주장이 없어야 한다.
- AC15. Fresh/populated database에 `0003`을 적용하면 constraint/index/FK가 통과하고 `0001`/`0002`/baseline SQL hash가 바뀌지 않으며 이전 rPPG와 V2.5 data를 계속 읽을 수 있다.
- AC16. Section 15의 backend, Android, web validation은 fake adapter로 live LLM call 없이 통과하고, manual acceptance flow는 saved/live PPG/GSR prediction부터 두 dashboard까지 완료된다.
- AC17. 성공한 신규 session create, get, message 또는 finish response가 있을 때 required `inactivityTimeoutSeconds`는 유효한 관리자 설정과 같고 Android는 hardcoded one-hour assumption 없이 해당 값을 표시한다.

## 15. 검증 계획

| 검사 | 명령 또는 방법 | 기대 결과 |
| --- | --- | --- |
| Spec pair | `python C:/Users/NeuroAI-Laptop/.codex/skills/feature-planner/scripts/validate_spec_pair.py docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.md` | English/Korean pair, ID, handoff, progress record가 일치한다. |
| Backend unit/integration | `apps/backend`에서 `.\.venv\Scripts\python.exe -m pytest -q` | Fake Bedrock으로 slot-free session, safety, interventions, state inference, dashboard, encryption, legacy, regression이 통과한다. |
| Fresh/populated migration | 격리된 fresh DB와 `20260715_0002` fixture DB에 `alembic upgrade head`를 실행하고 `alembic_version`과 constraint를 검사한다. | Revision이 `20260715_0003`이고 기존 data가 남으며 baseline file이 변경되지 않는다. |
| Android | `apps/mobile`에서 `.\gradlew.bat testDebugUnitTest assembleDebug lintDebug` | Notice, optional AUQ, chat, dashboard, hidden deferred UI의 unit test, debug APK, lint가 통과한다. |
| Administrator web | `apps/web`에서 `npm.cmd run build`와 repository test harness가 있으면 focused component/API test를 실행한다. | Production build가 통과하고 administrator PPG 또는 report body를 렌더링하지 않는다. |
| Static contract scan | New-session path에서 source/UI output의 `slots`, `missingSlots`, `handoffReady`, treatment claim, rPPG visibility를 검색한다. | 제거 field가 신규 contract에 없고 legacy read가 격리되며 금지 claim이 없다. |
| Security | Fake ciphertext/AAD tamper, cross-owner PPG, reasonless reveal, provider-error test. | Fail closed하고 plaintext/path/credential leak가 없으며 필요한 audit record가 존재한다. |
| Manual demo | Test patient와 saved/live PPG/GSR input으로 alert -> approval -> skip/submit AUQ -> safety -> intervention -> finish -> patient/admin dashboard를 수행한다. | End-to-end flow가 완료되고 class trend는 설명적이며 즉시 감소나 치료효과 주장이 없다. |
| Diff integrity | `git diff --check`와 `.env`, `.gitignore`, `0001`, `0002`, baseline SQL의 before/after SHA-256. | Whitespace error가 없고 모든 forbidden-file hash가 바뀌지 않는다. |

## 16. 위험과 참고 사항

- RISK1. Ledger가 있어도 free dialogue가 의미상 반복될 수 있다. Topic ID, full history, server validation, one repair, 집중된 한국어 paraphrase test로 완화한다.
- RISK2. Categorical model class와 AUQ가 불일치할 수 있다. 둘을 별도 evidence로 표시하고 state payload에 불일치를 암묵적으로 기록하되 새 score로 평균내지 않는다.
- RISK3. Android chart는 30일 범위에서 조밀할 수 있다. 모든 server event를 보존하면서 rendering에만 step aggregation을 사용하고 accessible label을 제공한다.
- RISK4. Historical sensor file에 usable PPG가 없거나 이전 shape일 수 있다. Safe validation 후 `ppg_preview_not_found`를 반환하고 data를 추정하거나 대체하지 않는다.
- RISK5. 연구 질문은행 source는 content를 안내하지만 agent를 clinician으로 만들지 않는다. Paraphrase를 non-diagnostic하고 versioned하게 유지하고 demo 전 검토한다.
- RISK6. 이번 release는 session response field를 의도적으로 breaking한다. 조율된 backend/Android deployment와 rollback이 필수다.

## 17. 구현 체크리스트 / 진행 기록

이 절은 구현 중 main orchestrating agent가 갱신한다. 영문 기준 문서를 먼저 갱신하고 한국어 mirror를 두 번째로 갱신한다.

| ID | 작업 / 범위 | 담당 | 상태 | 변경 파일 | 검증 | 참고 |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | English source와 synchronized Korean mirror 확정 | Spec worker | Complete | `docs/specs/2026-07-15-neurotruth-intervention-first-product-redesign-spec.md`; `.ko.md` | `validate_spec_pair.py` | Decision-complete implementation handoff. |
| P2 | `0003`, intervention-first backend, state inference, dashboards, security, test 추가 | Backend worker | Complete | `apps/backend/alembic/versions/20260715_0003_intervention_first.py`; `apps/backend/app/v25/`; focused backend tests | 전체 backend 162개 통과; focused suite 70개와 43개 통과; compile check 통과; 실제 PostgreSQL fresh `0001 -> 0003`와 populated `0002 -> 0003` migration이 legacy `interaction_phase=NULL`을 유지하며 통과 | `0001`, `0002`, baseline SQL은 수정하지 않았다. |
| P3 | Android notice, optional AUQ/chat contract, patient dashboard, test 구현 | Android worker | Complete | `AuthenticatedSessionApi.kt`; `InterventionNotice.kt`; `PatientDashboard.kt`; related view-model/UI/tests | 독립 Android 실행에서 unit test 61/61 통과; assemble 통과; lint 0 errors; debug APK 생성 | Watch collection을 보존하고 live PPG는 local에 유지한다. |
| P4 | 제한된 administrator dashboard, hidden deferred UI, build check 구현 | Web worker | Complete | `apps/web/src/App.js`; `apps/web/src/api.js`; `apps/web/src/styles.css` | Production web build 통과 | Administrator raw PPG 또는 report body 없음. |
| P5 | Minimal diff 검사, 최종 validation/migration 실행, prohibited claim과 forbidden-file hash 확인, progress 동기화 | Main agent | Complete | Verification only; spec progress updates | Backend 전체/focused/compile, 실제 fresh/populated PostgreSQL migration, Android test/assemble/lint/APK, web production build, final spec validator, protected-hash check가 통과했다. | 두 번째이자 최종 read-only re-review에서 unresolved issue가 없었다. 보호 대상 `.env`, `.gitignore`, `0001`, `0002`, baseline SQL hash는 변경되지 않았다. Live paid LLM call은 필요하지 않았다. |

## 18. 개정 이력

| Version | 날짜 | 작성자 | 변경 내용 |
| --- | --- | --- | --- |
| 1.2 | 2026-07-15 | Feature Planner | 최종 backend, PostgreSQL, Android, web, integrity, read-only review, spec-pair validation 후 implementation을 complete로 표시했다. |
| 1.1 | 2026-07-15 | Feature Planner | Server-authoritative `inactivityTimeoutSeconds` response contract를 추가하고 final review 전 implementation/validation progress를 기록했다. |
| 1.0 | 2026-07-15 | Feature Planner | 최초 확정 intervention-first redesign spec pair. |
