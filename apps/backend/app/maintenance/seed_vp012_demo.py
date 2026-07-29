from __future__ import annotations

import argparse
import asyncio
import json
import math
import os
import random
import sys
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import date, datetime, time, timedelta, timezone
from functools import lru_cache
from pathlib import Path
from typing import Any, Callable, Sequence
from uuid import NAMESPACE_URL, UUID, uuid5
from zoneinfo import ZoneInfo

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine

from app.core.security.crypto import AesGcmKeyring, aad_for
from app.core.security.passwords import hash_password


SEED_CONFIRMATION = "SEED-VP012-120D-DEMO"
PROVISION_CONFIRMATION = "PROVISION-VP012-DEMO"
DELETE_CONFIRMATION = "DELETE-VP012-DEMO"
ADVISORY_LOCK_KEY = "neurotruth-vp012-120day-demo"
DEMO_EMAIL = "woosik.jeong@neurotruth.kr"
DEMO_SEED = "vp012-120d-v8"
DEMO_DAYS = 120
HISTORICAL_DISPLAY_WEIGHT = 90
DEMO_EVENT_MIN_PER_DAY = 2
DEMO_EVENT_MAX_PER_DAY = 10
DEMO_EVENT_COOLDOWN = timedelta(minutes=15)
# Six of 120 days include an overnight Watch-worn period. This remains rare
# (5% of the demo days) and is never used to synthesize an alert event.
SLEEP_WEAR_DAY_OFFSETS = (11, 29, 47, 68, 91, 113)
RECENT_HOUR_TRACE_SCHEMA = "neurotruth-vp012-recent-hour-v1"
RECENT_HOUR_TRACE_SOURCE = "Alcohol_Test/1_1_010_V1"
RECENT_HOUR_TRACE_PATH = (
    Path(__file__).with_name("fixtures") / "vp012_recent_hour_1_1_010_V1.json"
)
SEOUL = ZoneInfo("Asia/Seoul")
_NS = uuid5(NAMESPACE_URL, "https://neurotruth.invalid/demo/vp012/v1")
DEMO_PATIENT_ID = uuid5(_NS, "patient")
DEMO_CONSENT_ID = uuid5(_NS, "consent")


class DemoSeedError(RuntimeError):
    """Sanitized failure for an invalid or incompatible demo seed."""


@dataclass(frozen=True)
class PreparedDemo:
    anchor_date: date
    start_date: date
    patient: dict[str, Any]
    consent: dict[str, Any]
    predictions: list[dict[str, Any]]
    alerts: list[dict[str, Any]]
    sessions: list[dict[str, Any]]
    messages: list[dict[str, Any]]
    assessments: list[dict[str, Any]]
    interventions: list[dict[str, Any]]
    inferences: list[dict[str, Any]]
    reports: list[dict[str, Any]]

    @property
    def counts(self) -> dict[str, int]:
        return {
            "craving_predictions": len(self.predictions),
            "craving_alerts": len(self.alerts),
            "sessions": len(self.sessions),
            "messages": len(self.messages),
            "craving_assessments": len(self.assessments),
            "interventions": len(self.interventions),
            "state_inferences": len(self.inferences),
            "session_reports": len(self.reports),
        }


@dataclass(frozen=True)
class DemoReport:
    mode: str
    exists: bool
    patient_id: str
    email: str
    start_date: str | None
    end_date: str | None
    table_counts: dict[str, int]


def stable_id(kind: str, index: int = 0) -> UUID:
    return uuid5(_NS, f"{kind}:{index}")


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _encrypt(
    keyring: AesGcmKeyring,
    *,
    table: str,
    column: str,
    record_id: UUID,
    value: Any,
) -> bytes:
    payload = value if isinstance(value, str) else _json(value)
    return keyring.encrypt(
        payload.encode("utf-8"),
        aad=aad_for(
            table=table,
            column=column,
            patient_id=str(DEMO_PATIENT_ID),
            record_id=str(record_id),
        ),
    ).pack()


def _stage(probability: float) -> str:
    if probability < 0.25:
        return "low"
    if probability < 0.50:
        return "observe"
    if probability < 0.75:
        return "caution"
    return "high"


def _demo_auq_responses(
    session_index: int,
    *,
    triggered_at: datetime,
    probability: float,
) -> list[int]:
    """Build a varied but reproducible VP-012 self-report.

    The AUQ is not derived directly from the model output. The model probability
    only contributes one bounded context term; time of day, weekend context,
    a slow non-monotonic pattern, and deterministic self-report mismatch keep
    the dashboard from implying that sensor output and self-report are the same
    construct. Item offsets reflect this fictional persona's stronger intrusive
    thoughts and relief expectancy around restaurant closing time.
    """

    local = triggered_at.astimezone(SEOUL)
    if local.hour < 10:
        time_adjustment = -4
    elif local.hour < 16:
        time_adjustment = -1
    elif local.hour < 19:
        time_adjustment = 2
    else:
        time_adjustment = 5

    weekend_adjustment = 2 if local.weekday() >= 5 else 0
    non_monotonic_variation = round(
        5.5 * math.sin(session_index * 0.43)
        + 3.5 * math.sin(local.date().toordinal() * 0.17)
    )
    rng = random.Random(88_012 + session_index)
    self_report_mismatch = rng.choice((-6, -3, 0, 2, 5))
    target_score = round(
        15
        + min(1.0, max(0.0, probability)) * 18
        + time_adjustment
        + weekend_adjustment
        + non_monotonic_variation
        + self_report_mismatch
    )
    target_score = min(45, max(9, target_score))

    item_offsets = (1.0, -0.3, 0.8, 0.5, -0.8, 0.1, -0.6, 0.8)
    center = target_score / 8.0
    responses = [
        min(6, max(0, round(center + offset)))
        for offset in item_offsets
    ]

    adjustment_order = list(range(8))
    rng.shuffle(adjustment_order)
    while sum(responses) != target_score:
        increase = sum(responses) < target_score
        changed = False
        for item_index in adjustment_order:
            if increase and responses[item_index] < 6:
                responses[item_index] += 1
                changed = True
            elif not increase and responses[item_index] > 0:
                responses[item_index] -= 1
                changed = True
            if sum(responses) == target_score:
                break
        if not changed:
            raise DemoSeedError("demo_auq_distribution_invalid")
    return responses


