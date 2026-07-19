from __future__ import annotations

import asyncio
import importlib.util
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.services.auth import AuthorizationError
from app.api.v1.dependencies import get_runtime, patient_user
from app.models.records import UserRecord
from app.api.v1.routes.stt import router
from app.adapters.stt_client import (
    SttAudioTooLarge,
    SttClient,
    SttNoSpeech,
    SttTimeout,
    SttUnavailable,
    SttUnsupportedAudio,
)


@pytest.fixture
def workdir() -> Path:
    root = Path.cwd() / ".pytest-stt" / str(uuid4())
    root.mkdir(parents=True)
    try:
        yield root
    finally:
        shutil.rmtree(root, ignore_errors=True)


class FakeAuth:
    def __init__(self, allowed: bool = True) -> None:
        self.allowed = allowed

    async def require_consent(self, patient_id, feature):
        assert feature == "voice"
        if not self.allowed:
            raise AuthorizationError("Consent required for voice")


class FakeSessionService:
    async def get(self, user, session_id):
        return {"sessionId": str(session_id), "status": "in_progress"}


class FakeSttClient:
    enabled = True
    max_upload_bytes = 1024 * 1024

    def __init__(self, root: Path) -> None:
        self.tmpfs_root = root
        self.error = None
        self.seen_path: Path | None = None

    async def status(self):
        return {
            "enabled": True,
            "available": True,
            "model": "whisper-large-v3-turbo",
            "requestedDevice": "auto",
            "actualDevice": "cuda",
            "fallback": False,
            "errorCode": None,
        }

    async def transcribe(self, path: Path, *, content_type: str):
        assert path.exists() and content_type == "audio/mp4"
        self.seen_path = path
        if self.error is not None:
            raise self.error
        return {
            "text": "편집 가능한 인식 문장",
            "language": "ko",
            "durationMs": 8400,
            "model": "whisper-large-v3-turbo",
        }


def app_client(workdir: Path, *, consent: bool = True):
    patient = UserRecord(
        uuid4(), "p@example.com", "hash", "patient", "active", False, datetime.now(timezone.utc),
    )
    stt = FakeSttClient(workdir)
    runtime = SimpleNamespace(
        stt_service=stt,
        service=FakeAuth(consent),
        session_service=FakeSessionService(),
    )
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_runtime] = lambda: runtime
    app.dependency_overrides[patient_user] = lambda: patient
    return TestClient(app), stt


def test_status_exposes_capability_without_model_path(workdir: Path) -> None:
    client, _ = app_client(workdir)
    response = client.get("/api/stt/status")
    assert response.status_code == 200
    assert response.json()["model"] == "whisper-large-v3-turbo"
    assert "path" not in response.text.lower()


def test_transcription_returns_draft_and_removes_backend_plaintext(workdir: Path) -> None:
    client, stt = app_client(workdir)
    response = client.post(
        f"/api/sessions/{uuid4()}/transcriptions",
        data={"language": "ko"},
        files={"audio": ("voice.m4a", b"ephemeral-audio", "audio/mp4")},
    )
    assert response.status_code == 200
    assert response.json() == {
        "text": "편집 가능한 인식 문장",
        "language": "ko",
        "durationMs": 8400,
        "model": "whisper-large-v3-turbo",
    }
    assert stt.seen_path is not None and not stt.seen_path.exists()
    assert not list(workdir.glob("nt-stt-*"))


def test_voice_consent_is_required_before_plaintext_creation(workdir: Path) -> None:
    client, _ = app_client(workdir, consent=False)
    response = client.post(
        f"/api/sessions/{uuid4()}/transcriptions",
        data={"language": "ko"},
        files={"audio": ("voice.m4a", b"private-audio", "audio/mp4")},
    )
    assert response.status_code == 403
    assert response.json() == {"detail": "voice_consent_required"}
    assert not list(workdir.glob("nt-stt-*"))


def test_format_size_and_empty_audio_errors_are_code_specific(workdir: Path) -> None:
    client, _ = app_client(workdir)
    session = uuid4()
    unsupported = client.post(
        f"/api/sessions/{session}/transcriptions",
        data={"language": "ko"},
        files={"audio": ("voice.mp3", b"audio", "audio/mpeg")},
    )
    assert unsupported.status_code == 415 and unsupported.json()["detail"] == "unsupported_audio"
    empty = client.post(
        f"/api/sessions/{session}/transcriptions",
        data={"language": "ko"},
        files={"audio": ("voice.wav", b"", "audio/wav")},
    )
    assert empty.status_code == 422 and empty.json()["detail"] == "no_speech"
    too_large = client.post(
        f"/api/sessions/{session}/transcriptions",
        data={"language": "ko"},
        files={"audio": ("voice.wav", b"x" * (1024 * 1024 + 1), "audio/wav")},
    )
    assert too_large.status_code == 413 and too_large.json()["detail"] == "audio_too_large"
    assert not list(workdir.glob("nt-stt-*"))


