# NeuroTruth v7 음성·rPPG·20초 갈망 모델 — 구현 명세

<!-- feature-planner-control
{
  "workflow": "feature-planner/v7",
  "state": "complete",
  "source_spec": "docs/specs/2026-07-17-neurotruth-v7-voice-rppg-20s-model-spec.md",
  "korean_mirror": "docs/specs/2026-07-17-neurotruth-v7-voice-rppg-20s-model-spec.ko.md",
  "spec_revision": 12,
  "reviewed_revision": 12,
  "selected_strategy": "STRAT-1",
  "implementation_direction": "user-approved-divergence",
  "direction_decision_id": "D-005",
  "minimal_change_policy": "strict",
  "final_domain_gate": "confirmed_none",
  "open_question_ids": [],
  "active_slices": [],
  "next_action": "none"
}
-->

> 영문 파일이 구현의 기준이며 이 문서는 사용자 검토와 인계를 위한 동기화된 한국어 미러다.

## 1. Review Snapshot

| Review item | Current value |
| --- | --- |
| Lifecycle | `complete`, revision 12, 사용자 승인 완료 |
| Outcome | v7 3탭 Android 흐름에 20초 갈망 추론, STT/TTS, 20초 rPPG, 문장형 AUQ와 확률 구간 문구를 통합한다. |
| Recommended implementation | `STRAT-1` — 기존 NeuroTruth 모델·세션·rPPG·동의·Compose 구조를 유지하며 확장한다. |
| Planned production targets | `apps/backend/model/weights/final_model_win20s_high2p4/model.py::Conv1DNet`; `apps/backend/model/weights/final_model_win20s_high2p4/model_weights.pt::state_dict`; `apps/backend/model/weights/final_model_win20s_high2p4/model_metadata.json::model metadata`; `apps/backend/app/inference.py::BinaryCravingModel`; `apps/backend/app/v25/rppg_service.py::RppgJobService`; `apps/backend/app/v25/routes_rppg.py::create_rppg_job`; `apps/backend/app/v25/rppg_media.py::probe_video`; `apps/backend/app/settings.py::Settings`; `apps/backend/alembic/versions/20260717_0005_voice_rppg_20s.py::upgrade`; `apps/db/docker-compose.yml::services`; `apps/db/docker-compose.dgx.yml::services`; `apps/stt-service/app.py::transcribe`; `apps/stt-service/Dockerfile::image`; `apps/stt-service/requirements.txt::dependencies`; `apps/backend/app/v25/stt_client.py::SttClient`; `apps/backend/app/v25/routes_stt.py::router`; `apps/backend/app/v25/runtime.py::Runtime`; `apps/backend/app/v25/models.py::ConsentInput`; `apps/backend/app/v25/auth_service.py::consent serialization`; `apps/backend/app/v25/repository.py::consent persistence and message modality`; `apps/backend/app/v25/session_agents.py::free-dialogue-v3`; `apps/backend/app/v25/session_service.py::post_message`; `apps/backend/app/v25/routes_sessions.py::message/transcription contracts`; `apps/backend/app/main.py::router registry`; `apps/mobile/app/src/main/AndroidManifest.xml::RECORD_AUDIO`; `apps/mobile/app/src/main/java/com/example/healthsensor/AuthModels.kt::ConsentSelection`; `apps/mobile/app/src/main/java/com/example/healthsensor/AuthScreen.kt::voice consent`; `apps/mobile/app/src/main/java/com/example/healthsensor/MobileAuthRuntime.kt::voice consent runtime`; `apps/mobile/app/src/main/java/com/example/healthsensor/AuthenticatedApiClient.kt::authenticated multipart`; `apps/mobile/app/src/main/java/com/example/healthsensor/AuthenticatedSessionApi.kt::transcription/message API`; `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt::monitoring/chat state`; `apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringService.kt::sensor cadence`; `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt::three-tab voice/TTS/AUQ UI`; `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt::probability bands and AUQ axis`; `apps/mobile/app/src/main/java/com/example/healthsensor/RppgCameraScreen.kt::20-second capture`; `apps/mobile/app/src/main/java/com/example/healthsensor/RppgModels.kt::20-second contract`; `apps/mobile/app/src/main/java/com/example/healthsensor/RppgApi.kt::20-second upload`; `apps/mobile/app/src/main/java/com/example/healthsensor/RppgViewModel.kt::20-second state` |
| Expected additions | New production files: `apps/backend/model/weights/final_model_win20s_high2p4/model.py`, `apps/backend/model/weights/final_model_win20s_high2p4/model_weights.pt`, `apps/backend/model/weights/final_model_win20s_high2p4/model_metadata.json`, `apps/backend/alembic/versions/20260717_0005_voice_rppg_20s.py`, `apps/stt-service/app.py`, `apps/stt-service/Dockerfile`, `apps/stt-service/requirements.txt`, `apps/backend/app/v25/stt_client.py`, `apps/backend/app/v25/routes_stt.py`; dependency: `faster-whisper`; shared abstraction: `One STT client boundary`; 집중 테스트와 v9 인계 자료. |
| Work plan | 모델/rPPG, STT/대화, Android, 인계자료 4개 slice. WS1·WS2 병렬, 이후 WS3, WS4 순서. |
| Open questions | 없음. |
| Agent decisions to review | 없음. 모든 주요 방향은 승인된 사용자 계획에서 확정됨. |
| Last material change | Revision 12 — v9 인계 자료 렌더링과 로컬 Docker migration, readiness, web, STT 비활성 health, 1,024-sample 모델 smoke 검증을 완료함. |

## 2. Outcome and Scope

### Outcome

