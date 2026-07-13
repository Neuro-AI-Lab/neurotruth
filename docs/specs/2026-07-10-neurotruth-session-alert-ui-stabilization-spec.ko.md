# NeuroTruth 세션, 알림 및 UI 안정화 스펙

- **언어 역할:** 한국어 미러 스펙
- **스펙 상태:** Finalized
- **스펙 버전:** 1.1
- **최종 업데이트:** 2026-07-10
- **영문 원본:** `docs/specs/2026-07-10-neurotruth-session-alert-ui-stabilization-spec.md`
- **한국어 미러:** `docs/specs/2026-07-10-neurotruth-session-alert-ui-stabilization-spec.ko.md`
- **요청자 / 소유자:** NeuroTruth team
- **구현 상태:** Implemented

## 0. Codex 구현 핸드오프

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: docs/specs/2026-07-10-neurotruth-session-alert-ui-stabilization-spec.md
korean_mirror_spec: docs/specs/2026-07-10-neurotruth-session-alert-ui-stabilization-spec.ko.md
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

## 1. 요약

NeuroTruth는 현재 end-to-end로 실행되지만 alert history와 cooldown 상태가 sensor session 사이에서 공유되고, 휴대폰은 sensor와 intervention memory에 서로 다른 identifier를 사용하며, phone/watch notification은 backend rule decision 대신 raw prediction class로 동작합니다. 이번 변경은 alert evaluation을 session별로 격리하고, 하나의 mobile session identity를 도입하며, backend alert metadata를 authoritative하게 만들고, 기존 `watch_test` 기반 UI를 단계별 recommendation 및 required-intervention 동작에 맞춥니다. Wear OS packaging lint 오류와 sensor simulator의 불안정한 session 및 console 동작도 수정합니다. Public endpoint, database schema, Bedrock prompt, RandomForest behavior는 호환성을 유지합니다.

## 2. 목표

- G1. Prediction window, cooldown, downtrend 상태가 session boundary를 넘어가지 않게 합니다.
- G2. Prediction, conversation, slot, handoff record를 하나의 mobile/backend session ID로 연결합니다.
- G3. Phone과 watch의 intervention action이 backend `alertAction` decision과 일치하게 합니다.
- G4. 현재 dashboard와 8문항 state-check experience를 유지합니다.
- G5. Backend, Docker, Android build, unit test, Wear lint validation을 깨끗하게 통과합니다.

## 3. 비목표

- NG1. API authentication, CORS redesign, external-network production hardening은 하지 않습니다.
- NG2. Database migration, model retraining, weight-format change, Bedrock prompt redesign은 하지 않습니다.
- NG3. Web UI redesign, dependency upgrade, broad Android refactor, generated-file churn은 하지 않습니다.
- NG4. STT 또는 microphone flow는 추가하지 않습니다.

## 4. 사용자와 사용 사례

- UC1. 휴대폰이 sensor monitoring을 시작하면 모든 prediction 및 intervention data가 하나의 session에 남습니다.
- UC2. Recommendation은 dashboard를 갱신하고 non-blocking phone notification과 짧은 watch vibration을 만듭니다.
- UC3. Required action은 phone state check를 열고 더 강한 watch alert를 만들며, 8문항 이후 text chat으로 전환합니다.
- UC4. Cooldown 또는 suppressed backend decision은 notification, vibration, automatic navigation 없이 표시 데이터만 갱신합니다.
- UC5. Operator가 simulator를 실행하면 console crash 없이 안정적인 same-session SSE event를 받습니다.

## 5. 최종 결정

| ID | 도메인 | 결정 | 출처 |
| --- | --- | --- | --- |
| D1 | Backend state | 최대 256 session의 LRU registry에서 session별 `AlertEvaluator`를 유지합니다. | Approved plan |
| D2 | Warm-up | Mean threshold는 전체 configured window가 필요하며 `ALERT_HIGH_STREAK`는 early required intervention을 허용합니다. | Approved plan |
| D3 | Session identity | `PhoneMonitoringState`가 active session을 소유하고 sensor, chat, slots, handoff가 이를 공유합니다. | Approved plan |
| D4 | Action precedence | `alertAction`, `alertLevel`, alert metadata가 없는 legacy payload의 raw `class` 순서로 결정합니다. | Approved plan |
| D5 | Phone UI | Recommendation은 non-blocking이며 required는 8문항 state check 후 chat을 엽니다. | User decision |
| D6 | Watch UI | Recommendation은 짧은 vibration, required는 강한 pattern과 phone-check guidance를 사용합니다. | User decision |
| D7 | Compatibility | Endpoint path와 기존 class/SSE field를 유지하고 optional `sessionId`를 공식화합니다. | Approved plan |

## 6. 기능 요구사항

