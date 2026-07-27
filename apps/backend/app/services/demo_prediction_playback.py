from __future__ import annotations

"""Deterministic live prediction overlay for the explicitly enabled VP-012 demo."""

from dataclasses import dataclass
from typing import Any
from uuid import UUID


@dataclass(frozen=True)
class DemoPredictionPlayback:
    """Map the first five Watch windows to a visible rise ending in one real alert.

    The overlay is deliberately narrow: it applies only to one configured patient while
    ``DEMO_SCENARIO_ENABLED`` is true. Sensor windows are still encrypted and persisted, and the
    resulting probabilities go through the production PostgreSQL alert transaction. Therefore the
    third danger result creates the same SSE/Phone/Watch alert as an ordinary model prediction.
    """

    enabled: bool
    patient_id: UUID
    probabilities: tuple[float, ...] = (0.38, 0.62, 0.82, 0.86, 0.89)
    scenario: str = "vp012-danger-to-chat-v1"

    def apply(
        self,
        *,
        patient_id: UUID,
        payload: dict[str, Any],
        prediction: dict[str, Any],
    ) -> dict[str, Any]:
        if not self.enabled or patient_id != self.patient_id:
            return prediction
        try:
            sequence = int(payload["sequence"])
        except (KeyError, TypeError, ValueError):
            return prediction
        if sequence < 0 or sequence >= len(self.probabilities):
            return prediction

        probability = self.probabilities[sequence]
        class_index = 1 if probability >= 0.5 else 0
        stage = _stage(probability)
        return {
            **prediction,
            "predictionSchema": "binary-craving-v1",
            "class": class_index,
            "classCode": "high" if class_index == 1 else "low",
            "confidence": max(probability, 1.0 - probability),
            "cravingProbability": probability,
            "classProbabilities": {
                "low": 1.0 - probability,
                "high": probability,
            },
            "timestampMs": payload.get("windowEndMs", prediction.get("timestampMs")),
            "sequence": sequence,
            "demoPlayback": {
                "scenario": self.scenario,
                "step": sequence + 1,
                "stage": stage,
            },
        }


def _stage(probability: float) -> str:
    if probability < 0.25:
        return "low"
    if probability < 0.50:
        return "observe"
    if probability < 0.75:
        return "caution"
    return "high"