환자는 Android `홈 · 대시보드 · 챗봇` 흐름에서 새 20초 binary 모델의 class-1 확률을 네 문장 구간으로 확인하고, 말하거나 입력하여 챗봇과 대화하고, Android TTS로 응답을 들으며, 선택적으로 20초 얼굴/rPPG 측정을 실행하고, AUQ를 8–56 원점수로 확인한다.

### In Scope

- `final_model_win20s_high2p4` 1,024점 모델과 checksum으로 교체.
- 선형 보간, 채널별 MinMax, 상수 zero, 필터 없음 유지.
- Android 20초 수집과 10초 간격 전송.
- 내부 STT Docker 서비스와 인증 backend proxy 및 voice 동의 연결.
- Android 편집 가능한 음성 인식과 로컬 TTS.
- `free-dialogue-v3` 프롬프트.
- 20초 rPPG, rPPG 1,024점 + zero EDA 1,024점, 기존 암호화 비동기 저장.
- 문장형 AUQ, 8–56 축, 네 확률 구간 문구.
- v7 원본을 보존한 v9 Markdown/PPT.

### Out of Scope / Non-Goals

- Whisper 가중치를 Git에 포함하거나 STT 포트를 외부 공개하지 않는다.
- 음성 원본·전송 전 transcript를 영구 저장하지 않고 server TTS/Watch TTS를 만들지 않는다.
- 모델 재학습, 신규 필터, AUQ 임상 cutoff, 진단·치료효과·인과 주장을 하지 않는다.
- `0001`–`0004` 또는 기존 10초 rPPG 이력을 파괴적으로 변경하지 않는다.
- 관리자 웹 재설계나 NeuroTruth 외부 provider 모델 소스 변경을 하지 않는다.

### Users and Primary Flow

1. 동의한 환자가 로그인하고 3탭 v7 화면에 진입한다.
2. Watch 데이터 20초 warm-up 뒤 휴대폰이 최근 20초 PPG/GSR을 10초마다 전송한다.
3. backend가 binary 확률을 생성하고 앱은 class-1 확률을 네 연구용 문구 중 하나로 표시한다.
4. 환자는 AUQ를 작성하고, 텍스트 또는 음성으로 대화하고, TTS를 듣고, 세션을 종료할 수 있다.
5. 환자는 20초 얼굴 측정을 실행할 수 있고 기존 rPPG job 흐름이 암호화 저장과 camera prediction을 수행한다.
6. 대시보드와 v9 인계 자료는 같은 용어와 내비게이션을 사용한다.

### Current Assumptions and Constraints

- 모델 SHA-256은 `2493e5d75fcdc9b066b341deb47f81c9db49aac37c626619b7afe1a6b2fc354c`다(D-001).
- 학습 stride 1초와 운영 stride 10초를 별도 기록한다(D-002).
- metadata의 5 Hz low-pass 설명과 달리 사용자 결정에 따라 현재 무필터 전처리를 유지하고 차이를 문서화한다(D-003).
- `STT_ENABLED=false`, `RPPG_ENABLED=false`로 독립 비활성화한다(D-005).
- 로컬 Docker 엔진이 꺼져 있으면 코드 검증은 진행하지만 Compose 인수 검증은 엔진 실행 뒤 완료한다.

## 3. Repository Pattern Baseline

### Current Pattern

| Area | Current pattern | Evidence | Must preserve |
| --- | --- | --- | --- |
| Model inference | SHA-checked PyTorch artifact, 2-channel normalization, CUDA smoke fallback | `apps/backend/app/inference.py::BinaryCravingModel` | Checksum, `eval`, inference mode, CPU fallback, binary response schema |
| Sensor ingestion | Authenticated window upload, resampling, prediction persistence and SSE | `apps/backend/app/v25/sensor_service.py`, `routes_sensor.py` | Patient ownership, encryption/persistence, alert flow |
| Free dialogue | Retry-safe Bedrock call with bounded history and question ledger | `apps/backend/app/v25/session_agents.py`, `session_service.py` | Idempotency, encrypted final text, repeat repair, safety/prohibited-claim guard |
| rPPG | CameraX/ML Kit capture, async job, DGX client, encrypted media/provider payload | `apps/mobile/.../RppgCameraScreen.kt`, `apps/backend/app/v25/rppg_service.py` | Job/API shape, idempotency, encrypted retention, legacy reads |
| Consent | Snapshot-based patient consent | `apps/backend/app/v25/models.py`, `auth_service.py`, Android auth models | Immutable snapshots and feature gating |
| Android UI | Compose screen state coordinated by `MainActivity` and view models | `apps/mobile/.../MainActivity.kt`, `SensorViewModel.kt` | Existing auth/session/Watch integrations and local error handling |

### Reuse Inventory

| ID | Existing asset | Evidence | Planned use |
| --- | --- | --- | --- |
| R-001 | Binary model loader and preprocessing | `apps/backend/app/inference.py` | 길이와 artifact 계약만 변경. |
| R-002 | Sensor upload/prediction flow | `apps/backend/app/v25/sensor_service.py` | 20초 window를 기존 DB/SSE로 연결. |
| R-003 | Retry-safe dialogue engine | `apps/backend/app/v25/session_service.py` | modality와 v3 prompt만 추가. |
| R-004 | rPPG persistence/job stack | `apps/backend/app/v25/rppg_service.py` | duration/sample만 변경하고 이력 보존. |
| R-005 | Authenticated Android API client | `apps/mobile/.../AuthenticatedApiClient.kt` | STT multipart와 modality 확장. |
| R-006 | Existing presentation lineage | `../neurotruth_app_design_handoff_v8.pptx`, v7 inspection assets | v9 시각 언어 유지. |

## 4. Decisions and Questions

### Decision Ledger

