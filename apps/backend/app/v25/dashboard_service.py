from __future__ import annotations

import json
import math
from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import UUID

from app.security.crypto import AesGcmKeyring, aad_for

from .repository import V25Repository
from .sensor_storage import EncryptedSensorStorage


RANGES = {"24h": timedelta(hours=24), "7d": timedelta(days=7), "30d": timedelta(days=30)}


class DashboardRangeError(ValueError): pass
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
    def __init__(self, repository: V25Repository, keyring: AesGcmKeyring, storage: EncryptedSensorStorage) -> None:
        self.repository, self.keyring, self.storage = repository, keyring, storage

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
        if not admin and row.get("summary_encrypted"):
            try:
                summary = self.keyring.decrypt(row["summary_encrypted"], aad=aad_for(
                    table="state_inferences", column="summary_encrypted", patient_id=str(patient_id), record_id=str(row["id"]),
                )).decode("utf-8")
            except Exception as exc:
                raise DashboardUnavailable("State summary is unavailable") from exc
        return {
            "inferenceId": str(row["id"]), "scope": row["inference_scope"], "state": row["state_class"],
            "confidence": float(row["confidence"]) if row.get("confidence") is not None else None,
            "summaryStatus": row["summary_status"], **({} if admin else {"summary": summary}),
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
