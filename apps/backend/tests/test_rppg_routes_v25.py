from __future__ import annotations

import shutil
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.v1.dependencies import get_runtime, password_ready_user, patient_user
from app.models.records import UserRecord
from app.api.v1.routes.admin import admin_user
from app.api.v1.routes.rppg import get_rppg_service, router
from app.adapters.rppg_media import VideoInspection


@pytest.fixture
def workdir() -> Path:
    root = Path.cwd() / ".pytest-rppg-routes" / str(uuid4())
    root.mkdir(parents=True)
    try: yield root
    finally: shutil.rmtree(root, ignore_errors=True)


class FakePatientRepository:
    def __init__(self, consent): self.consent = consent; self.audits = []
    async def current_consent(self, patient_id): return self.consent
    async def audit(self, **values): self.audits.append(values)


class FakeRppgService:
    enabled = True
    def __init__(self, root: Path):
        self.storage = SimpleNamespace(tmpfs_root=root)
        self.inspector = SimpleNamespace(inspect=lambda path: VideoInspection(20.0, 30.0, 1280, 720))
        self.accepted = None
        self.accept_error = None
    def _require_enabled_and_consent(self, consent):
        if not (consent.biosignal and consent.ai_analysis and consent.camera_rppg and consent.face_video_retention):
            from app.services.rppg import RppgConsentRequired
            raise RppgConsentRequired("RPPG_CONSENT_REQUIRED")
    async def status(self):
        return {"enabled": True, "available": True, "modelLoaded": True, "device": "cuda:0",
                "checkpoint": "PURE.pth", "queue": {"queued": 0, "running": 0, "maxConcurrency": 1}}
    async def accept(self, **values):
        assert values["plaintext_path"].exists()
        if self.accept_error is not None:
            raise self.accept_error
        self.accepted = values
        return {"jobId": str(uuid4()), "captureId": str(uuid4()), "status": "queued"}


def app_client(workdir: Path, *, all_consents: bool = True, max_mib: int = 40):
    patient = UserRecord(uuid4(), "p@example.com", "hash", "patient", "active", False, datetime.now(timezone.utc))
    admin = UserRecord(uuid4(), "a@example.com", "hash", "admin", "active", False, datetime.now(timezone.utc))
    consent = SimpleNamespace(id=uuid4(), biosignal=all_consents, ai_analysis=all_consents,
                              camera_rppg=all_consents, face_video_retention=all_consents)
    repository = FakePatientRepository(consent)
    runtime = SimpleNamespace(repository=repository, settings=SimpleNamespace(rppg_max_upload_mib=max_mib))
    service = FakeRppgService(workdir)
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_runtime] = lambda: runtime
    app.dependency_overrides[patient_user] = lambda: patient
    app.dependency_overrides[password_ready_user] = lambda: patient
    app.dependency_overrides[admin_user] = lambda: admin
    app.dependency_overrides[get_rppg_service] = lambda: service
    return TestClient(app), service, runtime, patient, admin


def test_patient_status_does_not_expose_dgx_url(workdir: Path) -> None:
    client, *_ = app_client(workdir)
    response = client.get("/api/rppg/status")
    assert response.status_code == 200
    assert response.json()["checkpoint"] == "PURE.pth"
    assert "url" not in response.text.lower()


def test_admin_can_read_only_shared_rppg_capability_status(workdir: Path) -> None:
    client, _, _, _, admin = app_client(workdir)
    client.app.dependency_overrides[password_ready_user] = lambda: admin
    response = client.get("/api/rppg/status")
    assert response.status_code == 200 and response.json()["modelLoaded"] is True


def test_upload_contract_returns_202_and_cleans_plaintext(workdir: Path) -> None:
    client, service, *_ = app_client(workdir)
    capture_id = uuid4()
    response = client.post("/api/rppg/jobs", data={
        "clientCaptureId": str(capture_id), "capturedAtMs": "1800000000000", "durationMs": "20000",
    }, files={"video": ("face.mp4", b"mock-mp4", "video/mp4")})
    assert response.status_code == 202 and response.json()["status"] == "queued"
    assert service.accepted["client_capture_id"] == capture_id
    assert not list(workdir.glob("nt-upload-*.mp4"))


def test_upload_requires_all_four_consents_before_accept(workdir: Path) -> None:
    client, service, *_ = app_client(workdir, all_consents=False)
    response = client.post("/api/rppg/jobs", data={
        "clientCaptureId": str(uuid4()), "capturedAtMs": "1800000000000", "durationMs": "20000",
    }, files={"video": ("face.mp4", b"mock-mp4", "video/mp4")})
    assert response.status_code == 403 and response.json()["detail"] == "RPPG_CONSENT_REQUIRED"
    assert service.accepted is None


