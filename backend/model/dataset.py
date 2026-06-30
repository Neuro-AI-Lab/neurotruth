"""데이터 로딩 / 10초 윈도우 분할 / 라벨 인코딩 / torch Dataset.

group = subject 단위라 같은 사람 V1/V2 가 train/val/test 로 갈리지 않음.
trial 의 Q값을 그 trial 의 모든 윈도우에 동일 부여.
"""
import numpy as np
import pandas as pd
import joblib
import torch
from torch.utils.data import Dataset

import config as C
import preprocessing as P


def load_label_table():
    df = pd.read_csv(C.LABEL_CSV)
    for col in ("subject", "version", "trial", "intensity"):
        df[col] = df[col].astype(str).str.strip()
    df["session"] = df["subject"] + "_" + df["version"]
    excl = P.excluded_sessions()
    n0 = len(df)
    df = df[~df["session"].isin(excl)].reset_index(drop=True)
    print(f"[label] {n0} -> {len(df)} trials  ('{C.SESSION_FILTER}' 로 {len(excl)} 세션 제외)")
    return df


def _trial_csv_path(row):
    return C.DATA_ROOT / row["session"] / "PPG" / row["intensity"] / f"{row['trial']}.csv"


def build_trial_records(force=False):
    """trial 별 filtered PPG/GSR + finite 마스크 + 라벨. joblib 캐시."""
    C.CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache_path = C.CACHE_DIR / (
        f"trials_{C.SESSION_FILTER}_{C.PPG_FILTER}_ppg{C.FS_PPG}_gsr{C.FS_GSR}.joblib")
    if cache_path.exists() and not force:
        print(f"[cache] load {cache_path.name}")
        return joblib.load(cache_path)

    df = load_label_table()
    gsr_mask_sess = P.gsr_masked_sessions()
    records, missing = [], 0

    for _, row in df.iterrows():
        path = _trial_csv_path(row)
        if not path.exists():
            missing += 1
            continue
        try:
            raw = pd.read_csv(path, usecols=[C.PPG_COL, C.GSR_COL])
        except Exception:
            raw = pd.read_csv(path)
            if C.PPG_COL not in raw or C.GSR_COL not in raw:
                missing += 1
                continue

        ppg, ppg_fin = P.preprocess_ppg(raw[C.PPG_COL].to_numpy())   # FS_PPG
        gsr, gsr_fin = P.preprocess_gsr(raw[C.GSR_COL].to_numpy())   # FS_GSR
        if row["session"] in gsr_mask_sess:               # GSR 결측 → 0 마스킹
            gsr = np.zeros_like(gsr)
            gsr_fin = np.zeros_like(gsr, dtype=bool)

        records.append({
            "subject": row["subject"], "session": row["session"], "version": row["version"],
            "trial": row["trial"], "intensity": row["intensity"],
            "trial_id": f"{row['session']}_{row['trial']}",
            "Q1": float(row["Q1"]), "Q2": float(row["Q2"]), "Q_mean": float(row["Q_mean"]),
            "ppg": ppg, "gsr": gsr, "ppg_fin": ppg_fin, "gsr_fin": gsr_fin,
        })

    print(f"[trials] {len(records)} 개 적재 (파일 누락 {missing})")
    joblib.dump(records, cache_path)
    return records


def target_spec(target):
    """(n_classes, class_label_list)."""
    if target == "Q_mean":
        return 3, ["low (0)", "mid (0.5-3.5)", "high (4-7)"]
    return 8, [str(i) for i in range(8)]


def encode_labels(values, target):
    """연속 Q값 -> 정수 클래스. Q_mean: 0=low / 0.5~3.5=mid / 4~7=high."""
    v = np.asarray(values, dtype=float)
    if target == "Q_mean":
        return np.where(v == 0, 0, np.where(v <= 3.5, 1, 2)).astype(np.int64)
    return np.rint(v).astype(np.int64)


