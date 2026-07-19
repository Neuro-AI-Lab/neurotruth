from __future__ import annotations

import asyncio
import shutil
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi import FastAPI

from app.core.security.crypto import AesGcmKeyring
from app.core import runtime as runtime_module


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
    monkeypatch.setattr("app.repositories.rppg.SqlAlchemyRppgRepository", lambda engine: SimpleNamespace(engine=engine))
    monkeypatch.setattr("app.storage.rppg.EncryptedRppgStorage", lambda *args: SimpleNamespace(args=args))
    monkeypatch.setattr("app.services.admin.AdminService", Admin)
    monkeypatch.setattr("app.storage.sensor.EncryptedSensorStorage", lambda *args: SimpleNamespace(args=args))
    monkeypatch.setattr(runtime_module, "BedrockClaudeAdapter", lambda: object())
    monkeypatch.setattr(runtime_module, "InterventionAgent", lambda _: object())
    monkeypatch.setattr(runtime_module, "StateSummaryAgent", lambda _: object())
    monkeypatch.setattr(runtime_module, "ReportAgent", lambda _: object())
    monkeypatch.setattr("app.services.session.SessionService", Session)

    async def exercise() -> None:
        app = FastAPI()
        runtime = await runtime_module.initialize_runtime(app)
        assert runtime.ready and runtime.error_code is None
        assert Settings.sensor_storage_root.is_dir()
        assert Settings.rppg_storage_root.is_dir()
        await runtime_module.shutdown_runtime(app)

    asyncio.run(exercise())


def test_partial_runtime_failure_closes_repository_once(workdir: Path, monkeypatch) -> None:
    instances = []

    class Connection:
        async def __aenter__(self): return self
        async def __aexit__(self, *_): return None
        async def execute(self, *_): return None
        async def scalar(self, *_): return runtime_module.REQUIRED_REVISION

    class Engine:
        def connect(self): return Connection()

    class Repository:
        def __init__(self, _):
            self.engine = Engine()
            self.close_calls = 0
            instances.append(self)

        async def close(self):
            self.close_calls += 1

    class Settings:
        database_url = "postgresql+asyncpg://test"
        sensor_storage_root = workdir / "sensors"
        rppg_enabled = False
        jwt_signing_key = "J" * 32
        access_token_minutes = 15
        refresh_token_days = 30
        admin_signup_code = SimpleNamespace(get_secret_value=lambda: "A" * 16)

    class FailingAuth:
        def __init__(self, *_, **__): pass

        async def bootstrap_admin_code(self, _):
            raise RuntimeError("bootstrap failed")

    monkeypatch.setattr(runtime_module, "SecuritySettings", Settings)
    monkeypatch.setattr(runtime_module, "SqlAlchemyV25Repository", Repository)
    monkeypatch.setattr(runtime_module, "AuthService", FailingAuth)

    async def exercise() -> None:
        app = FastAPI()
        runtime = await runtime_module.initialize_runtime(app)

        assert runtime.ready is False
        assert runtime.error_code == "v25_unavailable"
        assert runtime.repository is None
        assert instances[0].close_calls == 1

        await runtime_module.shutdown_runtime(app)
        assert instances[0].close_calls == 1

    asyncio.run(exercise())


def test_cancelled_shutdown_caller_does_not_interrupt_ordered_cleanup() -> None:
    async def exercise() -> None:
        events: list[str] = []
        entered = asyncio.Event()
        release = asyncio.Event()

        class BlockingSession:
            calls = 0

            async def shutdown(self) -> None:
                self.calls += 1
                events.append("session.shutdown")
                entered.set()
                await release.wait()

        class Resource:
            def __init__(self, name: str, method_name: str, *, cancel: bool = False) -> None:
                self.name = name
                self.calls = 0
                self.cancel = cancel
                setattr(self, method_name, self.close)

            async def close(self) -> None:
                self.calls += 1
                events.append(self.name)
                if self.cancel:
                    raise asyncio.CancelledError

        session = BlockingSession()
        rppg = Resource("rppg.shutdown", "shutdown", cancel=True)
        stt = Resource("stt.close", "close")
        repository = Resource("repository.close", "close")
        runtime = runtime_module.BackendRuntime(
            ready=True,
            session_service=session,
            rppg_service=rppg,
            stt_service=stt,
            repository=repository,
            error_code=None,
        )
        app = FastAPI()
        app.state.backend_runtime = runtime

        first = asyncio.create_task(runtime_module.shutdown_runtime(app))
        await asyncio.wait_for(entered.wait(), timeout=1)
        first.cancel()
        with pytest.raises(asyncio.CancelledError):
            await first
        assert runtime.shutdown_task is not None and not runtime.shutdown_task.done()

        second = asyncio.create_task(runtime_module.shutdown_runtime(app))
        await asyncio.sleep(0)
        assert not second.done()
        release.set()
        await asyncio.wait_for(second, timeout=1)

        assert events == [
            "session.shutdown", "rppg.shutdown", "stt.close", "repository.close",
        ]
        assert session.calls == rppg.calls == stt.calls == repository.calls == 1
        assert runtime.shutdown_task is None

    asyncio.run(exercise())


