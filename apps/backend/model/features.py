import warnings
from collections import Counter
from math import sqrt

import numpy as np
import pandas as pd
import neurokit2 as nk
from scipy.signal import lombscargle, butter, filtfilt
from scipy.stats import ConstantInputWarning
from neurokit2.misc import NeuroKitWarning

import config as C

warnings.filterwarnings("ignore")
warnings.filterwarnings("ignore", category=ConstantInputWarning)
warnings.filterwarnings("ignore", category=NeuroKitWarning)
warnings.filterwarnings("ignore", category=RuntimeWarning)

PPG_BAND = (0.3, 5)
RR_BAND = (0.1, 0.4)


def zscore_normalize(data):
    mean = np.mean(data)
    std = np.std(data)
    if std == 0:
        return data - mean
    return (data - mean) / std


def minmax_normalize(data):
    mn = np.min(data)
    mx = np.max(data)
    if mx == mn:
        return data - mn
    return (data - mn) / (mx - mn)


def butter_bandpass(sig, fs, low, high, order=4):
    nyq = 0.5 * fs
    b, a = butter(order, [low / nyq, high / nyq], btype="band")
    return filtfilt(b, a, sig)


def butter_lowpass(sig, fs, cutoff, order=4):
    nyq = 0.5 * fs
    b, a = butter(order, cutoff / nyq, btype="low")
    return filtfilt(b, a, sig)


def preprocess_ppg(ppg_dict, fs_ppg, lowcut=PPG_BAND[0], highcut=PPG_BAND[1], filter_type="lowpass"):
    out = {"timestamp": ppg_dict["timestamp"]}
    if filter_type == "lowpass":
        filtered = butter_lowpass(ppg_dict["ppg"], fs_ppg, highcut)
    elif filter_type == "bandpass":
        filtered = butter_bandpass(ppg_dict["ppg"], fs_ppg, lowcut, highcut)
    else:
        raise ValueError(f"Unsupported PPG filter_type: {filter_type}")
    norm = minmax_normalize if C.SIGNAL_NORM == "minmax" else zscore_normalize
    out["ppg"] = norm(np.asarray(filtered, dtype=float))
    return out


def get_hr(peaks, fs, len_sig):
    try:
        rate = nk.signal_rate(peaks, sampling_rate=fs, desired_length=len_sig)
        hr = np.mean(rate)
    except Exception as e:
        print(f"Error during HR processing: {e}")
        return np.nan
    return hr


def get_hrv(peaks, fs, show=False):
    try:
        hrv = nk.hrv(peaks, sampling_rate=fs, show=show)
    except Exception as e:
        print(f"Error during HRV processing: {e}")
        return None
    return hrv


def get_si(peaks, fs):
    try:
        peak_locs = np.asarray(peaks, dtype=int)
        ibi = np.diff(peak_locs) / fs
        if len(ibi) < 2:
            return np.nan
        ibi_binned = np.round(ibi / 0.05) * 0.05
        ibi_counter = Counter(ibi_binned)
        M0, M0_count = ibi_counter.most_common(1)[0]
        AM0 = (M0_count / len(ibi)) * 100
        MxDMn = max(ibi) - min(ibi)
        if (M0 == 0) or (MxDMn == 0):
            return np.nan
        si = sqrt(AM0 / (2 * M0 * MxDMn))
    except Exception as e:
        print(f"Error during SI processing: {e}")
        return np.nan
    return si


def get_rr(peaks, fs, len_sig, rr_band=RR_BAND):
    if len_sig < fs * 5:
        print("Warning: Input signal is too short for RR calculation (less than 5 seconds).")
        return np.nan
    try:
        peak_times = np.asarray(peaks) / fs
        ibi = np.diff(peak_times)
        ibi_times = peak_times[1:]
        valid_mask = (ibi >= 0.4) & (ibi <= 1.33)
        ibi = ibi[valid_mask]
        ibi_times = ibi_times[valid_mask]
        if len(ibi) < 4:
            print(f"Warning: Not enough valid IBIs after filtering. len(ibi) is {len(ibi)}.")
            return np.nan
        try:
            f_min, f_max = rr_band
            freqs = np.linspace(f_min, f_max, 1000)
            angular_freqs = 2 * np.pi * freqs
            ibi_mean_removed = ibi - np.mean(ibi)
            psd = lombscargle(ibi_times, ibi_mean_removed, angular_freqs)
            if len(psd) == 0:
                return np.nan
            peak_idx = np.argmax(psd)
            rr = freqs[peak_idx] * 60.0
        except Exception as e:
            print(f"Lomb-Scargle calculation failed: {e}")
            return np.nan
    except Exception as e:
        print(f"Error during RR processing: {e}")
        return np.nan
    return rr


