from pathlib import Path
from types import SimpleNamespace

from app.alerts import AlertConfig, AlertEvaluatorRegistry
from app.inference import RealtimePredictionService
from app.main import RuntimePredictorAdapter


def test_service_applies_alerts_with_resolved_session_evaluator() -> None:
    service = RealtimePredictionService()
    service.alerts = AlertEvaluatorRegistry(
        AlertConfig(window_size=2, high_streak=0, cooldown_seconds=30)
    )

    event_a1 = {"class": 2, "timestampMs": 1000}
    event_b1 = {"class": 0, "timestampMs": 1000}
    event_a2 = {"class": 2, "timestampMs": 2000}
    event_b2 = {"class": 0, "timestampMs": 2000}

    service._apply_alert({"sessionId": "a"}, event_a1)
    service._apply_alert({"sessionId": "b"}, event_b1)
    service._apply_alert({"sessionId": "a"}, event_a2)
    service._apply_alert({"sessionId": "b"}, event_b2)

    assert event_a1["triggerReason"] == "window_warming_up"
    assert event_a2["sessionId"] == "a"
    assert event_a2["alertAction"] == "required_intervention"
    assert event_b2["sessionId"] == "b"
    assert event_b2["alertAction"] == "none"


def test_service_keeps_legacy_session_started_at_fallback() -> None:
    service = RealtimePredictionService()
    service.alerts = AlertEvaluatorRegistry(AlertConfig(window_size=1))
    event = {"class": 0, "timestampMs": 1000}

    session_id, _ = service._apply_alert({"sessionStartedAtMs": 12345}, event)

    assert session_id == "12345"
    assert event["sessionId"] == "12345"


def test_authenticated_predictor_observes_model_readiness_after_startup() -> None:
    model = SimpleNamespace(
        ready=False,
        model_name=None,
        model_path=Path("model/weights/rf.joblib"),
    )
    adapter = RuntimePredictorAdapter(SimpleNamespace(model=model))

    assert adapter.ready is False
    model.ready = True
    model.model_name = "rf"

    assert adapter.ready is True
    assert adapter.model_name == "rf"
    assert adapter.model_version == "rf.joblib"
