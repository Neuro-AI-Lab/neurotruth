from __future__ import annotations

import asyncio
import os
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from fastapi import FastAPI
from sqlalchemy import text

from app.settings import SecuritySettings

from .auth_service import AuthService
from .repository import SqlAlchemyV25Repository


REQUIRED_REVISION = "20260715_0003"


@dataclass
class V25Runtime:
    ready: bool = False
    settings: SecuritySettings | None = None
    repository: SqlAlchemyV25Repository | Any | None = None
    service: AuthService | Any | None = None
    admin_service: Any | None = None
    session_service: Any | None = None
    dashboard_service: Any | None = None
    rppg_service: Any | None = None
    rppg_repository: Any | None = None
    rppg_storage: Any | None = None
    timeout_sweep_task: asyncio.Task[None] | None = None
    timeout_sweep_stop: asyncio.Event | None = None
    error_code: str | None = "not_initialized"

    def public_status(self) -> dict[str, Any]:
        return {
            "ready": self.ready,
            "schemaRevision": REQUIRED_REVISION if self.ready else None,
            "errorCode": self.error_code,
        }


async def initialize_v25_runtime(app: FastAPI) -> V25Runtime:
    injected = getattr(app.state, "v25_runtime", None)
    if injected is not None and getattr(injected, "ready", False):
        return injected

    runtime = V25Runtime()
    app.state.v25_runtime = runtime
    repository: SqlAlchemyV25Repository | None = None
    try:
        settings = SecuritySettings()
        repository = SqlAlchemyV25Repository(settings.database_url)
        async with repository.engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
            revision = await conn.scalar(text("SELECT version_num FROM alembic_version"))
        if revision != REQUIRED_REVISION:
            raise RuntimeError("schema_revision_mismatch")

        root = settings.sensor_storage_root
        root.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(prefix=".readiness-", dir=root, delete=True):
            pass
        if settings.rppg_enabled:
            assert settings.rppg_storage_root is not None
            if Path(tempfile.gettempdir()).resolve() != settings.rppg_tmpfs_root.resolve():
                raise RuntimeError("rppg_tmpdir_not_tmpfs")
            for rppg_root in (settings.rppg_storage_root, settings.rppg_tmpfs_root):
                rppg_root.mkdir(parents=True, exist_ok=True)
                with tempfile.NamedTemporaryFile(prefix=".readiness-", dir=rppg_root, delete=True):
                    pass

        service = AuthService(
            repository,
            settings.keyring(),
            jwt_signing_key=settings.jwt_signing_key,
            access_minutes=settings.access_token_minutes,
            refresh_days=settings.refresh_token_days,
        )
        await service.bootstrap_admin_code(settings.admin_signup_code.get_secret_value())
        runtime.settings = settings
        runtime.repository = repository
        runtime.service = service
        from .rppg_repository import SqlAlchemyRppgRepository
        from .rppg_storage import EncryptedRppgStorage
        runtime.rppg_repository = SqlAlchemyRppgRepository(repository.engine)
        rppg_root = settings.rppg_storage_root or (settings.sensor_storage_root / "rppg-disabled")
        runtime.rppg_storage = EncryptedRppgStorage(
            rppg_root, settings.rppg_tmpfs_root, settings.keyring()
        )
        from .admin_service import AdminService
        from .sensor_storage import EncryptedSensorStorage
        runtime.admin_service = AdminService(
            repository, settings.keyring(),
            EncryptedSensorStorage(settings.sensor_storage_root, settings.keyring()),
        )
        from .dashboard_service import DashboardService
        runtime.dashboard_service = DashboardService(
            repository, settings.keyring(),
            EncryptedSensorStorage(settings.sensor_storage_root, settings.keyring()),
        )
        runtime.admin_service.configure_dashboard(runtime.dashboard_service)
        from .session_agents import BedrockSessionAgent
        from .session_service import SessionService
        runtime.session_service = SessionService(
            repository, settings.keyring(), service, BedrockSessionAgent()
        )
        await runtime.session_service.resume_pending_reports()
        runtime.timeout_sweep_stop = asyncio.Event()
        runtime.timeout_sweep_task = asyncio.create_task(
            _timeout_sweep_loop(runtime.session_service, runtime.timeout_sweep_stop)
        )
        runtime.ready = True
        runtime.error_code = None
    except Exception as exc:
        if repository is not None:
            await repository.close()
        runtime.error_code = (
            "schema_revision_mismatch"
            if str(exc) == "schema_revision_mismatch"
            else "v25_unavailable"
        )
    return runtime


async def shutdown_v25_runtime(app: FastAPI) -> None:
    runtime = getattr(app.state, "v25_runtime", None)
    timeout_sweep_stop = getattr(runtime, "timeout_sweep_stop", None)
    if timeout_sweep_stop is not None:
        timeout_sweep_stop.set()
    timeout_sweep_task = getattr(runtime, "timeout_sweep_task", None)
    if timeout_sweep_task is not None:
        timeout_sweep_task.cancel()
        await asyncio.gather(timeout_sweep_task, return_exceptions=True)
    session_service = getattr(runtime, "session_service", None)
    if session_service is not None and hasattr(session_service, "shutdown"):
        await session_service.shutdown()
    rppg_service = getattr(runtime, "rppg_service", None)
    if rppg_service is not None and hasattr(rppg_service, "shutdown"):
        await rppg_service.shutdown()
    repository = getattr(runtime, "repository", None)
    if repository is not None and hasattr(repository, "close"):
        await repository.close()
    if runtime is not None:
        runtime.ready = False


async def _timeout_sweep_loop(session_service: Any, stop: asyncio.Event) -> None:
    try:
        interval = max(1.0, float(os.getenv("SESSION_TIMEOUT_SWEEP_SECONDS", "60")))
    except ValueError:
        interval = 60.0
    while not stop.is_set():
        try:
            await asyncio.wait_for(stop.wait(), timeout=interval)
        except TimeoutError:
            try:
                await session_service.sweep_timeouts()
            except asyncio.CancelledError:
                raise
            except Exception:
                # The next sweep retries; failures never expose provider or database details.
                continue