| ID | Domain | Decision | Source | Rationale or Evidence | Impact | User review | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D-001 | 모델 | 승인된 20초 모델과 SHA 사용. | user | 사용자 계획. | `(1,2,1024)`. | confirmed | resolved |
| D-002 | cadence | 20초 window/warm-up, 운영 stride 10초. | user | 사용자 수정 결정. | Android 전송과 등록 변경. | confirmed | resolved |
| D-003 | 전처리 | 선형 보간 + 채널별 MinMax + 필터 없음. | user | “기존거로”. | metadata 차이 기록. | confirmed | resolved |
| D-004 | 음성 | backend/DGX STT + Android TTS, audio ephemeral. | user | 승인 계획. | STT 경계와 mobile control 추가. | confirmed | resolved |
| D-005 | 방향 | 기존 흐름을 유지하며 STT와 rPPG/mobile을 확장. | user | 승인 계획. | 신규 production 파일 승인. | confirmed | resolved |
| D-006 | AUQ | 기존 8문항, 문장형 1–7, `X/56`. | user | 승인 계획. | cutoff/normalization 없음. | confirmed | resolved |
| D-007 | 확률 UX | 정확한 % 대신 네 문장 구간. | user | 승인 계획. | 환자 UI만 변경. | confirmed | resolved |
| D-008 | rPPG | 20초, rPPG/zero-EDA 각 1,024점. | user | 승인 계획. | additive constraint. | confirmed | resolved |
| D-009 | Repository mapping | Extend WS1 to the existing route and media-probe validators. | repository | `apps/backend/app/v25/routes_rppg.py`, `rppg_media.py` enforce the upload duration before the service. | Makes the approved 19.5–20.5 second contract complete without a new abstraction. | not-required | resolved |
| D-010 | Repository mapping | Store the artifact below `apps/backend/model/weights`, not `apps/backend/app/model/weights`. | repository | `apps/backend/Dockerfile` copies `model/`, and `_default_model_path` resolves from the backend root. | Makes runtime, tests, and Docker use the same artifact. | not-required | resolved |
| D-011 | Repository mapping | Extend WS2 to persist and read voice consent in `repository.py`. | repository | `_insert_consent` hardcodes voice false and `_consent` omits the voice column. | Makes the approved voice feature gate durable. | not-required | resolved |
| D-012 | Repository mapping | Register the STT router in `app/main.py`. | repository | `app/main.py` is the existing router registry. | Avoids an unconventional aggregate session router. | not-required | resolved |
| D-013 | Repository mapping | Bind message modality in `repository.py::append_message`. | repository | The existing INSERT hardcodes `text` although the schema permits voice. | Persists the approved `inputModality` without another message path. | not-required | resolved |
| D-014 | Validation | Update the schema regression expectation from migration `0004` to `0005`. | repository | Runtime and Alembic correctly report `20260717_0005`; one test still expects `0004`. | Restores the migration acceptance test without production changes. | not-required | resolved |
| D-015 | Repository mapping | Reuse `MobileAuthRuntime` and `AuthenticatedApiClient` for voice consent and STT multipart. | repository | These are the existing Android snapshot and authenticated transport boundaries. | Avoids a second auth or HTTP client path. | not-required | resolved |
| D-016 | Validation | Extend the existing auth model and authenticated-client tests. | repository | Voice consent and multipart transport changed their existing contracts. | Keeps new coverage beside the nearest regression tests. | not-required | resolved |
| D-017 | Repository mapping | `.env.example`에 배포된 20초 모델, 40 MiB rPPG 업로드, 기본 비활성 STT 제어를 반영한다. | repository | 템플릿이 폐기된 10초 artifact와 20 MiB 제한을 가리키고 새 STT 변수가 없다. | 로컬과 DGX 설정을 구현된 Compose 계약과 일치시킨다. | not-required | resolved |

### Question Register

| ID | Domain | Decision needed | Why it matters | Recommendation | Linked decision | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Q-001 | 범위 | 추가 방향 결정이 필요한가? | 추측 구현 방지. | 없음, 승인 계획 구현. | D-005 | answered | 사용자가 구현 준비 계획을 제공함. |

## 5. Requirements and Acceptance Criteria

### Functional Requirements

- **FR-001:** backend는 승인 SHA와 `(1,2,1024)`를 검증하고 무필터 MinMax로 기존 binary schema를 출력한다.
- **FR-002:** Android는 PPG/GSR 20초를 모아 최초 warm-up 후 10초마다 최신 window를 보낸다.
- **FR-003:** 내부 STT는 한국어 `large-v3-turbo`, VAD, beam 1, DGX CUDA FP16, 로컬 CPU fallback을 지원하고 포트를 공개하지 않는다.
- **FR-004:** 인증 STT API는 voice 동의, 포맷/크기/시간, 무음, timeout, availability, tmpfs 삭제를 보장하고 audio/draft transcript를 영구 저장하지 않는다.
- **FR-005:** Android는 최대 30초 녹음, 편집 가능한 transcript, 명시적 전송, text fallback, 말풍선별 TTS, 기본 OFF 자동 읽기를 제공한다.
- **FR-006:** `free-dialogue-v3`는 text/voice 최종 발화를 동일 처리하고 modality를 저장하며 최근 20개+ledger, 0~1개 질문, 반복 방지, 안전/금지문구 repair를 유지한다.
- **FR-007:** 신규 rPPG는 19.5–20.5초만 허용하고 기존 10초 행은 읽을 수 있으며 1,024 rPPG+zero EDA로 prediction을 저장한다.
- **FR-008:** Android는 3탭만 쓰고 홈 갈망 카드에서 rPPG에 진입한다.
- **FR-009:** 환자 UI는 `.25/.50/.75` 경계를 네 문구로 바꾸고 정확한 %를 노출하지 않는다.
- **FR-010:** AUQ는 승인된 7개 한국어 문장, 8–56 저장/그래프, cutoff 없음으로 표시한다.
- **FR-011:** 구현된 화면/흐름을 v9 Markdown/PPT에 동기화하고 v7 원본을 보존한다.

