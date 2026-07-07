from __future__ import annotations

"""Latency logging for the realtime craving service.

Records per-window latency to `inference_time.txt` next to this file:

- comm_ms     : uplink 통신시간 = 서버 수신 시각 - 앱 `sentAtMs`
                (⚠️ 폰/서버 시계가 동기화돼 있지 않으면 오차가 큽니다)
- queue_ms    : 서버 큐 대기시간 (수신 → 워커가 꺼낼 때까지)
- feature_ms  : PPG/GSR 피처 추출 시간
- model_ms    : RandomForest 추론 시간
- server_ms   : 서버 총 처리시간 (큐 진입 → 예측 완료)

파일은 prediction-stream 연결이 시작될 때마다 새로 덮어쓰이고(truncate),
연결이 끊길 때 min/mean/max 요약이 덧붙습니다. 즉 항상 "마지막 연결" 세션의
기록만 남습니다.
"""

import os
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Any

DEFAULT_LOG_PATH = Path(
    os.getenv("LATENCY_LOG_PATH", str(Path(__file__).resolve().parent / "inference_time.txt"))
)

_COLUMNS = [
    ("comm_ms", "comm"),
    ("queue_ms", "queue"),
    ("feature_ms", "feature"),
    ("model_ms", "model"),
    ("server_ms", "server"),
]


def _fmt(value: Any) -> str:
    if value is None:
        return "-"
    try:
        return f"{float(value):.3f}"
    except (TypeError, ValueError):
        return str(value)


def _fmt_ts(epoch: float) -> str:
    return datetime.fromtimestamp(epoch).strftime("%Y-%m-%d %H:%M:%S")


class LatencyRecorder:
    """Truncate-on-connect latency log. Thread-safe (worker thread + event loop)."""

    def __init__(self, log_path: Path = DEFAULT_LOG_PATH) -> None:
        self.log_path = log_path
        self._lock = threading.Lock()
        self._records: list[dict[str, Any]] = []
        self._session_active = False
        self._session_start = 0.0

    def start_session(self, client: str = "") -> None:
        """Overwrite the log file and begin a fresh latency session."""

        with self._lock:
            self._records = []
            self._session_active = True
            self._session_start = time.time()
            header = (
                f"# latency session start {_fmt_ts(self._session_start)}"
                + (f"  client={client}" if client else "")
                + "\n"
                + "# times in milliseconds; comm_ms uses app clock (may be skewed)\n"
                + "# seq\tcomm_ms\tqueue_ms\tfeature_ms\tmodel_ms\tserver_ms\n"
            )
            self._write(header, mode="w")

    def record(
        self,
        *,
        sequence: Any = None,
        comm_ms: float | None = None,
        queue_ms: float | None = None,
        feature_ms: float | None = None,
        model_ms: float | None = None,
        server_ms: float | None = None,
    ) -> None:
        """Append one window's latency row (no-op if no active session)."""

        with self._lock:
            if not self._session_active:
                return
            rec = {
                "sequence": sequence,
                "comm_ms": comm_ms,
                "queue_ms": queue_ms,
                "feature_ms": feature_ms,
                "model_ms": model_ms,
                "server_ms": server_ms,
            }
            self._records.append(rec)
            line = (
                f"{_fmt(sequence)}\t{_fmt(comm_ms)}\t{_fmt(queue_ms)}\t"
                f"{_fmt(feature_ms)}\t{_fmt(model_ms)}\t{_fmt(server_ms)}\n"
            )
            self._write(line, mode="a")

    def end_session(self) -> None:
        """Append a summary and close the current session."""

        with self._lock:
            if not self._session_active:
                return
            self._session_active = False
            self._write(self._summary(), mode="a")

    def _summary(self) -> str:
        n = len(self._records)
        duration = time.time() - self._session_start
        lines = [f"# latency session end {_fmt_ts(time.time())}  ({n} windows, {duration:.1f}s)\n"]
        for key, label in _COLUMNS:
            vals = [r[key] for r in self._records if r.get(key) is not None]
            if vals:
                lines.append(
                    f"# {label:8s} min={min(vals):.2f}  mean={sum(vals) / len(vals):.2f}  "
                    f"max={max(vals):.2f}  (n={len(vals)})\n"
                )
        return "".join(lines)

    def _write(self, text: str, mode: str) -> None:
        try:
            with self.log_path.open(mode, encoding="utf-8") as fh:
                fh.write(text)
        except OSError:
            # Never let logging break inference.
            pass