def make_windows(records, overlap, target=None):
    """dual-rate 윈도우. GSR-초 그리드 기준으로 PPG(250)·GSR(10) 윈도우 동시 추출.
    반환: Xppg(N,1,PPG_WIN), Xgsr(N,1,GSR_WIN), y, subjects, trial_ids. (표준화 전)"""
    target = target or C.TARGET_COL
    Wp, Wg = C.PPG_WIN, C.GSR_WIN
    ratio = int(round(C.FS_PPG / C.FS_GSR))   # GSR 1샘플 = PPG ratio샘플 (=25)
    stepg = max(1, int(Wg * (1 - overlap))) if overlap < 1 else Wg
    Xp, Xg, ys, subs, tids = [], [], [], [], []

    for r in records:
        ppg, gsr, pf, gf = r["ppg"], r["gsr"], r["ppg_fin"], r["gsr_fin"]
        Lp, Lg = len(ppg), len(gsr)
        if Lg < Wg or Lp < Wp:
            continue
        starts = list(range(0, Lg - Wg + 1, stepg))
        if C.INCLUDE_TAIL_WINDOW and starts and starts[-1] != Lg - Wg:
            starts.append(Lg - Wg)   # 끝-정렬 윈도우 (꼬리 포함)
        if C.MAX_WINDOWS_PER_TRIAL:
            starts = starts[:C.MAX_WINDOWS_PER_TRIAL]
        for sg in starts:
            sp = min(sg * ratio, Lp - Wp)            # PPG 시작 (시간 정렬)
            pw, gw = ppg[sp:sp + Wp], gsr[sg:sg + Wg]
            if not P.window_passes_quality(pw, gw, pf[sp:sp + Wp], gf[sg:sg + Wg]):
                continue
            Xp.append(pw[None, :])                   # (1, Wp)
            Xg.append(gw[None, :])                   # (1, Wg)
            ys.append(r[target])
            subs.append(r["subject"])
            tids.append(r["trial_id"])

    if not Xp:
        raise RuntimeError("윈도우가 0개입니다. 품질필터 임계값을 확인하세요.")
    Xp = np.asarray(Xp, dtype=np.float32)
    Xg = np.asarray(Xg, dtype=np.float32)
    y = np.asarray(ys, dtype=np.float32)
    print(f"[windows] overlap={overlap:.0%} -> {len(Xp)} windows "
          f"(PPG{Xp.shape[2]}/GSR{Xg.shape[2]}, trial {len(set(tids))}, subject {len(set(subs))})")
    return Xp, Xg, y, np.array(subs), np.array(tids)


class ChannelScaler:
    """채널별 스케일링.
    mode: 'global'(train 통계) | 'perwin'(윈도우별)
    method: 'zscore'((x-mean)/std) | 'minmax'((x-min)/range → 0~1)
    offset_/scale_ 는 (mean,std) 또는 (min,range) 를 담는다.
    """
    def __init__(self, mode=C.SCALE_MODE, method=C.SCALE_METHOD):
        self.mode = mode
        self.method = method
        self.offset_ = None
        self.scale_ = None

    def _stats(self, X, axis):
        if self.method == "minmax":
            offset = X.min(axis=axis, keepdims=True)
            scale = X.max(axis=axis, keepdims=True) - offset + 1e-8
        else:  # zscore
            offset = X.mean(axis=axis, keepdims=True)
            scale = X.std(axis=axis, keepdims=True) + 1e-8
        return offset, scale

    def fit(self, X):  # (N,2,W)
        if self.mode == "global":
            self.offset_, self.scale_ = self._stats(X, axis=(0, 2))
        return self

    def transform(self, X):
        if self.mode == "perwin":
            offset, scale = self._stats(X, axis=2)
            return (X - offset) / scale
        return (X - self.offset_) / self.scale_


class WindowDataset(Dataset):
    def __init__(self, Xp, Xg, y, trial_ids):
        self.Xp = torch.from_numpy(Xp)
        self.Xg = torch.from_numpy(Xg)
        self.y = torch.from_numpy(y)
        self.trial_ids = trial_ids

    def __len__(self):
        return len(self.Xp)

    def __getitem__(self, i):
        return self.Xp[i], self.Xg[i], self.y[i], i