def test_lifespan_rppg_start_failure_cleans_runtime_before_prediction(workdir: Path, monkeypatch) -> None:
    from app import main as main_module

    events: list[str] = []

    class Resource:
        def __init__(self, name: str, method_name: str) -> None:
            self.name = name
            self.calls = 0
            setattr(self, method_name, self.close)

        async def close(self) -> None:
            self.calls += 1
            events.append(self.name)

    class FailingRppg(Resource):
        def __init__(self) -> None:
            super().__init__("rppg.shutdown", "shutdown")

        async def start(self) -> None:
            events.append("rppg.start")
            raise RuntimeError("rppg startup failure")

    class Prediction:
        def __init__(self) -> None:
            self.hub = object()
            self.latency = object()
            self.alerts = SimpleNamespace()

        async def start(self) -> None:
            events.append("prediction.start")

        async def stop(self) -> None:
            events.append("prediction.stop")

    ring = AesGcmKeyring({"v1": b"K" * 32}, "v1")
    repository = Resource("repository.close", "close")
    session = Resource("session.shutdown", "shutdown")
    stt = Resource("stt.close", "close")
    failing_rppg = FailingRppg()
    runtime = runtime_module.BackendRuntime(
        ready=True,
        settings=SimpleNamespace(
            sensor_storage_root=workdir / "sensors",
            keyring=lambda: ring,
            rppg_base_url="http://rppg.test",
            rppg_connect_timeout_seconds=1,
            rppg_read_timeout_seconds=1,
            rppg_ffprobe_path="ffprobe",
            rppg_enabled=True,
            rppg_max_concurrency=1,
        ),
        repository=repository,
        session_service=session,
        stt_service=stt,
        rppg_repository=object(),
        rppg_storage=object(),
        admin_service=SimpleNamespace(configure_rppg=lambda *_: None),
        error_code=None,
    )
    prediction = Prediction()

    async def initialize(app: FastAPI):
        app.state.backend_runtime = runtime
        return runtime

    async def shutdown(app: FastAPI) -> None:
        events.append("runtime.shutdown")
        await runtime_module.shutdown_runtime(app)

    monkeypatch.setattr(main_module, "initialize_runtime", initialize)
    monkeypatch.setattr(main_module, "shutdown_runtime", shutdown)
    monkeypatch.setattr(main_module, "prediction_service", prediction)
    monkeypatch.setattr(main_module, "RuntimePredictorAdapter", lambda *_: object())
    monkeypatch.setattr(main_module, "SensorService", lambda *_: object())
    monkeypatch.setattr(main_module, "EncryptedSensorStorage", lambda *_: object())
    monkeypatch.setattr(main_module, "DgxClient", lambda *_args, **_kwargs: object())
    monkeypatch.setattr(main_module, "FfprobeMediaInspector", lambda *_: object())
    monkeypatch.setattr(main_module, "RppgService", lambda **_: failing_rppg)

    async def exercise() -> None:
        app = FastAPI()
        with pytest.raises(RuntimeError, match="rppg startup failure"):
            async with main_module.lifespan(app):
                raise AssertionError("lifespan must not yield after failed startup")

        assert events == [
            "prediction.start",
            "rppg.start",
            "runtime.shutdown",
            "session.shutdown",
            "rppg.shutdown",
            "stt.close",
            "repository.close",
            "prediction.stop",
        ]
        assert session.calls == stt.calls == repository.calls == failing_rppg.calls == 1

        await runtime_module.shutdown_runtime(app)
        assert session.calls == stt.calls == repository.calls == failing_rppg.calls == 1

    asyncio.run(exercise())
