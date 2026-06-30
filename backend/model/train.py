"""학습 엔트리포인트 (분류, subject 단위 nested CV).

바깥 GroupKFold(N_SPLITS): holdout = TEST(보고 전용).
train 부분을 GroupShuffleSplit 으로 inner-train/validation 분리 → val 로 early stopping.
최종 결과 = test BalancedAcc 가 가장 좋았던 fold.

실행: python train.py [--folds N] [--epochs N] [--rebuild]
"""
import argparse
import json
import random

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
from torch.utils.data import DataLoader
from sklearn.model_selection import (GroupKFold, GroupShuffleSplit,
                                     StratifiedKFold, StratifiedShuffleSplit)

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

import config as C
import dataset as D
import metrics as M
from model import DualBranchNet


def set_seed(seed):
    random.seed(seed); np.random.seed(seed)
    torch.manual_seed(seed); torch.cuda.manual_seed_all(seed)


def make_loss(class_weights=None, device=None):
    w = (torch.tensor(class_weights, dtype=torch.float32, device=device)
         if class_weights is not None else None)
    return nn.CrossEntropyLoss(weight=w)


def balanced_class_weights(y_int, n_classes):
    """sklearn 'balanced' 공식. fold train 에 없는 클래스는 가중치 0."""
    counts = np.bincount(y_int, minlength=n_classes).astype(float)
    n_present = int((counts > 0).sum())
    w = np.zeros(n_classes, dtype=float)
    w[counts > 0] = counts.sum() / (n_present * counts[counts > 0])
    return w


def make_loader(Xp, Xg, y, tids, batch, shuffle):
    return DataLoader(D.WindowDataset(Xp, Xg, y, tids), batch_size=batch, shuffle=shuffle,
                      num_workers=C.NUM_WORKERS, pin_memory=True)


def outer_folds(idx_all, subjects, labels):
    """SPLIT_MODE 에 따라 (train_idx, test_idx) fold 생성."""
    if C.SPLIT_MODE == "subject_independent":
        return GroupKFold(n_splits=C.N_SPLITS).split(idx_all, groups=subjects)
    return StratifiedKFold(n_splits=C.N_SPLITS, shuffle=True,
                           random_state=C.SEED).split(idx_all, labels)


def inner_split(tr_idx, tr_subjects, tr_labels):
    """train -> inner-train / validation 분리 인덱스 반환."""
    arr = np.array(tr_idx)
    if C.SPLIT_MODE == "subject_independent":
        sp = GroupShuffleSplit(n_splits=1, test_size=C.VAL_RATIO, random_state=C.SEED)
        return next(sp.split(arr, groups=tr_subjects))
    sp = StratifiedShuffleSplit(n_splits=1, test_size=C.VAL_RATIO, random_state=C.SEED)
    return next(sp.split(arr, tr_labels))


@torch.no_grad()
def predict_proba(model, loader, device, n_classes):
    model.eval()
    probs = np.zeros((len(loader.dataset), n_classes), dtype=np.float32)
    for xp, xg, _, idx in loader:
        p = torch.softmax(model(xp.to(device), xg.to(device)), dim=1).cpu().numpy()
        probs[idx.numpy()] = p
    return probs


@torch.no_grad()
def eval_loss(model, loader, crit, device):
    """윈도우 단위 평균 loss."""
    model.eval()
    tot, n = 0.0, 0
    for xp, xg, yb, _ in loader:
        xp, xg, yb = xp.to(device), xg.to(device), yb.to(device)
        tot += crit(model(xp, xg), yb).item() * len(xp)
        n += len(xp)
    return tot / max(n, 1)


def evaluate(model, loader, y_win, tid_win, n_classes, device):
    """예측 -> 분류 지표. EVAL_LEVEL 에 따라 윈도우 단위 / trial 집계.
    반환: (지표dict, y_true, y_pred, ids)"""
    probs = predict_proba(model, loader, device, n_classes)
    if C.EVAL_LEVEL == "trial":
        yt, yp, ids = M.aggregate_probs_to_trial(y_win, probs, tid_win)
    else:  # window 단위 그대로
        yt, yp, ids = np.asarray(y_win, int), probs.argmax(1), np.asarray(tid_win)
    return M.compute_metrics_cls(yt, yp, n_classes), yt, yp, ids


