# NeuroTruth DGX Spark FactorizePhys rPPG 연동 명세

- **언어 역할:** 한국어 미러 명세
- **명세 상태:** 확정
- **명세 버전:** 1.1
- **최종 업데이트:** 2026-07-15
- **영문 원본:** `neurotruth/docs/specs/2026-07-15-neurotruth-dgx-factorizephys-rppg-integration-spec.md`
- **한국어 미러:** `neurotruth/docs/specs/2026-07-15-neurotruth-dgx-factorizephys-rppg-integration-spec.ko.md`
- **요청자 / 소유자:** NeuroTruth 프로젝트 소유자
- **구현 상태:** 구현 완료 및 릴리스 비활성화 유지; 실제 휴대폰 DGX 라이브 검증 대기

## 0. Codex 구현 인계

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: neurotruth/docs/specs/2026-07-15-neurotruth-dgx-factorizephys-rppg-integration-spec.md
korean_mirror_spec: neurotruth/docs/specs/2026-07-15-neurotruth-dgx-factorizephys-rppg-integration-spec.ko.md
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
$feature-planner Implement `neurotruth/docs/specs/2026-07-15-neurotruth-dgx-factorizephys-rppg-integration-spec.md` using IMPLEMENTATION_ORCHESTRATION. Use the English source spec as authoritative, keep `neurotruth/docs/specs/2026-07-15-neurotruth-dgx-factorizephys-rppg-integration-spec.ko.md` synchronized, spawn worker sub-agents for source-code edits, and have the main agent verify minimal diffs and update progress records. If the spec has a gap, use TARGETED_REFINEMENT for only that gap before continuing.
```

## 1. 요약

환자 동의를 전제로 휴대폰에서 10초 얼굴 영상을 촬영하고 인증된 NeuroTruth 백엔드에만 업로드한 뒤 기존 DGX Spark 서비스의 FactorizePhys를 실행하는 카메라 측정 기능을 추가한다. 백엔드는 추론 작업을 DB에 영속적으로 큐잉하고, 수락된 모든 영상을 AES-256-GCM으로 영구 보존하며, 품질을 통과한 rPPG 파형을 51.2Hz 512점으로 변환하고 EDA 512점을 명시적인 0으로 채워 기존 3-class RandomForest를 실행한 뒤 별도 카메라 결과를 휴대폰에 반환한다. DGX endpoint는 backend-only이며 stateless로 유지되고 Android 앱은 주소를 알지 못한다. 실제 휴대폰 영상 추론과 생리학적 타당성은 검증된 것으로 가정하지 않고 release gate로 유지한다.

## 2. 목표

- G1. 신뢰할 수 있는 `phone -> NeuroTruth backend -> DGX Spark -> RandomForest -> database -> phone` 흐름을 제공한다.
- G2. 명확한 상태 및 취소 동작과 함께 안정적인 전면 카메라 720p/30fps 10초 영상을 촬영한다.
- G3. 업로드, 추론, 재시도, 재시작 복구, 암호화, idempotency 및 환자 소유권을 영속적이고 감사 가능하게 만든다.
- G4. 기존 휴대폰 alert, AUQ 및 chat 규칙은 재사용하되 카메라 예측이 watch에 표시되지 않게 격리한다.
- G5. V2.5 baseline과 관련 없는 sensor, watch, SSE, authentication, consent 및 conversation 동작을 보존한다.

## 3. 비목표

- NG1. FactorizePhys 또는 DGX source, checkpoint, container image, API contract를 수정·패키징·재학습하지 않는다.
- NG2. mobile-to-DGX 직접 통신이나 DGX credential, address, port의 앱 노출은 하지 않는다.
- NG3. 실패하거나 품질이 낮은 rPPG 측정을 갈망 없음으로 취급하거나 임상적 주장 또는 검증된 HR 정확성을 주장하지 않는다.
- NG4. 카메라 예측을 watch에 보내거나 기존 watch 인증 모델을 바꾸거나 촬영 중 watch-to-phone 수신을 중지하지 않는다.
- NG5. 서버 자동 재시도, 품질 실패 영상의 동일 영상 재분석, bulk data download, 자동 보존 삭제, Alembic `0001` 및 기준 18-table SQL 수정은 하지 않는다.
- NG6. 현재 인증되지 않은 DGX HTTP endpoint를 public에 노출하지 않는다.

## 4. 사용자 및 사용 사례

### 4.1 대상 사용자

- NeuroTruth Android phone 앱을 사용하는 인증되고 동의한 환자.
- 메타데이터, 사유가 필요한 video playback 및 확인 기반 삭제를 수행하는 관리자.
- NeuroTruth-to-DGX network를 설정하고 안전한 배포를 검증하는 운영자.

### 4.2 주요 사용 사례

- UC1. 환자가 카메라 측정을 시작하고 한 얼굴을 1초간 안정적으로 유지하여 10초 촬영한 뒤 rPPG/갈망 결과 또는 조치 가능한 retry 상태를 확인한다.
- UC2. 휴대폰은 수락된 영상을 중복 업로드하지 않고 process 재시작 후 polling과 pause 상태를 복원한다.
- UC3. 관리자는 capture metadata를 보고 사유와 audit trail이 있을 때만 일시 복호화 inline video 또는 삭제를 수행한다.
- UC4. 운영자는 `RPPG_ENABLED=false`로 기존 NeuroTruth data 또는 service를 방해하지 않고 기능 전체를 비활성화한다.

## 5. 최종 결정

| ID | 영역 | 결정 | 출처 |
| --- | --- | --- | --- |
| D1 | Topology | `RPPG_BASE_URL`의 DGX는 backend만 호출하고 phone은 인증된 NeuroTruth API만 호출한다. | 사용자 결정 |
| D2 | Capture | CameraX는 bundled ML Kit가 한 얼굴을 1초 안정적으로 감지하면 silent 720p/30fps MP4를 10초 촬영하며, 1초의 얼굴 이탈 또는 앱 background 전환 시 취소한다. | 사용자 결정 |
| D3 | Processing | persistent database queue는 기본 active inference 1개, connect timeout 10초, read timeout 180초를 사용하며 자동 재시도하지 않는다. | 사용자 결정 |
| D4 | Model input | 품질을 통과한 finite, non-constant rPPG를 51.2Hz 512점으로 선형 재표본화하고 대응 EDA channel은 512개의 literal `0.0`으로 구성한다. | 사용자 결정 |
| D5 | Retention | 수락된 success, quality-failure 및 technical-failure video를 감사 기반 삭제 전까지 모두 암호화하여 영구 보존하며 자동 만료하지 않는다. | 사용자 결정 |
| D6 | Consent | 신규 촬영에는 현재 `biosignal`, `ai_analysis`, `camera_rppg`, `face_video_retention` 동의가 필요하며 철회는 신규 촬영만 차단한다. | 사용자 결정 |
| D7 | Retry | timeout, connection 및 DGX 5xx failure는 retry action마다 사용자가 시작하는 새 attempt 1개를 허용하고, `retry_required`와 quality failure는 새 capture가 필요하다. | 사용자 결정 |
| D8 | Presentation | 카메라 결과는 휴대폰 전용 persistent card에 표시하고 notification consent가 있으면 기존 phone alert/AUQ/chat cooldown logic을 실행할 수 있지만 watch에는 절대 전달하지 않는다. | 사용자 결정 |
| D9 | Migration | Alembic `0002`만 추가하고 `0001` 또는 `neurotruth_schema_v2_5.sql`은 수정하지 않는다. | 사용자 결정 |
| D10 | Rollout | `RPPG_ENABLED=false`가 기본이며 실제 기기 end-to-end validation 통과 전까지 false로 유지한다. | 사용자 결정 |

## 6. 기능 요구사항

- FR1. Phone은 camera permission을 요청하고 중앙 face guide를 표시하며 얼굴이 없거나 여러 개면 거부하고 `finding_face -> stabilizing -> recording -> uploading -> analyzing -> completed|retry_required|failed`를 표시해야 한다.
- FR2. 안정화는 동일한 한 얼굴이 guide 안에 1,000ms 연속 유지되는 것을 의미한다. Recording은 자동 시작되어 10,000ms 동안 audio track 없이 진행되며 해당 얼굴이 1,000ms 연속 없거나 guide 밖이면 취소된다.
- FR3. 입력은 20MiB 이하 `video/mp4` MP4이고 선언한 `durationMs`는 9,500부터 10,500까지여야 한다. Backend media inspection도 202 반환 전에 decodable video, 해당 duration 범위 및 약 30fps stream을 확인해야 한다.
- FR4. Backend는 HTTP 202 반환 전에 수락된 video를 암호화·영속 저장하고 capture/job metadata를 transactionally 생성해야 한다. Plaintext 또는 process memory에만 영상이 있는 상태로 202를 반환하면 안 된다.
- FR5. Worker는 `video`, NeuroTruth job UUID를 값으로 갖는 `session_id`, `include_waveform=true`로 `POST /v1/rppg/infer`를 호출해야 한다.
- FR6. DGX success는 HTTP 200, `status == "success"`, `quality.passed == true`, null이 아니며 최소 두 개의 서로 다른 finite 값을 갖는 waveform, finite positive `rppg_sample_rate_hz`를 모두 요구한다. 그렇지 않으면 craving prediction을 생성하지 않는다.
- FR7. HTTP 200 `status == "retry_required"` 또는 `quality.passed == false`를 포함한 quality failure는 `retry_required`로 끝난다. Phone은 새 촬영을 안내하고 이를 갈망 없음으로 표시하면 안 된다.
- FR8. Quality-approved waveform은 기존 RandomForest 10초 contract에 맞춰 기간 전체를 선형 보간한다. 즉 512개의 `PPG_GREEN`, `0.0`으로 고정된 512개의 `EDA`, 동일한 51.2Hz timestamp grid를 만든다. Non-finite interpolation output은 sanitized technical failure다.
- FR9. 기존 3-class craving predictor가 class `0|1|2`, confidence 및 기존 alert metadata를 생성한다. 저장 시 `source=camera_rppg`, 원본 rPPG job, EDA-zero adaptation, craving model version 및 active `rppg_model` version을 기록한다.
- FR10. Capture 시작부터 terminal job 또는 local cancellation까지 기존 sensor upload와 prediction-SSE 적용은 pause하지만 watch-to-phone 수신은 유지한다. Success, failure, cancellation, logout 및 process-recovery 경로마다 정확한 이전 monitoring state를 복원한다.
- FR11. HTTP 202 이후 phone은 MP4를 삭제하고 `jobId`, `captureId`, pause-latch state를 저장한다. 재시작 후 polling을 재개하고 자동 재업로드하지 않는다. HTTP 202 전에는 measurement screen이 살아 있는 동안만 manual upload retry를 제공하며 화면 이탈 또는 app 종료 시 temporary MP4를 삭제한다.
- FR12. Camera card는 후속 watch prediction과 독립적으로 class, confidence, 존재하는 경우 HR, quality score, capture time 및 processing time을 유지한다. Notification consent는 phone alert/AUQ/chat presentation만 gate하며 rPPG inference 또는 craving persistence는 막지 않는다.
- FR13. Background worker는 backend startup 시 `queued` job과 stale `running` job을 복구한다. Row lock 또는 동등한 atomic claim으로 worker 전체에서 active DGX call이 `RPPG_MAX_CONCURRENCY`를 넘지 않게 한다.

## 7. 사용자 경험 / UI 요구사항

- UI1. Patient dashboard에 `카메라로 10초 측정`을 추가한다. Feature, required consent, authentication 또는 backend availability가 없으면 이유를 설명하면서 숨기거나 비활성화한다.
- UI2. Measurement screen은 front-camera preview, face guide, stability progress, 10-second countdown, upload/analysis progress, cancel, pre-202 upload retry, eligible post-failure analysis retry 및 quality failure용 recapture를 표시한다.
- UI3. Quality guidance는 `얼굴을 화면 중앙에 두고 밝은 곳에서 움직이지 않은 상태로 다시 측정해 주세요.`이며 갈망 없음으로 암시하면 안 된다.
- UI4. Result card는 class `0|1|2`에 기존 localized label을 사용하고 source를 camera rPPG로 명확히 표시하며 unavailable HR과 0 BPM을 구분한다.
- UI5. Consent settings에 별도 optional `camera_rppg` 및 `face_video_retention` control을 추가하고 관리자 삭제 전까지 영구 server retention임을 명확히 설명한다. Withdrawal 후에도 historic data는 남는다.
- UI6. Administrator web은 기본적으로 waveform, provider payload, filesystem path 또는 decrypted video 없이 capture time, patient, job status, quality, HR, model/checkpoint, processing time 및 failure code를 나열한다.

## 8. API / 데이터 / 상태 요구사항

### 8.1 환자 API

- API1. `GET /api/rppg/status`는 patient JWT를 요구하고 `{enabled, available, modelLoaded, device, checkpoint, queue:{queued,running,maxConcurrency}}`를 반환한다. DGX URL, filesystem path 또는 raw health response를 노출하지 않는다. Disabled는 HTTP 200 `enabled=false`, unreachable/unready는 HTTP 200 `available=false`를 반환한다.
- API2. `POST /api/rppg/jobs`는 patient JWT와 multipart field `video` binary MP4, `clientCaptureId` UUID, `capturedAtMs` positive epoch milliseconds, `durationMs` integer `9500..10500`, optional same-patient-owned `sessionId` UUID를 요구한다. 신규 또는 byte-identical idempotent input은 HTTP 202 `{jobId,captureId,status}`를 반환한다.
- API3. `GET /api/rppg/jobs/{jobId}`는 ownership을 요구하고 공통 field `{jobId,captureId,status,capturedAtMs,createdAt,updatedAt}`를 반환한다. `completed`는 `{source:"camera_rppg",classIndex,confidence,heartRateBpm,qualityScore,modelName,checkpoint,inferenceDevice,rppgSampleCount:512,rppgSamplingHz:51.2,processingMs}`도 반환한다. `retry_required`는 `{failureCode:"RPPG_QUALITY_RECAPTURE",qualityScore,reasons}`를, `failed`는 sanitized `failureCode`와 `retryAllowed`를 반환한다.
- API4. `POST /api/rppg/jobs/{jobId}/retry`는 ownership을 요구하며 body가 없다. Transient timeout, connection, HTTP 500 또는 HTTP 503 code를 가진 terminal `failed` job만 허용하고 같은 capture의 다음 attempt를 생성하여 새 `jobId`를 HTTP 202로 반환한다. Active, completed, validation failure 또는 quality-recapture state는 409를 반환한다.

### 8.2 관리자 API

- API5. `GET /api/admin/rppg/captures`는 기존 admin pagination을 사용하며 summary metadata만 반환한다.
- API6. `POST /api/admin/rppg/captures/{captureId}/reveal-video`는 nonblank reason의 JSON `{reason}`을 요구하고 content가 service 밖으로 나가기 전에 audit하며 persisted plaintext output 없이 `Cache-Control: no-store`, `Content-Disposition: inline`의 `video/mp4`를 stream한다.
- API7. `DELETE /api/admin/rppg/captures/{captureId}`는 JSON `{reason,confirmCaptureId}` 및 정확한 UUID confirmation을 요구한다. 연결된 camera prediction, 모든 job row 및 encrypted video를 삭제하고 non-sensitive audit event를 기록하며 storage 일부 실패 시 성공으로 잘못 보고하지 않고 재시도 가능해야 한다.

### 8.3 데이터베이스 및 암호화 저장소

- DATA1. Alembic `0002`는 immutable optional boolean `camera_rppg`와 `face_video_retention`을 `consent_snapshots`에 둘 다 `NOT NULL DEFAULT false`로 추가하고 `model_versions.component` check에 `rppg_model`을 추가한다.
- DATA2. `rppg_captures`는 UUID `id`, `patient_id`, optional owned `session_id`, `client_capture_id`, `consent_snapshot_id`, `captured_at`, `duration_ms`, `content_type`, `byte_size`, plaintext-video SHA-256, encrypted relative storage URI, encryption key version/nonce, capture metadata, deletion state 및 timestamp를 포함한다. Unique `(patient_id, client_capture_id)`와 patient/time 및 deletion-state index를 둔다.
- DATA3. `rppg_analysis_jobs`는 UUID `id`, `capture_id`, `attempt_no`, nullable `retry_of_job_id`, status, DGX `measurement_id`, `rppg_model`용 `model_version_id`, 검색 가능한 HR/quality/video/timing/model metadata, 암호화된 full provider response와 waveform envelope, encryption key version, sanitized failure code, start/finish timestamp 및 timestamp를 포함한다. Unique `(capture_id, attempt_no)`와 status/created index를 둔다.
- DATA4. `craving_predictions.rppg_analysis_job_id`를 nullable reference로 `rppg_analysis_jobs`에 추가하고 non-null일 때 unique partial index를 둔다. Camera prediction은 기존 nullable sensor relationship과 기존 3-class output contract를 유지한다. Administrative deletion은 참조 job보다 prediction을 먼저 삭제한다.
- DATA5. Full DGX JSON과 original/resampled waveform은 V2.5 keyring 및 table, column, patient UUID, record UUID에 bind된 AAD로 암호화한다. DATA2/DATA3에서 승인한 searchable metadata만 plaintext로 둔다.
- STATE1. Job transition은 `queued -> running -> completed|retry_required|failed`다. Retry는 terminal row를 변경하지 않고 새 `queued` attempt를 만든다. Startup recovery는 stale `running`을 sanitized recovery marker와 함께 `queued`로 바꾼 뒤 claim한다.
- STATE2. `(patient_id, client_capture_id)`와 plaintext-video SHA-256이 idempotency를 정의한다. 동일 UUID와 hash는 최신 existing job을 반환하고 동일 UUID와 다른 hash는 409 `RPPG_CAPTURE_CONFLICT` 및 audit record를 생성한다.

## 9. 권한, 보안, 개인정보 및 감사

- SEC1. 모든 public NeuroTruth rPPG route는 기존 JWT role 및 ownership check를 요구한다. DGX는 patient JWT, patient UUID, name 또는 app credential을 받지 않으며 `session_id`에는 random job UUID만 보낸다.
- SEC2. 수락된 MP4는 fresh nonce와 `rppg_captures/video/patient/capture` AAD를 사용하는 기존 versioned AES-256-GCM keyring으로 암호화한다. Permanent storage는 `RPPG_STORAGE_ROOT` 아래 packed encrypted envelope만 포함한다.
- SEC3. Plaintext는 검증하거나 DGX로 stream하는 동안 restrictive tmpfs file에만 존재하며 cancellation, exception, timeout 및 shutdown의 `finally`에서 삭제한다. Log, error, audit metadata 및 DGX-facing filename에는 patient data, path, credential, waveform 또는 provider response를 포함하지 않는다.
- PRIV1. 수락된 모든 success와 failure video를 영구 보존한다. Consent withdrawal은 신규 capture를 막지만 기존 capture, job, prediction 또는 audit data를 삭제하지 않는다.
- AUDIT1. Conflicting idempotency reuse, administrator playback 시도/결과, delete 시도/결과 및 patient-wide rPPG resource deletion은 actor, action, target, applicable reason, time, sanitized outcome을 `audit_logs`에 기록한다.
- AUDIT2. Playback은 inline only이며 product download endpoint 또는 button을 제공하지 않는다. Privileged viewer가 rendered bytes를 기술적으로 보존할 수 있고 policy, authorization 및 audit가 통제 수단임을 문서에 명시한다.

## 10. 오류, 경계 사례 및 동시성 동작

- ERR1. Backend upload validation은 authentication/consent에 401/403, idempotency conflict 또는 invalid retry state에 409, 20MiB 초과에 413, non-MP4에 415, malformed field 또는 invalid video/duration/fps에 422, disabled 또는 storage/queue prerequisite unavailable에 503을 반환한다. 수락된 artifact를 조용히 잃으면 안 된다.
- ERR2. DGX 413/415/422는 terminal `failed` non-retryable sanitized code로 매핑한다. Connection error, 180-second timeout, HTTP 500 및 HTTP 503은 `retryAllowed=true`의 `failed`로 매핑한다. Other non-200, malformed JSON, missing required success field 및 invalid waveform은 위에서 명시적으로 transient로 분류하지 않는 한 terminal sanitized failure다.
- ERR3. HTTP 200 `retry_required` 또는 `quality.passed=false`는 `retry_required`에만 매핑하고 recapture를 요구한다. RandomForest를 호출하지 않으며 analysis retry endpoint를 사용할 수 없다.
- EDGE1. 수락된 job 도중 logout 또는 consent withdrawal이 발생해도 durable server processing은 취소되지 않는다. 이후 조회에는 patient authentication과 ownership이 필요하며 withdrawal은 신규 capture/retry attempt를 차단한다.
- EDGE2. Optional `sessionId`가 없어도 measurement는 가능하다. 전달하면 patient 소유 session이어야 하며 upload 후 session completion은 camera job을 무효화하지 않는다.
- CONC1. Queue는 database locking으로 job을 atomically claim하고 default concurrency one을 준수하며 duplicate capture creation을 serialize하고 한 capture에 두 active attempt를 실행하지 않는다.
- CONC2. Patient-wide deletion과 individual capture deletion은 관련 row를 lock하고 신규 claim을 중지하며 linked prediction/job 및 encrypted storage를 제거하고 file removal 확인 실패 시 retryable deletion failure를 기록한다.

## 11. 의존성 및 설정

- DEP1. Backend 설정은 `RPPG_ENABLED=false`, `RPPG_BASE_URL=http://192.168.68.50:8000`, `RPPG_CONNECT_TIMEOUT_SECONDS=10`, `RPPG_READ_TIMEOUT_SECONDS=180`, `RPPG_MAX_UPLOAD_MIB=20`, `RPPG_MAX_CONCURRENCY=1`이며 enabled일 때 writable `RPPG_STORAGE_ROOT`가 필수다.
- DEP2. 확인된 개발 DGX는 `status=ok`, `model_loaded=true`, `device=cuda:0`, checkpoint `PURE_FactorizePhys_FSAM_Res.pth`를 보고하고 `/v1/rppg/infer`에서 `video`, `session_id`, `include_waveform`을 받는다.
- DEP3. Android는 CameraX VideoCapture/ImageAnalysis 및 bundled, on-device ML Kit face detector를 추가한다. Network-based face analysis에 의존하면 안 되며 original MP4 기록 외의 model-input preprocessing을 하지 않는다.
- DEP4. Test/LAN operation은 internal HTTP를 사용할 수 있다. Co-deployment는 `RPPG_BASE_URL`을 `http://rppg:8000`으로 바꾸고 DGX port 8000을 publish하지 않으며 DGX temporary upload storage를 tmpfs로 mount하고 `DELETE_UPLOADED_VIDEO=true`를 확인한다.

