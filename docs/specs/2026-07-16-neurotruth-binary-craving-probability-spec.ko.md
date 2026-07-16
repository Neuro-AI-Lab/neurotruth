# NeuroTruth 이진 갈망 확률 모델 전환 명세

- **언어 역할:** Korean mirror spec
- **명세 상태:** Finalized
- **명세 버전:** 1.1
- **최종 갱신:** 2026-07-16
- **영문 기준:** `docs/specs/2026-07-16-neurotruth-binary-craving-probability-spec.md`
- **한국어 미러:** `docs/specs/2026-07-16-neurotruth-binary-craving-probability-spec.ko.md`
- **요청자 / 소유자:** NeuroTruth 팀
- **구현 상태:** 구현 완료; 실제 DGX CUDA 인수 검증 대기

## 0. Codex 구현 인계

```yaml
<codex_feature_planner_handoff>
skill: feature-planner
next_mode: IMPLEMENTATION_ORCHESTRATION
english_source_spec: neurotruth/docs/specs/2026-07-16-neurotruth-binary-craving-probability-spec.md
korean_mirror_spec: neurotruth/docs/specs/2026-07-16-neurotruth-binary-craving-probability-spec.ko.md
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

현재 활성화된 3-class RandomForest 갈망 모델을 `../final_moving_average_k5_model`에서 제공된 2-class PyTorch `Conv1DNet`으로 교체한다. 백엔드는 매초 최신 10초 PPG/GSR 창을 처리하고 class 1 softmax 확률을 인증된 Android 대시보드에 연구용 “갈망 가능성(모델)”로 제공한다. DGX Spark 운영 환경에서는 CUDA를 우선 사용하며 CUDA smoke inference를 완료하지 못하면 CPU로 자동 전환한다. 기존 3-class 파생 데이터는 명시적 감사형 유지보수 명령으로만 제거하며 원시 센서, 사용자 데이터, 대화, AUQ, 세션, 중재, 보고서, rPPG capture/job은 보존한다.

## 2. 목표

- **G1.** 제공된 이진 모델을 결정론적이며 학습 계약에 맞는 입력·출력으로 서빙한다.
- **G2.** 두 class 확률을 저장·스트리밍하고 Android에서 class-1 확률을 매초 표시한다.
- **G3.** 3-class 임계값 알림을 binary class 이력 규칙으로 전환한다.
- **G4.** 관측 가능한 CPU fallback을 갖춘 CUDA 우선 DGX 배포를 지원한다.
- **G5.** 기존 3-class 파생 기록을 위한 안전하고 명시적인 정리 경로를 제공한다.

## 3. 비목표

- **NG1.** 제공된 모델 가중치를 재학습·보정·벤치마크하거나 변경하지 않는다.
- **NG2.** class-1 확률을 임상 점수, 진단, 치료 효과 또는 보정된 갈망 강도로 표현하지 않는다.
- **NG3.** 관리자 웹 대시보드나 Wear OS에 확률 그래프를 추가하지 않는다.
- **NG4.** 숨겨진 rPPG UI를 노출하거나 활성화하지 않는다.
- **NG5.** Alembic migration을 추가하지 않는다. 현재 prediction/model-version 컬럼으로 충분하다.
- **NG6.** 원시 센서, 사용자, 동의, AUQ, 세션, 메시지, 중재, 보고서 또는 rPPG capture/job을 삭제하지 않는다.

## 4. 사용자와 사용 사례

- **UC1. 환자:** 인증된 모니터링 중 최신 class-1 가능성과 매 prediction마다 갱신되는 최근 10분 이력을 본다.
- **UC2. 환자:** 10분, 24시간, 7일, 30일 확률 보기를 전환한다.
- **UC3. 운영자:** 비밀값이나 호스트 경로 노출 없이 runtime이 `cuda:0`인지 CPU fallback인지 확인한다.
- **UC4. 운영자:** binary 배포 전 기존 3-class 파생 기록을 미리 확인한 뒤 명시적으로 제거한다.

## 5. 최종 결정

| ID | 영역 | 결정 | 근거 |
| --- | --- | --- | --- |
| D1 | Runtime | PyTorch CUDA 우선, 자동 CPU fallback. | 사용자 결정 |
| D2 | 전처리 | 신호 필터 없이 각 채널을 512점으로 resample하고 독립 MinMax 정규화. | 사용자 결정 |
| D3 | Artifact | 약 1 MiB state dict와 metadata를 private repository에 포함. | 사용자 결정 |
| D4 | 정리 | 1회성 dry-run/confirm CLI. 자동 migration이나 관리자 버튼 없음. | 사용자 결정 |
| D5 | 알림 | 최근 10개 중 class 1 여섯 개면 recommend, class 1 세 번 연속이면 required. | 사용자 결정 |
| D6 | 그래프 | Android에만 10분 raw 1 Hz 및 장기 구간 server bucket. | 사용자 결정 |
| D7 | 표현 | 사용자 문구는 “갈망 가능성(모델)”이며 연구용 한계를 포함. | 보수적 제품 기본값 |

## 6. 기능 요구사항

- **FR1. 모델 artifact:** `model.py`, `model_weights.pt`, `model_metadata.json`을 backend model 영역에 복사한다. 로드 전에 원본 artifact에서 계산한 weights SHA-256 `9fec80f7b2c42ba8a5bdb1d702a82eb5bd73542365c7ccb91db3d880b9f321c5`와 일치하는지 확인한다. `weights_only=True`, `eval()`, `torch.inference_mode()`를 사용한다.
- **FR2. 입력:** 1초 stride의 10초 sliding window를 사용한다. PPG는 `PPG_GREEN`, `PPG_IR`, `PPG_RED` 순으로 선택하고 GSR은 `EDA`를 사용한다. 필터 없이 각 채널을 51.2 Hz / 512개 값으로 선형 resample한다. `[0,1]`로 독립 MinMax 정규화하며 상수 채널은 512개 0으로 만든다. `[PPG,GSR]` 순서의 `(1,2,512)`로 쌓는다.
- **FR3. 입력 실패:** 유한 PPG 값이 없는 창은 거부하고 prediction을 만들지 않는다. GSR 누락은 zero 채널로 처리하고 비민감 input-quality metadata에 표시한다.
- **FR4. 출력:** 두 logits에 softmax를 적용한다. index 0은 `low`, index 1은 `high`이다. `class`는 `argmax`이며 정확한 동률은 class 0이다. `confidence=max(p0,p1)`, `cravingProbability=p1`이다.
- **FR5. 모델 저장:** artifact SHA, preprocessing, input/output schema, PyTorch version, 요청 device, 실제 device를 포함한 새 `binary_classification` model version을 등록한다. `predicted_class_probability=confidence`, `class_probabilities={"low":p0,"high":p1}`, `continuous_value=p1`, scale `0.0..1.0`을 저장한다.
- **FR6. 알림 규칙:** sensor session별 binary class 10개를 유지한다. 가득 찬 window에 1이 6개 이상이면 `recommend`, 끝 3개가 1이면 warm-up 중에도 `required`이며 downtrend 억제보다 우선한다. 전반부 대비 후반부 평균이 `0.6` 이상 감소하면 recommendation을 억제한다. non-none level별 30초 cooldown을 유지한다.
- **FR7. 대시보드:** Android는 최신 `p1`을 소수점 한 자리 퍼센트와 고정 0–100% 선 그래프로 표시한다. `10m`, `24h`, `7d`, `30d`를 제공하고 누락 기간을 smoothing하거나 보간하지 않는다.
- **FR8. 호환 표면:** 관리자 웹은 binary label만 표시하고 확률 그래프는 제공하지 않는다. Wear OS는 class 0/1을 low/high로 표시하고 server `alertAction`을 신뢰하며 class만으로 local alert를 만들지 않는다. 숨겨진 rPPG flow는 기존 zero-EDA adapter와 binary predictor를 재사용하고 계속 숨긴다.
- **FR9. 기존 기록 제거:** 유지보수 명령은 `multiclass_classification` 또는 Low/Mid/High output schema로 기존 model version을 찾는다. PostgreSQL advisory lock 아래 한 transaction에서 해당 prediction, 연결 alert, 해당 prediction을 직접 참조하는 state inference를 삭제한다. 비목표 데이터는 보존하고 성공 후 집계 system audit record 하나를 삽입한다.
- **FR10. 기존 구현:** binary test 통과 뒤 현재 source/image에서 활성 RandomForest artifact, RF 전용 feature extraction 파일, RF 전용 dependency를 제거한다. repository history와 이전 image를 rollback 원본으로 사용한다.

## 7. 사용자 경험 / UI 요구사항

- **UI1.** Android card 제목은 `갈망 가능성(모델)`이며 최신 값은 `0.0%`부터 `100.0%`까지 표시한다.
- **UI2.** 이 값이 진단 또는 임상 강도가 아닌 연구 모델 출력임을 카드에 표시한다. 지원 문서에는 최종 가중치가 가용 학습 데이터를 모두 사용했고 독립 final-weight test 평가가 없음을 기록한다.
- **UI3.** 10분 보기는 API backfill로 시작한 뒤 인증 SSE point를 prediction timestamp/ID로 deduplicate하여 추가한다. raw point는 최대 600개를 유지한다.
- **UI4.** 누락 bucket은 시각적 공백으로 남긴다. loading, empty, unavailable 상태는 기존 dashboard pattern을 유지한다.
- **UI5.** 기존 AUQ, event, PPG preview, state summary, report status는 유지한다.

## 8. API / 데이터 / 상태 요구사항

### 8.1 Prediction event

기존 인증 SSE 및 sensor-result event는 의도적인 breaking binary contract이며 다음을 추가한다.

```json
{
  "predictionSchema": "binary-craving-v1",
  "class": 1,
  "classCode": "high",
  "confidence": 0.812345,
  "cravingProbability": 0.812345,
  "classProbabilities": {"low": 0.187655, "high": 0.812345},
  "timestampMs": 1784160000000,
  "alertLevel": "recommend",
  "alertAction": "recommend_intervention",
  "windowMean": 0.7,
  "classOneRatio": 0.7
}
```

확률은 유한하며 `[0,1]` 범위이고 wire 경계에서 소수점 여섯 자리로 반올림하며 합은 약 1이다. `windowMean`은 `classOneRatio`의 호환 alias로 유지한다.

### 8.2 환자 확률 series

`GET /api/me/craving-probability-series?range=10m|24h|7d|30d`는 환자 JWT ownership을 요구하고 UTC ISO-8601 시각을 반환한다.

```json
{
  "range": "24h",
  "from": "2026-07-15T00:00:00Z",
  "to": "2026-07-16T00:00:00Z",
  "bucketSeconds": 60,
  "points": [
    {"at": "2026-07-15T00:01:00Z", "averageCravingProbability": 0.7132, "sampleCount": 57}
  ]
}
```

bucket 크기는 `10m=1s`(최대 600), `24h=60s`(최대 1,440), `7d=600s`(최대 1,008), `30d=1800s`(최대 1,440)로 고정한다. 빈 bucket은 생략한다. 집계는 활성 binary model의 저장된 `continuous_value`만 사용한다. 기존 환자 dashboard prediction item에는 `cravingProbability`를 추가하며 관리자 probability-series route는 만들지 않는다.

### 8.3 Runtime 상태

Runtime status는 모델명, 기대/실제 checksum, 요청 device, 실제 device(`cuda:0|cpu`), fallback boolean/reason code, class label, sample rate, window length를 공개한다. 절대 host path, credential, stack trace, raw input은 공개하지 않는다.

## 9. 권한, 보안, 개인정보, 감사

- **SEC1.** 확률 이력은 환자 인증 및 기존 JWT dependency의 ownership scope를 적용한다.
- **SEC2.** `weights_only=True` state-dict load만 허용하며 전체 checkpoint를 unpickle하지 않는다.
- **PRIV1.** 로그와 runtime status는 raw sample, credential, 절대 host path, 기존 구조적 persistence 외 patient identifier를 제외한다.
- **AUDIT1.** 성공한 기존 기록 정리는 선택된 model ID/artifact hash와 table별 삭제 건수를 담은 system audit event를 기록한다. dry run은 읽기 전용이며 audit data를 넣지 않고 건수만 출력한다.

## 10. 오류, 경계 상황, 동시성

- **ERR1.** artifact 누락, checksum 불일치, 호환되지 않는 state dict, CPU load 실패가 있으면 auth/chat은 유지하지만 prediction 제출은 `503 model_unavailable`을 반환한다.
- **ERR2.** `CRAVING_INFERENCE_DEVICE=auto`는 `torch.cuda.is_available()`과 실제 smoke inference가 모두 성공한 뒤에만 CUDA를 사용한다. CUDA 초기화/smoke 실패는 code를 로그에 기록하고 같은 weights를 CPU에 다시 로드한다. CPU load도 실패하면 모델 unavailable 상태가 된다.
- **EDGE1.** 지원하지 않는 dashboard range는 기존 structured `422` 형식을 사용하고 prediction이 없으면 빈 points array를 반환한다.
- **EDGE2.** 갱신된 Android/Wear parser가 class 2를 받으면 high로 바꾸지 않고 호환되지 않는 prediction으로 거부한다.
- **CONC1.** 기존 bounded inference queue/drop-stale 동작을 유지한다. Dashboard history query는 read-only다. Cleanup은 backend prediction worker가 정지한 상태를 요구하며 advisory lock도 획득한다.

## 11. Dependency와 설정

- **DEP1.** backend base runtime을 Python 3.12로 올리고 `torch==2.12.0`을 pin한다. 다른 곳에서 계속 사용하는 비-RF scientific dependency만 유지한다.
- **DEP2.** backend에 `gpus: all`을 부여하는 DGX Compose override를 추가하고 base Compose는 GPU 없이 실행할 수 있게 유지한다.
- **CONF1.** 다음을 추가한다.

```dotenv
CRAVING_MODEL_PATH=/app/model/weights/final_moving_average_k5/model_weights.pt
CRAVING_MODEL_METADATA_PATH=/app/model/weights/final_moving_average_k5/model_metadata.json
CRAVING_MODEL_SHA256=9fec80f7b2c42ba8a5bdb1d702a82eb5bd73542365c7ccb91db3d880b9f321c5
CRAVING_INFERENCE_DEVICE=auto
ALERT_WINDOW_SIZE=10
ALERT_RECOMMEND_COUNT=6
ALERT_HIGH_STREAK=3
ALERT_COOLDOWN_SECONDS=30
ALERT_DOWNTREND_DELTA=0.6
```

`ALERT_REQUIRED_MIN`을 제거한다. 기존 배포 port, database credential, encryption key, Bedrock 설정, rPPG 설정은 변경하지 않는다.

## 12. Migration, 출시, rollback

- **MIG1.** Alembic revision은 없다. Binary 출시 전에 PostgreSQL을 backup하고 prediction worker를 정지한 뒤 다음을 실행한다.

```powershell
docker compose run --rm backend python -m app.maintenance.purge_legacy_predictions --dry-run
docker compose run --rm backend python -m app.maintenance.purge_legacy_predictions --confirm DELETE-LEGACY-3CLASS-PREDICTIONS
```

- **ROLL1.** class contract이 breaking이므로 backend와 Android를 함께 배포한다. DGX에서는 GPU Compose override로 시작하고 device 인수 전 `actualDevice=cuda:0`을 확인한다.
- **BACK1.** 이전 Git commit/image로 rollback한다. 삭제한 기존 파생 prediction은 자동 복원하지 않으며 복구에는 배포 전 DB backup이 필요하다. 원시 sensor와 user data는 계속 사용할 수 있다.

## 13. 구현 경계

### 13.1 예상 변경 영역

- Backend inference, alert, V25 model-version persistence/dashboard repository/route, 유지보수 CLI, Docker/requirements/config, 집중 test, model artifact.
- Android dashboard/SSE prediction contract와 test.
- Wear OS binary label/alert 호환과 test.
- 관련 README, API, 배포 문서.

### 13.2 금지 변경

- Alembic이나 baseline SQL 변경 없음.
- 관리자 probability graph 없음.
- rPPG UI 활성화나 DGX rPPG service 변경 없음.
- 대화 agent, AUQ, 인증, 암호화, port, database topology 재설계 없음.
- 관련 없는 refactor, formatting churn, mass rename, generated-file churn, 사용자 변경 복구 없음.

## 14. 인수 기준

- **AC1.** 유효한 10초 PPG/GSR fixture에서 service preprocessing은 `(1,2,512)`를 만들고 CPU에서 logits/softmax가 제공된 model 구현과 `1e-5` 이내로 일치한다.
- **AC2.** 허용된 매 1초 stride window는 새 model version 아래 `p0`, `p1`, binary class, predicted-class confidence, `p1` continuous value를 emit/store한다.
- **AC3.** 완전한 10-window 이력에서 1 여섯 개는 recommend, 끝의 1 세 개는 warm-up 중에도 required이며 downtrend는 세 번 연속 규칙을 억제하지 않고 cooldown은 반복 action을 막는다.
- **AC4.** Android는 최신 `p1` percentage와 gap/bound가 올바른 10m/24h/7d/30d series를 표시하고 관리자 웹과 watch에는 probability graph가 없다.
- **AC5.** DGX는 CUDA smoke inference 성공 뒤 CUDA를 보고하고 simulated CUDA failure에서는 CPU를 선택해 prediction을 계속한다.
- **AC6.** Cleanup dry run은 data를 바꾸지 않고 잘못된 confirm도 data를 바꾸지 않으며 확인된 cleanup은 지정된 기존 파생 기록만 제거하고 집계 audit count를 기록한다.
- **AC7.** 기존 authentication, encrypted sensor storage, AUQ, conversation, report, PPG preview, hidden rPPG flow의 regression test가 통과한다.
- **AC8.** 활성 source/image는 더 이상 RandomForest artifact나 RF 전용 inference path를 포함하거나 load하지 않는다.

## 15. 검증 계획

| 검사 | 명령 또는 방법 | 기대 결과 |
| --- | --- | --- |
| Backend unit/integration | repository 환경에서 `python -m pytest apps/backend/tests` | Binary inference, alert, persistence, dashboard, cleanup, regression 통과. |
| Android unit test | `apps/mobile/gradlew.bat :app:testDebugUnitTest` | Prediction parsing, probability series, graph state, alert policy 통과. |
| Wear OS unit/build | `apps/mobile/gradlew.bat :wearos:testDebugUnitTest :wearos:assembleDebug` | Binary label과 server-alert 우선 동작 compile/test 통과. |
| Android build | `apps/mobile/gradlew.bat :app:assembleDebug` | Debug APK build 성공. |
| Docker CPU smoke | Base Compose를 build/start하고 model status/prediction fixture 확인 | `actualDevice=cpu`, prediction 성공. |
| DGX CUDA smoke | GPU override로 시작하고 반복 fixture/window 제출 | `actualDevice=cuda:0`, 1 Hz 처리 안정. |
| Data cleanup | disposable PostgreSQL fixture에서 dry-run, 잘못된 confirm, 확인된 명령 실행 | AC6에 맞는 count, transaction, audit, 보존. |

## 16. 위험과 참고사항

- **RISK1.** 최종 weights는 유지된 학습 data를 모두 사용했고 독립 final-weight test set이 없다. 명확한 연구용 표현과 임상 해석 금지로 완화한다.
- **RISK2.** CUDA/PyTorch image 크기와 DGX driver 호환성이 배포 시간에 영향을 줄 수 있다. Runtime을 pin하고 `nvidia-smi`를 확인하며 CPU fallback을 유지한다.
- **RISK3.** 매초 겹치는 prediction은 자기상관이 있다. UI는 독립 sample이나 smoothing을 주장하지 않고 raw model probability를 표시한다.
- **RISK4.** 기존 기록 정리는 DB backup 없이는 되돌릴 수 없다. Application startup이나 migration에서 자동 실행하지 않는다.

## 17. 구현 체크리스트 / 진행 기록

| ID | 작업 / 범위 | 담당 | 상태 | 변경 파일 | 검증 | 참고 |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | 영문/한글 명세 쌍 확정 및 검증 | Main | Completed | Spec pair | `validate_spec_pair.py`: PASS | Source edit 시작 전. |
| P2 | Backend model, alert, API/data, CUDA packaging, cleanup CLI | Worker | Completed | Backend inference/alert/V25 route와 repository, model artifact, maintenance CLI, Docker/Compose, test | Backend `pytest`: 176 passed, 1 skipped; Docker CPU model parity smoke: PASS; Compose base+DGX config: PASS | 제공된 artifact의 실제 SHA는 `9fec…c7ccb91…`이다. 최초 기재값은 한 글자가 누락되어 code, 설정, test, 본 명세에서 바로잡았다. 로컬에서는 실제 DGX CUDA를 사용할 수 없었다. |
| P3 | Android dashboard와 prediction contract | Worker | Completed | Phone prediction parser, API client, dashboard/chart, ViewModel/uploader, test | `:app:testDebugUnitTest`, `:app:assembleDebug`: PASS | Binary 전용 parsing, p1 history/backfill, gap 표시, server 소유 alert action을 구현했다. |
| P4 | Wear OS 호환과 관련 문서 | Worker | Completed | Wear prediction/alert UI와 test; root/mobile/API/Wear 문서 | `:wearos:testDebugUnitTest`, `:wearos:assembleDebug`, Android 통합 build: PASS | Watch에는 probability graph가 없고 class 1 자체로 alert를 판단하지 않는다. |
| P5 | Main-agent diff review와 전체 검증 | Main | Completed | 전체 worker diff와 동기화된 명세 쌍 | `git diff --check`: PASS; spec pair validator: PASS; model/source softmax 최대 오차 `2.17e-7` | 실제 DGX `cuda:0`, 1 Hz soak, 실제 phone/watch E2E, disposable PostgreSQL 대상 destructive cleanup은 배포 인수 단계에 남긴다. |

## 18. 개정 이력

| 버전 | 날짜 | 작성자 | 변경 |
| --- | --- | --- | --- |
| 1.0 | 2026-07-16 | Feature Planner | Binary PyTorch 갈망 확률 서빙을 위한 최초 확정 명세. |
| 1.1 | 2026-07-16 | Feature Planner | 구현·검증 결과를 기록하고 제공된 artifact에서 계산한 값으로 checkpoint SHA를 바로잡음. |