def run_fold(k, tr_idx, te_idx, records, target, n_classes, out_dir, device, args):
    # inner split: train -> inner-train / validation
    tr_subj = np.array([records[i]["subject"] for i in tr_idx])
    tr_labels = D.encode_labels([records[i][target] for i in tr_idx], target)
    itr_pos, val_pos = inner_split(tr_idx, tr_subj, tr_labels)
    itr_recs = [records[tr_idx[p]] for p in itr_pos]
    val_recs = [records[tr_idx[p]] for p in val_pos]
    te_recs  = [records[i] for i in te_idx]

    Xp_tr, Xg_tr, ytr_raw, _, tid_tr   = D.make_windows(itr_recs, C.TRAIN_OVERLAP, target)
    Xp_va, Xg_va, yval_raw, _, tid_val = D.make_windows(val_recs, C.VAL_OVERLAP, target)
    Xp_te, Xg_te, yte_raw, _, tid_te   = D.make_windows(te_recs,  C.VAL_OVERLAP, target)

    # PPG·GSR 채널 각각 inner-train 통계로 표준화
    psc, gsc = D.ChannelScaler().fit(Xp_tr), D.ChannelScaler().fit(Xg_tr)
    Xp_tr, Xp_va, Xp_te = psc.transform(Xp_tr), psc.transform(Xp_va), psc.transform(Xp_te)
    Xg_tr, Xg_va, Xg_te = gsc.transform(Xg_tr), gsc.transform(Xg_va), gsc.transform(Xg_te)

    ytr  = D.encode_labels(ytr_raw, target)
    yval = D.encode_labels(yval_raw, target)
    yte  = D.encode_labels(yte_raw, target)
    class_w = balanced_class_weights(ytr, n_classes) if C.CLASS_WEIGHT == "balanced" else None

    tr_ld  = make_loader(Xp_tr, Xg_tr, ytr, tid_tr, C.BATCH_SIZE, shuffle=True)
    val_ld = make_loader(Xp_va, Xg_va, yval, tid_val, 256, shuffle=False)
    te_ld  = make_loader(Xp_te, Xg_te, yte, tid_te, 256, shuffle=False)

    model = DualBranchNet(out_dim=n_classes).to(device)
    opt = torch.optim.Adam(model.parameters(), lr=C.LR, weight_decay=C.WEIGHT_DECAY)
    crit = make_loss(class_w, device)

    sel = C.SELECT_METRIC
    minimize = (sel == "loss")
    sched = torch.optim.lr_scheduler.ReduceLROnPlateau(
        opt, mode="min" if minimize else "max", factor=0.5, patience=5)

    best_score = float("inf") if minimize else -float("inf")
    best_state, patience = None, 0
    for ep in range(1, args.epochs + 1):
        model.train()
        tot = 0.0
        for xp, xg, yb, _ in tr_ld:
            xp, xg, yb = xp.to(device), xg.to(device), yb.to(device)
            opt.zero_grad()
            loss = crit(model(xp, xg), yb)
            loss.backward()
            opt.step()
            tot += loss.item() * len(xp)
        tr_loss = tot / len(tr_ld.dataset)

        val_loss = eval_loss(model, val_ld, crit, device)
        vm, _, _, _ = evaluate(model, val_ld, yval, tid_val, n_classes, device)
        score = val_loss if minimize else vm[sel]
        sched.step(score)

        improved = (score < best_score) if minimize else (score > best_score)
        flag = ""
        if improved:
            best_score = score
            best_state = {kk: v.detach().cpu().clone() for kk, v in model.state_dict().items()}
            patience = 0
            flag = " *"
        else:
            patience += 1
        print(f"  [{target}] fold{k} ep{ep:02d}  train_loss={tr_loss:.4f}  "
              f"val_loss={val_loss:.4f} | val {M.fmt(vm)}{flag}")
        if patience >= C.EARLY_STOP_PATIENCE:
            print(f"  [{target}] fold{k} early stop @ ep{ep}")
            break

    # best 모델로 TEST 최종 평가
    model.load_state_dict(best_state)
    test_metrics, yt_t, yp_t, tids = evaluate(model, te_ld, yte, tid_te, n_classes, device)
    val_metrics, _, _, _ = evaluate(model, val_ld, yval, tid_val, n_classes, device)

    torch.save({"state_dict": best_state,
                "ppg_scaler": (psc.offset_, psc.scale_), "gsr_scaler": (gsc.offset_, gsc.scale_),
                "scale_mode": psc.mode, "scale_method": psc.method,
                "target": target, "n_classes": n_classes,
                "val_metrics": val_metrics, "test_metrics": test_metrics},
               out_dir / f"fold{k}_best.pt")

    oof = pd.DataFrame({"fold": k, "trial_id": tids, "y_true": yt_t, "y_pred": yp_t})
    return test_metrics, oof


