#!/usr/bin/env python3
"""Simulate one stable sensor session using only the standard library.

The simulator opens the prediction SSE stream, posts one ten-second sensor
window per second, and lets the backend record its normal latency metrics.

Usage:
  python3 tools/sim_app.py --host http://localhost:8000 --seconds 8
"""

import argparse
import json
import math
import threading
import time
import urllib.request

PPG_HZ, EDA_HZ, WIN_S = 25, 1, 10


def sse_listener(base, stop):
    try:
        req = urllib.request.Request(
            base + "/prediction-stream",
            headers={"Accept": "text/event-stream"},
        )
        with urllib.request.urlopen(req, timeout=60) as resp:
            print("[SSE] connected")
            for raw in resp:
                if stop.is_set():
                    break
                line = raw.decode("utf-8", "replace").rstrip()
                if line.startswith("data:"):
                    print("[SSE] <-", line[5:].strip())
    except Exception as exc:  # noqa: BLE001
        print("[SSE] closed:", exc)


def make_window(seq, now_ms, session_started_at_ms):
    start = now_ms - WIN_S * 1000
    samples = []
    for i in range(PPG_HZ * WIN_S):
        ts = start + int(i * 1000 / PPG_HZ)
        elapsed_s = i / PPG_HZ
        phase = (elapsed_s * 1.2) % 1.0
        systolic = math.exp(-((phase - 0.16) / 0.07) ** 2)
        reflected = 0.35 * math.exp(-((phase - 0.42) / 0.12) ** 2)
        respiration = 0.12 * math.sin(2.0 * math.pi * 0.25 * elapsed_s)
        samples.append(
            {
                "sensor": "PPG_GREEN",
                "timestampMs": ts,
                "value": 1800.0 + 260.0 * (systolic + reflected + respiration),
            }
        )
    for i in range(EDA_HZ * WIN_S):
        ts = start + int(i * 1000 / EDA_HZ)
        samples.append(
            {"sensor": "EDA", "timestampMs": ts, "value": 5.0 + 0.1 * i}
        )
    return {
        "sessionId": f"sim-{session_started_at_ms}",
        "sessionStartedAtMs": session_started_at_ms,
        "sequence": seq,
        "sentAtMs": int(time.time() * 1000),
        "windowStartMs": start,
        "windowEndMs": now_ms,
        "windowMs": WIN_S * 1000,
        "samples": samples,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="http://localhost:8000")
    ap.add_argument("--seconds", type=int, default=8)
    args = ap.parse_args()

    stop = threading.Event()
    session_started_at_ms = int(time.time() * 1000)
    thread = threading.Thread(
        target=sse_listener,
        args=(args.host, stop),
        daemon=True,
    )
    thread.start()
    time.sleep(1.0)  # Let the SSE session open before posting windows.

    for seq in range(1, args.seconds + 1):
        body = json.dumps(
            make_window(seq, int(time.time() * 1000), session_started_at_ms)
        ).encode()
        req = urllib.request.Request(
            args.host + "/sensor-window",
            data=body,
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as response:
                print(
                    f"[POST] seq={seq} -> {response.status} "
                    f"{response.read().decode()}"
                )
        except Exception as exc:  # noqa: BLE001
            print(f"[POST] seq={seq} FAILED: {exc}")
        time.sleep(1.0)

    time.sleep(1.5)
    stop.set()
    print("Done. Check inference_time.txt for latency measurements.")


if __name__ == "__main__":
    main()
