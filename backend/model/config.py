"""설정. 경로/신호/윈도우/필터/학습 파라미터."""
import os
from pathlib import Path

# 경로
_HERE = Path(__file__).resolve().parent
DATA_ROOT  = Path(os.getenv("TRAIN_DATA_ROOT", _HERE / "data" / "alcohol"))
LABEL_CSV  = Path(os.getenv("TRAIN_LABEL_CSV", _HERE / "data" / "alcohol_Qscores.csv"))
OUT_DIR    = Path(os.getenv("TRAIN_OUT_DIR", _HERE / "runs"))
CACHE_DIR  = Path(os.getenv("TRAIN_CACHE_DIR", _HERE / "cache"))
# trial 경로: DATA_ROOT/{subject}_{version}/PPG/{intensity}/{trial}.csv
PPG_COL = "id95AE_PPG_A13_CAL"
GSR_COL = "id95AE_GSR_Skin_Conductance_CAL"

# 신호 / 윈도우 (dual-rate: PPG·GSR 각자 다른 샘플레이트로 리샘플)
FS_RAW      = 51.2          # 원본 샘플레이트
FS_PPG      = 25.0          # PPG 리샘플 목표 (Hz)
FS_GSR      = 1.0           # GSR 리샘플 목표 (Hz)
WIN_SEC     = 10.0
PPG_WIN     = int(WIN_SEC * FS_PPG)   # 250
GSR_WIN     = int(WIN_SEC * FS_GSR)   # 10
TRAIN_OVERLAP = 0.0
VAL_OVERLAP   = 0.0
MAX_WINDOWS_PER_TRIAL = None
INCLUDE_TAIL_WINDOW = True  # 꼬리를 끝-정렬 윈도우로 포함 (패딩 없이 실제 신호)

PPG_FILTER  = "lowpass"     # 'lowpass' | 'bandpass'
PPG_BAND    = (0.3, 5.0)
GSR_HIGHCUT = 0.5           # FS_GSR(1Hz) Nyquist = 0.5Hz → anti-aliasing
FILTER_ORDER = 4
SCALE_MODE   = "perwin"     # 통계 기준: 'global'(fold train) | 'perwin'(윈도우별)
SCALE_METHOD = "minmax"     # 방식: 'zscore' | 'minmax'(0~1)

# 라벨 / 태스크 — Q_mean 3-class (0=low, 0.5~3.5=mid, 4~7=high)
TARGETS     = ["Q_mean"]
TARGET_COL  = "Q_mean"
CLASS_WEIGHT = "balanced"        # "balanced" | None
SELECT_METRIC = "loss"           # "loss"(최소화) | "BalancedAcc"|"MacroF1"|"Acc"(최대화)
EVAL_LEVEL  = "window"           # "window"(10초 윈도우 단위) | "trial"(윈도우 확률 평균→trial)
FINAL_REPORT = "pooled"          # "pooled"(전체 fold test 합침) | "best_fold"(최고 fold만)

# 품질 필터 (1) 세션 레벨 — 기존 05_machine_learning_and_shap.py 의 불량 세션 목록
MISSING_PPG = {'1_1_001_V1', '1_1_001_V2', '1_1_006_V2', '1_1_009_V1', '1_1_009_V2', '1_1_016_V2'}
MISSING_GSR = {
    '1_1_002_V1', '1_1_002_V2', '1_1_005_V1', '1_1_005_V2', '1_1_010_V1',
    '1_1_011_V1', '1_1_012_V2', '1_1_013_V2', '1_1_016_V1', '1_1_016_V2',
    '1_1_017_V1', '1_1_017_V2', '1_1_018_V2', '1_1_019_V1', '1_1_020_V2',
    '1_2_002_V2', '1_2_005_V1', '1_2_005_V2', '1_2_006_V1', '1_2_006_V2',
    '1_2_008_V2', '1_2_009_V1', '1_2_013_V1',
}
SESSION_FILTER = "mask_channel"  # 'mask_channel'(PPG결측만 제외, GSR결측은 마스킹) | 'drop_union'

# 품질 필터 (2) 윈도우 레벨
WIN_FILTER_ENABLE  = True
MIN_VALID_RATIO    = 0.7
MAX_FLATLINE_RATIO = 0.5
MAX_OUTLIER_RATIO  = 0.10
FLATLINE_EPS       = 1e-6
OUTLIER_Z_TH       = 6.0
WIN_FILTER_CHANNELS = ("ppg", "gsr")

# 학습
SEED        = 42
# 분할 방식: 'subject_independent'(GroupKFold, 같은 사람이 train/test 안섞임)
#          | 'subject_dependent'(StratifiedKFold, trial 단위 — 같은 사람이 섞임)
SPLIT_MODE  = "subject_dependent"
N_SPLITS    = 5             # outer = test fold
VAL_RATIO   = 0.2           # train 내 validation 비율
EPOCHS      = 100
BATCH_SIZE  = 64
LR          = 1e-3
WEIGHT_DECAY = 1e-4
LOSS        = "ce"
EARLY_STOP_PATIENCE = 12
NUM_WORKERS = 4
DEVICE      = "cuda:0"