### Non-Functional Requirements

- **NFR-001:** 기존 아키텍처/스타일을 유지하고 무관한 refactor·dependency를 추가하지 않는다.
- **NFR-002:** secret, audio, draft transcript, provider raw error, 복호화 media를 로그/영구 저장하지 않는다.
- **NFR-003:** STT/rPPG 실패가 인증, text chat, TTS text, Watch sensor를 깨뜨리지 않는다.
- **NFR-004:** backend/Android를 함께 배포하고 additive migration과 이전 image/commit rollback을 사용한다.

### Acceptance Criteria

- **AC-001:** 고정 입력의 logits/softmax가 artifact model과 일치하고 SHA/tensor/NaN 실패를 거부한다.
- **AC-002:** 테스트에서 20초 warm-up, 10초 cadence, PPG 채널별 500점, EDA 20점이 확인된다.
- **AC-003:** fake STT가 동의/포맷/크기/무음/timeout/cleanup/success를 검증한다.
- **AC-004:** Android transcript가 편집 가능하고 자동 전송되지 않으며 TTS가 전환/화면 종료 때 정리된다.
- **AC-005:** text/voice가 같은 retry/idempotency를 쓰며 최종 text+modality만 저장한다.
- **AC-006:** rPPG 20초, 1,024/1,024, 암호화, legacy read, prediction 연결을 검증한다.
- **AC-007:** 환자 UI 네 문구와 AUQ 일곱 문장/`X/56`을 검증한다.
- **AC-008:** 가능한 환경에서 backend, Android unit/build/lint, `0005`, status와 CPU smoke가 통과한다.
- **AC-009:** release 전 DGX Whisper/FactorizePhys/craving CUDA와 end-to-end를 검증한다.
- **AC-010:** v9 deck 렌더에 겹침/잘림이 없고 Markdown과 용어가 일치한다.

### Edge and Failure Cases

- EDA 누락/상수 → zero channel과 quality metadata; PPG 누락/비유한 → prediction 없음.
- STT disabled/동의 없음/model 없음/timeout/무음 → code별 오류, text chat 유지.
- TTS 실패 → assistant text 유지.
- camera 취소/background/얼굴 이탈 → pause 상태를 복원하고 종료.
- legacy 10초 rPPG → read-only, 신규 upload로는 거부.
- `.25/.50/.75` 정확히 일치 → 더 높은 인접 구간.

## 6. Implementation Strategy and Direction

### STRAT-1 — 기존 계약을 유지하는 수직 확장

- **Direction:** `user-approved-divergence`
- **Current approach:** 기존 inference/Android sensor 하나를 갱신하고, STT 경계 하나를 추가하며, rPPG 하나를 확장하고, 기존 Compose 화면에서 개념을 표시한다.
- **Existing flow to reuse:** R-001~R-006.
- **Why this is minimal:** 두 번째 prediction/chat/rPPG 저장 경로, server TTS, 신규 report table을 만들지 않는다.
- **Behavior-preserving limitations:** raw probability는 API/DB에 남고 환자 표시만 숨긴다. 10초 rPPG는 read-only다.
- **Explicit exclusions:** 재학습, filter 변경, admin redesign, Watch voice, audio 저장, baseline migration 변경, 무관한 cleanup.
- **Compatibility and migration posture:** `0005` 추가, backend/Android 동시 배포, optional flag, 이전 image rollback.
- **Direction approval:** D-005.
- **Open-question sensitivity:** 없음.

### Material Alternatives Considered

| Strategy | Direction | Benefit | Additional code or risk | Decision |
| --- | --- | --- | --- | --- |
| 기존 흐름+격리 STT | user-approved-divergence | 독립 실패 경계와 최소 변경 | 내부 서비스/클라이언트 하나 | user-approved |
| device-only STT | preserve | audio 전송 없음 | DGX Whisper 요구 미충족 | rejected |
| server TTS | divergence | 동일 음성 | 불필요한 streaming/privacy 추가 | rejected |
| 5 Hz filter 도입 | divergence | metadata 문구 일치 | 사용자 전처리 결정 위반 | rejected |

## 7. Modification Map and Change Budget

### Modification Map

