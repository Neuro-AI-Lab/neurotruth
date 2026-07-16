from __future__ import annotations

import asyncio
from uuid import UUID

import pytest

from app.maintenance.purge_legacy_predictions import (
    ADVISORY_LOCK_KEY,
    CONFIRMATION,
    LegacyPredictionPurger,
    run_cli,
)


LEGACY_MODEL_ID = UUID("00000000-0000-0000-0000-000000000301")


class _MappingsResult:
    def __init__(self, *, rows=None, row=None, rowcount=0):
        self._rows = rows or []
        self._row = row
        self.rowcount = rowcount

    def mappings(self):
        return self

    def all(self):
        return self._rows

    def one(self):
        return self._row


class _Connection:
    def __init__(self, *, mismatch=False):
        self.calls = []
        self.mismatch = mismatch

    async def execute(self, statement, parameters=None):
        sql = str(statement)
        self.calls.append((sql, parameters or {}))
        if "SELECT model.id" in sql:
            return _MappingsResult(
                rows=[
                    {
                        "id": LEGACY_MODEL_ID,
                        "artifact_sha256": "abc123",
                    }
                ]
            )
        if "AS prediction_count" in sql:
            return _MappingsResult(
                row={
                    "prediction_count": 4,
                    "alert_count": 2,
                    "state_inference_count": 1,
                }
            )
        if "DELETE FROM craving_alerts" in sql:
            return _MappingsResult(rowcount=2)
        if "DELETE FROM state_inferences" in sql:
            return _MappingsResult(rowcount=1)
        if "DELETE FROM craving_predictions" in sql:
            return _MappingsResult(rowcount=3 if self.mismatch else 4)
        return _MappingsResult()


class _Context:
    def __init__(self, engine, *, transactional):
        self.engine = engine
        self.transactional = transactional

    async def __aenter__(self):
        return self.engine.connection

    async def __aexit__(self, exc_type, exc, traceback):
        if self.transactional:
            self.engine.transaction_exit_type = exc_type
        return False


class _Engine:
    def __init__(self, *, mismatch=False):
        self.connection = _Connection(mismatch=mismatch)
        self.transaction_exit_type = "not-used"
        self.disposed = False

    def connect(self):
        return _Context(self, transactional=False)

    def begin(self):
        return _Context(self, transactional=True)

    async def dispose(self):
        self.disposed = True


def _sql_calls(engine):
    return [sql for sql, _ in engine.connection.calls]


def test_dry_run_is_read_only_and_reports_exact_scope() -> None:
    async def scenario():
        engine = _Engine()
        report = await LegacyPredictionPurger(engine).dry_run()

        assert report.mode == "dry-run"
        assert report.legacy_models == [
            {"id": str(LEGACY_MODEL_ID), "artifactSha256": "abc123"}
        ]
        assert (report.prediction_count, report.alert_count, report.state_inference_count) == (4, 2, 1)
        sql = "\n".join(_sql_calls(engine))
        assert "inference_task = 'multiclass_classification'" in sql
        for code in ("low", "mid", "high"):
            assert f"= '{code}'" in sql
        assert "DELETE FROM" not in sql
        assert "INSERT INTO audit_logs" not in sql
        assert "pg_advisory_xact_lock" not in sql
        assert engine.transaction_exit_type == "not-used"

    asyncio.run(scenario())


def test_confirmed_cleanup_locks_deletes_in_order_and_audits() -> None:
    async def scenario():
        engine = _Engine()
        report = await LegacyPredictionPurger(engine).purge()

        assert report.mode == "confirmed"
        sql = _sql_calls(engine)
        lock = next(i for i, value in enumerate(sql) if "pg_advisory_xact_lock" in value)
        alerts = next(i for i, value in enumerate(sql) if "DELETE FROM craving_alerts" in value)
        inferences = next(i for i, value in enumerate(sql) if "DELETE FROM state_inferences" in value)
        predictions = next(i for i, value in enumerate(sql) if "DELETE FROM craving_predictions" in value)
        audit = next(i for i, value in enumerate(sql) if "INSERT INTO audit_logs" in value)
        assert lock < alerts < inferences < predictions < audit
        assert engine.connection.calls[lock][1] == {"key": ADVISORY_LOCK_KEY}
        audit_parameters = engine.connection.calls[audit][1]
        assert '"cravingAlerts":2' in audit_parameters["metadata"]
        assert '"stateInferences":1' in audit_parameters["metadata"]
        assert '"cravingPredictions":4' in audit_parameters["metadata"]
        assert '"artifactSha256":"abc123"' in audit_parameters["metadata"]
        assert engine.transaction_exit_type is None

    asyncio.run(scenario())


def test_count_mismatch_raises_inside_transaction_so_it_rolls_back_without_audit() -> None:
    async def scenario():
        engine = _Engine(mismatch=True)
        with pytest.raises(RuntimeError, match="legacy_cleanup_count_mismatch"):
            await LegacyPredictionPurger(engine).purge()

        assert engine.transaction_exit_type is RuntimeError
        assert not any("INSERT INTO audit_logs" in sql for sql in _sql_calls(engine))

    asyncio.run(scenario())


def test_wrong_confirmation_never_constructs_an_engine(monkeypatch, capsys) -> None:
    monkeypatch.setenv("DATABASE_URL", "postgresql+asyncpg://unused")

    def forbidden_engine_factory(*args, **kwargs):
        raise AssertionError("engine must not be constructed")

    assert run_cli(
        ["--confirm", "WRONG"], engine_factory=forbidden_engine_factory
    ) == 2
    assert "no database connection was made" in capsys.readouterr().err


def test_no_arguments_default_to_dry_run(monkeypatch, capsys) -> None:
    monkeypatch.setenv("DATABASE_URL", "postgresql+asyncpg://unused")
    engine = _Engine()

    assert run_cli([], engine_factory=lambda *args, **kwargs: engine) == 0
    assert '"mode":"dry-run"' in capsys.readouterr().out
    assert engine.disposed is True
    assert not any("DELETE FROM" in sql for sql in _sql_calls(engine))


def test_exact_confirmation_executes(monkeypatch, capsys) -> None:
    monkeypatch.setenv("DATABASE_URL", "postgresql+asyncpg://unused")
    engine = _Engine()

    assert run_cli(
        ["--confirm", CONFIRMATION],
        engine_factory=lambda *args, **kwargs: engine,
    ) == 0
    assert '"mode":"confirmed"' in capsys.readouterr().out
    assert any("DELETE FROM craving_predictions" in sql for sql in _sql_calls(engine))
    assert engine.disposed is True
