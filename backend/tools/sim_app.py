#!/usr/bin/env python3
"""앱 없이 latency 측정을 검증하는 시뮬레이터 (표준 라이브러리만 사용).

동작:
  1) /prediction-stream 에 SSE 연결을 열어 둡니다 (앱의 다운링크 역할).
  2) 매초 /sensor-window 로 10초 윈도우를 POST 합니다 (앱의 업링크 역할).
  3) 서버는 예측을 계산하고, main.py가 SSE로 결과를 보내는 순간
     inference_time.txt 에 한 줄(comm/queue/feature/model/server/send)을 기록합니다.

사용:
  python3 tools/sim_app.py --host http://localhost:8000 --seconds 8
"""
import argparse
import json
import threading
import time
import urllib.request

PPG_HZ, EDA_HZ, WIN_S = 25, 1, 10


def sse_listener(base, stop):
    try:
        req = urllib.request.Request(base + "/prediction-stream",
                                     headers={"Accept": "text/event-stream"})
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


def make_window(seq, now_ms):
    start = now_ms - WIN_S * 1000
    samples = []
    for i in range(PPG_HZ * WIN_S):
        ts = start + int(i * 1000 / PPG_HZ)
        samples.append({"sensor": "PPG_GREEN", "timestampMs": ts,
                        "value": 1800.0 + (i % 20)})
    for i in range(EDA_HZ * WIN_S):
        ts = start + int(i * 1000 / EDA_HZ)
        samples.append({"sensor": "EDA", "timestampMs": ts, "value": 5.0 + 0.1 * i})
    return {
        "sessionStartedAtMs": start, "sequence": seq,
        "sentAtMs": int(time.time() * 1000),
        "windowStartMs": start, "windowEndMs": now_ms, "windowMs": WIN_S * 1000,
        "samples": samples,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="http://localhost:8000")
    ap.add_argument("--seconds", type=int, default=8)
    args = ap.parse_args()

    stop = threading.Event()
    t = threading.Thread(target=sse_listener, args=(args.host, stop), daemon=True)
    t.start()
    time.sleep(1.0)  # let the SSE session open (start_session) first

    for seq in range(1, args.seconds + 1):
        body = json.dumps(make_window(seq, int(time.time() * 1000))).encode()
        req = urllib.request.Request(args.host + "/sensor-window", data=body,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                print(f"[POST] seq={seq} -> {r.status} {r.read().decode()}")
        except Exception as exc:  # noqa: BLE001
            print(f"[POST] seq={seq} FAILED: {exc}")
        time.sleep(1.0)

    time.sleep(1.5)
    stop.set()
    print("done. inference_time.txt 를 확인하세요.")


if __name__ == "__main__":
    main()
