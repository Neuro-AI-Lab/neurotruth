from __future__ import annotations

import asyncio
import json
import shutil
from datetime import datetime, timezone
from inspect import getsource
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import httpx
import numpy as np
import pytest

from app.core.security.crypto import AesGcmKeyring, DecryptionError, EncryptedEnvelope, aad_for
from app.adapters.rppg_dgx import DgxClient, DgxResponse
from app.adapters.rppg_media import VideoInspection
from app.services.rppg import RppgService, RppgUnavailable
from app.repositories.rppg import SqlAlchemyRppgRepository
from app.storage.rppg import EncryptedRppgStorage


def keyring() -> AesGcmKeyring:
    return AesGcmKeyring({"v1": b"K" * 32}, "v1")


@pytest.fixture
def workdir() -> Path:
    root = Path.cwd() / ".pytest-rppg" / str(uuid4())
    root.mkdir(parents=True)
    try:
        yield root
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_rppg_video_storage_is_ciphertext_and_aad_bound(workdir: Path) -> None:
    tmp_path = workdir
    patient, capture = uuid4(), uuid4()
    source = tmp_path / "input.mp4"
    source.write_bytes(b"plain-face-video-marker")
    storage = EncryptedRppgStorage(tmp_path / "encrypted", tmp_path / "tmpfs", keyring())
    stored = storage.store_path(patient_id=patient, capture_id=capture, source=source)
    permanent = (tmp_path / "encrypted" / stored.relative_path).read_bytes()
    assert b"plain-face-video-marker" not in permanent
    assert storage.read(patient_id=patient, capture_id=capture, relative_path=stored.relative_path) == source.read_bytes()
    with pytest.raises(DecryptionError):
        storage.read(patient_id=uuid4(), capture_id=capture, relative_path=stored.relative_path)
    with storage.plaintext(patient_id=patient, capture_id=capture, relative_path=stored.relative_path) as path:
        assert path.read_bytes() == source.read_bytes()
        transient = path
    assert not transient.exists()


def test_dgx_client_uses_backend_job_id_and_requests_waveform(workdir: Path) -> None:
    tmp_path = workdir
    seen: dict[str, bytes] = {}
    async def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = await request.aread()
        return httpx.Response(200, json={"status": "success"})
    video = tmp_path / "v.mp4"
    video.write_bytes(b"mp4")
    client = DgxClient("http://dgx:8000", connect_timeout=10, read_timeout=180,
                       transport=httpx.MockTransport(handler))
    result = asyncio.run(client.infer(video, "job-123"))
    assert result.status_code == 200
    assert b'name="session_id"' in seen["body"] and b"job-123" in seen["body"]
    assert b'name="include_waveform"' in seen["body"] and b"true" in seen["body"]


def test_waveform_is_exactly_1024_finite_samples_and_rejects_bad_input() -> None:
    original, output = RppgService.resample_waveform([0.0, 1.0, 0.2], 30)
    assert original == [0.0, 1.0, 0.2]
    assert output.shape == (1024,)
    assert np.all(np.isfinite(output))
    for waveform, rate in (([1.0, 1.0], 30), ([0.0, float("nan")], 30),
                           ([0.0, 1.0], 0), ([0.0, 1.0], float("nan")),
                           ([0.0, 1.0], float("inf")), (None, 30)):
        with pytest.raises(ValueError):
            RppgService.resample_waveform(waveform, rate)


class FakeRppgRepository:
    def __init__(self) -> None:
        self.quality = None
        self.failure = None
        self.success = None
        self.owned_job_row = None
        self.rppg_model_id = uuid4()
    async def owned_job(self, patient_id, job_id): return self.owned_job_row
    async def finish_quality(self, job_id, **values): self.quality = (job_id, values)
    async def finish_failure(self, job_id, **values): self.failure = (job_id, values)
    async def ensure_rppg_model(self, **values): return self.rppg_model_id
    async def finish_success(self, **values): self.success = values


class FakeV25Repository:
    def __init__(self, notification: bool = False) -> None: self.notification = notification
    async def current_consent(self, patient_id): return SimpleNamespace(notification=self.notification)
    async def ensure_craving_model_version(self, **values): return uuid4()