def _historical_probability(
    day_index: int,
    hour: int,
    quarter: int,
    weekend: bool,
) -> float:
    """Return one representative probability for a 15-minute historical block.

    VP-012's restaurant-closing context raises the evening baseline without creating a
    treatment-shaped decline. Non-event blocks remain below the danger threshold; explicitly
    scheduled event blocks supply their own three consecutive danger values.
    """

    hourly_profile = (
        0.18, 0.16, 0.15, 0.14, 0.14, 0.16,
        0.18, 0.15, 0.17, 0.20, 0.31, 0.34,
        0.18, 0.22, 0.40, 0.43, 0.46, 0.50,
        0.55, 0.60, 0.65, 0.70, 0.58, 0.36,
    )
    day_variation = (
        0.055 * math.sin(day_index * 0.37)
        + 0.035 * math.sin(day_index * 0.13 + 1.2)
        + (0.035 if weekend else 0.0)
        + (0.035 if day_index % 17 in (0, 1) else 0.0)
    )
    quarter_variation = (-0.025, 0.0, 0.03, 0.01)[quarter]
    probability = hourly_profile[hour] + day_variation + quarter_variation
    return round(min(0.72, max(0.06, probability)), 4)


def _historical_day_points(
    day_index: int,
    local_day: date,
) -> list[tuple[datetime, float, int]]:
    """Compress plausible Watch-worn periods into weighted representatives.

    Each representative carries the display weight of one 15-minute period at
    the production ten-second cadence.  Deliberate gaps remain outside the
    morning, midday, and evening wearing periods, so the dashboard never implies
    that this fictional patient was measured continuously for 24 hours.  On a
    scheduled event day, one representative is split into three ten-second
    points while preserving the same aggregate count and satisfying the
    production three-danger alert rule.
    """

    weekend = local_day.weekday() >= 5
    start = datetime.combine(local_day, time.min, SEOUL).astimezone(timezone.utc)
    morning_start = 7 + (day_index % 3)
    evening_start = 16 if weekend else 17
    evening_end = 23 + (day_index % 2)
    sleep_worn = day_index in SLEEP_WEAR_DAY_OFFSETS
    worn_slots = [
        (hour, quarter)
        for hour in range(24)
        for quarter in range(4)
        if (
            (sleep_worn and 0 <= hour < 6)
            or morning_start <= hour < 10
            or (day_index % 4 != 0 and 12 <= hour < 14)
            or evening_start <= hour < evening_end
        )
    ]
    event_eligible_slots = [
        slot for slot in worn_slots if slot[0] >= 6
    ]
    rng = random.Random(12_012 + day_index)
    event_count = rng.randint(DEMO_EVENT_MIN_PER_DAY, DEMO_EVENT_MAX_PER_DAY)
    event_slots = set(rng.sample(event_eligible_slots, event_count))
    points: list[tuple[datetime, float, int]] = []
    for hour, quarter in worn_slots:
        offset = timedelta(hours=hour, minutes=quarter * 15)
        if (hour, quarter) in event_slots:
            # One alert event is represented by the same three-danger sequence
            # used by production. The total display weight of the 15-minute
            # block is preserved for the stacked dashboard.
            for seconds, probability in zip((0, 10, 20), (0.82, 0.87, 0.84)):
                points.append((
                    start + offset + timedelta(seconds=seconds),
                    probability,
                    HISTORICAL_DISPLAY_WEIGHT // 3,
                ))
            continue
        points.append((
            start + offset,
            _historical_probability(day_index, hour, quarter, weekend),
            HISTORICAL_DISPLAY_WEIGHT,
        ))
    return points


@lru_cache(maxsize=1)
def _recent_hour_trace() -> tuple[float, ...]:
    """Load the checked-in Alcohol_Test trace used by the login-relative hour.

    The fixture preserves the value order of ``1_1_010_V1`` after its existing
    60-minute normalization. It is demo-only source data, not a VP-012
    participant measurement.
    """
    try:
        payload = json.loads(RECENT_HOUR_TRACE_PATH.read_text(encoding="utf-8"))
        if payload.get("schemaVersion") != RECENT_HOUR_TRACE_SCHEMA:
            raise ValueError("schema")
        if payload.get("sourceSubjectVisit") != "1_1_010_V1":
            raise ValueError("source")
        if payload.get("bucketSeconds") != 10:
            raise ValueError("bucket")
        values = tuple(float(value) for value in payload["probabilities"])
        if len(values) != 360:
            raise ValueError("length")
        if any(
            not math.isfinite(value) or not 0.0 <= value <= 1.0
            for value in values
        ):
            raise ValueError("probability")
        return values
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        raise DemoSeedError("demo_recent_hour_fixture_invalid") from exc