- FR1. Backend는 alert 평가 전에 session ID를 결정하고 해당 session의 evaluator만 사용해야 합니다.
- FR2. Evaluator registry는 사용 시 LRU order를 갱신하고 256개 초과 시 least recently used session만 evict해야 합니다.
- FR3. `ALERT_WINDOW_SIZE` 값이 모이기 전 mean-based alert는 `none`, `triggerReason=window_warming_up`을 반환해야 하며 configured class-2 high streak는 required를 반환할 수 있습니다.
- FR4. Cooldown은 session-local이어야 하며 `alertLevel`을 유지하면서 `alertAction=cooldown`, `alertRequired=false`를 반환해야 합니다.
- FR5. Mobile sensor payload는 `sessionId`와 backward-compatible `sessionStartedAtMs`를 모두 전송해야 합니다.
- FR6. SSE parsing은 `sessionId`를 유지하고 intervention request는 latest server-echoed session ID를 우선 사용하며 없으면 shared local ID를 사용해야 합니다.
- FR7. Mobile data clear는 새 shared session을 시작하고 alert gate, chat, state check, handoff, latency UI state를 함께 초기화해야 합니다.
- FR8. Phone과 watch는 `none`, `cooldown`에서 visible prediction state만 갱신하고 user-facing action을 억제해야 합니다.
- FR9. Phone recommendation은 non-blocking banner/notification을 표시하고 required는 state check를 한 번 열며 제출 후에만 chat으로 진행해야 합니다.
- FR10. Watch recommendation과 required는 서로 다른 vibration/notification copy를 사용하고 기존 local cooldown은 동일 authoritative action의 duplicate rendering 방지에만 사용해야 합니다.
- FR11. Alert metadata 없는 legacy SSE는 0 none, 1 recommend, 2 required class mapping을 유지해야 합니다.
- FR12. Simulator는 하나의 session start value를 재사용하고 physiological synthetic PPG waveform 및 ASCII-only completion copy를 사용해야 합니다.

## 7. 사용자 경험 / UI 요구사항

- UI1. 기존 user dashboard, developer dashboard, chart, state-check question, text chat layout을 유지합니다.
- UI2. Recommendation은 현재 phone screen에서 이동시키지 않습니다.
- UI3. Required는 authoritative action당 state check를 한 번 요청하며 cooldown 중 다시 열지 않습니다.
- UI4. Watch는 action이 suppressed되어도 class와 alert level을 계속 표시합니다.
- UI5. 기존 Korean copy를 기본으로 유지하고 recommendation과 required를 구분하는 alert-stage copy만 변경할 수 있습니다.

## 8. API / 데이터 / 상태 요구사항

- API1. `POST /sensor-window`, `GET /prediction-stream`, `POST /api/intervention/chat`, `POST /api/intervention/slots`, `POST /api/intervention/handoff`를 변경하지 않습니다.
- API2. Sensor-window body에 optional `sessionId`를 추가하고 `sessionStartedAtMs`만 보내는 client도 계속 허용합니다.
- API3. SSE `class`, `alertLevel`, `alertAction`, `windowMean`, `triggerReason`, `alertRequired`, `sessionId`를 유지합니다.
- DATA1. Schema migration 또는 historical-data rewrite는 필요하지 않습니다.
- STATE1. Alert registry state는 in-process이며 256 session으로 제한되고 database persistence는 기존 best-effort를 유지합니다.

## 9. 권한, 보안, 개인정보 및 감사

- SEC1. Internal-LAN demo security model과 현재 Android permission을 유지하고 required Wear packaging metadata만 추가합니다.
- PRIV1. Bearer token 또는 `.env` 값을 test output, log, docs에 출력하지 않습니다.
- AUDIT1. 기존 prediction, alert, conversation, slot, handoff database record를 audit trail로 유지합니다.

## 10. 오류, 엣지 케이스 및 동시성 동작

- ERR1. Unknown `alertAction`은 valid `alertLevel`로 fallback하고 unknown level은 metadata가 없을 때만 class로 fallback합니다.
- ERR2. `alertLevel=required`여도 cooldown action은 vibration, notification, navigation을 실행하지 않습니다.
- EDGE1. Server session ID가 없으면 legacy server compatibility를 깨지 않고 shared local session을 사용합니다.
- EDGE2. Service restart는 independent private ID를 만들지 않고 current shared session을 읽습니다.
- CONC1. Concurrent session은 independent evaluator를 사용하며 LRU eviction은 evicted session의 future history에만 영향을 주고 다른 active evaluator에는 영향을 주지 않습니다.

## 11. 의존성과 설정

- DEP1. 현재 Python, Android, Wear OS, Samsung SDK, Bedrock dependency를 재사용합니다.
- DEP2. 기존 alert environment variable을 유지하고 새 dependency 또는 environment key를 추가하지 않습니다.

## 12. 마이그레이션, 롤아웃 및 롤백

- MIG1. Database 또는 stored-data migration은 필요하지 않습니다.
- ROLL1. Backend와 Android app을 함께 rebuild하며 legacy client는 class 및 `sessionStartedAtMs` fallback으로 호환됩니다.
- BACK1. Source-level rollback으로 이전 backend/mobile image와 APK를 복원하며 persisted record rollback은 필요하지 않습니다.