class FakePredictor:
    ready = True
    model_name = "Conv1DNet"
    model_version = "test"
    artifact_uri = None
    def __init__(self): self.payload = None
    async def predict(self, payload):
        self.payload = payload
        return {
            "predictionSchema": "binary-craving-v1", "class": 1, "classCode": "high",
            "confidence": 0.9, "cravingProbability": 0.9,
            "classProbabilities": {"low": 0.1, "high": 0.9},
            "_lat": {"server_ms": 5.0}, "_readyPerf": 123.0,
        }


def make_service(tmp_path: Path) -> tuple[RppgService, FakeRppgRepository, FakePredictor]:
    repo, predictor = FakeRppgRepository(), FakePredictor()
    storage = EncryptedRppgStorage(tmp_path / "storage", tmp_path / "tmp", keyring())
    service = RppgService(
        repository=repo, v25_repository=FakeV25Repository(), storage=storage, keyring=keyring(),
        dgx=SimpleNamespace(), inspector=SimpleNamespace(), predictor=predictor,
        alert_decider=lambda patient, prediction: {"alertRequired": True, "alertAction": "open_auq"},
        enabled=True,
    )
    return service, repo, predictor


def test_quality_failure_never_calls_craving_model(workdir: Path) -> None:
    tmp_path = workdir
    service, repo, predictor = make_service(tmp_path)
    row = {"id": uuid4(), "patient_id": uuid4()}
    asyncio.run(service._apply_response(row, DgxResponse(200, {
        "status": "retry_required", "quality": {"passed": False, "score": .2, "reasons": ["motion"]},
        "model": {"name": "FactorizePhys"}, "timing": {"total_ms": 50},
    })))
    assert repo.quality is not None
    assert repo.quality[1]["rppg_model_version_id"] == repo.rppg_model_id
    assert repo.success is None and predictor.payload is None


def test_upload_storage_and_queue_failures_are_sanitized_and_cleanup_encrypted_file(workdir: Path) -> None:
    consent = SimpleNamespace(id=uuid4(), biosignal=True, ai_analysis=True,
                              camera_rppg=True, face_video_retention=True)
    source = workdir / "secret-face.mp4"
    source.write_bytes(b"face-video")
    inspection = VideoInspection(20.0, 30.0, 1280, 720)

    class BrokenStorage:
        def store_path(self, **values):
            raise OSError(f"cannot write {workdir / 'private-storage'}")

    class LookupRepo(FakeRppgRepository):
        async def capture_by_client(self, *values): return None

    service = RppgService(
        repository=LookupRepo(), v25_repository=FakeV25Repository(), storage=BrokenStorage(),
        keyring=keyring(), dgx=SimpleNamespace(), inspector=SimpleNamespace(), predictor=FakePredictor(),
        alert_decider=lambda *_: {}, enabled=True,
    )
    with pytest.raises(RppgUnavailable, match="^RPPG_STORAGE_UNAVAILABLE$"):
        asyncio.run(service.accept(
            patient_id=uuid4(), consent=consent, client_capture_id=uuid4(),
            captured_at_ms=1_800_000_000_000, duration_ms=20_000, session_id=None,
            plaintext_path=source, inspection=inspection,
        ))

    class QueueDownRepo(FakeRppgRepository):
        async def capture_by_client(self, *values): return None
        async def create_capture_and_job(self, **values):
            raise RuntimeError(f"database unavailable at {workdir / 'private-db'}")

    encrypted_root = workdir / "encrypted"
    service = RppgService(
        repository=QueueDownRepo(), v25_repository=FakeV25Repository(),
        storage=EncryptedRppgStorage(encrypted_root, workdir / "tmp", keyring()),
        keyring=keyring(), dgx=SimpleNamespace(), inspector=SimpleNamespace(), predictor=FakePredictor(),
        alert_decider=lambda *_: {}, enabled=True,
    )
    with pytest.raises(RppgUnavailable, match="^RPPG_QUEUE_UNAVAILABLE$"):
        asyncio.run(service.accept(
            patient_id=uuid4(), consent=consent, client_capture_id=uuid4(),
            captured_at_ms=1_800_000_000_000, duration_ms=20_000, session_id=None,
            plaintext_path=source, inspection=inspection,
        ))
    assert not list(encrypted_root.rglob("*.ntr"))


