"""RandomForest(craving) 서빙 설정.

학습 파이프라인(feature_classification)과 동일한 51.2Hz 피처 추출 조건만 남긴 것.
앱에서 들어온 희소 신호를 FS_RAW 그리드로 보간한 뒤 features.py 로 피처를 추출한다.
"""

# 신호 그리드 / 윈도우 — 앱 신호를 이 그리드(51.2Hz x 512)로 보간한 뒤 피처를 뽑는다.
FS_RAW  = 51.2                    # 앱 신호를 올려붙일 raw 그리드 (학습과 동일)
FS      = 51.2                    # PPG 피처 추출 샘플레이트 (features.extract_ppg_features)
FS_GSR  = 51.2                    # GSR 피처 추출 샘플레이트 (features.extract_gsr_features)
WIN_SEC = 10.0
WIN_LEN = int(WIN_SEC * FS)      # 512
SIGNAL_NORM = "minmax"           # PPG 윈도우 정규화 방식 (features.preprocess_ppg)

# 라벨 — Q_mean 3-class (0=low, 0.5~3.5=mid, 4~7=high)
N_CLASSES = 3
CLASS_LABELS = ["low (0)", "mid (0.5-3.5)", "high (4-7)"]