## 12. 마이그레이션, 출시 및 롤백

- MIG1. DATA1-DATA4용 additive Alembic revision `0002`를 만든다. `0001`, `neurotruth_schema_v2_5.sql` 또는 명시한 additive column/check change 외 기존 18 table은 수정하지 않는다.
- MIG2. 기존 consent snapshot은 두 새 field가 false가 된다. 기존 환자는 자동 eligible이 아니며 mobile app에서 명시적 선택 후 새 immutable consent snapshot을 생성해야 한다.
- ROLL1. Feature를 disabled한 상태로 migration/backend를 먼저 배포하고 compatible admin web과 Android를 배포한다. Status, encryption, queue 및 real-device check가 통과한 controlled LAN에서만 활성화한다.
- ROLL2. Release readiness는 실제 10초 phone video success, DGX 또는 backend plaintext residue 없음, valid 512-point RF input, 올바른 phone-only result routing 및 contact PPG와의 HR comparison 기록을 추가로 요구한다.
- BACK1. `RPPG_ENABLED=false`로 behavior를 롤백하며 migration, encrypted video, job, prediction 및 unrelated service는 유지한다. Retained rPPG data가 존재하면 `0002`를 downgrade하지 않는다.

## 13. 구현 경계

### 13.1 예상 변경 영역

