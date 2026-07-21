from __future__ import annotations

import json
import math
from datetime import date, datetime, time, timedelta, timezone
from typing import Any
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from app.core.security.crypto import AesGcmKeyring, aad_for

from app.repositories.postgres import V25Repository
from app.storage.sensor import EncryptedSensorStorage


RANGES = {"24h": timedelta(hours=24), "7d": timedelta(days=7), "30d": timedelta(days=30)}
PROBABILITY_RANGES = {
    "1h": (timedelta(hours=1), 10, 360),
    "10m": (timedelta(minutes=10), 1, 600),
    "24h": (timedelta(hours=24), 60, 1440),
    "7d": (timedelta(days=7), 600, 1008),
    "30d": (timedelta(days=30), 1800, 1440),
}


class DashboardRangeError(ValueError): pass
class DashboardTimezoneError(ValueError): pass
class PpgPreviewNotFound(ValueError): pass
class DashboardUnavailable(RuntimeError): pass


def class_from_schema(code: Any, index: Any, schema: Any, quality: bool = True) -> str:
    if not quality:
        return "unknown"
    direct = str(code or "").lower()
    if direct in {"low", "mid", "high"}:
        return direct
    classes = schema.get("classes") if isinstance(schema, dict) else None
    if isinstance(classes, list):
        for position, item in enumerate(classes):
            if isinstance(item, dict) and item.get("index", position) == index:
                mapped = str(item.get("code") or "").lower()
                if mapped in {"low", "mid", "high"}:
                    return mapped
    return "unknown"


