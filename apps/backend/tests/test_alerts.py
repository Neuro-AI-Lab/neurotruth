from app.alerts import (
    MAX_ALERT_SESSIONS,
    AlertConfig,
    AlertEvaluator,
    AlertEvaluatorRegistry,
)


def test_alert_config_reads_env_overrides(monkeypatch) -> None:
    monkeypatch.setenv("ALERT_WINDOW_SIZE", "7")
    monkeypatch.setenv("ALERT_RECOMMEND_MIN", "0.7")
    monkeypatch.setenv("ALERT_REQUIRED_MIN", "1.7")
    monkeypatch.setenv("ALERT_HIGH_STREAK", "4")
    monkeypatch.setenv("ALERT_COOLDOWN_SECONDS", "45")
    monkeypatch.setenv("ALERT_DOWNTREND_DELTA", "0.8")

    config = AlertConfig.from_env()

    assert config.window_size == 7
    assert config.recommend_min == 0.7
    assert config.required_min == 1.7
    assert config.high_streak == 4
    assert config.cooldown_seconds == 45
    assert config.downtrend_delta == 0.8


def test_default_rule_recommends_for_acceptance_example() -> None:
    evaluator = AlertEvaluator(AlertConfig(cooldown_seconds=0))

    decision = None
    for index, craving_class in enumerate([0, 0, 1, 1, 1, 1, 1, 1, 1, 1]):
        decision = evaluator.evaluate(craving_class, now_ms=index * 1000)

    assert decision is not None
    assert decision.alertLevel == "recommend"
    assert decision.alertAction == "recommend_intervention"
    assert decision.windowMean == 0.8
    assert decision.alertRequired is False


def test_mean_thresholds_wait_for_full_window() -> None:
    evaluator = AlertEvaluator(AlertConfig(cooldown_seconds=0))

    for index in range(9):
        decision = evaluator.evaluate(1, now_ms=index * 1000)
        assert decision.alertLevel == "none"
        assert decision.alertAction == "none"
        assert decision.triggerReason == "window_warming_up"

    decision = evaluator.evaluate(1, now_ms=9000)
    assert decision.alertLevel == "recommend"
    assert decision.alertAction == "recommend_intervention"
    assert decision.triggerReason == "window_mean_recommend"


def test_high_streak_can_require_during_warm_up() -> None:
    evaluator = AlertEvaluator(AlertConfig(cooldown_seconds=0))

    first = evaluator.evaluate(2, now_ms=0)
    second = evaluator.evaluate(2, now_ms=1000)
    third = evaluator.evaluate(2, now_ms=2000)

    assert first.triggerReason == "window_warming_up"
    assert second.triggerReason == "window_warming_up"
    assert third.alertLevel == "required"
    assert third.alertAction == "required_intervention"
    assert third.triggerReason == "high_streak"
    assert third.alertRequired is True


def test_default_rule_requires_for_high_window_mean() -> None:
    evaluator = AlertEvaluator(AlertConfig(cooldown_seconds=0))

    decision = None
    for index, craving_class in enumerate([1, 1, 2, 2, 2, 1, 2, 1, 2, 2]):
        decision = evaluator.evaluate(craving_class, now_ms=index * 1000)

    assert decision is not None
    assert decision.alertLevel == "required"
    assert decision.alertAction == "required_intervention"
    assert decision.windowMean == 1.6
    assert decision.alertRequired is True


def test_downtrend_suppresses_escalation() -> None:
    evaluator = AlertEvaluator(AlertConfig(cooldown_seconds=0, downtrend_delta=0.6))

    decision = None
    for index, craving_class in enumerate([2, 2, 2, 2, 2, 1, 1, 1, 1, 1]):
        decision = evaluator.evaluate(craving_class, now_ms=index * 1000)

    assert decision is not None
    assert decision.alertLevel == "recommend"
    assert decision.triggerReason == "downtrend_suppressed"
    assert decision.alertRequired is False


def test_cooldown_keeps_level_but_suppresses_repeated_action() -> None:
    evaluator = AlertEvaluator(AlertConfig(cooldown_seconds=30))

    evaluator.evaluate(2, now_ms=0)
    evaluator.evaluate(2, now_ms=1000)
    first = evaluator.evaluate(2, now_ms=2000)
    second = evaluator.evaluate(2, now_ms=3000)

    assert first.alertLevel == "required"
    assert first.alertAction == "required_intervention"
    assert second.alertLevel == "required"
    assert second.alertAction == "cooldown"
    assert second.alertRequired is False


def test_registry_isolates_interleaved_session_history_and_cooldown() -> None:
    registry = AlertEvaluatorRegistry(
        AlertConfig(window_size=4, high_streak=0, cooldown_seconds=30)
    )

    for index in range(4):
        required = registry.for_session("session-a").evaluate(2, now_ms=index * 1000)
        none = registry.for_session("session-b").evaluate(0, now_ms=index * 1000)

    assert required.alertLevel == "required"
    assert required.alertAction == "required_intervention"
    assert none.alertLevel == "none"
    assert none.windowMean == 0.0

    a_cooldown = registry.for_session("session-a").evaluate(2, now_ms=4000)
    b_still_none = registry.for_session("session-b").evaluate(0, now_ms=4000)
    assert a_cooldown.alertAction == "cooldown"
    assert b_still_none.alertAction == "none"


def test_registry_refreshes_lru_and_caps_at_exactly_256_sessions() -> None:
    registry = AlertEvaluatorRegistry()
    for index in range(MAX_ALERT_SESSIONS):
        registry.for_session(f"session-{index}")

    registry.for_session("session-0")
    registry.for_session(f"session-{MAX_ALERT_SESSIONS}")

    assert len(registry) == 256
    assert "session-0" in registry
    assert "session-1" not in registry
    assert f"session-{MAX_ALERT_SESSIONS}" in registry