- Backend V2.5 settings/runtime/routes, 집중된 DGX adapter/queue/storage/service/repository slice, additive Alembic `0002` 및 집중 테스트.
- Android phone app camera screen, consent/auth model, API client, durable job polling/pause latch, result card 및 test. Camera result를 watch로 보내지 않도록 하는 데 필요한 경우에만 Wear OS code를 수정한다.
- Administrator web capture summary/playback/delete control과 현재 API, privacy, deployment 및 operator documentation.

### 13.2 금지 변경

- DGX source, checkpoint, Docker project, API 또는 model behavior를 수정하지 않는다.
- Alembic `0001`, `neurotruth_schema_v2_5.sql` 또는 관련 없는 V2.5 authentication/session/agent contract를 수정하지 않는다.
- DGX를 mobile 또는 public network에 직접 노출하거나 camera result를 watch로 보내거나 automatic retry/retention deletion을 추가하거나 video/waveform download API를 추가하지 않는다.
- 위에서 명시하지 않은 unrelated refactor, formatting churn, dependency upgrade, broad rewrite 또는 generated-file churn은 금지한다.

### 13.3 최소 변경 지침

- 기존 AES-GCM, AAD, JWT ownership, audit, prediction, alert/cooldown, consent snapshot, Android auth storage 및 pause-latch pattern을 재사용한다.
- 신규 rPPG endpoint와 consent field 밖의 public interface를 보존한다.
- 현재 Android toolchain과 호환되는 CameraX 및 bundled ML Kit에 필요한 dependency만 추가한다.