| ID | Kind | Target | Symbol | Action | Existing anchor | Required change | Why necessary | Slice | Direction |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CH-001 | production | `apps/backend/model/weights/final_model_win20s_high2p4/model.py` | `Conv1DNet` | add | `apps/backend/model/weights/final_moving_average_k5/model.py` | Import approved architecture. | FR-001 | WS1 | preserve |
| CH-002 | production | `apps/backend/model/weights/final_model_win20s_high2p4/model_weights.pt` | `state_dict` | add | supplied artifact | Import approved weights. | FR-001 | WS1 | preserve |
| CH-003 | production | `apps/backend/model/weights/final_model_win20s_high2p4/model_metadata.json` | model metadata | add | supplied artifact | Import approved metadata. | FR-001 | WS1 | preserve |
| CH-004 | production | `apps/backend/app/inference.py` | `BinaryCravingModel` | extend | current 512-sample loader | Switch to 1,024 samples and new artifact while preserving preprocessing. | FR-001, FR-002 | WS1 | preserve |
| CH-005 | production | `apps/backend/app/v25/rppg_service.py` | `RppgJobService` | extend | current 10-second job | Create 1,024 rPPG and zero-EDA samples. | FR-007 | WS1 | preserve |
| CH-006 | production | `apps/backend/app/settings.py` | `Settings` | extend | current rPPG/model settings | Set new model paths, 20-second duration, and 40 MiB default. | FR-001, FR-007 | WS1 | preserve |
| CH-007 | production | `apps/backend/alembic/versions/20260717_0005_voice_rppg_20s.py` | `upgrade` | add | `apps/backend/alembic/versions/20260715_0002_dgx_rppg.py` | Permit legacy 10-second and new 20-second captures. | FR-007 | WS1 | preserve |
| CH-008 | config | `apps/db/docker-compose.yml` | services | extend | backend model mounts | Mount new artifact and defaults. | FR-001 | WS1 | preserve |
| CH-009 | config | `apps/db/docker-compose.dgx.yml` | services | extend | backend GPU override | Preserve CUDA-first deployment with new model. | FR-001 | WS1 | preserve |
| CH-010 | test | `apps/backend/tests/test_binary_craving_model.py` | model contract tests | extend | existing binary tests | Prove SHA, tensor, preprocessing, and logits. | AC-001, AC-002 | WS1 | preserve |
| CH-011 | test | `apps/backend/tests/test_rppg_v25.py` | rPPG service tests | extend | existing fake DGX tests | Prove 20-second 1,024/1,024 flow. | AC-006 | WS1 | preserve |
| CH-012 | test | `apps/backend/tests/test_rppg_routes_v25.py` | rPPG route tests | extend | current upload tests | Prove duration and 40 MiB contract. | AC-006 | WS1 | preserve |
| CH-013 | production | `apps/stt-service/app.py` | `transcribe` | add | existing internal-service conventions | Implement ephemeral Korean Whisper and status. | FR-003, FR-004 | WS2 | approved-divergence |
| CH-014 | production | `apps/stt-service/Dockerfile` | image | add | backend Docker conventions | Build isolated STT runtime. | FR-003 | WS2 | approved-divergence |
| CH-015 | production | `apps/stt-service/requirements.txt` | dependencies | add | isolated service | Pin STT runtime dependencies. | FR-003 | WS2 | approved-divergence |
| CH-016 | production | `apps/backend/app/v25/stt_client.py` | `SttClient` | add | `apps/backend/app/v25/rppg_dgx.py` | Proxy status/transcription with timeouts and error mapping. | FR-004 | WS2 | approved-divergence |
| CH-017 | production | `apps/backend/app/v25/routes_stt.py` | router | add | `apps/backend/app/v25/routes_rppg.py` | Add authenticated status/transcription API. | FR-004 | WS2 | approved-divergence |
| CH-018 | production | `apps/backend/app/v25/runtime.py` | `Runtime` | extend | current router/services wiring | Wire STT client and router. | FR-004 | WS2 | preserve |
| CH-019 | production | `apps/backend/app/v25/models.py` | `ConsentInput` | extend | current consent model | Expose voice consent in API model. | FR-004 | WS2 | preserve |
| CH-020 | production | `apps/backend/app/v25/auth_service.py` | consent serialization | extend | current voice hard-code | Persist and return voice consent. | FR-004 | WS2 | preserve |
| CH-021 | production | `apps/backend/app/v25/session_agents.py` | `free-dialogue-v3` | extend | `free-dialogue-v2` | Update the approved prompt and ledger rules. | FR-006 | WS2 | preserve |
| CH-022 | production | `apps/backend/app/v25/session_service.py` | `post_message` | extend | retry-safe message flow | Persist `inputModality` without a second path. | FR-006 | WS2 | preserve |
| CH-023 | production | `apps/backend/app/v25/routes_sessions.py` | message/transcription contracts | extend | current session router | Validate modality and expose STT route linkage. | FR-004, FR-006 | WS2 | preserve |
| CH-024 | config | `apps/db/docker-compose.yml` | services | extend | private Compose network | Add disabled-by-default CPU STT without host port. | FR-003 | WS2 | approved-divergence |
| CH-025 | config | `apps/db/docker-compose.dgx.yml` | services | extend | DGX GPU overrides | Add CUDA STT override. | FR-003 | WS2 | approved-divergence |
| CH-026 | test | `apps/backend/tests/test_stt_routes_v25.py` | STT route tests | add | fake provider pattern | Prove consent, format, size, no-speech, timeout, cleanup. | AC-003 | WS2 | preserve |
| CH-027 | test | `apps/backend/tests/test_auth_routes_v25.py` | voice consent tests | extend | current consent tests | Prove voice snapshot behavior. | AC-003 | WS2 | preserve |
| CH-028 | test | `apps/backend/tests/test_sessions_v25.py` | modality/dialogue tests | extend | current retry tests | Prove v3 prompt, modality, and idempotency. | AC-005 | WS2 | preserve |
| CH-029 | production | `apps/mobile/app/src/main/AndroidManifest.xml` | `RECORD_AUDIO` | extend | current camera/network permissions | Add microphone permission. | FR-005 | WS3 | preserve |
| CH-030 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/AuthModels.kt` | `ConsentSelection` | extend | current consent selection | Add voice choice. | FR-004 | WS3 | preserve |
| CH-031 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/AuthScreen.kt` | voice consent | extend | current consent UI | Enable voice setting. | FR-004 | WS3 | preserve |
| CH-032 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/AuthenticatedSessionApi.kt` | transcription/message API | extend | current message API | Add multipart STT and modality. | FR-004, FR-005 | WS3 | preserve |
| CH-033 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/SensorViewModel.kt` | monitoring/chat state | extend | current 10-second and free chat state | Use 20-second/10-second cadence and voice/TTS UI state. | FR-002, FR-005 | WS3 | preserve |
| CH-034 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/PhoneMonitoringService.kt` | sensor cadence | extend | current 10-second constants | Send newest 20 seconds every 10 seconds. | FR-002 | WS3 | preserve |
| CH-035 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/MainActivity.kt` | three-tab voice/TTS/AUQ UI | extend | current Compose navigation/chat | Implement v7 shell and voice controls. | FR-005, FR-008, FR-010 | WS3 | preserve |
| CH-036 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/PatientDashboard.kt` | probability bands and AUQ axis | extend | current percentage/normalized cards | Hide exact percent and use 8–56 AUQ. | FR-009, FR-010 | WS3 | preserve |
| CH-037 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgCameraScreen.kt` | 20-second capture | extend | current 10-second capture | Record 20 seconds. | FR-007, FR-008 | WS3 | preserve |
| CH-038 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgModels.kt` | 20-second contract | extend | current rPPG models | Represent new duration/sample count. | FR-007 | WS3 | preserve |
| CH-039 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgApi.kt` | 20-second upload | extend | current multipart upload | Send new duration metadata. | FR-007 | WS3 | preserve |
| CH-040 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/RppgViewModel.kt` | 20-second state | extend | current job state | Present 20-second capture lifecycle. | FR-007 | WS3 | preserve |
| CH-041 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/AuthenticatedSessionApiContractTest.kt` | voice API tests | extend | current session tests | Prove transcript/modality contract. | AC-004, AC-005 | WS3 | preserve |
| CH-042 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/PatientDashboardParserTest.kt` | label/AUQ tests | extend | current dashboard tests | Prove band boundaries and raw AUQ. | AC-007 | WS3 | preserve |
| CH-043 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/RppgContractsTest.kt` | rPPG tests | extend | current 10-second tests | Prove 20-second mobile contract. | AC-006 | WS3 | preserve |
| CH-044 | docs | `docs/neurotruth_app_design_handoff_v9.md` | developer handoff | add | v7/v8 handoff assets | Describe implemented screens and workflow. | FR-011 | WS4 | preserve |
| CH-045 | docs | `docs/handoff/neurotruth_app_design_handoff_v9.pptx` | presentation | add | workspace `neurotruth_app_design_handoff_v8.pptx` | Produce and render synchronized v9 without modifying v7. | FR-011 | WS4 | preserve |
| CH-046 | production | `apps/backend/app/v25/routes_rppg.py` | `create_rppg_job` | extend | current 9.5–10.5 second form validation | Enforce the new 19.5–20.5 second upload contract. | FR-007 | WS1 | preserve |
| CH-047 | production | `apps/backend/app/v25/rppg_media.py` | `probe_video` | extend | current 10-second ffprobe validation | Validate actual video duration against the new contract. | FR-007 | WS1 | preserve |
| CH-048 | production | `apps/backend/app/v25/repository.py` | consent persistence and message modality | extend | `_insert_consent`, `_consent`, `append_message` | Persist/read voice consent and bind text-or-voice message modality. | FR-004, FR-006 | WS2 | preserve |
| CH-049 | production | `apps/backend/app/main.py` | router registry | extend | existing `include_router` calls | Import and include the STT router conventionally. | FR-004 | WS2 | preserve |
| CH-050 | test | `apps/backend/tests/test_rppg_schema_v25.py` | required migration revision | extend | stale `20260716_0004` expectation | Expect additive migration `20260717_0005`. | AC-008 | WS1 | preserve |
| CH-051 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/MobileAuthRuntime.kt` | voice consent runtime | extend | current consent snapshot | Expose the persisted voice flag to chat UI. | FR-004 | WS3 | preserve |
| CH-052 | production | `apps/mobile/app/src/main/java/com/example/healthsensor/AuthenticatedApiClient.kt` | authenticated multipart | extend | current authenticated request helper | Reuse auth refresh/error handling for STT upload. | FR-004, FR-005 | WS3 | preserve |
| CH-053 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/ConversationSessionManagerTest.kt` | voice session regression | extend | current session lifecycle tests | Prove voice/text lifecycle does not break session state. | AC-004, AC-005 | WS3 | preserve |
| CH-054 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/AuthenticatedSensorContractTest.kt` | 20-second sensor contract | extend | current window contract tests | Prove warm-up/window/cadence payload values. | AC-002 | WS3 | preserve |
| CH-055 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/AuthModelsTest.kt` | voice consent model tests | extend | current consent mapping tests | Prove voice snapshot round-trip. | AC-004 | WS3 | preserve |
| CH-056 | test | `apps/mobile/app/src/test/java/com/example/healthsensor/AuthenticatedApiClientTest.kt` | multipart authentication tests | extend | current authenticated-client tests | Prove authenticated STT multipart behavior. | AC-004, AC-005 | WS3 | preserve |
| CH-057 | config | `.env.example` | model/rPPG/STT deployment values | extend | stale 10-second model and rPPG defaults | secret 없이 새 경로, SHA, 40 MiB 제한, STT feature flag, timeout, upload limit, 외부 모델 mount 경로를 게시한다. | FR-001, FR-003, FR-007 | WS2 | preserve |