def _consecutive_danger_triggers(
    rows: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    streak: list[dict[str, Any]] = []
    triggers: list[dict[str, Any]] = []
    last_triggered_at: datetime | None = None
    for row in sorted(rows, key=lambda item: item["predicted_at"]):
        if (
            row["continuous_value"] < 0.75
            or (
                streak
                and row["predicted_at"] - streak[-1]["predicted_at"]
                > timedelta(seconds=20)
            )
        ):
            streak = []
        if row["continuous_value"] >= 0.75:
            streak.append(row)
            if len(streak) >= 3:
                if (
                    last_triggered_at is None
                    or row["predicted_at"] - last_triggered_at >= DEMO_EVENT_COOLDOWN
                ):
                    triggers.append(row)
                    last_triggered_at = row["predicted_at"]
                streak = []
    return triggers


def prepare_demo(
    anchor_date: date,
    keyring: AesGcmKeyring,
    *,
    now: datetime | None = None,
) -> PreparedDemo:
    now_utc = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    today = now_utc.astimezone(SEOUL).date()
    if anchor_date > today:
        raise DemoSeedError("demo_anchor_in_future")
    start_date = anchor_date - timedelta(days=DEMO_DAYS - 1)
    consent = {
        "id": DEMO_CONSENT_ID, "user_id": DEMO_PATIENT_ID,
        "tos": True, "privacy": True, "sensitive": True,
        "biosignal": True, "voice": True, "ai_analysis": True,
        "notification": True, "report_generation": True,
        "camera_rppg": True, "face_video_retention": True,
        "tos_version": "demo-v1", "privacy_version": "demo-v1",
        "consent_form_version": "demo-v1",
        "collected_at": datetime.combine(start_date, time(9), SEOUL).astimezone(timezone.utc),
    }
    patient = {
        "id": DEMO_PATIENT_ID, "email": DEMO_EMAIL,
        "name_encrypted": _encrypt(
            keyring, table="patient_profiles", column="name_encrypted",
            record_id=DEMO_PATIENT_ID, value="정우식",
        ),
        "birth_year": anchor_date.year - 51, "gender": "male",
        "pseudonymous_id": "DEMO-VP012-FICTIONAL",
        "encryption_key_version": keyring.current_key_id,
    }

    predictions: list[dict[str, Any]] = []
    alert_candidates: list[dict[str, Any]] = []
    recent_hour_trace = _recent_hour_trace()
    for day_index in range(DEMO_DAYS):
        local_day = start_date + timedelta(days=day_index)
        recent_trace_start: datetime | None = None
        if local_day == today:
            latest = now_utc.replace(microsecond=0)
            latest -= timedelta(seconds=latest.second % 10)
            first = latest - timedelta(seconds=(len(recent_hour_trace) - 1) * 10)
            recent_trace_start = first
            elapsed_wearing_points = [
                spec
                for spec in _historical_day_points(day_index, local_day)
                if spec[0] < first
            ]
            point_specs = elapsed_wearing_points + [
                (first + timedelta(seconds=index * 10), probability, 1)
                for index, probability in enumerate(recent_hour_trace)
            ]
        else:
            point_specs = _historical_day_points(day_index, local_day)
        day_rows: list[dict[str, Any]] = []
        for point_index, (predicted_at, probability, display_weight) in enumerate(point_specs):
            if predicted_at > now_utc:
                continue
            is_recent_trace = (
                recent_trace_start is not None
                and predicted_at >= recent_trace_start
            )
            prediction_id = stable_id("prediction", day_index * 400 + point_index)
            stage = _stage(probability)
            trace_metadata = (
                {
                    "traceSource": RECENT_HOUR_TRACE_SOURCE,
                    "traceTransformation": "ma10_order_preserved_time_normalized_60m",
                }
                if is_recent_trace
                else {}
            )
            row = {
                "id": prediction_id, "patient_id": DEMO_PATIENT_ID,
                "window_started_at": predicted_at - timedelta(seconds=20),
                "window_ended_at": predicted_at, "input_modalities": ["ppg", "eda"],
                "class_index": 1 if probability >= 0.50 else 0,
                "class_code": "high" if probability >= 0.50 else "low",
                "confidence": round(max(probability, 1.0 - probability), 5),
                "probabilities": _json(
                    {"low": round(1.0 - probability, 5), "high": probability},
                ),
                "continuous_value": probability,
                "signal_quality": _json({
                    "passed": True,
                    "demo": True,
                    **(
                        {"traceSource": RECENT_HOUR_TRACE_SOURCE}
                        if is_recent_trace
                        else {}
                    ),
                }),
                "output_metadata": _json({
                    "predictionSchema": "binary-craving-v1", "source": "watch_sensor",
                    "stage": stage, "demo": True, "fictional": True, "seed": DEMO_SEED,
                    "demoDisplayWeight": display_weight,
                    **trace_metadata,
                }),
                "predicted_at": predicted_at,
            }
            predictions.append(row)
            day_rows.append(row)
        day_triggers = _consecutive_danger_triggers(day_rows)
        if local_day < today and not (
            DEMO_EVENT_MIN_PER_DAY <= len(day_triggers) <= DEMO_EVENT_MAX_PER_DAY
        ):
            raise DemoSeedError("demo_event_count_invalid")
        alert_candidates.extend(day_triggers[:DEMO_EVENT_MAX_PER_DAY])

    alerts: list[dict[str, Any]] = []
    for alert_index, prediction in enumerate(alert_candidates):
        alert_id = stable_id("alert", alert_index)
        alerts.append({
            "id": alert_id, "patient_id": DEMO_PATIENT_ID,
            "trigger_prediction_id": prediction["id"],
            "trigger_reason": _json({
                "alertLevel": "required", "alertAction": "required_intervention",
                "triggerReason": "three_consecutive_danger", "demo": True,
                "seed": DEMO_SEED,
            }),
            "triggered_at": prediction["predicted_at"], "notified_at": prediction["predicted_at"],
        })

    sessions: list[dict[str, Any]] = []
    messages: list[dict[str, Any]] = []
    assessments: list[dict[str, Any]] = []
    interventions: list[dict[str, Any]] = []
    inferences: list[dict[str, Any]] = []
    reports: list[dict[str, Any]] = []
    dialogue_pairs = (
        ("식당 마감 시간이 가까워지니 술 생각이 자꾸 나네요.",
         "마감 무렵 긴장이 커지는군요. 지금 가장 부담되는 부분을 편하게 말씀해 주세요."),
        ("오늘 손님 응대가 힘들어서 퇴근하면 한잔하고 싶은 마음이 커졌어요.",
         "힘든 하루 뒤에 익숙한 방식이 떠오른 것 같아요. 잠시 시간을 두는 방법은 어떨까요?"),
        ("집사람에게 걱정을 끼치고 싶지는 않아요.",
         "걱정을 줄이고 싶은 마음이 중요하게 느껴져요. 지금 가능한 작은 선택부터 살펴봐도 좋습니다."),
    )
    prediction_by_id = {row["id"]: row for row in predictions}
    for session_index, alert in enumerate(alerts):
        session_id = stable_id("session", session_index)
        started = alert["triggered_at"] + timedelta(minutes=1)
        ended = started + timedelta(minutes=8)
        dialogue_state = {
            "phase": "free_dialogue",
            "questionLedger": ["current_context", "coping_preference"],
            "demo": True, "fictional": True, "persona": "VP-012",
        }
        sessions.append({
            "id": session_id, "patient_id": DEMO_PATIENT_ID,
            "trigger_alert_id": alert["id"], "started_at": started,
            "ended_at": ended, "created_at": started, "updated_at": ended,
            "dialogue_state": _encrypt(
                keyring, table="sessions", column="dialogue_state_encrypted",
                record_id=session_id, value=dialogue_state,
            ),
            "dialogue_key": keyring.current_key_id,
        })
        message_ids: list[UUID] = []
        for pair_index, (user_text, assistant_text) in enumerate(dialogue_pairs):
            for role_offset, (role, content) in enumerate((
                ("user", user_text), ("assistant", assistant_text),
            )):
                sequence = pair_index * 2 + role_offset + 1
                message_id = stable_id("message", session_index * 6 + sequence)
                message_ids.append(message_id)
                messages.append({
                    "id": message_id, "session_id": session_id,
                    "sequence_no": sequence, "role": role,
                    "content": _encrypt(
                        keyring, table="messages", column="content_encrypted",
                        record_id=message_id, value=content,
                    ),
                    "key_version": keyring.current_key_id,
                    "source_agent": "dialogue" if role == "assistant" else None,
                    "metadata": _json({
                        "demo": True, "fictional": True, "inputModality": "text",
                    }),
                    "created_at": started + timedelta(seconds=sequence * 45),
                })
        # Event-linked AUQ values vary across dates and events while remaining
        # deterministic, non-diagnostic, and valid on the 0..48 dashboard axis.
        trigger_prediction = prediction_by_id[alert["trigger_prediction_id"]]
        responses = _demo_auq_responses(
            session_index,
            triggered_at=alert["triggered_at"],
            probability=float(trigger_prediction["continuous_value"]),
        )
        assessment_id = stable_id("assessment", session_index)
        score = sum(responses)
        assessments.append({
            "id": assessment_id, "session_id": session_id,
            "answers": _encrypt(
                keyring, table="craving_assessments", column="answers_encrypted",
                record_id=assessment_id, value={
                    "responses": responses, "scoredItems": responses,
                    "rawTotalScore": score, "demo": True, "fictional": True,
                },
            ),
            "key_version": keyring.current_key_id, "score": score,
            "metadata": _json({
                "adaptation": "ko-research",
                "demo": True,
                "scoreProfile": "vp012-contextual-v2",
            }),
            "completed_at": started + timedelta(seconds=30),
        })
        intervention_ids: list[UUID] = []
        if session_index % 2 == 0:
            intervention_id = stable_id("intervention", session_index)
            intervention_ids.append(intervention_id)
            interventions.append({
                "id": intervention_id, "session_id": session_id,
                "type": "attention_shift",
                "basis": _encrypt(
                    keyring, table="interventions", column="selection_basis_encrypted",
                    record_id=intervention_id, value={
                        "reason": "사용자가 잠시 시간을 두는 방법을 선택함", "demo": True,
                    },
                ),
                "content": _encrypt(
                    keyring, table="interventions", column="content_encrypted",
                    record_id=intervention_id,
                    value={"text": "마감 정리를 하며 10분 동안 다른 행동에 집중해 보기"},
                ),
                "key_version": keyring.current_key_id,
                "evidence_refs": _json({"messageIds": [str(value) for value in message_ids]}),
                "created_at": started + timedelta(minutes=4),
            })
        inference_id = stable_id("inference", session_index)
        trigger_prediction_id = alert["trigger_prediction_id"]
        evidence = {
            "eventKey": f"demo-vp012-session-{session_index}",
            "predictionId": str(trigger_prediction_id),
            "assessmentIds": [str(assessment_id)],
            "messageIds": [str(value) for value in message_ids],
            "interventionIds": [str(value) for value in intervention_ids],
            "demo": True,
        }
        inferences.append({
            "id": inference_id, "patient_id": DEMO_PATIENT_ID,
            "session_id": session_id,
            "trigger_prediction_id": trigger_prediction_id,
            "evidence_refs": _json(evidence),
            "payload": _encrypt(
                keyring, table="state_inferences", column="payload_encrypted",
                record_id=inference_id,
                value={"state": "high", "basis": evidence, "causalClaim": False},
            ),
            "summary": _encrypt(
                keyring, table="state_inferences", column="summary_encrypted",
                record_id=inference_id,
                value="식당 마감 무렵 기록된 갈망 단계와 자기설문 응답을 함께 정리했습니다.",
            ),
            "key_version": keyring.current_key_id, "created_at": ended,
        })
        report_id = stable_id("report", session_index)
        reports.append({
            "id": report_id, "session_id": session_id,
            "content": _encrypt(
                keyring, table="session_reports", column="content_encrypted",
                record_id=report_id, value={
                    "title": "가상 데모 세션 기록",
                    "summary": "마감 시간대의 기록과 대화 내용을 기술적으로 정리했습니다.",
                    "disclosure": "진단 또는 치료 효과를 의미하지 않습니다.",
                },
            ),
            "key_version": keyring.current_key_id,
            "evidence_refs": _json(evidence), "created_at": ended,
        })

    prepared = PreparedDemo(
        anchor_date=anchor_date, start_date=start_date, patient=patient,
        consent=consent, predictions=predictions, alerts=alerts,
        sessions=sessions, messages=messages, assessments=assessments,
        interventions=interventions, inferences=inferences, reports=reports,
    )
    _validate(prepared, now_utc)
    return prepared


def _validate(prepared: PreparedDemo, now_utc: datetime) -> None:
    local_dates = {
        row["predicted_at"].astimezone(SEOUL).date() for row in prepared.predictions
    }
    if (
        len(local_dates) != DEMO_DAYS
        or min(local_dates) != prepared.start_date
        or max(local_dates) != prepared.anchor_date
        or any(row["predicted_at"] > now_utc for row in prepared.predictions)
    ):
        raise DemoSeedError("demo_prediction_date_contract_invalid")
    stages = {_stage(float(row["continuous_value"])) for row in prepared.predictions}
    if stages != {"low", "observe", "caution", "high"}:
        raise DemoSeedError("demo_stage_distribution_invalid")
    stage_weights: Counter[str] = Counter()
    for row in prepared.predictions:
        metadata = json.loads(row["output_metadata"])
        stage_weights[metadata["stage"]] += int(metadata["demoDisplayWeight"])
    total_stage_weight = sum(stage_weights.values())
    low_ratio = stage_weights["low"] / total_stage_weight
    if not 0.20 <= low_ratio <= 0.30:
        raise DemoSeedError("demo_stable_stage_balance_invalid")
    monthly: list[float] = []
    for offset in range(0, DEMO_DAYS, 30):
        block_start = prepared.start_date + timedelta(days=offset)
        block_end = block_start + timedelta(days=30)
        block = [
            row for row in prepared.predictions
            if block_start <= row["predicted_at"].astimezone(SEOUL).date() < block_end
        ]
        monthly.append(sum(float(row["continuous_value"]) for row in block) / len(block))
    if monthly == sorted(monthly) or monthly == sorted(monthly, reverse=True):
        raise DemoSeedError("demo_monotonic_distribution_invalid")
    ordered_predictions = sorted(
        prepared.predictions, key=lambda row: (row["predicted_at"], row["id"]),
    )
    prediction_index = {row["id"]: index for index, row in enumerate(ordered_predictions)}
    alert_times = []
    for alert in prepared.alerts:
        index = prediction_index[alert["trigger_prediction_id"]]
        run = ordered_predictions[index - 2:index + 1]
        if (
            len(run) != 3
            or any(row["continuous_value"] < 0.75 for row in run)
            or any(
                later["predicted_at"] - earlier["predicted_at"] > timedelta(seconds=20)
                for earlier, later in zip(run, run[1:])
            )
        ):
            raise DemoSeedError("demo_alert_prediction_invalid")
        alert_times.append(alert["triggered_at"])
    if any(
        later - earlier < timedelta(minutes=15)
        for earlier, later in zip(alert_times, alert_times[1:])
    ):
        raise DemoSeedError("demo_alert_spacing_invalid")
    alert_counts = Counter(
        alert["triggered_at"].astimezone(SEOUL).date()
        for alert in prepared.alerts
    )
    today = now_utc.astimezone(SEOUL).date()
    if any(count > DEMO_EVENT_MAX_PER_DAY for count in alert_counts.values()):
        raise DemoSeedError("demo_event_daily_cap_invalid")
    if any(
        not DEMO_EVENT_MIN_PER_DAY <= alert_counts[local_day] <= DEMO_EVENT_MAX_PER_DAY
        for local_day in local_dates
        if local_day < today
    ):
        raise DemoSeedError("demo_event_daily_count_invalid")
    if len(prepared.assessments) != len(prepared.alerts):
        raise DemoSeedError("demo_event_auq_link_invalid")
    if any(not 0 <= row["score"] <= 48 for row in prepared.assessments):
        raise DemoSeedError("demo_auq_contract_invalid")


_IDENTITY_SQL = text("""/* demo:identity */ SELECT u.id,u.email,u.role,u.status,
 p.pseudonymous_id FROM users u LEFT JOIN patient_profiles p ON p.user_id=u.id
 WHERE u.id=:patient_id OR lower(u.email)=lower(:email)
 ORDER BY (u.id=:patient_id) DESC""")
_MODEL_SQL = text("""/* demo:model */ SELECT id FROM model_versions
 WHERE component='craving_model' AND is_active
 AND inference_task='binary_classification' LIMIT 1""")
_MANIFEST_SQL = text("""/* demo:manifest */ SELECT action,metadata FROM audit_logs
 WHERE action IN ('demo.vp012.armed','demo.vp012.started')
 AND resource_id=:patient_id
 ORDER BY created_at DESC,id DESC LIMIT 1""")
_LOGOUT_OWNER_SQL = text("""/* demo:logout-owner */ SELECT u.id,u.email
 FROM auth_sessions a JOIN users u ON u.id=a.user_id
 WHERE a.refresh_token_hash=:refresh_hash FOR UPDATE""")
_FILE_PATHS_SQL = text("""/* demo:file-paths */
 SELECT 'sensor' AS kind,storage_uri FROM sensor_recordings
 WHERE patient_id=:patient_id
 UNION ALL
 SELECT 'rppg' AS kind,storage_uri FROM rppg_captures
 WHERE patient_id=:patient_id""")
_COUNTS_SQL = text("""
    /* demo:counts */
    SELECT
      (SELECT count(*) FROM craving_predictions WHERE patient_id=:patient_id) AS craving_predictions,
      (SELECT count(*) FROM craving_alerts WHERE patient_id=:patient_id) AS craving_alerts,
      (SELECT count(*) FROM sessions WHERE patient_id=:patient_id) AS sessions,
      (SELECT count(*) FROM messages m JOIN sessions s ON s.id=m.session_id WHERE s.patient_id=:patient_id) AS messages,
      (SELECT count(*) FROM craving_assessments a JOIN sessions s ON s.id=a.session_id WHERE s.patient_id=:patient_id) AS craving_assessments,
      (SELECT count(*) FROM interventions i JOIN sessions s ON s.id=i.session_id WHERE s.patient_id=:patient_id) AS interventions,
      (SELECT count(*) FROM state_inferences WHERE patient_id=:patient_id) AS state_inferences,
      (SELECT count(*) FROM session_reports r JOIN sessions s ON s.id=r.session_id WHERE s.patient_id=:patient_id) AS session_reports,
      (SELECT (min(predicted_at) AT TIME ZONE 'Asia/Seoul')::date
         FROM craving_predictions WHERE patient_id=:patient_id) AS first_prediction,
      (SELECT (max(predicted_at) AT TIME ZONE 'Asia/Seoul')::date
         FROM craving_predictions WHERE patient_id=:patient_id) AS last_prediction
""")


class Vp012DemoSeeder:
    def __init__(
        self,
        engine: AsyncEngine,
        keyring: AesGcmKeyring,
        *,
        sensor_storage: Any | None = None,
        rppg_storage: Any | None = None,
    ) -> None:
        self.engine = engine
        self.keyring = keyring
        self.sensor_storage = sensor_storage
        self.rppg_storage = rppg_storage

    async def dry_run(self, anchor_date: date, password: str) -> DemoReport:
        prepared = prepare_demo(anchor_date, self.keyring)
        async with self.engine.connect() as connection:
            await self._preflight(connection, prepared)
        return self._planned_report("dry-run", prepared)

    async def seed(self, anchor_date: date, password: str) -> DemoReport:
        prepared = prepare_demo(anchor_date, self.keyring)
        async with self.engine.begin() as connection:
            await self._lock(connection)
            model_id, identity, manifest = await self._preflight(connection, prepared)
            if identity is None:
                await self._insert_identity(connection, prepared, password)
                await self._audit_state(connection, "armed", {"fictional": True})
            await self._insert_dataset(connection, prepared, model_id)
            if manifest is None or manifest["action"] != "demo.vp012.started":
                await self._audit_state(connection, "started", self._manifest(prepared))
            return await self._status(connection, mode="confirmed")

    async def provision(
        self, password: str, *, now: datetime | None = None,
    ) -> DemoReport:
        login_now = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
        prepared = prepare_demo(login_now.astimezone(SEOUL).date(), self.keyring, now=login_now)
        async with self.engine.begin() as connection:
            await self._lock(connection)
            identity = await self._identity(connection)
            manifest = await self._manifest_row(connection)
            if identity is not None:
                self._validate_identity(identity)
                if manifest and manifest["action"] == "demo.vp012.armed":
                    return await self._status(connection, mode="provisioned")
                raise DemoSeedError("demo_identity_conflict")
            await self._insert_identity(connection, prepared, password)
            await self._audit_state(connection, "armed", {"fictional": True})
            return await self._status(connection, mode="provisioned")

    async def start_for_login(
        self,
        *,
        user_id: UUID,
        email: str,
        now: datetime | None = None,
    ) -> None:
        if user_id != DEMO_PATIENT_ID or email.lower() != DEMO_EMAIL:
            return
        login_now = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
        async with self.engine.begin() as connection:
            await self._lock(connection)
            identity = await self._identity(connection)
            if identity is None:
                raise DemoSeedError("demo_identity_conflict")
            self._validate_identity(identity)
            manifest = await self._manifest_row(connection)
            if manifest and manifest["action"] == "demo.vp012.started":
                return
            if manifest is None or manifest["action"] != "demo.vp012.armed":
                raise DemoSeedError("demo_state_invalid")
            model_id = await connection.scalar(_MODEL_SQL)
            if model_id is None:
                raise DemoSeedError("demo_model_unavailable")
            prepared = prepare_demo(
                login_now.astimezone(SEOUL).date(), self.keyring, now=login_now,
            )
            await self._insert_dataset(connection, prepared, UUID(str(model_id)))
            await self._audit_state(connection, "started", self._manifest(prepared))

    async def delete_for_logout(self, refresh_hash: str) -> bool:
        async with self.engine.begin() as connection:
            await self._lock(connection)
            owner = (
                await connection.execute(
                    _LOGOUT_OWNER_SQL, {"refresh_hash": refresh_hash},
                )
            ).mappings().one_or_none()
            if owner is None:
                return False
            if UUID(str(owner["id"])) != DEMO_PATIENT_ID or owner["email"].lower() != DEMO_EMAIL:
                return False
            identity = await self._identity(connection)
            if identity is None:
                return False
            self._validate_identity(identity)
            manifest = await self._manifest_row(connection)
            if manifest is None or manifest["action"] != "demo.vp012.started":
                return False
            await self._delete_locked(connection)
            return True

    async def status(self) -> DemoReport:
        async with self.engine.connect() as connection:
            return await self._status(connection, mode="status")

    async def delete(self) -> DemoReport:
        async with self.engine.begin() as connection:
            await self._lock(connection)
            identity = await self._identity(connection)
            if identity is None:
                return self._empty_report("deleted")
            self._validate_identity(identity)
            return await self._delete_locked(connection)

    async def _preflight(
        self, connection: Any, prepared: PreparedDemo,
    ) -> tuple[UUID, dict[str, Any] | None, dict[str, Any] | None]:
        model_id = await connection.scalar(_MODEL_SQL)
        if model_id is None:
            raise DemoSeedError("demo_model_unavailable")
        identity = await self._identity(connection)
        manifest_value = await self._manifest_row(connection)
        if identity is not None:
            self._validate_identity(identity)
            if manifest_value is None:
                raise DemoSeedError("demo_identity_conflict")
            metadata = manifest_value["metadata"]
            if manifest_value["action"] == "demo.vp012.started" and (
                metadata.get("seed") != DEMO_SEED
                or metadata.get("anchorDate") != prepared.anchor_date.isoformat()
            ):
                raise DemoSeedError("demo_manifest_incompatible")
        else:
            # A previous delete intentionally leaves its system audit trail.
            # A recreated account needs a fresh manifest for its new anchor.
            manifest_value = None
        return UUID(str(model_id)), identity, manifest_value

    async def _manifest_row(self, connection: Any) -> dict[str, Any] | None:
        row = (
            await connection.execute(
                _MANIFEST_SQL, {"patient_id": DEMO_PATIENT_ID},
            )
        ).mappings().one_or_none()
        return (
            {"action": row["action"], "metadata": dict(row["metadata"])}
            if row else None
        )

    @staticmethod
    async def _lock(connection: Any) -> None:
        await connection.execute(
            text("SELECT pg_advisory_xact_lock(hashtext(:key))"),
            {"key": ADVISORY_LOCK_KEY},
        )

    async def _insert_identity(
        self, connection: Any, prepared: PreparedDemo, password: str,
    ) -> None:
        await connection.execute(text("""/* demo:insert-user */ INSERT INTO users
            (id,email,password_hash,role,status)
            VALUES (:id,:email,:password_hash,'patient','active')"""), {
            "id": DEMO_PATIENT_ID, "email": DEMO_EMAIL,
            "password_hash": hash_password(password),
        })
        await connection.execute(text("""/* demo:insert-profile */ INSERT INTO patient_profiles
              (user_id,name_encrypted,birth_year,gender,pseudonymous_id,encryption_key_version)
            VALUES (:id,:name_encrypted,:birth_year,:gender,:pseudonymous_id,:key)"""), {
            **prepared.patient, "key": self.keyring.current_key_id,
        })
        await connection.execute(text("""/* demo:insert-consent */ INSERT INTO consent_snapshots
              (id,user_id,tos,privacy,sensitive,biosignal,voice,ai_analysis,notification,
               report_generation,camera_rppg,face_video_retention,tos_version,
               privacy_version,consent_form_version,collected_at)
            VALUES (:id,:user_id,:tos,:privacy,:sensitive,:biosignal,:voice,:ai_analysis,
               :notification,:report_generation,:camera_rppg,:face_video_retention,
               :tos_version,:privacy_version,:consent_form_version,:collected_at)"""),
            prepared.consent)

    async def _audit_state(
        self, connection: Any, state: str, metadata: dict[str, Any],
    ) -> None:
        await connection.execute(text("""/* demo:insert-audit */ INSERT INTO audit_logs
              (actor_id,actor_role,action,resource_type,resource_id,metadata)
            VALUES (NULL,'system',:action,'demo_patient',:patient_id,
                    CAST(:metadata AS jsonb))"""), {
            "action": f"demo.vp012.{state}",
            "patient_id": DEMO_PATIENT_ID,
            "metadata": _json({"seed": DEMO_SEED, **metadata}),
        })

    @staticmethod
    def _manifest(prepared: PreparedDemo) -> dict[str, Any]:
        return {
            "anchorDate": prepared.anchor_date.isoformat(),
            "startDate": prepared.start_date.isoformat(),
            "fictional": True,
            "counts": prepared.counts,
        }

    async def _delete_locked(self, connection: Any) -> DemoReport:
        before = await self._status(connection, mode="deleted")
        paths = (
            await connection.execute(
                _FILE_PATHS_SQL, {"patient_id": DEMO_PATIENT_ID},
            )
        ).mappings().all()
        for statement in (
            "DELETE FROM memory_snapshots WHERE patient_id=:patient_id",
            "DELETE FROM state_inferences WHERE patient_id=:patient_id",
            "DELETE FROM sessions WHERE patient_id=:patient_id",
            "DELETE FROM craving_alerts WHERE patient_id=:patient_id",
            "DELETE FROM craving_predictions WHERE patient_id=:patient_id",
            """DELETE FROM rppg_analysis_jobs WHERE capture_id IN
               (SELECT id FROM rppg_captures WHERE patient_id=:patient_id)""",
            "DELETE FROM rppg_captures WHERE patient_id=:patient_id",
            "DELETE FROM sensor_recordings WHERE patient_id=:patient_id",
            "DELETE FROM auth_sessions WHERE user_id=:patient_id",
            "DELETE FROM consent_snapshots WHERE user_id=:patient_id",
            "DELETE FROM patient_profiles WHERE user_id=:patient_id",
            """DELETE FROM audit_logs
               WHERE actor_id=:patient_id OR resource_id=:patient_id""",
            "DELETE FROM users WHERE id=:patient_id",
        ):
            await connection.execute(
                text("/* demo:delete */ " + statement),
                {"patient_id": DEMO_PATIENT_ID},
            )
        for row in paths:
            storage = self.sensor_storage if row["kind"] == "sensor" else self.rppg_storage
            if storage is None:
                raise DemoSeedError("demo_storage_unavailable")
            storage.delete(row["storage_uri"])
        await connection.execute(text("""/* demo:delete-audit */ INSERT INTO audit_logs
              (actor_id,actor_role,action,resource_type,resource_id,metadata)
            VALUES (NULL,'system','demo.vp012.deleted','demo_patient',:patient_id,
                    CAST(:metadata AS jsonb))"""), {
            "patient_id": DEMO_PATIENT_ID,
            "metadata": _json({"seed": DEMO_SEED, "deletedCounts": before.table_counts}),
        })
        return before

    async def _identity(self, connection: Any) -> dict[str, Any] | None:
        rows = (
            await connection.execute(
                _IDENTITY_SQL, {"patient_id": DEMO_PATIENT_ID, "email": DEMO_EMAIL},
            )
        ).mappings().all()
        if len(rows) > 1:
            raise DemoSeedError("demo_identity_conflict")
        return dict(rows[0]) if rows else None

    @staticmethod
    def _validate_identity(identity: dict[str, Any]) -> None:
        if (
            UUID(str(identity["id"])) != DEMO_PATIENT_ID
            or str(identity["email"]).lower() != DEMO_EMAIL
            or identity["role"] != "patient"
            or identity.get("pseudonymous_id") != "DEMO-VP012-FICTIONAL"
        ):
            raise DemoSeedError("demo_identity_conflict")

    async def _insert_dataset(
        self, connection: Any, prepared: PreparedDemo, model_id: UUID,
    ) -> None:
        await connection.execute(text("""/* demo:insert-predictions */ INSERT INTO craving_predictions
              (id,patient_id,window_started_at,window_ended_at,input_modalities,model_version_id,
               predicted_class_index,predicted_class_code,predicted_class_probability,
               class_probabilities,continuous_value,continuous_scale_min,continuous_scale_max,
               signal_quality,motion_context,quality_gate_passed,output_metadata,predicted_at)
            VALUES (:id,:patient_id,:window_started_at,:window_ended_at,:input_modalities,:model_id,
               :class_index,:class_code,:confidence,CAST(:probabilities AS jsonb),:continuous_value,
               0,1,CAST(:signal_quality AS jsonb),'{}'::jsonb,true,
               CAST(:output_metadata AS jsonb),:predicted_at)
            ON CONFLICT (id) DO UPDATE
              SET model_version_id=EXCLUDED.model_version_id"""),
            [{**row, "model_id": model_id} for row in prepared.predictions])
        await connection.execute(text("""/* demo:insert-alerts */ INSERT INTO craving_alerts
              (id,patient_id,trigger_prediction_id,rule_code,rule_version,trigger_reason,status,
               notification_channel,triggered_at,notified_at)
            VALUES (:id,:patient_id,:trigger_prediction_id,'required_intervention',
               'watch-danger-v1',CAST(:trigger_reason AS jsonb),'notified','demo',
               :triggered_at,:notified_at)
            ON CONFLICT (id) DO NOTHING"""), prepared.alerts)
        await connection.execute(text("""/* demo:insert-sessions */ INSERT INTO sessions
              (id,patient_id,trigger_alert_id,session_type,status,started_at,ended_at,
               completion_reason,created_at,updated_at,interaction_phase,
               dialogue_state_encrypted,dialogue_state_key_version)
            VALUES (:id,:patient_id,:trigger_alert_id,'alert_checkin','completed',:started_at,
               :ended_at,'normal',:created_at,:updated_at,'completed',:dialogue_state,:dialogue_key)
            ON CONFLICT (id) DO NOTHING"""), prepared.sessions)
        if prepared.messages:
            await connection.execute(text("""/* demo:insert-messages */ INSERT INTO messages
                  (id,session_id,sequence_no,role,content_encrypted,encryption_key_version,
                   modality,source_agent,generation_metadata,created_at)
                VALUES (:id,:session_id,:sequence_no,:role,:content,:key_version,'text',
                   :source_agent,CAST(:metadata AS jsonb),:created_at)
                ON CONFLICT (id) DO NOTHING"""), prepared.messages)
        if prepared.assessments:
            await connection.execute(text("""/* demo:insert-assessments */ INSERT INTO craving_assessments
                  (id,session_id,instrument_code,instrument_version,phase,attempt_no,
                   answers_encrypted,encryption_key_version,raw_score,scale_min,scale_max,
                   scoring_metadata,completed_at)
                VALUES (:id,:session_id,'AUQ','2.0','pre_intervention',1,:answers,:key_version,
                   :score,0,48,CAST(:metadata AS jsonb),:completed_at)
                ON CONFLICT (id) DO NOTHING"""), prepared.assessments)
        if prepared.interventions:
            await connection.execute(text("""/* demo:insert-interventions */ INSERT INTO interventions
                  (id,session_id,intervention_type,selection_basis_encrypted,content_encrypted,
                   status,encryption_key_version,presentation_order,evidence_refs,created_at)
                VALUES (:id,:session_id,:type,:basis,:content,'delivered',:key_version,1,
                   CAST(:evidence_refs AS jsonb),:created_at)
                ON CONFLICT (id) DO NOTHING"""), prepared.interventions)
        if prepared.inferences:
            await connection.execute(text("""/* demo:insert-inferences */ INSERT INTO state_inferences
                  (id,patient_id,session_id,trigger_prediction_id,inference_scope,state_class,
                   confidence,evidence_refs,payload_encrypted,summary_encrypted,
                   encryption_key_version,rule_version,summary_status,created_at)
                VALUES (:id,:patient_id,:session_id,:trigger_prediction_id,'realtime','high',0.8,
                   CAST(:evidence_refs AS jsonb),:payload,:summary,:key_version,
                   'state-rule-v1','ready',:created_at)
                ON CONFLICT (id) DO NOTHING"""), prepared.inferences)
        if prepared.reports:
            await connection.execute(text("""/* demo:insert-reports */ INSERT INTO session_reports
                  (id,session_id,version,status,content_encrypted,encryption_key_version,
                   evidence_refs,created_at,generated_at,updated_at)
                VALUES (:id,:session_id,1,'ready',:content,:key_version,
                   CAST(:evidence_refs AS jsonb),:created_at,:created_at,:created_at)
                ON CONFLICT (id) DO NOTHING"""), prepared.reports)

    async def _status(self, connection: Any, *, mode: str) -> DemoReport:
        identity = await self._identity(connection)
        if identity is None:
            return self._empty_report(mode)
        self._validate_identity(identity)
        row = (
            await connection.execute(
                _COUNTS_SQL, {"patient_id": DEMO_PATIENT_ID},
            )
        ).mappings().one()
        counts = {
            key: int(row[key]) for key in (
                "craving_predictions", "craving_alerts", "sessions", "messages",
                "craving_assessments", "interventions", "state_inferences",
                "session_reports",
            )
        }
        return DemoReport(
            mode=mode, exists=True, patient_id=str(DEMO_PATIENT_ID), email=DEMO_EMAIL,
            start_date=row["first_prediction"].isoformat() if row["first_prediction"] else None,
            end_date=row["last_prediction"].isoformat() if row["last_prediction"] else None,
            table_counts=counts,
        )

    @staticmethod
    def _planned_report(mode: str, prepared: PreparedDemo) -> DemoReport:
        return DemoReport(
            mode=mode, exists=False, patient_id=str(DEMO_PATIENT_ID), email=DEMO_EMAIL,
            start_date=prepared.start_date.isoformat(),
            end_date=prepared.anchor_date.isoformat(),
            table_counts=prepared.counts,
        )

    @staticmethod
    def _empty_report(mode: str) -> DemoReport:
        return DemoReport(
            mode=mode, exists=False, patient_id=str(DEMO_PATIENT_ID), email=DEMO_EMAIL,
            start_date=None, end_date=None,
            table_counts={
                name: 0 for name in (
                    "craving_predictions", "craving_alerts", "sessions", "messages",
                    "craving_assessments", "interventions", "state_inferences",
                    "session_reports",
                )
            },
        )


def _database_url() -> str:
    value = os.getenv("DATABASE_URL", "").strip()
    if value.startswith("postgresql://"):
        value = "postgresql+asyncpg://" + value.removeprefix("postgresql://")
    if not value.startswith("postgresql+asyncpg://"):
        raise RuntimeError("DATABASE_URL must use PostgreSQL")
    return value


def _keyring() -> AesGcmKeyring:
    return AesGcmKeyring.from_config(
        os.getenv("DATA_ENCRYPTION_KEYS_B64", ""),
        os.getenv("DATA_ENCRYPTION_CURRENT_KEY_ID", ""),
    )


def _password() -> str:
    value = os.getenv("DEMO_PATIENT_PASSWORD", "")
    if len(value) < 12 or len(value) > 1024:
        raise DemoSeedError("demo_password_invalid")
    return value


async def _run(
    *,
    mode: str,
    anchor_date: date,
    engine_factory: Callable[..., AsyncEngine],
) -> DemoReport:
    password = _password() if mode in {"dry-run", "provision", "seed"} else ""
    engine = engine_factory(_database_url(), pool_pre_ping=True)
    try:
        keys = _keyring()
        sensor_storage = rppg_storage = None
        if os.getenv("SENSOR_STORAGE_ROOT"):
            from app.storage.sensor import EncryptedSensorStorage
            sensor_storage = EncryptedSensorStorage(
                Path(os.environ["SENSOR_STORAGE_ROOT"]), keys,
            )
        if os.getenv("RPPG_STORAGE_ROOT"):
            from app.storage.rppg import EncryptedRppgStorage
            rppg_storage = EncryptedRppgStorage(
                Path(os.environ["RPPG_STORAGE_ROOT"]),
                Path(os.getenv("RPPG_TMPFS_ROOT", "/dev/shm/neurotruth-rppg")),
                keys,
            )
        seeder = Vp012DemoSeeder(
            engine, keys,
            sensor_storage=sensor_storage,
            rppg_storage=rppg_storage,
        )
        if mode == "dry-run":
            return await seeder.dry_run(anchor_date, password)
        if mode == "provision":
            return await seeder.provision(password)
        if mode == "seed":
            return await seeder.seed(anchor_date, password)
        if mode == "status":
            return await seeder.status()
        return await seeder.delete()
    finally:
        await engine.dispose()


def run_cli(
    argv: Sequence[str] | None = None,
    *,
    engine_factory: Callable[..., AsyncEngine] = create_async_engine,
) -> int:
    parser = argparse.ArgumentParser(
        description="Manage the fictional VP-012 120-day demo account.",
    )
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--dry-run", action="store_true")
    group.add_argument("--provision-confirm", metavar="TOKEN")
    group.add_argument("--confirm", metavar="TOKEN")
    group.add_argument("--status", action="store_true")
    group.add_argument("--delete-confirm", metavar="TOKEN")
    parser.add_argument("--anchor-date", type=date.fromisoformat)
    args = parser.parse_args(argv)
    if args.confirm is not None and args.confirm != SEED_CONFIRMATION:
        print("confirmation token did not match; no database connection was made", file=sys.stderr)
        return 2
    if (
        args.provision_confirm is not None
        and args.provision_confirm != PROVISION_CONFIRMATION
    ):
        print("confirmation token did not match; no database connection was made", file=sys.stderr)
        return 2
    if args.delete_confirm is not None and args.delete_confirm != DELETE_CONFIRMATION:
        print("confirmation token did not match; no database connection was made", file=sys.stderr)
        return 2
    mode = (
        "provision" if args.provision_confirm else "seed" if args.confirm
        else "delete" if args.delete_confirm
        else "status" if args.status else "dry-run"
    )
    anchor_date = args.anchor_date or datetime.now(SEOUL).date()
    try:
        report = asyncio.run(_run(
            mode=mode, anchor_date=anchor_date, engine_factory=engine_factory,
        ))
    except Exception:
        print(
            _json({"status": "failed", "errorCode": "vp012_demo_operation_failed"}),
            file=sys.stderr,
        )
        return 1
    print(_json({"status": "ok", **asdict(report)}))
    return 0


def main() -> None:
    raise SystemExit(run_cli())


if __name__ == "__main__":
    main()
