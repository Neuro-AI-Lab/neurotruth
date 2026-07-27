from uuid import uuid4

from app.ml.craving.pipeline import AlertConfig, AlertEvaluator
from app.services.demo_prediction_playback import DemoPredictionPlayback


def base_prediction() -> dict[str, object]:
    return {
        "predictionSchema": "binary-craving-v1",
        "class": 0,
        "classCode": "low",
        "confidence": 0.7,
        "cravingProbability": 0.3,
        "classProbabilities": {"low": 0.7, "high": 0.3},
        "timestampMs": 1,
    }


def test_demo_playback_is_scoped_to_enabled_reserved_patient() -> None:
    demo_patient, other_patient = uuid4(), uuid4()
    disabled = DemoPredictionPlayback(enabled=False, patient_id=demo_patient)
    enabled = DemoPredictionPlayback(enabled=True, patient_id=demo_patient)
    original = base_prediction()

    assert disabled.apply(
        patient_id=demo_patient,
        payload={"sequence": 0, "windowEndMs": 20_000},
        prediction=original,
    ) is original
    assert enabled.apply(
        patient_id=other_patient,
        payload={"sequence": 0, "windowEndMs": 20_000},
        prediction=original,
    ) is original
    assert enabled.apply(
        patient_id=demo_patient,
        payload={"sequence": 5, "windowEndMs": 70_000},
        prediction=original,
    ) is original


def test_demo_playback_rises_and_third_danger_uses_production_alert_rule() -> None:
    patient_id = uuid4()
    playback = DemoPredictionPlayback(enabled=True, patient_id=patient_id)
    evaluator = AlertEvaluator(AlertConfig(cooldown_seconds=900))
    probabilities: list[float] = []
    decisions = []

    for sequence in range(5):
        timestamp_ms = 20_000 + sequence * 10_000
        prediction = playback.apply(
            patient_id=patient_id,
            payload={"sequence": sequence, "windowEndMs": timestamp_ms},
            prediction=base_prediction(),
        )
        probabilities.append(float(prediction["cravingProbability"]))
        decisions.append(evaluator.evaluate(probabilities[-1], timestamp_ms))
        assert prediction["timestampMs"] == timestamp_ms
        assert prediction["demoPlayback"] == {
            "scenario": "vp012-danger-to-chat-v1",
            "step": sequence + 1,
            "stage": ("observe", "caution", "high", "high", "high")[sequence],
        }

    assert probabilities == [0.38, 0.62, 0.82, 0.86, 0.89]
    assert all(not decision.alertRequired for decision in decisions[:4])
    assert decisions[-1].alertRequired is True
    assert decisions[-1].alertAction == "required_intervention"
    # The checked-in recent-hour fixture's seeded alert is 950 seconds before login.
    # At the fifth live window another 60 seconds have elapsed, so the 15-minute cooldown is clear.
    persisted = evaluator.evaluate_history(
        [(0.82, 40_000), (0.86, 50_000), (0.89, 60_000)],
        last_alert_ms=-950_000,
        notification_allowed=True,
    )
    assert persisted.alertRequired is True