### Change Budget

| Slice | Max changed files | Max production files | Max new production files | Max production added lines | New dependencies | New shared abstractions |
| --- | --- | --- | --- | --- | --- | --- |
| WS1 | 18 | 12 | 4 | 700 | None | None |
| WS2 | 20 | 15 | 5 | 970 | `faster-whisper` | One STT client boundary |
| WS3 | 22 | 15 | 2 | 1350 | None | None |
| WS4 | 6 | 0 | 0 | 0 | None | None |

The production-line budget is an expansion alarm, not a compression target. Revise the design if the clear minimal implementation exceeds it.

## 8. Work Plan

| ID | Goal | Depends on | Parallel group | Change IDs | Write scope | Do not touch | Covers | Validation | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| WS1 | Integrate 20-second model and rPPG contracts | None | Serial | CH-001, CH-002, CH-003, CH-004, CH-005, CH-006, CH-007, CH-008, CH-009, CH-010, CH-011, CH-012, CH-046, CH-047, CH-050 | `apps/backend/model/weights/final_model_win20s_high2p4/**`; `apps/backend/app/inference.py`; `apps/backend/app/v25/rppg_service.py`; `apps/backend/app/v25/routes_rppg.py`; `apps/backend/app/v25/rppg_media.py`; `apps/backend/app/settings.py`; `apps/backend/alembic/versions/20260717_0005_voice_rppg_20s.py`; `apps/db/*.yml`; `apps/backend/tests/test_binary_craving_model.py`; `apps/backend/tests/test_rppg*.py` | Session/auth/mobile/web | FR-001, FR-002, FR-007, NFR-001, NFR-003, NFR-004, AC-001, AC-002, AC-006, AC-008, AC-009 | Focused backend pytest plus artifact smoke | verified |
| WS2 | Add ephemeral STT, voice consent, and free-dialogue-v3 | WS1 | Serial | CH-013, CH-014, CH-015, CH-016, CH-017, CH-018, CH-019, CH-020, CH-021, CH-022, CH-023, CH-024, CH-025, CH-026, CH-027, CH-028, CH-048, CH-049, CH-057 | `.env.example`; `apps/stt-service/**`; `apps/backend/app/v25/**`; `apps/backend/app/main.py`; `apps/db/*.yml`; `apps/backend/tests/test_stt_routes_v25.py`; `apps/backend/tests/test_auth_routes_v25.py`; `apps/backend/tests/test_sessions_v25.py` | Inference/rPPG/mobile/web | FR-001, FR-003, FR-004, FR-006, FR-007, NFR-001, NFR-002, NFR-003, AC-003, AC-005, AC-008, AC-009 | Fake STT/session/auth pytest and environment-template cross-check | verified |
| WS3 | Implement Android v7 voice, cadence, rPPG, AUQ, and label UI | WS2 | Serial | CH-029, CH-030, CH-031, CH-032, CH-033, CH-034, CH-035, CH-036, CH-037, CH-038, CH-039, CH-040, CH-041, CH-042, CH-043, CH-051, CH-052, CH-053, CH-054, CH-055, CH-056 | `apps/mobile/app/src/main/**`; `apps/mobile/app/src/test/**` | Wear OS/admin web/backend internals | FR-002, FR-004, FR-005, FR-007, FR-008, FR-009, FR-010, NFR-001, NFR-003, NFR-004, AC-002, AC-004, AC-005, AC-006, AC-007, AC-008, AC-009 | Unit tests, assembleDebug, lintDebug | verified |
| WS4 | Produce v9 synchronized handoff artifacts | WS3 | Serial | CH-044, CH-045 | `docs/neurotruth_app_design_handoff_v9.md`; `docs/handoff/neurotruth_app_design_handoff_v9.pptx` | v7 source artifacts and production code | FR-011, NFR-001, AC-010 | Render all slides and cross-check terms | verified |

