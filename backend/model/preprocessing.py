"""신호 전처리(필터+리샘플) + 품질 필터(세션/윈도우). 필터·품질 함수는 기존 repo methods.py 차용."""
from fractions import Fraction
import numpy as np
from scipy.signal import butter, filtfilt, resample_poly

import config as C


def _butter_lowpass(sig, fs, cutoff, order=4):
    nyq = 0.5 * fs
    b, a = butter(order, cutoff / nyq, btype="low")
    return filtfilt(b, a, sig)


def _butter_bandpass(sig, fs, low, high, order=4):
    nyq = 0.5 * fs
    b, a = butter(order, [low / nyq, high / nyq], btype="band")
    return filtfilt(b, a, sig)


def _resample(x, fs_in, fs_out):
    """fs_in -> fs_out 폴리페이즈 리샘플."""
    if fs_in == fs_out:
        return x
    frac = Fraction(fs_out / fs_in).limit_denominator(1000)
    return resample_poly(x, frac.numerator, frac.denominator)


def preprocess_ppg(ppg_raw):
    """PPG: 원본 rate 로 lowpass/bandpass -> FS_PPG 로 리샘플. finite 마스크 반환."""
    x = np.asarray(ppg_raw, dtype=float)
    if C.PPG_FILTER == "bandpass":
        y = _butter_bandpass(x, C.FS_RAW, C.PPG_BAND[0], C.PPG_BAND[1], C.FILTER_ORDER)
    else:
        y = _butter_lowpass(x, C.FS_RAW, C.PPG_BAND[1], C.FILTER_ORDER)
    y = _resample(y, C.FS_RAW, C.FS_PPG)
    return y.astype(np.float32), np.isfinite(y)


def preprocess_gsr(gsr_raw):
    """GSR: 원본 rate 로 lowpass(1Hz) -> FS_GSR 로 리샘플."""
    x = np.asarray(gsr_raw, dtype=float)
    y = _butter_lowpass(x, C.FS_RAW, C.GSR_HIGHCUT, C.FILTER_ORDER)
    y = _resample(y, C.FS_RAW, C.FS_GSR)
    return y.astype(np.float32), np.isfinite(y)


def valid_ratio(finite_mask):
    finite_mask = np.asarray(finite_mask)
    return float(finite_mask.mean()) if finite_mask.size else 0.0


def flatline_ratio(x, fs, eps=C.FLATLINE_EPS):
    """|dx/dt|≈0 비율. 센서 탈락/평탄선이면 1.0 에 가까움."""
    x = np.asarray(x, dtype=float)
    if x.size < 3:
        return 1.0
    dx = np.diff(x) * fs
    return float(np.mean(np.abs(dx) <= eps)) if dx.size else 1.0


def outlier_ratio_robust(x, z_th=C.OUTLIER_Z_TH):
    """MAD 기반 robust z-score 가 z_th 초과인 비율."""
    x = np.asarray(x, dtype=float)
    x = x[np.isfinite(x)]
    if x.size < 5:
        return 1.0
    med = np.median(x)
    mad = np.median(np.abs(x - med))
    if mad <= 0:
        return 0.0
    rz = 0.6745 * (x - med) / mad
    return float(np.mean(np.abs(rz) > z_th))


def window_passes_quality(ppg_win, gsr_win, ppg_finite, gsr_finite):
    """윈도우 품질 통과 여부. 통째로 마스킹된 채널은 건너뜀."""
    if not C.WIN_FILTER_ENABLE:
        return True
    checks = {"ppg": (ppg_win, ppg_finite, C.FS_PPG), "gsr": (gsr_win, gsr_finite, C.FS_GSR)}
    for ch in C.WIN_FILTER_CHANNELS:
        sig, finite, fs = checks[ch]
        if np.asarray(finite).sum() == 0:   # 마스킹된 채널 → 검사 skip
            continue
        if valid_ratio(finite) < C.MIN_VALID_RATIO:
            return False
        if flatline_ratio(sig, fs) > C.MAX_FLATLINE_RATIO:
            return False
        if outlier_ratio_robust(sig) > C.MAX_OUTLIER_RATIO:
            return False
    return True


def excluded_sessions():
    """제외할 세션 집합."""
    if C.SESSION_FILTER == "drop_union":
        return set(C.MISSING_PPG) | set(C.MISSING_GSR)
    if C.SESSION_FILTER == "mask_channel":
        return set(C.MISSING_PPG)
    raise ValueError(f"Unknown SESSION_FILTER: {C.SESSION_FILTER}")


def gsr_masked_sessions():
    """GSR 채널을 0으로 마스킹할 세션 ('mask_channel' 일 때만)."""
    if C.SESSION_FILTER == "mask_channel":
        return set(C.MISSING_GSR) - set(C.MISSING_PPG)
    return set()
