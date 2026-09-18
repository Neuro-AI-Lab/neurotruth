# NeuroTruth 데모 페르소나 데이터

최종 업데이트: 2026-07-23

이 폴더는 `NT-DP-001 / 1_1_004_V2`와 `NT-DP-002 / 1_1_010_V1` 데모를
재현하고 프론트엔드 fixture로 활용하는 데 필요한 데이터를 함께 보관합니다.

## 구성

| 경로 | 내용 | 성격 |
|---|---|---|
| `raw_g_sensor/<subject>/` | 선택한 G session의 원본 calibrated PPG/GSR CSV | 실제 연구 데이터 |
| `source_model_outputs/<subject>.csv` | 20초 window, 10초 stride 모델 추론 결과 | 실제 모델 출력 |
| `source_model_outputs/accounts.csv` | 두 subject의 batch test 계정 추출 | 테스트 메타데이터 |
| `source_model_outputs/plots/original/` | class 1 softmax 원본 그래프 | 실제 모델 출력 시각화 |
| `source_model_outputs/plots/moving_average_10/` | MA10 그래프 | 실제 모델 출력 시각화 |
| `scenarios/<subject>.csv` | 앱 재생용 1시간 event stream | 데모 fixture |
| `scenarios/<subject>.json` | 1시간 stream과 cue, alert metadata | 데모 fixture |
| `scenarios/<subject>.png` | 최종 1시간 곡선 | 데모 시각화 |
| `scenarios/monthly_history/` | 30일 갈망 이벤트와 AUQ | 전부 합성 |
| `local_validation/20260723T045807Z/` | 계정, 대화, session, dashboard 통합 검증 데이터 | 과거 실행 snapshot |

## 변환 규칙

1. `source_model_outputs/<subject>.csv`의 `cravingProbability`가 class 1
   softmax 원본입니다.
2. 원본 10개 window의 이동평균을 먼저 계산합니다.
3. MA10 값과 순서는 유지하고 시간축만 60분으로 선형 정규화합니다.
4. 임의 확률, 수동 상승 keyframe 또는 서로 다른 subject의 연결은 사용하지 않습니다.
5. `scenarios`의 재생 시간, 알림, cue와 30일 이력은 데모 동작을 위한 합성
   metadata입니다.

## 원본 수량

| Subject | 원본 window | 원본 측정 길이 | MA10 평균 | MA10 최소 | MA10 최대 | MA10 마지막 |
|---|---:|---:|---:|---:|---:|---:|
| `1_1_004_V2` | 149 | 약 25분 | 0.5111 | 0.1730 | 0.8487 | 0.6740 |
| `1_1_010_V1` | 113 | 약 19분 | 0.5184 | 0.0660 | 0.8321 | 0.6440 |

## 검증 snapshot 주의

`local_validation/20260723T045807Z`는 최종 1시간 scenario를 다시 생성하기
약 28분 전에 수행한 실행 기록입니다. 계정, 30일 dashboard, session과 대화
예시를 확인하는 용도로만 사용합니다. 리포트, 리포트 요약과 자동 분석 산출물은
이 데이터 묶음에서 제외했습니다. 최종 1시간 그래프의 기준은 `scenarios`입니다.

## 데이터 취급

- `raw_g_sensor`는 합성 데이터가 아닙니다. 비공개 저장소에서도 연구 데이터
  접근 권한과 기관의 데이터 사용 조건을 확인해야 합니다.
- subject ID는 가명화 식별자이지만 원본 timestamp와 생리신호가 포함됩니다.
- 이름, 생활사, 음주 이력, 월간 이벤트, AUQ와 대화는 모두 데모용 합성 정보입니다.
- 이 자료는 모델 정확도, 임상 진단 또는 중재 효과의 근거가 아닙니다.

영어 문서: [Persona data](README.md)