@pytest.mark.parametrize(
    ("error", "status", "code"),
    [
        (SttAudioTooLarge("audio_too_large"), 413, "audio_too_large"),
        (SttUnsupportedAudio("unsupported_audio"), 415, "unsupported_audio"),
        (SttNoSpeech("no_speech"), 422, "no_speech"),
        (SttUnavailable("stt_unavailable"), 503, "stt_unavailable"),
        (SttTimeout("stt_timeout"), 504, "stt_timeout"),
    ],
)
def test_provider_errors_are_sanitized_and_plaintext_is_removed(
    workdir: Path, error: Exception, status: int, code: str,
) -> None:
    client, stt = app_client(workdir)
    stt.error = error
    response = client.post(
        f"/api/sessions/{uuid4()}/transcriptions",
        data={"language": "ko"},
        files={"audio": ("voice.m4a", b"private-audio", "audio/mp4")},
    )
    assert response.status_code == status and response.json() == {"detail": code}
    assert "private-audio" not in response.text and not list(workdir.glob("nt-stt-*"))


def test_client_maps_timeout_and_does_not_expose_provider_body(workdir: Path) -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("provider-secret", request=request)

    async def scenario() -> None:
        audio = workdir / "voice.m4a"
        audio.write_bytes(b"ephemeral")
        client = SttClient(
            "http://stt:8001",
            enabled=True,
            connect_timeout=1,
            read_timeout=1,
            max_upload_mib=1,
            tmpfs_root=workdir,
            transport=httpx.MockTransport(handler),
        )
        try:
            with pytest.raises(SttTimeout, match="stt_timeout"):
                await client.transcribe(audio, content_type="audio/mp4")
        finally:
            await client.close()

    asyncio.run(scenario())


def test_internal_service_uses_fixed_korean_fast_settings_and_cleans_tmpfs(workdir: Path) -> None:
    service_path = Path(__file__).resolve().parents[2] / "stt-service" / "app.py"
    spec = importlib.util.spec_from_file_location("neurotruth_test_stt_service", service_path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    seen = {}

    class Model:
        def transcribe(self, path, **values):
            seen.update(values)
            assert Path(path).exists()
            return [SimpleNamespace(text=" 인식 결과 ")], SimpleNamespace(duration=2.5)

    module.runtime.tmpfs_root = workdir
    with TestClient(module.app) as client:
        module.runtime.enabled = True
        module.runtime.model = Model()
        response = client.post(
            "/transcribe",
            data={"language": "ko"},
            files={"audio": ("voice.wav", b"ephemeral", "audio/wav")},
        )
    assert response.status_code == 200
    assert response.json()["text"] == "인식 결과"
    assert seen == {
        "language": "ko",
        "beam_size": 1,
        "vad_filter": True,
        "condition_on_previous_text": False,
    }
    assert not list(workdir.glob("nt-stt-*"))


def _load_internal_stt_module():
    service_path = Path(__file__).resolve().parents[2] / "stt-service" / "app.py"
    spec = importlib.util.spec_from_file_location(f"neurotruth_test_stt_{uuid4().hex}", service_path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_dgx_cuda_mode_fails_closed_without_cpu_fallback(workdir: Path, monkeypatch) -> None:
    module = _load_internal_stt_module()
    gpu_model = workdir / "pytorch"
    cpu_model = workdir / "ctranslate2"
    gpu_model.mkdir()
    cpu_model.mkdir()
    monkeypatch.setenv("STT_ENABLED", "true")
    monkeypatch.setenv("STT_MODEL_PATH", str(gpu_model))
    monkeypatch.setenv("STT_CPU_MODEL_PATH", str(cpu_model))
    monkeypatch.setenv("STT_DEVICE", "cuda")
    monkeypatch.setenv("STT_ALLOW_CPU_FALLBACK", "false")

    class BrokenCuda:
        def __init__(self, *_args, **_values):
            raise RuntimeError("driver secret must not leak")

    monkeypatch.setattr(module, "TorchWhisperModel", BrokenCuda)
    runtime = module.WhisperRuntime()
    runtime.load()

    assert runtime.model is None
    assert runtime.error_code == "stt_cuda_unavailable"
    assert runtime.actual_device is None
    assert runtime.fallback_reason == "RuntimeError"


def test_auto_mode_uses_ctranslate2_only_as_cpu_fallback(workdir: Path, monkeypatch) -> None:
    module = _load_internal_stt_module()
    gpu_model = workdir / "pytorch"
    cpu_model = workdir / "ctranslate2"
    gpu_model.mkdir()
    cpu_model.mkdir()
    monkeypatch.setenv("STT_ENABLED", "true")
    monkeypatch.setenv("STT_MODEL_PATH", str(gpu_model))
    monkeypatch.setenv("STT_CPU_MODEL_PATH", str(cpu_model))
    monkeypatch.setenv("STT_DEVICE", "auto")
    monkeypatch.setenv("STT_ALLOW_CPU_FALLBACK", "true")

    class BrokenCuda:
        def __init__(self, *_args, **_values):
            raise RuntimeError("cuda unavailable")

    class CpuModel:
        def __init__(self, path, **values):
            assert Path(path) == cpu_model
            assert values == {
                "device": "cpu",
                "compute_type": "int8",
                "local_files_only": True,
            }

    monkeypatch.setattr(module, "TorchWhisperModel", BrokenCuda)
    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=CpuModel))
    runtime = module.WhisperRuntime()
    runtime.load()

    assert isinstance(runtime.model, CpuModel)
    assert runtime.engine == "ctranslate2"
    assert runtime.actual_device == "cpu"
    assert runtime.fallback is True
    assert runtime.error_code is None
