"""Dual-branch 1D CNN. PPG(25Hz)·GSR(1Hz) 를 각자 conv 브랜치로 처리 후 합쳐 분류.

  PPG (B,1,250) → conv branch → GAP → feat_p
  GSR (B,1, 10) → conv branch → GAP → feat_g
  [feat_p; feat_g] → Dropout → Linear → (B, out_dim) 로짓
"""
import torch
import torch.nn as nn


class ConvBlock(nn.Module):
    def __init__(self, c_in, c_out, k, pool=1):
        super().__init__()
        self.net = nn.Sequential(
            nn.Conv1d(c_in, c_out, kernel_size=k, padding=k // 2, bias=False),
            nn.BatchNorm1d(c_out),
            nn.ELU(inplace=True),
            nn.MaxPool1d(pool) if pool > 1 else nn.Identity(),
        )

    def forward(self, x):
        return self.net(x)


class Branch(nn.Module):
    """conv 블록 N단 -> GAP -> (B, last_width) 특징."""
    def __init__(self, in_ch, widths, kernels, n_pool):
        super().__init__()
        blocks, c_prev = [], in_ch
        for i, c_out in enumerate(widths):
            blocks.append(ConvBlock(c_prev, c_out, k=kernels[i], pool=2 if i < n_pool else 1))
            c_prev = c_out
        self.features = nn.Sequential(*blocks)
        self.gap = nn.AdaptiveAvgPool1d(1)
        self.out_ch = c_prev

    def forward(self, x):
        return self.gap(self.features(x)).flatten(1)


class DualBranchNet(nn.Module):
    def __init__(self, out_dim=3, dropout=0.3,
                 ppg_widths=(32, 64, 128, 64, 32), ppg_kernels=(7, 5, 3, 3, 3), ppg_pool=3,
                 gsr_widths=(16, 16), gsr_kernels=(3, 3), gsr_pool=0):
        super().__init__()
        self.out_dim = out_dim
        self.ppg = Branch(1, ppg_widths, ppg_kernels, ppg_pool)   # 250샘플
        self.gsr = Branch(1, gsr_widths, gsr_kernels, gsr_pool)   # 10샘플 (pool 없음)
        feat = self.ppg.out_ch + self.gsr.out_ch
        self.head = nn.Sequential(nn.Dropout(dropout), nn.Linear(feat, out_dim))

    def forward(self, ppg, gsr):
        f = torch.cat([self.ppg(ppg), self.gsr(gsr)], dim=1)
        out = self.head(f)
        return out.squeeze(-1) if self.out_dim == 1 else out
