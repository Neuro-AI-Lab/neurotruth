from __future__ import annotations

import asyncio
import shutil
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi import FastAPI

from app.security.crypto import AesGcmKeyring
from app.v25 import runtime as runtime_module


@pytest.fixture
def workdir() -> Path:
    root = Path.cwd() / ".pytest-rppg-runtime" / str(uuid4())
    root.mkdir(parents=True)
    try:
        yield root
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_enabled_rppg_runtime_readiness_uses_path_and_writable_roots(workdir: Path, monkeypatch) -> None:
    ring = AesGcmKeyring({"v1": b"K" * 32}, "v1")
    tmpfs_root = workdir / "tmpfs"
    tmpfs_root.mkdir()
    monkeypatch.setattr(runtime_module.tempfile, "gettempdir", lambda: str(tmpfs_root))

    class Connection:
        async def __aenter__(self): return self
        async def __aexit__(self, *_): return None
        async def execute(self, *_): return None
        async def scalar(self, *_): return runtime_module.REQUIRED_REVISION

    class Engine:
        def connect(self): return Connection()

    class Repository:
        def __init__(self, _): self.engine = Engine()
        async def close(self): pass

    class Settings:
        database_url = "postgresql+asyncpg://test"
        sensor_storage_root = workdir / "sensors"
        rppg_enabled = True
        rppg_storage_root = workdir / "rppg"
        rppg_tmpfs_root = tmpfs_root.resolve()
        jwt_signing_key = "J" * 32
        access_token_minutes = 15
        refresh_token_days = 30
        admin_signup_code = SimpleNamespace(get_secret_value=lambda: "A" * 16)
        def keyring(self): return ring

    class Auth:
        def __init__(self, *_, **__): pass
        async def bootstrap_admin_code(self, _): pass

    class Admin:
        def __init__(self, *_, **__): pass
        def configure_dashboard(self, service): self.dashboard_service = service

    class Session:
        def __init__(self, *_, **__): pass
        async def resume_pending_reports(self): pass
        async def sweep_timeouts(self): pass
        async def shutdown(self): pass

    monkeypatch.setattr(runtime_module, "SecuritySettings", Settings)
    monkeypatch.setattr(runtime_module, "SqlAlchemyV25Repository", Repository)
    monkeypatch.setattr(runtime_module, "AuthService", Auth)
    monkeypatch.setattr("app.v25.rppg_repository.SqlAlchemyRppgRepository", lambda engine: SimpleNamespace(engine=engine))
    monkeypatch.setattr("app.v25.rppg_storage.EncryptedRppgStorage", lambda *args: SimpleNamespace(args=args))
    monkeypatch.setattr("app.v25.admin_service.AdminService", Admin)
    monkeypatch.setattr("app.v25.sensor_storage.EncryptedSensorStorage", lambda *args: SimpleNamespace(args=args))
    monkeypatch.setattr("app.v25.session_agents.BedrockSessionAgent", lambda: object())
    monkeypatch.setattr("app.v25.session_service.SessionService", Session)

    async def exercise() -> None:
        app = FastAPI()
        runtime = await runtime_module.initialize_v25_runtime(app)
        assert runtime.ready and runtime.error_code is None
        assert Settings.sensor_storage_root.is_dir()
        assert Settings.rppg_storage_root.is_dir()
        await runtime_module.shutdown_v25_runtime(app)

    asyncio.run(exercise())
