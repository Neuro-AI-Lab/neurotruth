import pytest

from app.ml.craving.pipeline import MAX_ALERT_SESSIONS, AlertConfig, AlertEvaluator, AlertEvaluatorRegistry


def test_alert_config_reads_binary_env_overrides(monkeypatch) -> None:
    monkeypatch.setenv("ALERT_WINDOW_SIZE", "7")
    monkeypatch.setenv("ALERT_RECOMMEND_COUNT", "5")
    monkeypatch.setenv("ALERT_HIGH_STREAK", "4")
    monkeypatch.setenv("ALERT_COOLDOWN_SECONDS", "45")
    monkeypatch.setenv("ALERT_DOWNTREND_DELTA", "0.8")
    config = AlertConfig.from_env()
    assert config == AlertConfig(
        window_size=7, recommend_count=5, high_streak=4,
        cooldown_seconds=45, downtrend_delta=0.8,
    )


def test_six_ones_in_full_window_recommends() -> None:
    evaluator = AlertEvaluator(AlertConfig(cooldown_seconds=0, high_streak=0))
    decision = None
    for index, value in enumerate([1, 0, 1, 0, 1, 0, 1, 0, 1, 1]):
        decision = evaluator.evaluate(value, index * 1000)
    assert decision is not None
    assert decision.alertLevel == "recommend"
    assert decision.alertAction == "recommend_intervention"
    assert decision.windowMean == decision.classOneRatio == 0.6


def test_three_trailing_ones_requires_during_warmup() -> None:
    evaluator = AlertEvaluator(AlertConfig(cooldown_seconds=0))
    assert evaluator.evaluate(1, 0).alertLevel == "none"
    assert evaluator.evaluate(1, 1000).alertLevel == "none"
    decision = evaluator.evaluate(1, 2000)
    assert decision.alertLevel == "required"
    assert decision.alertAction == "required_intervention"
    assert decision.alertRequired is True


def test_downtrend_suppresses_recommend_but_not_high_streak() -> None:
    evaluator = AlertEvaluator(AlertConfig(cooldown_seconds=0, downtrend_delta=0.6, high_streak=0))
    decision = None
    for index, value in enumerate([1, 1, 1, 1, 1, 1, 0, 0, 0, 0]):
        decision = evaluator.evaluate(value, index * 1000)
    assert decision is not None
    assert decision.alertLevel == "none"
    assert decision.triggerReason == "downtrend_suppressed"

    priority = AlertEvaluator(AlertConfig(cooldown_seconds=0, downtrend_delta=0.6))
    for index, value in enumerate([1, 1, 1, 1, 1, 0, 0, 1, 1, 1]):
        decision = priority.evaluate(value, index * 1000)
    assert decision.alertLevel == "required"
    assert decision.triggerReason == "high_streak"


def test_cooldown_is_separate_per_level() -> None:
    evaluator = AlertEvaluator(AlertConfig(cooldown_seconds=30))
    evaluator.evaluate(1, 0)
    evaluator.evaluate(1, 1000)
    first = evaluator.evaluate(1, 2000)
    second = evaluator.evaluate(1, 3000)
    assert first.alertAction == "required_intervention"
    assert second.alertAction == "cooldown"
    assert second.alertRequired is False


def test_non_binary_class_is_rejected() -> None:
    with pytest.raises(ValueError, match="0 or 1"):
        AlertEvaluator().evaluate(2, 0)


def test_registry_isolates_sessions_and_caps_lru() -> None:
    registry = AlertEvaluatorRegistry(AlertConfig(window_size=1, recommend_count=1, high_streak=0))
    assert registry.for_session("a").evaluate(1, 0).alertLevel == "recommend"
    assert registry.for_session("b").evaluate(0, 0).alertLevel == "none"
    for index in range(MAX_ALERT_SESSIONS):
        registry.for_session(f"session-{index}")
    registry.for_session("session-0")
    registry.for_session(f"session-{MAX_ALERT_SESSIONS}")
    assert len(registry) == MAX_ALERT_SESSIONS
    assert "session-0" in registry
    assert "session-1" not in registry