### Parallelization Rationale

WS1은 inference/rPPG, WS2는 STT/session/auth를 소유한다. 공유 Compose diff만 main이 병합 검토한다. WS3은 API/sample 계약 이후, WS4는 최종 UI 용어 이후 실행한다.

### Final Integration

Backend suite/model smoke, Android unit/build/lint, spec scope/patch, migration/status를 검증한다. Docker가 가능하면 rebuild 후 `/health`, `/ready`, `/api/stt/status`, `/api/rppg/status`를 확인한다. DGX CUDA는 배포 인수 항목이다.

## 9. Validation, Rollout, and Risk

### Validation Plan

- WS1: logits/SHA/tensor/NaN, 20초 resample, legacy/new rPPG.
- WS2: fake Whisper success/error/cleanup/consent, v3 modality/idempotency.
- WS3: Kotlin contract/parser/policy, debug APK/lint.
- WS4: slide PNG, 겹침/잘림, 문서/PPT 용어 일치.
- 통합: 전체 pytest, migration head, CPU smoke, Compose status, device install, DGX CUDA.

### Minimality and Style-Fidelity Review

- 모든 production path를 CH-001–CH-018과 대조한다.
- 두 번째 prediction/chat/rPPG 경로, server TTS, baseline migration, formatting churn, 무관한 dependency를 거부한다.
- 기존 encrypted repository, retry/idempotency, CameraX job state, Compose convention을 유지한다.

### Rollout and Rollback

PostgreSQL backup 후 backend와 Android/Wear sensor contract를 함께 배포하고 `0005`를 적용한다. status/smoke 뒤 STT/rPPG flag를 각각 활성화한다. rollback은 이전 image/commit으로 하고 additive row는 삭제하지 않는다. Whisper/credential은 외부 mount/secret이다.

### Risks and Mitigations

| Risk | Impact | Mitigation or Evidence |
| --- | --- | --- |
| metadata/filter 차이 | 학습 문서와 출력 차이 가능 | 사용자 결정 D-003, preprocessing version/제한 기록 |
| DGX ARM64/CUDA Whisper | GPU init 실패 가능 | 격리 서비스, CPU fallback, status, DGX smoke |
| 큰 Android 화면 변경 | auth/session 회귀 | view model/API 재사용, 집중 테스트/build/lint |
| optional provider 실패 | 음성/rPPG 불가 | 독립 flag, text/Watch/auth 유지 |
| 구간/AUQ 오해 | 임상 판단처럼 해석 | 연구 UI 문구와 cutoff/진단 금지 |

## 10. Revision and Progress

### Design Revision History

