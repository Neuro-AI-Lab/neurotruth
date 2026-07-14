from tools.sim_app import PPG_HZ, WIN_S, make_window


def test_simulator_reuses_session_identity_and_generates_pulse_waveform() -> None:
    session_started_at_ms = 1_000_000
    first = make_window(1, 1_010_000, session_started_at_ms)
    second = make_window(2, 1_011_000, session_started_at_ms)

    assert first["sessionId"] == second["sessionId"] == "sim-1000000"
    assert first["sessionStartedAtMs"] == second["sessionStartedAtMs"] == 1_000_000

    ppg = [
        sample["value"]
        for sample in first["samples"]
        if sample["sensor"] == "PPG_GREEN"
    ]
    assert len(ppg) == PPG_HZ * WIN_S
    assert min(ppg) > 1700
    assert max(ppg) > 2000
    assert len({round(value, 3) for value in ppg}) > PPG_HZ