def save_confusion(y_true, y_pred, n_classes, class_labels, out_dir, target,
                   fname="confusion_matrix", tag="pooled OOF (test)"):
    cm = M.confusion(y_true, y_pred, n_classes)
    support = cm.sum(axis=1)                               # 클래스별 데이터 개수
    cmn = cm / support[:, None].clip(min=1)                # 행 정규화 = P(pred | true)

    pd.DataFrame(cm, index=class_labels, columns=class_labels).to_csv(
        out_dir / f"{fname}_counts.csv", encoding="utf-8-sig")
    prob_df = pd.DataFrame(cmn, index=class_labels, columns=class_labels)
    prob_df.insert(0, "support_n", support)
    prob_df.to_csv(out_dir / f"{fname}_prob.csv", encoding="utf-8-sig")

    vmax = float(cmn.max()) if cmn.max() > 0 else 1.0      # 최댓값을 가장 진하게
    cell_fs = max(4, 8 - n_classes // 4)                   # 클래스 많을수록 작게
    tick_fs = max(5, 8 - n_classes // 5)
    fig, ax = plt.subplots(figsize=(0.75 * n_classes + 2.5, 0.75 * n_classes + 2.5))
    im = ax.imshow(cmn, cmap="Blues", vmin=0, vmax=vmax)
    ax.set_xticks(range(n_classes)); ax.set_xticklabels(class_labels, fontsize=tick_fs)
    ax.set_yticks(range(n_classes))
    ax.set_yticklabels([f"{lab} (n={int(s)})" for lab, s in zip(class_labels, support)], fontsize=tick_fs)
    ax.set_xlabel("Predicted", fontsize=tick_fs + 1)
    ax.set_ylabel("True (class size n)", fontsize=tick_fs + 1)
    bacc = float(np.mean([cmn[i, i] for i in range(n_classes) if support[i] > 0]))
    ax.set_title(f"{target}  ({tag})\nBalancedAcc={bacc:.3f}   total n={int(support.sum())}",
                 fontsize=tick_fs + 1)
    for i in range(n_classes):
        for j in range(n_classes):
            if cmn[i, j] > 0:
                ax.text(j, i, f"{cmn[i, j]:.2f}", ha="center", va="center",
                        color="white" if cmn[i, j] > 0.5 * vmax else "black", fontsize=cell_fs)
    cbar = fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04,
                        label=f"P(predicted | true)  [scale 0~{vmax:.2f}]")
    cbar.ax.tick_params(labelsize=tick_fs)
    fig.tight_layout()
    fig.savefig(out_dir / f"{fname}.png", dpi=200, bbox_inches="tight")
    plt.close(fig)


def run_target(target, records, subjects, idx_all, device, args):
    n_classes, class_labels = D.target_spec(target)
    out_dir = C.OUT_DIR / target
    out_dir.mkdir(parents=True, exist_ok=True)
    print(f"\n########## TARGET = {target}  ({n_classes} classes) ##########")

    labels_all = D.encode_labels([records[i][target] for i in idx_all], target)
    fold_metrics, oof_all = {}, []
    for k, (tr, te) in enumerate(outer_folds(idx_all, subjects, labels_all), start=1):
        if k > args.folds:
            break
        print(f"\n===== [{target}] Fold {k}/{C.N_SPLITS} ({C.SPLIT_MODE})  "
              f"train {len(tr)} / test {len(te)} trials =====")
        m, oof = run_fold(k, list(tr), list(te), records, target, n_classes, out_dir, device, args)
        fold_metrics[f"fold{k}"] = m
        oof_all.append(oof)
        print(f"  >> [{target}] fold{k} TEST | {M.fmt(m)}")

    oof_df = pd.concat(oof_all, ignore_index=True)
    oof_df.to_csv(out_dir / "oof_predictions.csv", index=False)   # 전체 fold 예측

    best_fk = max(fold_metrics, key=lambda fk: fold_metrics[fk]["BalancedAcc"])
    best_k = int(best_fk.replace("fold", ""))
    yt_all, yp_all = oof_df["y_true"].to_numpy(), oof_df["y_pred"].to_numpy()
    pooled = M.compute_metrics_cls(yt_all, yp_all, n_classes)

    # ---- 최종 결과: 전체 fold 합침(pooled) 또는 best fold ----
    if C.FINAL_REPORT == "pooled":
        final_metrics, yt_f, yp_f, tag = pooled, yt_all, yp_all, "pooled all folds (TEST)"
    else:
        final_metrics = fold_metrics[best_fk]
        sub = oof_df[oof_df["fold"] == best_k]
        yt_f, yp_f, tag = sub["y_true"].to_numpy(), sub["y_pred"].to_numpy(), f"best fold {best_k} (TEST)"
    save_confusion(yt_f, yp_f, n_classes, class_labels, out_dir, target, tag=tag)

    fold_avg = {kk: float(np.mean([fm[kk] for fm in fold_metrics.values()]))
                for kk in next(iter(fold_metrics.values())) if kk != "n"}
    fold_std = {kk: float(np.std([fm[kk] for fm in fold_metrics.values()])) for kk in fold_avg}

    print(f"\n----- [{target}] 요약 (TEST) -----")
    for fk, m in fold_metrics.items():
        mark = "  <== BEST" if fk == best_fk else ""
        print(f"  {fk}: {M.fmt(m)}{mark}")
    print(f"  fold 평균(참고): " + "  ".join(f"{k}={fold_avg[k]:.3f}±{fold_std[k]:.3f}" for k in fold_avg))
    print(f"  pooled(전체): {M.fmt(pooled)}")
    print(f"  >> 최종({C.FINAL_REPORT}): {M.fmt(final_metrics)}")

    result = {"target": target, "n_classes": n_classes, "report": C.FINAL_REPORT,
              "final_metrics": final_metrics, "pooled": pooled,
              "best_fold": best_k, "best_fold_metrics": fold_metrics[best_fk],
              "folds_test": fold_metrics, "fold_mean": fold_avg, "fold_std": fold_std}
    with open(out_dir / "metrics.json", "w") as f:
        json.dump(result, f, indent=2)
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rebuild", action="store_true", help="trial 캐시 강제 재생성")
    ap.add_argument("--folds", type=int, default=C.N_SPLITS, help="실행할 fold 수")
    ap.add_argument("--epochs", type=int, default=C.EPOCHS)
    ap.add_argument("--targets", nargs="+", default=C.TARGETS)
    args = ap.parse_args()

    set_seed(C.SEED)
    C.OUT_DIR.mkdir(parents=True, exist_ok=True)
    device = torch.device(C.DEVICE if torch.cuda.is_available() else "cpu")
    print(f"device = {device}  |  targets = {args.targets}  |  select = {C.SELECT_METRIC}")

    records = D.build_trial_records(force=args.rebuild)
    subjects = np.array([r["subject"] for r in records])
    idx_all = np.arange(len(records))

    summary = {}
    for target in args.targets:
        res = run_target(target, records, subjects, idx_all, device, args)
        summary[target] = {"n_classes": res["n_classes"], "report": res["report"],
                           "final_metrics": res["final_metrics"], "fold_mean": res["fold_mean"]}

    with open(C.OUT_DIR / "summary.json", "w") as f:
        json.dump({"config": _cfg_snapshot(), "targets": summary}, f, indent=2)

    print(f"\n========== 전체 요약 (final={C.FINAL_REPORT}, TEST) ==========")
    for t, s in summary.items():
        o = s["final_metrics"]
        print(f"  {t:7s} ({s['n_classes']:2d}cls): "
              f"BalancedAcc={o['BalancedAcc']:.3f}  MacroF1={o['MacroF1']:.3f}  Acc={o['Acc']:.3f}")
    print(f"\n저장 완료 -> {C.OUT_DIR}")


def _cfg_snapshot():
    keys = ["TARGETS", "SPLIT_MODE", "EVAL_LEVEL", "FINAL_REPORT", "CLASS_WEIGHT", "SELECT_METRIC", "WIN_SEC", "TRAIN_OVERLAP",
            "VAL_OVERLAP", "SCALE_MODE", "SCALE_METHOD", "SESSION_FILTER", "WIN_FILTER_ENABLE", "MIN_VALID_RATIO",
            "MAX_FLATLINE_RATIO", "MAX_OUTLIER_RATIO", "N_SPLITS", "VAL_RATIO", "EPOCHS",
            "BATCH_SIZE", "LR", "LOSS"]
    return {k: getattr(C, k) for k in keys}


if __name__ == "__main__":
    main()