class DashboardService:
    def __init__(
        self,
        repository: V25Repository,
        keyring: AesGcmKeyring,
        storage: EncryptedSensorStorage,
        *,
        state_summary_ai_enabled: bool = False,
    ) -> None:
        self.repository, self.keyring, self.storage = repository, keyring, storage
        self.state_summary_ai_enabled = state_summary_ai_enabled

    async def dashboard(self, patient_id: UUID, range_code: str, *, admin: bool = False) -> dict[str, Any]:
        if range_code not in RANGES:
            raise DashboardRangeError("Unsupported dashboard range")
        end = datetime.now(timezone.utc)
        start = end - RANGES[range_code]
        rows = await self.repository.dashboard_rows(patient_id, start)
        predictions = [{
            "predictionId": str(row["id"]),
            "at": row["predicted_at"].isoformat(),
            "class": class_from_schema(row.get("predicted_class_code"), row.get("predicted_class_index"), row.get("output_schema"), bool(row.get("quality_gate_passed", True))),
            "probability": float(row["predicted_class_probability"]) if row.get("predicted_class_probability") is not None else None,
            "cravingProbability": float(row["continuous_value"]) if row.get("continuous_value") is not None else None,
            **({} if admin else {"ppgPreviewAvailable": bool(row.get("ppg_preview_available", row.get("sensor_recording_id") is not None))}),
        } for row in rows["predictions"]]
        assessments = [{
            "assessmentId": str(row["id"]), "sessionId": str(row["session_id"]),
            "at": row["completed_at"].isoformat(), "instrumentCode": row["instrument_code"],
            "rawScore": float(row["raw_score"]), "scaleMin": float(row["scale_min"]),
            "scaleMax": float(row["scale_max"]),
        } for row in rows["assessments"]]
        events: list[dict[str, Any]] = []
        for row in rows["predictions"]:
            events.append(self._event(row["id"], "detection", row["predicted_at"], prediction=row["id"], label="갈망 상태 추정"))
        for row in rows["alerts"]:
            events.append(self._event(row["id"], "notification", row.get("notified_at") or row["triggered_at"], prediction=row["trigger_prediction_id"], label="갈망 상승 가능성 알림"))
        for row in rows["assessments"]:
            events.append(self._event(row["id"], "auq", row["completed_at"], session=row["session_id"], label=f"{row['instrument_code']} 자기보고"))
        for row in rows["sessions"]:
            events.append(self._event(row["id"], "session_started", row.get("started_at") or row["created_at"], session=row["id"], label="대화 승인"))
            if row.get("ended_at"):
                events.append(self._event(f"{row['id']}:finished", "session_finished", row["ended_at"], session=row["id"], label="대화 종료"))
        for row in rows["interventions"]:
            events.append(self._event(row["id"], "intervention", row["created_at"], session=row["session_id"], label=row["intervention_type"]))
        events.sort(key=lambda item: (item["at"], item["eventId"]))
        realtime = next((row for row in reversed(rows["inferences"]) if row["inference_scope"] == "realtime"), None)
        longitudinal = next((row for row in reversed(rows["inferences"]) if row["inference_scope"] == "longitudinal"), None)
        return {
            "range": range_code, "from": start.isoformat(), "to": end.isoformat(),
            "predictions": predictions, "assessments": assessments, "events": events,
            "latestState": self._state(patient_id, realtime, admin),
            "longitudinalState": self._state(patient_id, longitudinal, admin),
            "reports": [{"sessionId": str(row["session_id"]), "reportId": str(row["id"]),
                         "status": row["status"], "updatedAt": row["updated_at"].isoformat()}
                        for row in rows["reports"]],
        }

    async def craving_probability_series(self, patient_id: UUID, range_code: str) -> dict[str, Any]:
        selected = PROBABILITY_RANGES.get(range_code)
        if selected is None:
            raise DashboardRangeError("Unsupported probability range")
        duration, bucket_seconds, max_points = selected
        end = datetime.now(timezone.utc)
        start = end - duration
        rows = await self.repository.craving_probability_rows(
            patient_id, start, end, bucket_seconds, max_points
        )
        points = []
        for row in rows:
            bucket_at = row["bucket_at"]
            if not start <= bucket_at <= end:
                continue
            value = float(row["average_probability"])
            if not math.isfinite(value):
                continue
            points.append({
                "at": bucket_at.isoformat(),
                "averageCravingProbability": round(min(1.0, max(0.0, value)), 6),
                "sampleCount": int(row["sample_count"]),
            })
        points.sort(key=lambda item: item["at"])
        points = points[-max_points:]
        return {
            "range": range_code,
            "from": start.isoformat(),
            "to": end.isoformat(),
            "bucketSeconds": bucket_seconds,
            "points": points,
        }

    async def craving_dashboard(
        self,
        patient_id: UUID,
        timezone_name: str,
        event_range: str,
        auq_range: str,
        *,
        now: datetime | None = None,
    ) -> dict[str, Any]:
        if event_range not in {"7d", "30d"} or auq_range not in {"today", "7d", "30d"}:
            raise DashboardRangeError("Unsupported craving dashboard range")
        try:
            local_zone = ZoneInfo(timezone_name)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise DashboardTimezoneError("Invalid IANA timezone") from exc

        generated_at = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
        local_today = generated_at.astimezone(local_zone).date()
        day_start_local = datetime.combine(local_today, time.min, tzinfo=local_zone)
        day_end_local = datetime.combine(local_today + timedelta(days=1), time.min, tzinfo=local_zone)
        event_days = 7 if event_range == "7d" else 30
        event_start_date = local_today - timedelta(days=event_days - 1)
        event_start_local = datetime.combine(event_start_date, time.min, tzinfo=local_zone)
        if auq_range == "today":
            auq_start_date = local_today
            auq_bucket_unit = "hour"
        else:
            auq_days = 7 if auq_range == "7d" else 30
            auq_start_date = local_today - timedelta(days=auq_days - 1)
            auq_bucket_unit = "day"
        auq_start_local = datetime.combine(auq_start_date, time.min, tzinfo=local_zone)

        rows = await self.repository.craving_dashboard_rows(
            patient_id,
            timezone_name,
            day_start_local.astimezone(timezone.utc),
            day_end_local.astimezone(timezone.utc),
            event_start_local.astimezone(timezone.utc),
            auq_start_local.astimezone(timezone.utc),
            auq_bucket_unit,
        )
        hourly_by_hour = {int(row["local_hour"]): row for row in rows["hourly"]}
        hourly_buckets = []
        for hour in range(24):
            row = hourly_by_hour.get(hour)
            stage_counts = {
                "low": int(row["low_count"]) if row else 0,
                "observe": int(row["observe_count"]) if row else 0,
                "caution": int(row["caution_count"]) if row else 0,
                "high": int(row["high_count"]) if row else 0,
            }
            hourly_buckets.append({
                "localStart": datetime.combine(
                    local_today, time(hour=hour), tzinfo=local_zone,
                ).isoformat(),
                "averageProbability": self._probability(row, "average_probability"),
                "minimumProbability": self._probability(row, "minimum_probability"),
                "maximumProbability": self._probability(row, "maximum_probability"),
                "sampleCount": int(row["sample_count"]) if row else 0,
                "stageCounts": stage_counts,
            })

        prediction_days = {
            self._as_date(row["local_date"]): int(row["prediction_count"])
            for row in rows["prediction_days"]
        }
        alert_days = {
            self._as_date(row["local_date"]): row for row in rows["alert_days"]
        }
        event_buckets = []
        for offset in range(event_days):
            local_date = event_start_date + timedelta(days=offset)
            row = alert_days.get(local_date)
            recommend = int(row["recommend_count"]) if row else 0
            required = int(row["required_count"]) if row else 0
            event_buckets.append({
                "localDate": local_date.isoformat(),
                "hasPredictionData": prediction_days.get(local_date, 0) > 0,
                "recommendCount": recommend,
                "requiredCount": required,
                "totalCount": recommend + required,
            })

        auq_rows = {row["bucket_key"]: row for row in rows["auq"]}
        auq_buckets = []
        if auq_bucket_unit == "hour":
            keyed = {int(key): value for key, value in auq_rows.items()}
            for hour in range(24):
                row = keyed.get(hour)
                auq_buckets.append({
                    "localStart": datetime.combine(
                        local_today, time(hour=hour), tzinfo=local_zone,
                    ).isoformat(),
                    "averageNormalizedScore": self._probability(
                        row, "average_normalized_score",
                    ),
                    "averageScore": self._auq_score(row),
                    "sampleCount": int(row["sample_count"]) if row else 0,
                })
        else:
            keyed = {self._as_date(key): value for key, value in auq_rows.items()}
            auq_days = 7 if auq_range == "7d" else 30
            for offset in range(auq_days):
                local_date = auq_start_date + timedelta(days=offset)
                row = keyed.get(local_date)
                auq_buckets.append({
                    "localDate": local_date.isoformat(),
                    "averageNormalizedScore": self._probability(
                        row, "average_normalized_score",
                    ),
                    "averageScore": self._auq_score(row),
                    "sampleCount": int(row["sample_count"]) if row else 0,
                })

        current = rows.get("current")
        return {
            "timezone": timezone_name,
            "generatedAt": generated_at.isoformat(),
            "currentCraving": {
                "probability": self._probability(current, "probability"),
                "at": current["predicted_at"].isoformat() if current else None,
            },
            "hourlyCraving": {"buckets": hourly_buckets},
            "dailyEvents": {"range": event_range, "buckets": event_buckets},
            "auq": {
                "range": auq_range,
                "bucketUnit": auq_bucket_unit,
                "buckets": auq_buckets,
            },
        }

    async def ppg_preview(self, patient_id: UUID, prediction_id: UUID) -> dict[str, Any]:
        row = await self.repository.prediction_sensor(patient_id, prediction_id)
        if row is None:
            await self.repository.audit(actor_id=patient_id, actor_role="patient", action="ppg_preview.denied",
                                        resource_type="prediction", resource_id=prediction_id,
                                        metadata={"code": "ppg_preview_not_found"})
            raise PpgPreviewNotFound("PPG preview not found")
        try:
            raw = self.storage.read(patient_id=patient_id, recording_id=row["recording_id"], relative_path=row["storage_uri"])
            payload = json.loads(raw)
            points = self._ppg_points(payload, row["window_started_at"], row["window_ended_at"])
        except Exception as exc:
            raise DashboardUnavailable("PPG preview is unavailable") from exc
        if not points:
            raise PpgPreviewNotFound("PPG preview not found")
        if len(points) > 512:
            indexes = [round(i * (len(points) - 1) / 511) for i in range(512)]
            points = [points[index] for index in indexes]
        duration = (row["window_ended_at"] - row["window_started_at"]).total_seconds()
        return {
            "predictionId": str(prediction_id),
            "windowStartedAt": row["window_started_at"].isoformat(),
            "windowEndedAt": row["window_ended_at"].isoformat(),
            "samplingHz": len(points) / duration if duration > 0 else None,
            "samples": [{"at": at.isoformat(), "value": value} for at, value in points],
        }

    def _state(self, patient_id: UUID, row: dict[str, Any] | None, admin: bool) -> dict[str, Any] | None:
        if row is None:
            return None
        summary = None
        summary_status = "unavailable"
        if self.state_summary_ai_enabled:
            summary_status = row["summary_status"]
        if self.state_summary_ai_enabled and not admin and row.get("summary_encrypted"):
            try:
                summary = self.keyring.decrypt(row["summary_encrypted"], aad=aad_for(
                    table="state_inferences", column="summary_encrypted", patient_id=str(patient_id), record_id=str(row["id"]),
                )).decode("utf-8")
            except Exception as exc:
                raise DashboardUnavailable("State summary is unavailable") from exc
        return {
            "inferenceId": str(row["id"]), "scope": row["inference_scope"], "state": row["state_class"],
            "confidence": float(row["confidence"]) if row.get("confidence") is not None else None,
            "summaryStatus": summary_status, **({} if admin else {"summary": summary}),
            "createdAt": row["created_at"].isoformat(),
        }

    @staticmethod
    def _event(event_id: Any, kind: str, at: datetime, *, session: Any = None, prediction: Any = None, label: str) -> dict[str, Any]:
        return {"eventId": str(event_id), "type": kind, "at": at.isoformat(),
                "sessionId": str(session) if session else None,
                "predictionId": str(prediction) if prediction else None, "label": label}

    @staticmethod
    def _ppg_points(payload: dict[str, Any], start: datetime, end: datetime) -> list[tuple[datetime, float]]:
        output = []
        for sample in payload.get("samples") or []:
            sensor = str(sample.get("sensor") or sample.get("type") or "").lower()
            if "ppg" not in sensor:
                continue
            value = float(sample.get("value"))
            if not math.isfinite(value):
                continue
            stamp = sample.get("timestampMs")
            at = datetime.fromtimestamp(float(stamp) / 1000, tz=timezone.utc) if stamp is not None else None
            if at is not None and start <= at <= end:
                output.append((at, value))
        output.sort(key=lambda item: item[0])
        return output

    @staticmethod
    def _probability(row: dict[str, Any] | None, key: str) -> float | None:
        if not row or row.get(key) is None:
            return None
        value = float(row[key])
        if not math.isfinite(value):
            return None
        return round(min(1.0, max(0.0, value)), 6)

    @staticmethod
    def _auq_score(row: dict[str, Any] | None) -> float | None:
        if not row:
            return None
        value = row.get("average_score")
        if value is None and row.get("average_normalized_score") is not None:
            # Compatibility for repository fakes and a coordinated rollout before
            # the confirmed legacy conversion has run.
            value = float(row["average_normalized_score"]) * 48.0
        if value is None:
            return None
        score = float(value)
        if not math.isfinite(score):
            return None
        return round(min(48.0, max(0.0, score)), 6)

    @staticmethod
    def _as_date(value: Any) -> date:
        if isinstance(value, date):
            return value
        return date.fromisoformat(str(value))
