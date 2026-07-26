from __future__ import annotations

"""Rule-based alert decisions for consecutive Watch craving predictions."""

import os
from collections import OrderedDict, deque
from dataclasses import dataclass
from typing import Deque


MAX_ALERT_SESSIONS = 256


@dataclass(frozen=True)
class AlertConfig:
    danger_threshold: float = 0.75
    danger_streak: int = 3
    max_gap_seconds: float = 20.0
    cooldown_seconds: float = 900.0

    @classmethod
    def from_env(cls) -> "AlertConfig":
        return cls(
            danger_threshold=_env_float("ALERT_DANGER_THRESHOLD", cls.danger_threshold),
            danger_streak=_env_int("ALERT_DANGER_STREAK", cls.danger_streak),
            max_gap_seconds=_env_float("ALERT_MAX_GAP_SECONDS", cls.max_gap_seconds),
            cooldown_seconds=_env_float("ALERT_COOLDOWN_SECONDS", cls.cooldown_seconds),
        )


@dataclass(frozen=True)
class AlertDecision:
    alertLevel: str
    alertAction: str
    windowMean: float
    triggerReason: str
    alertRequired: bool

    @property
    def classOneRatio(self) -> float:
        return self.windowMean

    def as_dict(self) -> dict[str, object]:
        return {
            "alertLevel": self.alertLevel,
            "alertAction": self.alertAction,
            "windowMean": self.windowMean,
            "classOneRatio": self.classOneRatio,
            "triggerReason": self.triggerReason,
            "alertRequired": self.alertRequired,
        }


class AlertEvaluator:
    """Evaluate a bounded chronological Watch history.

    The instance state exists only for compatibility with diagnostics. Production
    persistence supplies history from PostgreSQL through :meth:`evaluate_history`.
    """

    def __init__(self, config: AlertConfig | None = None) -> None:
        self.config = config or AlertConfig.from_env()
        self._samples: Deque[tuple[float, int]] = deque(
            maxlen=max(1, self.config.danger_streak)
        )
        self._last_alert_ms: int | None = None

    def evaluate(self, craving_probability: float, now_ms: int) -> AlertDecision:
        probability = self._probability(craving_probability)
        self._samples.append((probability, int(now_ms)))
        decision = self.evaluate_history(
            list(self._samples),
            last_alert_ms=self._last_alert_ms,
            notification_allowed=True,
        )
        if decision.alertRequired:
            self._last_alert_ms = int(now_ms)
        return decision

    def evaluate_history(
        self,
        samples: list[tuple[float, int]],
        *,
        last_alert_ms: int | None,
        notification_allowed: bool,
    ) -> AlertDecision:
        required = max(1, self.config.danger_streak)
        recent = samples[-required:]
        current_probability = self._probability(recent[-1][0]) if recent else 0.0
        window_mean = round(
            sum(self._probability(value) for value, _ in recent) / len(recent), 3
        ) if recent else 0.0

        if not notification_allowed:
            return self._none(window_mean, "notifications_disabled")
        if not recent or current_probability < self.config.danger_threshold:
            return self._none(window_mean, "below_danger_threshold")
        if len(recent) < required:
            return self._none(window_mean, "danger_streak_warming_up")
        if any(
            self._probability(value) < self.config.danger_threshold
            for value, _ in recent
        ):
            return self._none(window_mean, "danger_streak_reset")

        max_gap_ms = int(self.config.max_gap_seconds * 1000)
        if any(
            later_ms <= earlier_ms or later_ms - earlier_ms > max_gap_ms
            for (_, earlier_ms), (_, later_ms) in zip(recent, recent[1:])
        ):
            return self._none(window_mean, "danger_gap_reset")

        now_ms = recent[-1][1]
        cooldown_ms = int(self.config.cooldown_seconds * 1000)
        if last_alert_ms is not None and now_ms - last_alert_ms < cooldown_ms:
            return self._none(window_mean, "cooldown_active", action="cooldown")

        return AlertDecision(
            alertLevel="required",
            alertAction="required_intervention",
            windowMean=window_mean,
            triggerReason="danger_streak",
            alertRequired=True,
        )

    @staticmethod
    def _probability(value: float) -> float:
        probability = float(value)
        if probability < 0.0 or probability > 1.0:
            raise ValueError("Craving probability must be between 0 and 1")
        return probability

    @staticmethod
    def _none(window_mean: float, reason: str, *, action: str = "none") -> AlertDecision:
        return AlertDecision(
            alertLevel="none",
            alertAction=action,
            windowMean=window_mean,
            triggerReason=reason,
            alertRequired=False,
        )


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
