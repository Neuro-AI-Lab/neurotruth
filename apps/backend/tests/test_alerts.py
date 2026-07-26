import pytest

from app.ml.craving.pipeline import (
    MAX_ALERT_SESSIONS,
    AlertConfig,
    AlertEvaluator,
    AlertEvaluatorRegistry,
)


def test_alert_config_reads_watch_danger_env_overrides(monkeypatch) -> None:
    monkeypatch.setenv("ALERT_DANGER_THRESHOLD", "0.8")
    monkeypatch.setenv("ALERT_DANGER_STREAK", "4")
    monkeypatch.setenv("ALERT_MAX_GAP_SECONDS", "15")
    monkeypatch.setenv("ALERT_COOLDOWN_SECONDS", "1200")
    assert AlertConfig.from_env() == AlertConfig(
        danger_threshold=0.8,
        danger_streak=4,
        max_gap_seconds=15,
        cooldown_seconds=1200,
    )


def test_three_consecutive_danger_probabilities_require_intervention() -> None:
    evaluator = AlertEvaluator(AlertConfig(cooldown_seconds=0))
    assert evaluator.evaluate(0.75, 0).triggerReason == "danger_streak_warming_up"
    assert evaluator.evaluate(0.81, 10_000).alertRequired is False
    decision = evaluator.evaluate(0.99, 20_000)
    assert decision.alertLevel == "required"
    assert decision.alertAction == "required_intervention"
    assert decision.alertRequired is True
    assert decision.triggerReason == "danger_streak"


def test_below_threshold_and_long_gap_reset_streak() -> None:
    evaluator = AlertEvaluator(AlertConfig(cooldown_seconds=0))
    evaluator.evaluate(0.9, 0)
    evaluator.evaluate(0.74, 10_000)
    assert evaluator.evaluate(0.9, 20_000).triggerReason == "danger_streak_reset"

    gap = AlertEvaluator(AlertConfig(cooldown_seconds=0))
    gap.evaluate(0.9, 0)
    gap.evaluate(0.9, 20_000)
    decision = gap.evaluate(0.9, 40_001)
    assert decision.alertRequired is False
    assert decision.triggerReason == "danger_gap_reset"


def test_fifteen_minute_cooldown_suppresses_additional_alert() -> None:
    evaluator = AlertEvaluator(AlertConfig(cooldown_seconds=900))
    evaluator.evaluate(0.9, 0)
    evaluator.evaluate(0.9, 10_000)
    assert evaluator.evaluate(0.9, 20_000).alertRequired is True
    suppressed = evaluator.evaluate(0.9, 30_000)
    assert suppressed.alertAction == "cooldown"
    assert suppressed.triggerReason == "cooldown_active"
    assert suppressed.alertRequired is False


def test_persisted_history_path_honors_notification_consent() -> None:
    decision = AlertEvaluator().evaluate_history(
        [(0.9, 0), (0.9, 10_000), (0.9, 20_000)],
        last_alert_ms=None,
        notification_allowed=False,
    )
    assert decision.alertRequired is False
    assert decision.triggerReason == "notifications_disabled"


def test_invalid_probability_is_rejected() -> None:
    with pytest.raises(ValueError, match="between 0 and 1"):
        AlertEvaluator().evaluate(2.0, 0)


def test_registry_isolates_patients_and_caps_lru() -> None:
    registry = AlertEvaluatorRegistry(
        AlertConfig(danger_streak=1, cooldown_seconds=0)
    )
    assert registry.for_session("a").evaluate(1.0, 0).alertRequired is True
    assert registry.for_session("b").evaluate(0.0, 0).alertRequired is False
    for index in range(MAX_ALERT_SESSIONS):
        registry.for_session(f"patient-{index}")
    registry.for_session("patient-0")
    registry.for_session(f"patient-{MAX_ALERT_SESSIONS}")
    assert len(registry) == MAX_ALERT_SESSIONS
    assert "patient-0" in registry
    assert "patient-1" not in registry
