from __future__ import annotations

"""Conservative rule-based alert decisions for craving predictions."""

import os
from collections import OrderedDict, deque
from dataclasses import dataclass
from typing import Deque


MAX_ALERT_SESSIONS = 256


@dataclass(frozen=True)
class AlertConfig:
    window_size: int = 10
    recommend_min: float = 0.6
    required_min: float = 1.5
    high_streak: int = 3
    cooldown_seconds: float = 30.0
    downtrend_delta: float = 0.6

    @classmethod
    def from_env(cls) -> "AlertConfig":
        return cls(
            window_size=_env_int("ALERT_WINDOW_SIZE", cls.window_size),
            recommend_min=_env_float("ALERT_RECOMMEND_MIN", cls.recommend_min),
            required_min=_env_float("ALERT_REQUIRED_MIN", cls.required_min),
            high_streak=_env_int("ALERT_HIGH_STREAK", cls.high_streak),
            cooldown_seconds=_env_float("ALERT_COOLDOWN_SECONDS", cls.cooldown_seconds),
            downtrend_delta=_env_float("ALERT_DOWNTREND_DELTA", cls.downtrend_delta),
        )


@dataclass(frozen=True)
class AlertDecision:
    alertLevel: str
    alertAction: str
    windowMean: float
    triggerReason: str
    alertRequired: bool

    def as_dict(self) -> dict[str, object]:
        return {
            "alertLevel": self.alertLevel,
            "alertAction": self.alertAction,
            "windowMean": self.windowMean,
            "triggerReason": self.triggerReason,
            "alertRequired": self.alertRequired,
        }


class AlertEvaluator:
    """Stateful evaluator over the latest prediction classes."""

    def __init__(self, config: AlertConfig | None = None) -> None:
        self.config = config or AlertConfig.from_env()
        self._classes: Deque[int] = deque(maxlen=max(1, self.config.window_size))
        self._last_action_ms_by_level: dict[str, int] = {}

    def evaluate(self, craving_class: int, now_ms: int) -> AlertDecision:
        craving_class = max(0, min(2, int(craving_class)))
        self._classes.append(craving_class)
        values = list(self._classes)
        window_mean = round(sum(values) / len(values), 3) if values else 0.0

        warming_up = len(values) < (self._classes.maxlen or 1)
        high_streak = self._has_high_streak(values)
        if warming_up and not high_streak:
            return AlertDecision(
                alertLevel="none",
                alertAction="none",
                windowMean=window_mean,
                triggerReason="window_warming_up",
                alertRequired=False,
            )

        if warming_up:
            level = "required"
            reason = "high_streak"
        else:
            level, reason = self._level_from_mean(window_mean)
            if high_streak and level != "required":
                level = "required"
                reason = "high_streak"

        if not warming_up and level != "none" and self._is_downtrend(values):
            level = "recommend" if level == "required" else "none"
            reason = "downtrend_suppressed"

        action = self._action_for(level)
        if level != "none" and self._in_cooldown(level, now_ms):
            action = "cooldown"
            reason = "cooldown_active"
        elif level != "none":
            self._last_action_ms_by_level[level] = now_ms

        return AlertDecision(
            alertLevel=level,
            alertAction=action,
            windowMean=window_mean,
            triggerReason=reason,
            alertRequired=(level == "required" and action == "required_intervention"),
        )

    def _level_from_mean(self, window_mean: float) -> tuple[str, str]:
        if window_mean >= self.config.required_min:
            return "required", "window_mean_required"
        if window_mean >= self.config.recommend_min:
            return "recommend", "window_mean_recommend"
        return "none", "window_mean_none"

    def _has_high_streak(self, values: list[int]) -> bool:
        if self.config.high_streak <= 0:
            return False
        streak = 0
        for value in reversed(values):
            if value >= 2:
                streak += 1
                if streak >= self.config.high_streak:
                    return True
            else:
                break
        return False

    def _is_downtrend(self, values: list[int]) -> bool:
        if self.config.downtrend_delta <= 0 or len(values) < 4:
            return False
        split = len(values) // 2
        first = values[:split]
        second = values[split:]
        if not first or not second:
            return False
        first_mean = sum(first) / len(first)
        second_mean = sum(second) / len(second)
        return (first_mean - second_mean) >= self.config.downtrend_delta

    def _in_cooldown(self, level: str, now_ms: int) -> bool:
        previous_ms = self._last_action_ms_by_level.get(level)
        if previous_ms is None:
            return False
        return (now_ms - previous_ms) < int(self.config.cooldown_seconds * 1000)

    @staticmethod
    def _action_for(level: str) -> str:
        if level == "required":
            return "required_intervention"
        if level == "recommend":
            return "recommend_intervention"
        return "none"


class AlertEvaluatorRegistry:
    """Session-keyed LRU registry of independent alert evaluators."""

    def __init__(self, config: AlertConfig | None = None) -> None:
        self.config = config or AlertConfig.from_env()
        self._evaluators: OrderedDict[str, AlertEvaluator] = OrderedDict()

    def for_session(self, session_id: str) -> AlertEvaluator:
        key = str(session_id)
        evaluator = self._evaluators.get(key)
        if evaluator is not None:
            self._evaluators.move_to_end(key)
            return evaluator

        evaluator = AlertEvaluator(self.config)
        self._evaluators[key] = evaluator
        if len(self._evaluators) > MAX_ALERT_SESSIONS:
            self._evaluators.popitem(last=False)
        return evaluator

    def __contains__(self, session_id: object) -> bool:
        return str(session_id) in self._evaluators

    def __len__(self) -> int:
        return len(self._evaluators)


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except ValueError:
        return default


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)))
    except ValueError:
        return default
