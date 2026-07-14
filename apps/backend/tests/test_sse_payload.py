from app.main import _public_sse_event


def test_public_sse_event_preserves_legacy_class_and_strips_internal_keys() -> None:
    event = {
        "class": 1,
        "timestampMs": 123,
        "confidence": 0.8,
        "sequence": 9,
        "alertLevel": "recommend",
        "alertAction": "recommend_intervention",
        "windowMean": 0.7,
        "triggerReason": "window_mean_recommend",
        "alertRequired": False,
        "sessionId": "session-9",
        "_lat": {"server_ms": 10},
        "_readyPerf": 42.0,
    }

    public = _public_sse_event(event)

    assert public["class"] == 1
    assert public["timestampMs"] == 123
    assert public["confidence"] == 0.8
    assert public["sequence"] == 9
    assert public["alertLevel"] == "recommend"
    assert public["alertAction"] == "recommend_intervention"
    assert public["windowMean"] == 0.7
    assert public["triggerReason"] == "window_mean_recommend"
    assert public["alertRequired"] is False
    assert public["sessionId"] == "session-9"
    assert "_lat" not in public
    assert "_readyPerf" not in public