def test_success_supplies_1024_ppg_and_literal_zero_eda(workdir: Path) -> None:
    tmp_path = workdir
    service, repo, predictor = make_service(tmp_path)
    row = {"id": uuid4(), "patient_id": uuid4(), "captured_at": datetime.now(timezone.utc), "duration_ms": 20_000}
    asyncio.run(service._apply_response(row, DgxResponse(200, {
        "measurement_id": "m1", "status": "success", "heart_rate_bpm": 72,
        "rppg_sample_rate_hz": 30, "waveform": [0, .5, 1, .2],
        "quality": {"passed": True, "score": .8, "reasons": []},
        "model": {"name": "FactorizePhys", "checkpoint": "/secret/PURE.pth", "device": "cuda:0"},
        "timing": {"total_ms": 100},
    })))
    assert repo.success is not None
    samples = predictor.payload["samples"]
    ppg = [sample for sample in samples if sample["sensor"] == "PPG_GREEN"]
    eda = [sample for sample in samples if sample["sensor"] == "EDA"]
    assert len(ppg) == len(eda) == 1024
    assert all(sample["value"] == 0.0 for sample in eda)
    assert predictor.payload["windowMs"] == 20_000
    assert predictor.payload["windowEndMs"] - predictor.payload["windowStartMs"] == 20_000
    assert repo.success["prediction"]["source"] == "camera_rppg"
    assert repo.success["prediction"]["edaAdaptation"] == "zero_1024"
    assert repo.success["prediction"]["class"] == 1
    assert not any(key.startswith("_") for key in repo.success["prediction"])
    assert repo.success["checkpoint"] == "PURE.pth"
    assert repo.success["alert_id"] is None
    assert repo.success["prediction"]["alertAction"] == "none"
    assert repo.success["prediction"]["triggerReason"] == "camera_rppg_alert_excluded"


def test_rppg_repository_has_no_alert_insert_path() -> None:
    source = getsource(SqlAlchemyRppgRepository.finish_success)
    assert "INSERT INTO craving_alerts" not in source


def test_legacy_ten_second_completed_job_remains_readable(workdir: Path) -> None:
    service, repo, _ = make_service(workdir)
    patient_id, job_id, capture_id = uuid4(), uuid4(), uuid4()
    now = datetime.now(timezone.utc)
    repo.owned_job_row = {
        "id": job_id,
        "capture_id": capture_id,
        "status": "completed",
        "captured_at": now,
        "created_at": now,
        "updated_at": now,
        "duration_ms": 10_000,
        "output_metadata": {},
        "predicted_class_index": 0,
        "predicted_class_probability": 0.8,
        "heart_rate_bpm": 70,
        "quality_score": 0.9,
        "model_name": "FactorizePhys",
        "checkpoint": "legacy.pth",
        "inference_device": "cuda:0",
        "processing_ms": 100,
    }
    result = asyncio.run(service.job(patient_id, job_id))
    assert result["status"] == "completed"
    assert result["rppgSampleCount"] == 512


def test_provider_payload_encryption_is_aad_bound(workdir: Path) -> None:
    tmp_path = workdir
    service, _, _ = make_service(tmp_path)
    patient, job = uuid4(), uuid4()
    packed, key_id = service._encrypted_json("rppg_analysis_jobs", "provider_response_encrypted", patient, job, {"waveform": [1,2]})
    assert key_id == "v1" and b"waveform" not in packed
    plaintext = keyring().decrypt(EncryptedEnvelope.unpack(packed), aad=aad_for(
        table="rppg_analysis_jobs", column="provider_response_encrypted", patient_id=str(patient), record_id=str(job)))
    assert json.loads(plaintext) == {"waveform": [1, 2]}