## 14. 인수 기준

- AC1. 네 가지 현재 동의를 모두 가진 로그인 환자가 한 얼굴을 1초 안정적으로 유지하면 phone은 silent 720p/30fps 10초 MP4를 녹화하고 1초 얼굴 이탈 또는 backgrounding 시 취소한다.
- AC2. 수락된 upload에 backend가 202를 반환할 때 encrypted permanent video 및 durable capture/job row가 이미 존재하고 phone은 resumable job state를 잃지 않은 채 local MP4를 삭제한다.
- AC3. DGX quality-approved success 처리 완료 시 정확히 512 finite rPPG value와 512 zero EDA value가 기존 3-class RandomForest에 입력되고 추적 가능한 `camera_rppg` prediction이 저장된다.
- AC4. DGX `retry_required` 또는 failed quality 시 craving prediction은 없고 phone은 no craving 표시나 same-video retry 대신 새 capture를 요청한다.
- AC5. Transient DGX failure 후 patient가 manual retry하면 retained encrypted video를 한 번 사용하는 새 queued attempt가 생성되고 automatic retry는 발생하지 않는다.
- AC6. Duplicate `clientCaptureId`에서 video hash가 같으면 기존 latest job을 반환하고 다르면 overwrite 없이 HTTP 409 및 audit event가 발생한다.
- AC7. Queued/running job이 있는 backend restart 후 durable work를 안전하게 requeue하고 configured concurrency보다 많은 요청이 DGX에 도달하지 않는다.
- AC8. 수락된 success 또는 failure의 storage를 검사하면 permanent file, DGX response 및 waveform에 plaintext가 없고 AES-GCM/AAD tampering이 거부된다.
- AC9. Capture 또는 job completion 후 monitoring을 재개하면 이전 sensor-upload/SSE state가 복원되고 watch reception은 중단되지 않았으며 camera prediction은 watch로 전송되지 않는다.
- AC10. Notification consent가 있는 camera craving prediction이 기존 cooldown rule을 만족하면 phone alert/AUQ/chat이 실행될 수 있고, consent가 없으면 prediction은 저장되지만 presentation은 실행되지 않는다.
- AC11. Administrator가 필요한 reason/confirmation으로 video를 reveal 또는 delete하면 authorization, no-store inline handling, linked deletion 및 완전한 audit 동작이 통과하고 download endpoint는 존재하지 않는다.
- AC12. Missing consent, disabled feature, invalid media, excessive size, ownership failure, timeout 또는 DGX error에서 지정된 sanitized status/code를 반환하고 credential, path, patient data, waveform 또는 provider payload가 유출되지 않는다.
- AC13. `RPPG_ENABLED=false`일 때 기존 NeuroTruth workflow를 실행하면 camera entry/API processing은 unavailable이고 watch sensor, SSE, alert, AUQ, chat, report 및 저장된 rPPG data는 유지된다.
- AC14. Controlled live environment에서 실제 phone measurement를 end-to-end 실행하면 app, backend, DGX, RF, DB 및 result card가 완료되고 release readiness 전에 DGX tmpfs와 backend plaintext cleanup을 모두 검증한다.