def test_upload_rejects_non_mp4_with_415(workdir: Path) -> None:
    client, *_ = app_client(workdir)
    response = client.post("/api/rppg/jobs", data={
        "clientCaptureId": str(uuid4()), "capturedAtMs": "1800000000000", "durationMs": "20000",
    }, files={"video": ("face.mov", b"video", "video/quicktime")})
    assert response.status_code == 415


@pytest.mark.parametrize("code", ["RPPG_STORAGE_UNAVAILABLE", "RPPG_QUEUE_UNAVAILABLE"])
def test_upload_prerequisite_failures_are_sanitized_and_clean_plaintext(workdir: Path, code: str) -> None:
    from app.services.rppg import RppgUnavailable

    client, service, *_ = app_client(workdir)
    service.accept_error = RppgUnavailable(code)
    response = client.post("/api/rppg/jobs", data={
        "clientCaptureId": str(uuid4()), "capturedAtMs": "1800000000000", "durationMs": "20000",
    }, files={"video": ("face.mp4", b"private-face-video", "video/mp4")})
    assert response.status_code == 503 and response.json() == {"detail": code}
    assert str(workdir) not in response.text and "private-face-video" not in response.text
    assert not list(workdir.glob("nt-upload-*.mp4"))


def test_tmpfs_creation_failure_is_sanitized_without_path_leak(workdir: Path, monkeypatch) -> None:
    from app.api.v1.routes import rppg

    client, *_ = app_client(workdir)
    private_path = workdir / "should-not-leak"
    monkeypatch.setattr(rppg.tempfile, "mkstemp", lambda **_: (_ for _ in ()).throw(
        OSError(f"cannot create {private_path}")
    ))
    response = client.post("/api/rppg/jobs", data={
        "clientCaptureId": str(uuid4()), "capturedAtMs": "1800000000000", "durationMs": "20000",
    }, files={"video": ("face.mp4", b"private-face-video", "video/mp4")})
    assert response.status_code == 503 and response.json() == {"detail": "RPPG_STORAGE_UNAVAILABLE"}
    assert str(private_path) not in response.text and "private-face-video" not in response.text
    assert not list(workdir.glob("nt-upload-*.mp4"))


def test_consent_database_failure_is_sanitized_before_plaintext_creation(workdir: Path) -> None:
    client, _, runtime, *_ = app_client(workdir)
    private_path = workdir / "database-secret"

    async def unavailable(_):
        raise RuntimeError(f"database failed at {private_path}")

    runtime.repository.current_consent = unavailable
    response = client.post("/api/rppg/jobs", data={
        "clientCaptureId": str(uuid4()), "capturedAtMs": "1800000000000", "durationMs": "20000",
    }, files={"video": ("face.mp4", b"private-face-video", "video/mp4")})
    assert response.status_code == 503 and response.json() == {"detail": "RPPG_QUEUE_UNAVAILABLE"}
    assert str(private_path) not in response.text and "private-face-video" not in response.text
    assert not list(workdir.glob("nt-upload-*.mp4"))


def test_upload_rejects_legacy_ten_second_duration_for_new_jobs(workdir: Path) -> None:
    client, service, *_ = app_client(workdir)
    response = client.post("/api/rppg/jobs", data={
        "clientCaptureId": str(uuid4()), "capturedAtMs": "1800000000000", "durationMs": "10000",
    }, files={"video": ("face.mp4", b"mock-mp4", "video/mp4")})
    assert response.status_code == 422
    assert service.accepted is None


def test_admin_reveal_is_inline_no_store_and_audited(workdir: Path) -> None:
    client, _, runtime, _, _ = app_client(workdir)
    capture_id, patient_id = uuid4(), uuid4()
    class CaptureRepo:
        async def capture(self, value):
            return {"id": capture_id, "patient_id": patient_id, "storage_uri": "x.ntr"}
    class Storage:
        def read(self, **values): return b"decrypted-video"
    runtime.rppg_repository, runtime.rppg_storage = CaptureRepo(), Storage()
    response = client.post(f"/api/admin/rppg/captures/{capture_id}/reveal-video", json={"reason": "quality review"})
    assert response.status_code == 200 and response.content == b"decrypted-video"
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["content-disposition"].startswith("inline;")
    assert runtime.repository.audits[-1]["action"] == "admin.rppg_video_reveal"
