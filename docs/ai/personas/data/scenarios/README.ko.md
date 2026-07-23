# NeuroTruth 3분 콘티 연동 시간 정규화 시나리오

이 폴더의 데이터는 앱 영상 촬영을 위한 **synthetic/demo-only 데이터**입니다. 모델 성능, 임상 성능, 정확도 또는 F1 평가 결과로 사용하면 안 됩니다.

## 추천 시나리오

- `1_1_010_V1`: 깊은 저점 뒤 빠르게 반등하고 높은 상태가 지속되는 메인 시나리오
- `1_1_004_V2`: 높은 초기 구간이 완화된 뒤 후반에 다시 상승하는 보조 시나리오

두 시나리오는 실제 class 1 softmax에서 MA10을 먼저 계산한 뒤 값과 순서는 그대로
유지하고 시간축만 60분으로 정규화합니다. 두 subject를 연결하거나 임의 상승
keyframe을 추가하지 않습니다.

## 파일

각 subject마다 다음 세 파일이 생성됩니다.

- `<subject>.csv`: 프론트엔드에서 행 단위로 재생하기 쉬운 형식
- `<subject>.json`: 메타데이터, 임계값, 영상 cue와 event를 포함한 권장 형식
- `<subject>.png`: 원본 synthetic 값과 10-window 이동평균 확인용 그래프

## 데이터 시간과 재생 규칙

- 앱 그래프 데이터 범위: 1시간
- 원본 window stride: 10초
- 총 event: 360개
- 1시간 신호 모션 압축 재생 간격: 약 83.333ms
- 신호 수집·분석 모션 구간: 0:37~1:07, 30초
- 실제 앱 구간: 1:07~2:37, 90초
- `movingAverage10`: 원본 20초 window·10초 stride에서 계산한 실제 MA10을 시간 정규화
- 정규화 전 원본 최초 9개 window: `window_warming_up`
- demo 권고 임계값: `0.55`
- demo 필수 중재 임계값: `0.72`
- `notificationEvent=true`: 권고 또는 필수 단계로 처음 상승하는 순간

이 임계값은 영상 시연 전용이며 임상 기준이 아닙니다.

## 프론트엔드 연결

JSON의 `events`를 `playbackIntervalMs=83.333` 간격으로 순서대로 읽으면 1시간 데이터가 0:37~1:07의 30초 모션 구간에 압축 재생됩니다. 앱 그래프의 x축에는 `sourceElapsedSecond` 또는 `timestampOffsetMs`를 사용하고, 모션 타이머에는 `playbackOffsetMs`를 사용합니다. 1:07부터는 새 센서 곡선을 재생하지 않고 같은 `triggerEventId`를 사용해 알림 → AUQ → 대화 → 대시보드를 녹화합니다.

## 콘티 기준 실제 앱 타임라인

- `1:07~1:17`: 워치·폰 상태 확인 알림
- `1:17~1:42`: AUQ 8문항
- `1:42~2:17`: 지지적 대화
- `2:17~2:37`: 1시간 그래프, AUQ, 최근 이벤트, 대화 요약

현재 NeuroTruth 계약에 맞춰 AUQ는 화면상 7개 문장 선택지를 사용하고 API 값은 문항별 `0~6`, 총점 `0~48`입니다. Fixture 점수는 `28/48`이며 임의의 낮음·중간·높음 cutoff를 붙이지 않습니다. 원본 콘티의 `1~7`, `X/56` 표기는 현재 구현과 맞지 않으므로 사용하지 않습니다.

챗봇 이후 곡선을 낮춰 중재 효과를 암시하지 않습니다. 1시간 곡선은 상태 확인 알림 직전까지의 모델 출력이며, AUQ와 대화는 별도 자기보고·맥락 기록입니다.

기본 추천은 반등 구간이 선명한 `1_1_010_V1.json`입니다. `1_1_004_V2.json`은
한번 가라앉은 뒤 다시 올라오는 보조 사례로 사용합니다.

## 데모 페르소나와 직접 입력 대사

- `1_1_004_V2`: [`NT-DP-001` 완화 후 재상승형](../../NT-DP-001_1_1_004_V2_resurgence.ko.md)
- `1_1_010_V1`: [`NT-DP-002` 저점 후 지속 반등형](../../NT-DP-002_1_1_010_V1_rebound.ko.md)
- 촬영용 문장: [운영자 입력 대본](../../DEMO_OPERATOR_SCRIPT.ko.md)

사용자는 운영자 대본의 문장을 직접 입력하고 Bedrock 응답은 실시간 생성합니다.
페르소나 문서나 기대 slot은 NeuroTruth 중재 agent에 전달하지 않습니다.

## 지난 30일 합성 이력

`2026-06-23~2026-07-22` 기간의 AUQ 자가평가와 갈망 이벤트는
[`monthly_history`](monthly_history/README.ko.md)에 있습니다.

- `NT-DP-001 / 1_1_004_V2`: 갈망 이벤트 42회(날짜별 0~4회, 하루 평균
  1.40회), AUQ 7회, 최신 `28/48`
- `NT-DP-002 / 1_1_010_V1`: 갈망 이벤트 65회(날짜별 0~4회, 하루 평균
  2.17회), AUQ 10회, 최신 `28/48`

각 subject별 통합 JSON과 이벤트/AUQ CSV가 있으며, 전체 집계는
`monthly_history/monthly_summary.csv`를 사용합니다.

## 재생성

`Alcohol_Test` 루트에서 실행합니다.

```powershell
..\neurotruth\apps\backend\.venv\Scripts\python.exe scripts\build_demo_scenarios.py
..\neurotruth\apps\backend\.venv\Scripts\python.exe scripts\build_monthly_persona_history.py
```