## 15. 검증 계획

| 확인 | 명령 또는 방법 | 기대 결과 |
| --- | --- | --- |
| Spec pair | `python C:\Users\NeuroAI-Laptop\.codex\skills\feature-planner\scripts\validate_spec_pair.py neurotruth/docs/specs/2026-07-15-neurotruth-dgx-factorizephys-rppg-integration-spec.md` | PASS |
| Backend network-free | Fake DGX와 함께 `neurotruth/apps/backend`에서 `apps/backend/.venv/Scripts/python.exe -m pytest` | Network 없이 multipart/status/error, 512+zero EDA, idempotency, recovery, encryption, consent, audit 및 deletion 통과 |
| Fresh migration | Blank PostgreSQL에 Alembic을 `0002`까지 적용하고 schema 검사 | Existing 18-table baseline을 보존하고 승인한 additive object만 존재 |
| Android | `neurotruth/apps/mobile`에서 `.\gradlew.bat testDebugUnitTest assembleDebug lintDebug` | Camera state, pause/recovery, API polling, consent, phone-only routing test와 build/lint 통과 |
| Admin web | `neurotruth/apps/web`에서 `npm run build`와 focused test | Capture summary, reason playback/delete, permission 및 no-download behavior 통과 |
| Security inspection | Success, quality failure, timeout, cancellation 및 restart 후 tmpfs와 encrypted volume 검사 | Plaintext residue가 없고 ciphertext/AAD tampering은 fail closed |
| Live joint test | Real phone 10초 capture를 backend를 통해 `192.168.68.50:8000`으로 실행한 뒤 contact PPG와 HR 비교 | Full flow 완료, quality failure가 craving result가 아님, routing/cleanup 정확, HR validity 기록 |

