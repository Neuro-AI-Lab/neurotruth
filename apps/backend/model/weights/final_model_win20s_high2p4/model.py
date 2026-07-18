"""Final 5-layer 1D CNN for PPG+GSR binary craving classification."""

from pathlib import Path

import torch
import torch.nn as nn


class ConvBlock(nn.Module):
    def __init__(self, c_in, c_out, kernel_size, pool=2):
        super().__init__()
        self.net = nn.Sequential(
            nn.Conv1d(
                c_in,
                c_out,
                kernel_size=kernel_size,
                padding=kernel_size // 2,
                bias=False,
            ),
            nn.BatchNorm1d(c_out),
            nn.ELU(inplace=True),
            nn.MaxPool1d(pool) if pool > 1 else nn.Identity(),
        )

    def forward(self, x):
        return self.net(x)


class Conv1DNet(nn.Module):
    def __init__(
        self,
        in_ch=2,
        out_dim=2,
        widths=(128, 128, 128, 128, 128),
        n_pool=3,
        dropout=0.3,
    ):
        super().__init__()
        kernels = (7, 5, 3, 3, 3)
        if len(widths) != len(kernels):
            raise ValueError("widths는 5개여야 합니다.")
        blocks = []
        c_prev = in_ch
        for index, (c_out, kernel) in enumerate(zip(widths, kernels)):
            blocks.append(
                ConvBlock(
                    c_prev,
                    c_out,
                    kernel_size=kernel,
                    pool=2 if index < n_pool else 1,
                )
            )
            c_prev = c_out
        self.features = nn.Sequential(*blocks)
        self.gap = nn.AdaptiveAvgPool1d(1)
        self.head = nn.Sequential(
            nn.Flatten(),
            nn.Dropout(dropout),
            nn.Linear(c_prev, out_dim),
        )

    def forward(self, x):
        return self.head(self.gap(self.features(x)))


def load_trained_model(weights_path=None, device="cpu"):
    """저장된 state_dict를 로드해 eval mode 모델을 반환한다."""
    if weights_path is None:
        weights_path = Path(__file__).resolve().parent / "model_weights.pt"
    model = Conv1DNet()
    state_dict = torch.load(weights_path, map_location=device, weights_only=True)
    model.load_state_dict(state_dict)
    model.to(device)
    model.eval()
    return model

