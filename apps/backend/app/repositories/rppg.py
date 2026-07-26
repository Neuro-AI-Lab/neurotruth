from __future__ import annotations

import json
from datetime import datetime, timedelta
from typing import Any
from uuid import UUID

from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncEngine


class RppgConflict(ValueError):
    pass


class SqlAlchemyRppgRepository:
    def __init__(self, engine: AsyncEngine) -> None:
        self.engine = engine

    async def queue_status(self) -> dict[str, int]:
        async with self.engine.connect() as conn:
            rows = (await conn.execute(text("""
                SELECT status,count(*) AS count FROM rppg_analysis_jobs
                WHERE status IN ('queued','running') GROUP BY status
            """))).mappings().all()
        values = {row["status"]: int(row["count"]) for row in rows}
        return {"queued": values.get("queued", 0), "running": values.get("running", 0)}

    async def capture_by_client(self, patient_id: UUID, client_capture_id: UUID) -> dict[str, Any] | None:
        async with self.engine.connect() as conn:
            row = (await conn.execute(text("""
                SELECT c.*,j.id AS job_id,j.status AS job_status
                FROM rppg_captures c
                LEFT JOIN LATERAL (
                  SELECT id,status FROM rppg_analysis_jobs WHERE capture_id=c.id
                  ORDER BY attempt_no DESC LIMIT 1
                ) j ON true
                WHERE c.patient_id=:patient AND c.client_capture_id=:client AND c.deleted_at IS NULL
            """), {"patient": patient_id, "client": client_capture_id})).mappings().one_or_none()
        return dict(row) if row else None

    async def create_capture_and_job(self, **values: Any) -> dict[str, Any]:
        try:
            async with self.engine.begin() as conn:
                if values.get("session_id") is not None:
                    owned = await conn.scalar(text("""
                        SELECT EXISTS(SELECT 1 FROM sessions WHERE id=:session_id AND patient_id=:patient_id)
                    """), values)
                    if not owned:
                        raise RppgConflict("RPPG_SESSION_NOT_OWNED")
                await conn.execute(text("""
                    INSERT INTO rppg_captures
                      (id,patient_id,session_id,client_capture_id,consent_snapshot_id,captured_at,
                       duration_ms,content_type,byte_size,checksum_sha256,storage_uri,
                       encryption_key_version,encryption_nonce,capture_metadata)
                    VALUES (:capture_id,:patient_id,:session_id,:client_capture_id,:consent_snapshot_id,
                       :captured_at,:duration_ms,'video/mp4',:byte_size,:checksum,:storage_uri,
                       :key_version,:nonce,CAST(:metadata AS jsonb))
                """), {**values, "metadata": json.dumps(values.get("metadata") or {}, separators=(",", ":"))})
                await conn.execute(text("""
                    INSERT INTO rppg_analysis_jobs (id,capture_id,attempt_no,status)
                    VALUES (:job_id,:capture_id,1,'queued')
                """), values)
            return {"capture_id": values["capture_id"], "job_id": values["job_id"], "status": "queued"}
        except IntegrityError as exc:
            raise RppgConflict("RPPG_CAPTURE_CONFLICT") from exc

    async def owned_job(self, patient_id: UUID, job_id: UUID) -> dict[str, Any] | None:
        async with self.engine.connect() as conn:
            row = (await conn.execute(text("""
                SELECT j.*,c.patient_id,c.captured_at,c.created_at AS capture_created_at,
                       c.id AS capture_id,c.storage_uri,c.duration_ms,c.checksum_sha256,
                       p.id AS prediction_id,p.predicted_class_index,p.predicted_class_probability,
                       p.output_metadata
                FROM rppg_analysis_jobs j JOIN rppg_captures c ON c.id=j.capture_id
                LEFT JOIN craving_predictions p ON p.rppg_analysis_job_id=j.id
                WHERE j.id=:job AND c.patient_id=:patient AND c.deleted_at IS NULL
            """), {"job": job_id, "patient": patient_id})).mappings().one_or_none()
        return dict(row) if row else None

    async def job_for_processing(self, job_id: UUID) -> dict[str, Any] | None:
        async with self.engine.connect() as conn:
            row = (await conn.execute(text("""
                SELECT j.*,c.patient_id,c.session_id,c.storage_uri,c.captured_at,c.duration_ms,
                       c.id AS capture_id FROM rppg_analysis_jobs j
                JOIN rppg_captures c ON c.id=j.capture_id
                WHERE j.id=:id AND c.deletion_state='active'
            """), {"id": job_id})).mappings().one_or_none()
        return dict(row) if row else None

    async def recover_stale(self, cutoff: datetime) -> int:
        async with self.engine.begin() as conn:
            result = await conn.execute(text("""
                UPDATE rppg_analysis_jobs SET status='queued',failure_code='RPPG_RECOVERED',
                  retry_allowed=false,started_at=NULL,updated_at=now()
                WHERE status='running' AND updated_at<:cutoff
            """), {"cutoff": cutoff})
        return int(result.rowcount)

    async def claim_next(self, max_concurrency: int) -> UUID | None:
        async with self.engine.begin() as conn:
            await conn.execute(text("SELECT pg_advisory_xact_lock(hashtext('neurotruth-rppg-queue'))"))
            running = int(await conn.scalar(text("SELECT count(*) FROM rppg_analysis_jobs WHERE status='running'")) or 0)
            if running >= max_concurrency:
                return None
            row = (await conn.execute(text("""
                SELECT j.id FROM rppg_analysis_jobs j JOIN rppg_captures c ON c.id=j.capture_id
                WHERE j.status='queued' AND c.deletion_state='active'
                ORDER BY j.created_at FOR UPDATE OF j SKIP LOCKED LIMIT 1
            """))).mappings().one_or_none()
            if row is None:
                return None
            await conn.execute(text("""
                UPDATE rppg_analysis_jobs SET status='running',started_at=now(),updated_at=now(),
                  failure_code=NULL WHERE id=:id
            """), {"id": row["id"]})
            return row["id"]

    async def ensure_rppg_model(self, *, name: str, version: str, artifact: str | None) -> UUID:
        async with self.engine.begin() as conn:
            await conn.execute(text("SELECT pg_advisory_xact_lock(hashtext('neurotruth-rppg-model'))"))
            await conn.execute(text("UPDATE model_versions SET is_active=false WHERE component='rppg_model' AND is_active"))
            return (await conn.execute(text("""
                INSERT INTO model_versions
                  (component,model_name,model_version,inference_task,output_schema,config,artifact_uri,is_active)
                VALUES ('rppg_model',:name,:version,'regression','{"output":"waveform_hr"}'::jsonb,
                        '{"input":"face_video"}'::jsonb,:artifact,true)
                ON CONFLICT (component,model_name,model_version) DO UPDATE
                  SET artifact_uri=EXCLUDED.artifact_uri,is_active=true RETURNING id
            """), {"name": name, "version": version, "artifact": artifact})).scalar_one()

    async def finish_quality(self, job_id: UUID, **values: Any) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                UPDATE rppg_analysis_jobs SET status='retry_required',measurement_id=:measurement_id,
                  model_version_id=:rppg_model_version_id,
                  heart_rate_bpm=:heart_rate,quality_score=:quality_score,
                  quality_reasons=CAST(:reasons AS jsonb),model_name=:model_name,checkpoint=:checkpoint,
                  inference_device=:device,processing_ms=:processing_ms,
                  provider_response_encrypted=:provider_encrypted,encryption_key_version=:key_version,
                  failure_code='RPPG_QUALITY_RECAPTURE',retry_allowed=false,finished_at=now(),updated_at=now()
                WHERE id=:job_id AND status='running'
            """), {"job_id": job_id, **values, "reasons": json.dumps(values.get("reasons") or [])})

    async def finish_failure(self, job_id: UUID, *, code: str, retry_allowed: bool,
                             provider_encrypted: bytes | None = None, key_version: str | None = None) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                UPDATE rppg_analysis_jobs SET status='failed',failure_code=:code,retry_allowed=:retry,
                  provider_response_encrypted=:provider,encryption_key_version=:key,
                  finished_at=now(),updated_at=now() WHERE id=:id AND status='running'
            """), {"id": job_id, "code": code, "retry": retry_allowed,
                    "provider": provider_encrypted, "key": key_version})

    async def finish_success(self, *, job_id: UUID, patient_id: UUID, prediction_id: UUID,
                             model_version_id: UUID, rppg_model_version_id: UUID,
                             captured_at: datetime, duration_ms: int, prediction: dict[str, Any],
                             alert_id: UUID | None, alert: dict[str, Any], **values: Any) -> None:
        async with self.engine.begin() as conn:
            updated = await conn.execute(text("""
                UPDATE rppg_analysis_jobs SET status='completed',measurement_id=:measurement_id,
                  model_version_id=:rppg_model_version_id,heart_rate_bpm=:heart_rate,
                  quality_score=:quality_score,quality_reasons=CAST(:reasons AS jsonb),
                  model_name=:model_name,checkpoint=:checkpoint,inference_device=:device,
                  processing_ms=:processing_ms,provider_response_encrypted=:provider_encrypted,
                  waveform_encrypted=:waveform_encrypted,encryption_key_version=:key_version,
                  failure_code=NULL,retry_allowed=false,finished_at=now(),updated_at=now()
                WHERE id=:job_id AND status='running'
            """), {"job_id": job_id, "rppg_model_version_id": rppg_model_version_id,
                    **values, "reasons": json.dumps(values.get("reasons") or [])})
            if updated.rowcount != 1:
                return
            ended_at = captured_at + timedelta(milliseconds=duration_ms)
            await conn.execute(text("""
                INSERT INTO craving_predictions
                  (id,patient_id,rppg_analysis_job_id,window_started_at,window_ended_at,
                   input_modalities,model_version_id,predicted_class_index,predicted_class_code,
                   predicted_class_probability,class_probabilities,continuous_value,
                   continuous_scale_min,continuous_scale_max,signal_quality,motion_context,
                   quality_gate_passed,output_metadata,predicted_at)
                VALUES (:id,:patient,:job,:started,:ended,ARRAY['ppg','eda_zero'],:model,:class_index,
                   :class_code,:confidence,CAST(:probabilities AS jsonb),:continuous_value,0.0,1.0,
                   CAST(:quality AS jsonb),
                   '{}'::jsonb,true,CAST(:metadata AS jsonb),now())
            """), {"id": prediction_id, "patient": patient_id, "job": job_id,
                    "started": captured_at, "ended": ended_at, "model": model_version_id,
                    "class_index": int(prediction["class"]),
                    "class_code": str(prediction.get("classCode") or ("high" if int(prediction["class"]) == 1 else "low")),
                    "confidence": prediction.get("confidence"),
                    "probabilities": json.dumps(prediction.get("classProbabilities") or {}),
                    "continuous_value": prediction.get("cravingProbability"),
                    "quality": json.dumps({"score": values.get("quality_score"), "passed": True}),
                    "metadata": json.dumps(prediction, separators=(",", ":"))})
            # Camera rPPG predictions are persisted for history and dialogue
            # handoff only. Alert creation is intentionally Watch-only.

    async def create_retry(self, patient_id: UUID, job_id: UUID, new_job_id: UUID) -> dict[str, Any]:
        async with self.engine.begin() as conn:
            await conn.execute(text("SELECT pg_advisory_xact_lock(hashtext(:key))"), {"key": f"rppg-retry:{job_id}"})
            row = (await conn.execute(text("""
                SELECT j.*,c.patient_id,c.deletion_state FROM rppg_analysis_jobs j
                JOIN rppg_captures c ON c.id=j.capture_id
                WHERE j.id=:id AND c.patient_id=:patient FOR UPDATE
            """), {"id": job_id, "patient": patient_id})).mappings().one_or_none()
            if row is None:
                raise LookupError("RPPG_JOB_NOT_FOUND")
            if row["status"] != "failed" or not row["retry_allowed"] or row["deletion_state"] != "active":
                raise RppgConflict("RPPG_RETRY_NOT_ALLOWED")
            already_retried = await conn.scalar(text("""
                SELECT EXISTS(SELECT 1 FROM rppg_analysis_jobs
                  WHERE capture_id=:capture AND (status IN ('queued','running') OR retry_of_job_id=:job))
            """), {"capture": row["capture_id"], "job": job_id})
            if already_retried:
                raise RppgConflict("RPPG_RETRY_NOT_ALLOWED")
            attempt = int(await conn.scalar(text("SELECT max(attempt_no)+1 FROM rppg_analysis_jobs WHERE capture_id=:capture"), {"capture": row["capture_id"]}))
            await conn.execute(text("""
                INSERT INTO rppg_analysis_jobs (id,capture_id,attempt_no,retry_of_job_id,status)
                VALUES (:id,:capture,:attempt,:retry,'queued')
            """), {"id": new_job_id, "capture": row["capture_id"], "attempt": attempt, "retry": job_id})
        return {"job_id": new_job_id, "capture_id": row["capture_id"], "status": "queued"}

    async def list_captures(self, *, limit: int, offset: int) -> list[dict[str, Any]]:
        async with self.engine.connect() as conn:
            rows = (await conn.execute(text("""
                SELECT c.id,c.patient_id,c.captured_at,c.duration_ms,c.byte_size,c.deletion_state,
                  j.id AS job_id,j.status,j.quality_score,j.heart_rate_bpm,j.model_name,j.checkpoint,
                  j.inference_device,j.processing_ms,j.failure_code
                FROM rppg_captures c LEFT JOIN LATERAL (
                  SELECT * FROM rppg_analysis_jobs WHERE capture_id=c.id ORDER BY attempt_no DESC LIMIT 1
                ) j ON true WHERE c.deleted_at IS NULL ORDER BY c.captured_at DESC LIMIT :limit OFFSET :offset
            """), {"limit": limit, "offset": offset})).mappings().all()
        return [dict(row) for row in rows]

    async def capture(self, capture_id: UUID) -> dict[str, Any] | None:
        async with self.engine.connect() as conn:
            row = (await conn.execute(text("SELECT * FROM rppg_captures WHERE id=:id AND deleted_at IS NULL"), {"id": capture_id})).mappings().one_or_none()
        return dict(row) if row else None

    async def mark_deleting(self, capture_id: UUID) -> dict[str, Any] | None:
        async with self.engine.begin() as conn:
            row = (await conn.execute(text("""
                UPDATE rppg_captures SET deletion_state='deleting',deletion_error_code=NULL,updated_at=now()
                WHERE id=:id AND deletion_state IN ('active','deleting','delete_failed') AND deleted_at IS NULL RETURNING *
            """), {"id": capture_id})).mappings().one_or_none()
        return dict(row) if row else None

    async def mark_delete_failed(self, capture_id: UUID) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                UPDATE rppg_captures SET deletion_state='delete_failed',deletion_error_code='RPPG_STORAGE_DELETE_FAILED',updated_at=now()
                WHERE id=:id
            """), {"id": capture_id})

    async def delete_capture_rows(self, capture_id: UUID) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("""DELETE FROM craving_predictions WHERE rppg_analysis_job_id IN
                (SELECT id FROM rppg_analysis_jobs WHERE capture_id=:id)"""), {"id": capture_id})
            await conn.execute(text("DELETE FROM rppg_analysis_jobs WHERE capture_id=:id"), {"id": capture_id})
            await conn.execute(text("DELETE FROM rppg_captures WHERE id=:id"), {"id": capture_id})

    async def patient_capture_paths(self, patient_id: UUID) -> list[dict[str, Any]]:
        async with self.engine.begin() as conn:
            rows = (await conn.execute(text("""
                UPDATE rppg_captures SET deletion_state='deleting',updated_at=now()
                WHERE patient_id=:id AND deletion_state IN ('active','deleting','delete_failed')
                RETURNING id,storage_uri
            """), {"id": patient_id})).mappings().all()
        return [dict(row) for row in rows]

    async def mark_patient_delete_failed(self, patient_id: UUID) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("""
                UPDATE rppg_captures SET deletion_state='delete_failed',
                  deletion_error_code='RPPG_STORAGE_DELETE_FAILED',updated_at=now()
                WHERE patient_id=:id AND deletion_state='deleting'
            """), {"id": patient_id})

    async def delete_patient_rows(self, patient_id: UUID) -> None:
        async with self.engine.begin() as conn:
            await conn.execute(text("""DELETE FROM craving_predictions WHERE rppg_analysis_job_id IN
              (SELECT j.id FROM rppg_analysis_jobs j JOIN rppg_captures c ON c.id=j.capture_id WHERE c.patient_id=:id)"""), {"id": patient_id})
            await conn.execute(text("DELETE FROM rppg_captures WHERE patient_id=:id"), {"id": patient_id})