## 16. 위험 및 참고 사항

- RISK1. DGX service는 아직 실제 smartphone-video end-to-end validation을 완료하지 않았다. 기본 disabled를 유지하고 공동 테스트 전에는 quality threshold, waveform validity 및 HR을 실험값으로 취급한다.
- RISK2. 기존 craving model은 실제 EDA로 학습되었으므로 all-zero EDA는 의도적인 experimental input이며 임상 검증된 것으로 표시하면 안 된다.
- RISK3. Internal DGX HTTP에는 authentication 또는 TLS가 없다. Test 중 LAN/VPN으로 제한하고 co-deployment 시 외부에 노출되지 않는 Docker service network를 사용한다.
- RISK4. Permanent facial-video retention은 매우 민감하다. Explicit consent, reason-gated access, least privilege, encryption, deletion 및 audit가 필수 control이다.
- RISK5. Inline playback은 privileged viewer가 rendered bytes를 보존하는 것을 기술적으로 막을 수 없으며 운영 policy와 audit가 accountability를 제공한다.

## 17. 구현 체크리스트 / 진행 기록

| ID | 작업 / 범위 | 담당 | 상태 | 변경 파일 | 검증 | 참고 |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | 영문/한국어 spec pair 확정 및 검증 | Spec worker | 완료 | 두 rPPG spec file | `validate_spec_pair.py` | Source edit 전 authoritative decision 확정. |
| P2 | Backend, DB `0002`, DGX adapter, queue, encryption, prediction integration | Backend worker | 완료 | Additive `0002`; focused `app/v25/rppg_*` services/routes/storage/repository; runtime/config 및 tests | Backend suite: `136 passed`; enabled-runtime, sanitized storage/queue failure, finite sample-rate, quality model-version, AES/AAD, 512 PPG + 512 zero EDA, API 및 schema tests 통과 | Baseline `0001`과 authoritative SQL은 rPPG를 위해 수정하지 않았다. Feature는 기본 disabled 상태를 유지한다. |
| P3 | Android capture, consent, polling, pause recovery, phone result | Android worker | 완료 | Phone CameraX/ML Kit flow, rPPG API/models/runtime/ViewModel, consent 및 monitoring integration, focused unit tests | Android: 15 suites / 61 tests / 0 failures; `assembleDebug` 성공; lint 0 errors 및 기존 warnings 26개 | Backend `alertAction`을 보존하고 routed-result receipt는 recreation 후에도 유지되며 camera prediction은 watch로 전송되지 않는다. |
| P4 | Administrator web, configuration, privacy/API/deployment documentation | Web/docs worker | 완료 | Admin capture summary, reason-gated inline playback/delete controls, configuration 및 operator documentation | `npm run build` 성공; static review에서 download endpoint/button 부재와 object-URL cleanup 확인 | Playback은 `no-store`, reason-gated 및 audited 상태를 유지한다. |
| P5 | Minimal-diff review, full validation, live integration evidence | Main verifier | 자동 검증 완료; 라이브 gate 대기 | 영문/한국어 spec pair 및 cross-contract verification record | `git diff --check` 통과; verifier defect 5개 수정 및 재검증; Backend, Android, lint, APK 및 Web 검증 통과 | 실제 10초 phone video의 Backend -> DGX -> RF -> DB -> phone, DGX/backend plaintext cleanup 및 contact-PPG HR comparison은 release enable 전 필수다. |

## 18. 개정 이력

| 버전 | 날짜 | 작성자 | 변경 사항 |
| --- | --- | --- | --- |
| 1.1 | 2026-07-15 | Feature Planner verifier | Backend, Android, admin web, documentation 및 automated verification 완료를 기록했다. 실제 휴대폰 DGX 라이브 검증 전까지 release는 disabled 상태를 유지한다. |
| 1.0 | 2026-07-15 | Feature Planner | 최초 확정 DGX FactorizePhys 연동 spec pair. |
