"""분류 지표. 윈도우 softmax 확률을 trial 단위로 평균→argmax 후 BalancedAcc/MacroF1/Acc 계산."""
import numpy as np
import pandas as pd
from sklearn.metrics import (
    balanced_accuracy_score, f1_score, accuracy_score, confusion_matrix,
)


def aggregate_probs_to_trial(y_true, probs, trial_ids):
    """윈도우 softmax 확률(probs: N×C)을 trial 단위로 평균 후 argmax."""
    n_classes = probs.shape[1]
    df = pd.DataFrame(probs, columns=[f"p{i}" for i in range(n_classes)])
    df["trial_id"] = trial_ids
    df["y_true"] = y_true
    g = df.groupby("trial_id")
    yt = g["y_true"].first().to_numpy().astype(int)
    pmean = g[[f"p{i}" for i in range(n_classes)]].mean().to_numpy()
    yp = pmean.argmax(axis=1).astype(int)
    tids = g["y_true"].first().index.to_numpy()
    return yt, yp, tids


def compute_metrics_cls(y_true, y_pred, n_classes):
    """분류 지표. y_true/y_pred 는 정수 클래스(0..n_classes-1)."""
    yt = np.asarray(y_true, dtype=int)
    yp = np.asarray(y_pred, dtype=int)
    labels = list(range(n_classes))
    return {
        "BalancedAcc": float(balanced_accuracy_score(yt, yp)),
        "MacroF1":     float(f1_score(yt, yp, labels=labels, average="macro", zero_division=0)),
        "Acc":         float(accuracy_score(yt, yp)),
        "n":           int(len(yt)),
    }


def confusion(y_true, y_pred, n_classes):
    """혼동행렬 (행=정답, 열=예측). labels 로 클래스 순서 고정."""
    return confusion_matrix(np.asarray(y_true, int), np.asarray(y_pred, int),
                            labels=list(range(n_classes)))


def fmt(m):
    order = ["BalancedAcc", "MacroF1", "Acc"]
    parts = [f"{k}={m[k]:.3f}" for k in order if k in m]
    return "  ".join(parts) + f"  (n={m['n']})"
