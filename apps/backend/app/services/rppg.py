from __future__ import annotations

import asyncio
import json
import logging
import math
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Protocol
from uuid import UUID, uuid4

import httpx
import numpy as np

from app.core.security.crypto import AesGcmKeyring, aad_for

from app.adapters.rppg_dgx import DgxClient, DgxResponse
from app.adapters.rppg_media import FfprobeMediaInspector, VideoInspection
from app.models.records import ConsentRecord
from app.repositories.postgres import V25Repository
from app.repositories.rppg import RppgConflict, SqlAlchemyRppgRepository
from app.storage.rppg import EncryptedRppgStorage


class RppgUnavailable(RuntimeError): pass
class RppgConsentRequired(ValueError): pass
class RppgJobNotFound(LookupError): pass


class CameraPredictor(Protocol):
    ready: bool
    model_name: str
    model_version: str
    artifact_uri: str | None
    async def predict(self, payload: dict[str, Any]) -> dict[str, Any]: ...


LOGGER = logging.getLogger(__name__)


class RppgService:
    def __init__(
        self, *, repository: SqlAlchemyRppgRepository, v25_repository: V25Repository,
        storage: EncryptedRppgStorage, keyring: AesGcmKeyring, dgx: DgxClient,
        inspector: FfprobeMediaInspector, predictor: CameraPredictor,
        alert_decider: Callable[[UUID, dict[str, Any]], dict[str, Any]],
        enabled: bool, max_concurrency: int = 1, read_timeout_seconds: float = 180,
    ) -> None:
        self.repository, self.v25_repository = repository, v25_repository
        self.storage, self.keyring, self.dgx = storage, keyring, dgx
        self.inspector, self.predictor, self.alert_decider = inspector, predictor, alert_decider
        self.enabled, self.max_concurrency = enabled, max_concurrency
        self.read_timeout_seconds = read_timeout_seconds
        self._stop = asyncio.Event()
        self._workers: list[asyncio.Task[None]] = []

    async def start(self) -> None:
        if not self.enabled:
            return
        cutoff = datetime.now(timezone.utc) - timedelta(seconds=self.read_timeout_seconds + 60)
        await self.repository.recover_stale(cutoff)
        self._workers = [asyncio.create_task(self._worker(), name=f"rppg-worker-{i}")
                         for i in range(self.max_concurrency)]

    async def shutdown(self) -> None:
        self._stop.set()
        for task in self._workers: task.cancel()
        if self._workers:
            await asyncio.gather(*self._workers, return_exceptions=True)
        self._workers.clear()

    async def status(self) -> dict[str, Any]:
        queue = await self.repository.queue_status()
        result: dict[str, Any] = {
            "enabled": self.enabled, "available": False, "modelLoaded": False,
            "device": None, "checkpoint": None,
            "queue": {**queue, "maxConcurrency": self.max_concurrency},
        }
        if not self.enabled:
            return result
        health = await self.dgx.health()
        body = health.payload or {}
        ready = health.status_code == 200 and body.get("status") == "ok" and body.get("model_loaded") is True
        result.update({
            "available": ready, "modelLoaded": bool(body.get("model_loaded")) if health.status_code == 200 else False,
            "device": body.get("device") if health.status_code == 200 else None,
            "checkpoint": Path(str(body.get("checkpoint"))).name if body.get("checkpoint") else None,
        })
        return result

    async def accept(self, *, patient_id: UUID, consent: ConsentRecord, client_capture_id: UUID,
                     captured_at_ms: int, duration_ms: int, session_id: UUID | None,
                     plaintext_path: Path, inspection: VideoInspection) -> dict[str, Any]:
        self._require_enabled_and_consent(consent)
        try:
            checksum = __import__("hashlib").sha256(plaintext_path.read_bytes()).hexdigest()
        except OSError as exc:
            raise RppgUnavailable("RPPG_STORAGE_UNAVAILABLE") from exc
        try:
            existing = await self.repository.capture_by_client(patient_id, client_capture_id)
        except Exception as exc:
            raise RppgUnavailable("RPPG_QUEUE_UNAVAILABLE") from exc
        if existing is not None:
            if existing["checksum_sha256"] != checksum:
                await self.v25_repository.audit(
                    actor_id=patient_id, actor_role="patient", action="rppg.idempotency_conflict",
                    resource_type="rppg_capture", resource_id=existing["id"],
                    metadata={"outcome": "hash_mismatch"},
                )
                raise RppgConflict("RPPG_CAPTURE_CONFLICT")
            return self._accepted(existing["job_id"], existing["id"], existing["job_status"])
        capture_id, job_id = uuid4(), uuid4()
        try:
            stored = self.storage.store_path(patient_id=patient_id, capture_id=capture_id, source=plaintext_path)
        except Exception as exc:
            raise RppgUnavailable("RPPG_STORAGE_UNAVAILABLE") from exc
        try:
            created = await self.repository.create_capture_and_job(
                capture_id=capture_id, job_id=job_id, patient_id=patient_id, session_id=session_id,
                client_capture_id=client_capture_id, consent_snapshot_id=consent.id,
                captured_at=datetime.fromtimestamp(captured_at_ms / 1000, tz=timezone.utc),
                duration_ms=duration_ms, byte_size=stored.byte_size, checksum=stored.checksum_sha256,
                storage_uri=stored.relative_path, key_version=stored.key_id, nonce=stored.nonce,
                metadata={"fps": inspection.fps, "width": inspection.width, "height": inspection.height},
            )
        except RppgConflict as exc:
            self._delete_failed_upload(stored.relative_path)
            if str(exc) == "RPPG_SESSION_NOT_OWNED":
                raise
            try:
                race = await self.repository.capture_by_client(patient_id, client_capture_id)
            except Exception as lookup_exc:
                raise RppgUnavailable("RPPG_QUEUE_UNAVAILABLE") from lookup_exc
            if race is not None and race["checksum_sha256"] == checksum:
                return self._accepted(race["job_id"], race["id"], race["job_status"])
            if race is not None:
                await self.v25_repository.audit(
                    actor_id=patient_id, actor_role="patient", action="rppg.idempotency_conflict",
                    resource_type="rppg_capture", resource_id=race["id"],
                    metadata={"outcome": "hash_mismatch"},
                )
            raise
        except Exception as exc:
            self._delete_failed_upload(stored.relative_path)
            raise RppgUnavailable("RPPG_QUEUE_UNAVAILABLE") from exc
        return self._accepted(created["job_id"], created["capture_id"], created["status"])

    async def job(self, patient_id: UUID, job_id: UUID) -> dict[str, Any]:
        row = await self.repository.owned_job(patient_id, job_id)
        if row is None: raise RppgJobNotFound("RPPG_JOB_NOT_FOUND")
        common = {
            "jobId": str(row["id"]), "captureId": str(row["capture_id"]), "status": row["status"],
            "capturedAtMs": int(row["captured_at"].timestamp() * 1000),
            "createdAt": row["created_at"].isoformat(), "updatedAt": row["updated_at"].isoformat(),
        }
        if row["status"] == "completed":
            metadata = dict(row.get("output_metadata") or {})
            sample_count = 1024 if int(row.get("duration_ms") or 0) >= 19_500 else 512
            common.update({
                "source": "camera_rppg", "classIndex": row["predicted_class_index"],
                "confidence": self._float(row["predicted_class_probability"]),
                "heartRateBpm": self._float(row["heart_rate_bpm"]),
                "qualityScore": self._float(row["quality_score"]), "modelName": row["model_name"],
                "checkpoint": row["checkpoint"], "inferenceDevice": row["inference_device"],
                "rppgSampleCount": sample_count, "rppgSamplingHz": 51.2,
                "processingMs": row["processing_ms"],
                "alertRequired": bool(metadata.get("alertRequired", False)),
                "alertAction": metadata.get("alertAction", "none"),
            })
        elif row["status"] == "retry_required":
            common.update({"failureCode": "RPPG_QUALITY_RECAPTURE",
                           "qualityScore": self._float(row["quality_score"]),
                           "reasons": list(row["quality_reasons"] or [])})
        elif row["status"] == "failed":
            common.update({"failureCode": row["failure_code"] or "RPPG_PROCESSING_FAILED",
                           "retryAllowed": bool(row["retry_allowed"])})
        return common

    async def retry(self, patient_id: UUID, consent: ConsentRecord, job_id: UUID) -> dict[str, Any]:
        self._require_enabled_and_consent(consent)
        try:
            row = await self.repository.create_retry(patient_id, job_id, uuid4())
        except LookupError as exc:
            raise RppgJobNotFound("RPPG_JOB_NOT_FOUND") from exc
        return self._accepted(row["job_id"], row["capture_id"], row["status"])

    async def _worker(self) -> None:
        while not self._stop.is_set():
            try:
                cutoff = datetime.now(timezone.utc) - timedelta(seconds=self.read_timeout_seconds + 60)
                await self.repository.recover_stale(cutoff)
                job_id = await self.repository.claim_next(self.max_concurrency)
                if job_id is None:
                    await asyncio.wait_for(self._stop.wait(), timeout=1.0)
                    continue
                await self._process(job_id)
            except asyncio.TimeoutError:
                continue
            except asyncio.CancelledError:
                raise
            except Exception:
                LOGGER.warning("rPPG queue iteration failed", exc_info=True)
                await asyncio.sleep(0.5)

    async def _process(self, job_id: UUID) -> None:
        row = await self.repository.job_for_processing(job_id)
        if row is None: return
        try:
            with self.storage.plaintext(patient_id=row["patient_id"], capture_id=row["capture_id"],
                                        relative_path=row["storage_uri"]) as path:
                response = await self.dgx.infer(path, str(job_id))
        except httpx.TimeoutException:
            await self.repository.finish_failure(job_id, code="RPPG_DGX_TIMEOUT", retry_allowed=True)
            return
        except httpx.ConnectError:
            await self.repository.finish_failure(job_id, code="RPPG_DGX_CONNECTION", retry_allowed=True)
            return
        except httpx.HTTPError:
            await self.repository.finish_failure(job_id, code="RPPG_DGX_CONNECTION", retry_allowed=True)
            return
        except Exception:
            await self.repository.finish_failure(job_id, code="RPPG_VIDEO_DECRYPT_FAILED", retry_allowed=False)
            return
        await self._apply_response(row, response)

    async def _apply_response(self, row: dict[str, Any], response: DgxResponse) -> None:
        job_id, patient_id = row["id"], row["patient_id"]
        try:
            provider_encrypted, key_version = self._encrypted_json(
                "rppg_analysis_jobs", "provider_response_encrypted", patient_id, job_id, response.payload
            ) if response.payload is not None else (None, None)
        except (TypeError, ValueError):
            await self.repository.finish_failure(job_id, code="RPPG_DGX_INVALID_RESPONSE", retry_allowed=False)
            return
        if response.status_code != 200:
            retry = response.status_code in {500, 503}
            code = f"RPPG_DGX_HTTP_{response.status_code}" if response.status_code in {413, 415, 422, 500, 503} else "RPPG_DGX_HTTP_ERROR"
            await self.repository.finish_failure(job_id, code=code, retry_allowed=retry,
                                                 provider_encrypted=provider_encrypted, key_version=key_version)
            return
        body = response.payload
        if not isinstance(body, dict):
            await self.repository.finish_failure(job_id, code="RPPG_DGX_INVALID_RESPONSE", retry_allowed=False)
            return
        quality = body.get("quality") if isinstance(body.get("quality"), dict) else {}
        reasons = quality.get("reasons") if isinstance(quality.get("reasons"), list) else []
        model = body.get("model") if isinstance(body.get("model"), dict) else {}
        timing = body.get("timing") if isinstance(body.get("timing"), dict) else {}
        common = {
            "measurement_id": self._text(body.get("measurement_id")),
            "heart_rate": self._finite_or_none(body.get("heart_rate_bpm")),
            "quality_score": self._quality(quality.get("score")), "reasons": [str(x)[:256] for x in reasons[:32]],
            "model_name": self._text(model.get("name")) or "FactorizePhys",
            "checkpoint": Path(str(model.get("checkpoint"))).name if model.get("checkpoint") else None,
            "device": self._text(model.get("device")),
            "processing_ms": self._int_or_none(timing.get("total_ms")),
            "provider_encrypted": provider_encrypted, "key_version": key_version,
        }
        if body.get("status") == "retry_required" or quality.get("passed") is False:
            rppg_model = await self.repository.ensure_rppg_model(
                name=common["model_name"] or "FactorizePhys", version=common["checkpoint"] or "unknown",
                artifact=common["checkpoint"],
            )
            await self.repository.finish_quality(job_id, rppg_model_version_id=rppg_model, **common)
            return
        if body.get("status") != "success" or quality.get("passed") is not True:
            await self.repository.finish_failure(job_id, code="RPPG_DGX_INVALID_RESPONSE", retry_allowed=False,
                                                 provider_encrypted=provider_encrypted, key_version=key_version)
            return
        try:
            original, resampled = self.resample_waveform(body.get("waveform"), body.get("rppg_sample_rate_hz"))
        except ValueError:
            await self.repository.finish_failure(job_id, code="RPPG_INVALID_WAVEFORM", retry_allowed=False,
                                                 provider_encrypted=provider_encrypted, key_version=key_version)
            return
        if not self.predictor.ready:
            await self.repository.finish_failure(job_id, code="RPPG_CRAVING_MODEL_UNAVAILABLE", retry_allowed=False,
                                                 provider_encrypted=provider_encrypted, key_version=key_version)
            return
        start_ms = int(row["captured_at"].timestamp() * 1000)
        timestamps = [start_ms + round(i * 1000 / 51.2) for i in range(1024)]
        payload = {
            "windowStartMs": start_ms, "windowEndMs": start_ms + 20_000, "windowMs": 20_000,
            "samples": ([{"sensor": "PPG_GREEN", "timestampMs": ts, "value": float(value)}
                         for ts, value in zip(timestamps, resampled)] +
                        [{"sensor": "EDA", "timestampMs": ts, "value": 0.0} for ts in timestamps]),
        }
        try:
            prediction = await self.predictor.predict(payload)
        except Exception:
            await self.repository.finish_failure(job_id, code="RPPG_CRAVING_MODEL_FAILED", retry_allowed=False,
                                                 provider_encrypted=provider_encrypted, key_version=key_version)
            return
        # Camera measurements remain available for history and chat handoff, but
        # only Watch sensor predictions may create craving alerts.
        alert = {
            "alertLevel": "none",
            "alertRequired": False,
            "alertAction": "none",
            "triggerReason": "camera_rppg_alert_excluded",
        }
        public_prediction = {
            key: value
            for key, value in {
                **prediction, **alert, "source": "camera_rppg", "edaAdaptation": "zero_1024"
            }.items()
            if not str(key).startswith("_")
        }
        craving_model = await self.v25_repository.ensure_craving_model_version(
            model_name=self.predictor.model_name, model_version=self.predictor.model_version,
            artifact_uri=self.predictor.artifact_uri,
            inference_task=getattr(self.predictor, "inference_task", "binary_classification"),
            output_schema=getattr(self.predictor, "output_schema", {
                "predictionSchema": "binary-craving-v1",
                "classes": [{"index": 0, "code": "low"}, {"index": 1, "code": "high"}],
            }),
            config=getattr(self.predictor, "registration_config", {"window_sec": 20}),
        )
        rppg_model = await self.repository.ensure_rppg_model(
            name=common["model_name"] or "FactorizePhys", version=common["checkpoint"] or "unknown",
            artifact=common["checkpoint"],
        )
        waveform_encrypted, _ = self._encrypted_json(
            "rppg_analysis_jobs", "waveform_encrypted", patient_id, job_id,
            {"original": original, "resampled": resampled.tolist(), "samplingHz": 51.2},
        )
        await self.repository.finish_success(
            job_id=job_id, patient_id=patient_id, prediction_id=uuid4(), model_version_id=craving_model,
            rppg_model_version_id=rppg_model, captured_at=row["captured_at"], duration_ms=row["duration_ms"],
            prediction=public_prediction, alert_id=None,
            alert=alert, waveform_encrypted=waveform_encrypted, **common,
        )

    @staticmethod
    def resample_waveform(waveform: Any, sampling_hz: Any) -> tuple[list[float], np.ndarray]:
        try:
            rate = float(sampling_hz)
            values = np.asarray(waveform, dtype=np.float64)
        except (TypeError, ValueError):
            raise ValueError("invalid waveform")
        if not math.isfinite(rate) or rate <= 0 or values.ndim != 1 or values.size < 2 or not np.all(np.isfinite(values)):
            raise ValueError("invalid waveform")
        if float(np.ptp(values)) <= 0:
            raise ValueError("constant waveform")
        source = np.linspace(0.0, 1.0, values.size, endpoint=True)
        target = np.linspace(0.0, 1.0, 1024, endpoint=True)
        output = np.interp(target, source, values)
        if output.size != 1024 or not np.all(np.isfinite(output)):
            raise ValueError("invalid interpolation")
        return values.astype(float).tolist(), output.astype(np.float64)

    def _encrypted_json(self, table: str, column: str, patient_id: UUID, record_id: UUID,
                        value: Any) -> tuple[bytes, str]:
        packed = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()
        envelope = self.keyring.encrypt(packed, aad=aad_for(
            table=table, column=column, patient_id=str(patient_id), record_id=str(record_id)))
        return envelope.pack(), envelope.key_id

    def _require_enabled_and_consent(self, consent: ConsentRecord | None) -> None:
        if not self.enabled: raise RppgUnavailable("RPPG_DISABLED")
        required = consent and consent.biosignal and consent.ai_analysis and consent.camera_rppg and consent.face_video_retention
        if not required: raise RppgConsentRequired("RPPG_CONSENT_REQUIRED")

    def _delete_failed_upload(self, relative_path: str) -> None:
        try:
            self.storage.delete(relative_path)
        except Exception as exc:
            raise RppgUnavailable("RPPG_STORAGE_UNAVAILABLE") from exc

    @staticmethod
    def _accepted(job_id: UUID, capture_id: UUID, status: str) -> dict[str, Any]:
        return {"jobId": str(job_id), "captureId": str(capture_id), "status": status}

    @staticmethod
    def _float(value: Any) -> float | None:
        return float(value) if value is not None else None
    @staticmethod
    def _finite_or_none(value: Any) -> float | None:
        try: result = float(value)
        except (TypeError, ValueError): return None
        return result if math.isfinite(result) else None
    @staticmethod
    def _quality(value: Any) -> float | None:
        result = RppgService._finite_or_none(value)
        return result if result is not None and 0 <= result <= 1 else None
    @staticmethod
    def _int_or_none(value: Any) -> int | None:
        try: return max(0, int(float(value)))
        except (TypeError, ValueError): return None
    @staticmethod
    def _text(value: Any) -> str | None:
        return str(value)[:1024] if value is not None else None
