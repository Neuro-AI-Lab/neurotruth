from __future__ import annotations

import asyncio
import os
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from fastapi import FastAPI
from sqlalchemy import text

from app.agents.bedrock import BedrockClaudeAdapter
from app.agents.intervention import InterventionAgent
from app.agents.report import ReportAgent
from app.agents.state_summary import StateSummaryAgent
from app.core.config import SecuritySettings
from app.maintenance.seed_vp012_demo import Vp012DemoSeeder
from app.repositories.postgres import SqlAlchemyV25Repository
from app.services.auth import AuthService


REQUIRED_REVISION = "20260717_0005"


@dataclass
class BackendRuntime:
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
    stt_service: Any | None = None
    timeout_sweep_task: asyncio.Task[None] | None = None
    timeout_sweep_stop: asyncio.Event | None = None
    shutdown_task: asyncio.Task[None] | None = None
    error_code: str | None = "not_initialized"

    def public_status(self) -> dict[str, Any]:
        return {
            "ready": self.ready,
            "schemaRevision": REQUIRED_REVISION if self.ready else None,
            "errorCode": self.error_code,
        }


async def initialize_runtime(app: FastAPI) -> BackendRuntime:
    injected = getattr(app.state, "backend_runtime", None)
    if injected is not None and getattr(injected, "ready", False):
        return injected

    runtime = BackendRuntime()
    app.state.backend_runtime = runtime
    try:
        settings = SecuritySettings()
        repository = SqlAlchemyV25Repository(settings.database_url)
        runtime.repository = repository
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
        from app.storage.rppg import EncryptedRppgStorage
        from app.storage.sensor import EncryptedSensorStorage
        sensor_storage = EncryptedSensorStorage(root, settings.keyring())
        rppg_root = settings.rppg_storage_root or (root / "rppg-disabled")
        rppg_storage = EncryptedRppgStorage(
            rppg_root, settings.rppg_tmpfs_root, settings.keyring()
        )

        service = AuthService(
            repository,
            settings.keyring(),
            jwt_signing_key=settings.jwt_signing_key,
            access_minutes=settings.access_token_minutes,
            refresh_days=settings.refresh_token_days,
            demo_scenario_enabled=getattr(settings, "demo_scenario_enabled", False),
            demo_lifecycle=Vp012DemoSeeder(
                repository.engine,
                settings.keyring(),
                sensor_storage=sensor_storage,
                rppg_storage=rppg_storage,
            ),
        )
        await service.bootstrap_admin_code(settings.admin_signup_code.get_secret_value())
        runtime.settings = settings
        runtime.service = service
        stt_client = None
        try:
            from app.adapters.stt_client import SttClient
            stt_client = SttClient.from_env()
            if stt_client.enabled:
                stt_client.tmpfs_root.mkdir(parents=True, exist_ok=True)
                with tempfile.NamedTemporaryFile(
                    prefix=".readiness-", dir=stt_client.tmpfs_root, delete=True
                ):
                    pass
            from app.services.stt import SttService
            runtime.stt_service = SttService(stt_client)
        except Exception:
            if stt_client is not None:
                await stt_client.close()
            runtime.stt_service = None
        from app.repositories.rppg import SqlAlchemyRppgRepository
        runtime.rppg_repository = SqlAlchemyRppgRepository(repository.engine)
        runtime.rppg_storage = rppg_storage
        from app.services.admin import AdminService
        runtime.admin_service = AdminService(
            repository, settings.keyring(), sensor_storage,
        )
        from app.services.dashboard import DashboardService
        runtime.dashboard_service = DashboardService(
            repository, settings.keyring(), sensor_storage,
            state_summary_ai_enabled=getattr(settings, "state_summary_ai_enabled", False),
        )
        runtime.admin_service.configure_dashboard(runtime.dashboard_service)
        from app.services.session import SessionService
        adapter = BedrockClaudeAdapter()
        runtime.session_service = SessionService(
            repository,
            settings.keyring(),
            service,
            InterventionAgent(adapter),
            StateSummaryAgent(adapter),
            ReportAgent(adapter),
            state_summary_ai_enabled=getattr(settings, "state_summary_ai_enabled", False),
            report_ai_enabled=getattr(settings, "report_ai_enabled", False),
        )
        await runtime.session_service.resume_pending_reports()
        runtime.timeout_sweep_stop = asyncio.Event()
        runtime.timeout_sweep_task = asyncio.create_task(
            _timeout_sweep_loop(runtime.session_service, runtime.timeout_sweep_stop)
        )
        runtime.ready = True
        runtime.error_code = None
    except Exception as exc:
        runtime.error_code = (
            "schema_revision_mismatch"
            if str(exc) == "schema_revision_mismatch"
            else "v25_unavailable"
        )
        await shutdown_runtime(app)
    return runtime


async def shutdown_runtime(app: FastAPI) -> None:
    runtime = getattr(app.state, "backend_runtime", None)
    if runtime is None:
        return
    task = runtime.shutdown_task
    if task is None or task.done():
        task = asyncio.create_task(_shutdown_runtime(runtime), name="backend-runtime-shutdown")
        runtime.shutdown_task = task
    try:
        await asyncio.shield(task)
    finally:
        if task.done() and runtime.shutdown_task is task:
            runtime.shutdown_task = None


async def _shutdown_runtime(runtime: BackendRuntime) -> None:
    runtime.ready = False
    timeout_sweep_stop = getattr(runtime, "timeout_sweep_stop", None)
    runtime.timeout_sweep_stop = None
    if timeout_sweep_stop is not None:
        timeout_sweep_stop.set()
    timeout_sweep_task = getattr(runtime, "timeout_sweep_task", None)
    runtime.timeout_sweep_task = None
    if timeout_sweep_task is not None:
        timeout_sweep_task.cancel()
        await asyncio.gather(timeout_sweep_task, return_exceptions=True)
    for attribute, method_name in (
        ("session_service", "shutdown"),
        ("rppg_service", "shutdown"),
        ("stt_service", "close"),
        ("repository", "close"),
    ):
        resource = getattr(runtime, attribute, None)
        setattr(runtime, attribute, None)
        method = getattr(resource, method_name, None)
        if method is None:
            continue
        try:
            await method()
        except (Exception, asyncio.CancelledError):
            # Shutdown is best effort; later resources must still be released.
            continue


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