## 13. 구현 경계

- Backend worker는 alert evaluation/registry, inference wiring, simulator, focused backend test를 변경할 수 있습니다.
- Mobile worker는 shared session state, payload/SSE model, alert policy, phone/watch action handling, Wear manifest, focused Android test를 변경할 수 있습니다.
- Main agent는 이 spec pair와 active Markdown documentation을 업데이트할 수 있습니다.
- Web source, DB schema, model code/weight, Bedrock prompt, dependency version, public endpoint name, unrelated UI는 변경하지 않습니다.

## 14. 인수 조건

- AC1. Interleaved 두 session이 independent mean, streak, downtrend decision, cooldown action을 만듭니다.
- AC2. Mean-based alert는 기본 10 sample 전까지 warm-up이며 class 2 세 번 연속은 early required를 만들 수 있습니다.
- AC3. 하나의 mobile monitoring run에서 sensor, chat, handoff request가 같은 session ID를 사용하고 DB handoff context가 해당 session alert를 포함합니다.
- AC4. `cooldown`, `none`은 phone/watch display state를 갱신하지만 notification, vibration, navigation은 실행하지 않습니다.
- AC5. Recommendation은 non-blocking이고 required는 chat 전에 state check를 엽니다.
- AC6. Legacy class-only payload는 기존 phone/watch behavior를 유지합니다.
- AC7. Wear lint error가 0개이고 simulator가 exit zero이며 public API field가 호환됩니다.

## 15. 검증 계획

| 검사 | 명령 또는 방법 | 기대 결과 |
| --- | --- | --- |
| Backend compile | `python -m compileall app tests` from `apps/backend` | PASS |
| Backend tests | `.venv/Scripts/python.exe -m pytest -q` from `apps/backend` | PASS |
| Docker | `docker compose -f apps/db/docker-compose.yml up -d --build` | DB healthy; backend/web up |
| Integration | Sensor POST, SSE, DB query, Bedrock chat/slots/handoff | Same session and HTTP 200 |
| Android | `apps/mobile/gradlew.bat clean assembleDebug testDebugUnitTest lintDebug` | PASS, zero lint errors |
| Web | Browser and nginx proxy check | Connected, no console error |
| Hygiene | `git diff --check` and stale-path scan | No errors; historical refs only |

## 16. 위험과 참고사항

- RISK1. LRU eviction은 inactive session의 in-memory alert history를 의도적으로 버리지만 persisted record는 삭제하지 않습니다.
- RISK2. Physical phone/watch vibration과 automatic navigation은 connected device가 필요하며 ADB device가 없으면 skipped validation을 기록합니다.
- RISK3. `.env`, model weight, Samsung AAR, `local.properties`는 이전 user decision에 따라 trackable 상태를 유지하며 자동 stage, commit, push하지 않습니다.

## 17. 구현 체크리스트 / 진행 기록

| ID | 작업 / 범위 | 소유자 | 상태 | 변경 파일 | 검증 | 참고 |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Synchronized spec pair 확정 | Main | Complete | This spec pair | Pair self-review | Approved plan 및 UI refinement decision을 기록했습니다. |
| P2 | Backend session alert isolation 및 simulator | Backend worker | Complete | `apps/backend/app/alerts.py`, `apps/backend/app/inference.py`, `apps/backend/tools/sim_app.py`, focused backend tests | Compileall; pytest 24개 통과; Docker sensor/SSE/Postgres 통합 | 최대 크기가 제한된 session별 LRU evaluator, warm-up, early high-streak required, 안정적인 simulator session을 구현했습니다. |
| P3 | Mobile shared session 및 authoritative alert UI | Mobile worker | Complete | Phone/Wear alert policy, monitoring state, uploader/sender, manifest, focused unit tests | `assembleDebug`, `testDebugUnitTest`, `lintDebug` 통과 | Shared session identity와 authoritative action 우선순위를 구현했습니다. ADB 기기 연결 후 실제 기기 동작 확인은 남아 있습니다. |
| P4 | Documentation 및 end-to-end verification | Main | Complete | 활성 README, API, mobile readiness, upload notes, plan, 이 spec pair | Spec pair 검증; 실제 Bedrock chat/handoff; same-session DB 연결; `git diff --check` | Docker stack은 실행 중이며 ADB 연결 기기가 없어 실제 phone/watch 검증은 건너뛰었습니다. |

## 18. 변경 이력

| 버전 | 날짜 | 작성자 | 변경사항 |
| --- | --- | --- | --- |
| 1.1 | 2026-07-10 | Feature Planner | 완료된 backend/mobile 구현, 문서 업데이트, 검증 결과를 기록했습니다. |
| 1.0 | 2026-07-10 | Feature Planner | Initial finalized stabilization spec. |