| Revision | Timestamp | Trigger | Changes | Decision IDs | Question IDs |
| --- | --- | --- | --- | --- | --- |
| 1 | 2026-07-17T12:00:00+09:00 | approved-user-plan | 구현 준비 bilingual contract와 4개 slice 생성. | D-001, D-002, D-003, D-004, D-005, D-006, D-007, D-008 | Q-001 |
| 2 | 2026-07-17T12:25:00+09:00 | repository-evidence | 기존 rPPG route/media duration validator를 WS1에 추가. | D-009 | None |
| 3 | 2026-07-17T12:35:00+09:00 | test-evidence | artifact target을 test/Docker가 사용하는 backend model directory로 수정. | D-010 | None |
| 4 | 2026-07-17T12:50:00+09:00 | repository-evidence | repository voice-consent 저장/읽기 경로를 WS2에 추가. | D-011 | None |
| 5 | 2026-07-17T12:52:00+09:00 | repository-evidence | 기존 backend router registry를 WS2에 추가. | D-012 | None |
| 6 | 2026-07-17T12:54:00+09:00 | repository-evidence | 기존 message modality INSERT를 승인된 repository scope에 추가. | D-013 | None |
| 7 | 2026-07-17T13:05:00+09:00 | regression-evidence | stale migration-head assertion을 WS1 test scope에 추가. | D-014 | None |
| 8 | 2026-07-17T13:20:00+09:00 | repository-evidence | Android auth-runtime/multipart anchor와 focused tests를 WS3에 추가. | D-015 | None |
| 9 | 2026-07-17T20:35:00+09:00 | implementation-evidence | nearest Android auth model/client regression target을 WS3에 추가. | D-016 | None |
| 10 | 2026-07-17T21:05:00+09:00 | integration-evidence | 수정 전 구형 배포 환경 템플릿을 WS2 범위에 추가함. | D-017 | None |
| 11 | 2026-07-17T21:12:00+09:00 | implementation-evidence | `.env.example`을 Compose와 대조 검증하고 v9 인계 slice를 재개함. | D-017 | None |
| 12 | 2026-07-17T21:20:00+09:00 | verification-evidence | v9 슬라이드별 렌더링과 migration `0005`, 배포된 20초 모델의 로컬 Docker 통합 검증을 완료함. | D-001, D-002, D-004, D-008 | None |

### Implementation Progress Record

| Timestamp | Spec revision | Slice | State | Evidence or Notes |
| --- | --- | --- | --- | --- |
| 2026-07-17T12:00:00+09:00 | 1 | — | ready | 모든 material behavior 사용자 승인, open question 없음. |
| 2026-07-17T12:20:00+09:00 | 1 | WS1 | implementing | 20초 모델과 rPPG 계약 변경을 단일 worker에게 위임함. |
| 2026-07-17T12:25:00+09:00 | 2 | WS1 | implementing | worker가 기존 duration validator 2개를 발견해 수정 전 범위를 갱신함. |
| 2026-07-17T12:35:00+09:00 | 3 | WS1 | implementing | focused test가 artifact 경로 오류를 찾아 파일 이동 전에 수정함. |
| 2026-07-17T12:45:00+09:00 | 3 | WS1 | completed | scope PASS, SHA 일치, focused 24 passed/1 skipped, compile/Alembic head 통과. |
| 2026-07-17T12:46:00+09:00 | 3 | WS2 | implementing | STT, voice consent, free-dialogue-v3를 단일 worker에게 위임함. |
| 2026-07-17T12:50:00+09:00 | 4 | WS2 | implementing | worker가 repository voice hard-code를 발견해 수정 전 범위를 갱신함. |
| 2026-07-17T12:52:00+09:00 | 5 | WS2 | implementing | worker가 router registry를 발견해 import/include 수정 전 범위를 갱신함. |
| 2026-07-17T12:54:00+09:00 | 6 | WS2 | implementing | worker가 hardcoded text modality를 발견해 수정 전 change map을 갱신함. |
| 2026-07-17T13:04:00+09:00 | 6 | WS2 | completed | focused 36 passed, stale migration assertion 전 full backend 164 passed/1 skipped. |
| 2026-07-17T13:05:00+09:00 | 7 | WS1 | implementing | migration-head schema assertion 수정만 위해 재개함. |
| 2026-07-17T13:10:00+09:00 | 7 | WS1 | completed | migration 3 passed, full backend 166 passed/1 skipped. |
| 2026-07-17T13:11:00+09:00 | 7 | WS3 | implementing | Android v7 voice/cadence/rPPG/AUQ/probability-label UI를 위임함. |
| 2026-07-17T13:20:00+09:00 | 8 | WS3 | implementing | worker가 Android consent/HTTP boundary를 확인해 수정 전 scope를 갱신함. |
| 2026-07-17T20:35:00+09:00 | 9 | WS3 | implementing | main scope/build 검증 전 final changed-test inventory를 추가함. |
| 2026-07-17T20:45:00+09:00 | 9 | WS3 | completed | final unit, assembleDebug, lintDebug, scope 검증 통과. |
| 2026-07-17T20:46:00+09:00 | 9 | WS4 | implementing | synchronized v9 Markdown/PPT 생성과 slide render를 위임함. |
| 2026-07-17T21:05:00+09:00 | 10 | WS2 | implementing | `.env.example`을 구현된 20초 모델, rPPG, STT 계약과 맞추기 위해 slice를 다시 열었음. |
| 2026-07-17T21:12:00+09:00 | 11 | WS2 | completed | 환경 템플릿의 경로, SHA, rPPG 제한, STT feature flag, timeout, upload limit, 외부 모델 mount를 Compose와 대조함. |
| 2026-07-17T21:12:00+09:00 | 11 | WS4 | implementing | 설정 통합 후 최종 Markdown/PPT 렌더링 및 시각 QA를 재개함. |
| 2026-07-17T21:20:00+09:00 | 12 | WS4 | completed | 9개 렌더 슬라이드, 템플릿 충실도, 빈 자리, 경계, 환자용 문구, 동기화된 Markdown을 검증함. |
| 2026-07-17T21:20:00+09:00 | 12 | integration | completed | 기존 로컬 DB를 백업하고 Compose 재빌드 후 Alembic `20260717_0005`, `/health`, `/ready`, web 200, STT 비활성 health, CPU 모델 SHA/1,024-sample smoke를 확인함. |