def _post_fix_peak_indices(peak_indices, fs, method="Kubios", iterative=True, show=False):
    peak_indices = np.asarray(peak_indices, dtype=int)
    peak_indices = np.unique(peak_indices)
    peak_indices = peak_indices[peak_indices >= 0]
    if len(peak_indices) < 3:
        return peak_indices, {}
    try:
        artifacts, fixed = nk.signal_fixpeaks(peak_indices, sampling_rate=fs, method=method,
                                              iterative=iterative, show=show)
        fixed = np.asarray(fixed, dtype=int)
        fixed = np.unique(fixed)
        fixed = fixed[fixed >= 0]
        return fixed, artifacts
    except Exception as e:
        print(f"Warning: signal_fixpeaks failed: {e}")
        return peak_indices, {}


def get_PPG_features(ppg_dict, fs_ppg, preprocess=True, filter_type="lowpass",
                     method="elgendi", post_fix_ppg_peaks=True, ppg_fix_method="Kubios"):
    if preprocess:
        preprocessed_dict = preprocess_ppg(ppg_dict, fs_ppg, filter_type=filter_type)
    else:
        preprocessed_dict = ppg_dict
    sig = preprocessed_dict["ppg"]
    try:
        peaks, info = nk.ppg_peaks(sig, sampling_rate=fs_ppg, method=method, correct_artifacts=True)
        ppg_idx = np.asarray(info.get("PPG_Peaks", []), dtype=int)
        if post_fix_ppg_peaks and (len(ppg_idx) >= 3):
            ppg_idx, _ = _post_fix_peak_indices(ppg_idx, fs=fs_ppg, method=ppg_fix_method,
                                                iterative=True, show=False)
        peaks = pd.DataFrame({"PPG_Peaks": np.zeros(len(sig), dtype=int)})
        ppg_idx = ppg_idx[(ppg_idx >= 0) & (ppg_idx < len(sig))]
        peaks.loc[ppg_idx, "PPG_Peaks"] = 1
        info = {"PPG_Peaks": ppg_idx}
        mean_hr = get_hr(info["PPG_Peaks"], fs_ppg, len(sig))
        hrv = get_hrv(peaks, fs_ppg)
        si = get_si(info["PPG_Peaks"], fs_ppg)
        rr = get_rr(info["PPG_Peaks"], fs_ppg, len(sig))
    except Exception as e:
        print(f"Error during PPG features processing: {e}")
        mean_hr, hrv, si, rr = np.nan, None, np.nan, np.nan
    return mean_hr, hrv, si, rr


def extract_ppg_features(ppg_window, fs=C.FS, preprocess=True):
    ppg_dict = {"timestamp": np.arange(len(ppg_window)) / fs,
                "ppg": np.asarray(ppg_window, dtype=float)}
    try:
        hr, hrv, si, rr = get_PPG_features(ppg_dict, fs_ppg=fs, preprocess=preprocess)
    except Exception:
        return {}
    row = {"HR": float(hr) if hr is not None else np.nan,
           "SI": float(si) if si is not None else np.nan,
           "RR": float(rr) if rr is not None else np.nan}
    if hrv is not None and hasattr(hrv, "iloc") and len(hrv) > 0:
        for k, v in hrv.iloc[0].to_dict().items():
            row[str(k)] = float(v) if np.isreal(v) else np.nan
    return row


def extract_gsr_features(gsr_window, fs=C.FS_GSR):
    x = np.asarray(gsr_window, dtype=float)
    x = x[np.isfinite(x)]
    if x.size < 3:
        return {}
    q25, q75 = np.percentile(x, [25, 75])
    t = np.arange(x.size, dtype=float) / fs
    slope = float(np.polyfit(t, x, 1)[0]) if x.size >= 2 else np.nan
    return {"EDA_Mean": float(np.mean(x)), "EDA_SD": float(np.std(x)),
            "EDA_Min": float(np.min(x)), "EDA_Max": float(np.max(x)),
            "EDA_Range": float(np.max(x) - np.min(x)), "EDA_Median": float(np.median(x)),
            "EDA_IQR": float(q75 - q25), "EDA_Slope": slope}
