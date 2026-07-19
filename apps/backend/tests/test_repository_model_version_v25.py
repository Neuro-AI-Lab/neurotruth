from __future__ import annotations

import asyncio
import json
from datetime import datetime, timezone
from uuid import UUID

from app.repositories.postgres import SqlAlchemyV25Repository


class _Result:
    def __init__(self, row=None, scalar=None):
        self._row = row
        self._scalar = scalar

    def mappings(self):
        return self

    def one_or_none(self):
        return self._row

    def scalar_one(self):
        return self._scalar

    def all(self):
        return []


class _Connection:
    def __init__(self):
        self.calls = []

    async def execute(self, statement, parameters=None):
        self.calls.append((statement, parameters or {}))
        sql = str(statement)
        if "SELECT id,model_name,model_version" in sql:
            return _Result(row=None)
        if "INSERT INTO model_versions" in sql:
            return _Result(scalar=UUID("00000000-0000-0000-0000-000000000123"))
        return _Result()


class _Transaction:
    def __init__(self, connection):
        self.connection = connection

    async def __aenter__(self):
        return self.connection

    async def __aexit__(self, exc_type, exc, traceback):
        return False


class _Engine:
    def __init__(self):
        self.connection = _Connection()

    def begin(self):
        return _Transaction(self.connection)

    def connect(self):
        return _Transaction(self.connection)


def test_craving_model_json_uses_named_parameters_not_numeric_bind_tokens() -> None:
    async def scenario() -> None:
        engine = _Engine()
        repository = SqlAlchemyV25Repository(
            "postgresql+asyncpg://unused", engine=engine
        )

        model_id = await repository.ensure_craving_model_version(
            model_name="rf",
            model_version="fixture-v1",
            artifact_uri="/models/rf.joblib",
        )

        assert model_id == UUID("00000000-0000-0000-0000-000000000123")
        statement, parameters = next(
            call for call in engine.connection.calls if "INSERT INTO model_versions" in str(call[0])
        )
        assert set(statement._bindparams) == {
            "name", "version", "inference_task", "output_schema", "config", "artifact"
        }
        assert all(not key.isdigit() for key in statement._bindparams)
        assert json.loads(parameters["output_schema"])["classes"] == [
            {"index": 0, "code": "low"}, {"index": 1, "code": "high"}
        ]
        assert parameters["inference_task"] == "binary_classification"
        assert json.loads(parameters["config"]) == {"window_sec": 10}

    asyncio.run(scenario())


def test_optional_inference_filters_and_terminal_statuses_have_explicit_sql_types() -> None:
    async def scenario() -> None:
        engine = _Engine()
        repository = SqlAlchemyV25Repository(
            "postgresql+asyncpg://unused", engine=engine
        )
        patient_id = UUID("00000000-0000-0000-0000-000000000124")
        session_id = UUID("00000000-0000-0000-0000-000000000125")

        await repository.inference_evidence(patient_id, session_id)
        await repository.craving_probability_rows(
            patient_id,
            datetime(2026, 7, 15, tzinfo=timezone.utc),
            datetime(2026, 7, 16, tzinfo=timezone.utc),
            60,
            1440,
        )
        await repository.craving_dashboard_rows(
            patient_id,
            "Asia/Seoul",
            datetime(2026, 7, 15, 15, tzinfo=timezone.utc),
            datetime(2026, 7, 16, 15, tzinfo=timezone.utc),
            datetime(2026, 7, 9, 15, tzinfo=timezone.utc),
            datetime(2026, 7, 15, 15, tzinfo=timezone.utc),
            "hour",
        )
        await repository.finish_session(
            session_id, status="completed", reason="normal"
        )
        await repository.finish_report_job(
            id=UUID("00000000-0000-0000-0000-000000000126"),
            status="ready",
            content=None,
            key=None,
            failure=None,
        )

        sql = "\n".join(str(statement) for statement, _ in engine.connection.calls)
        assert ":session IS NULL" not in sql
        assert ":since IS NULL" not in sql
        assert "CAST(:session AS uuid) IS NULL" in sql
        assert "CAST(:since AS timestamptz) IS NULL" in sql
        assert "CAST(:status AS varchar)='abandoned'" in sql
        assert "CAST(:status AS varchar)='ready'" in sql
        assert "p.predicted_at<=:until" in sql
        assert "ORDER BY bucket_at DESC LIMIT :max_points" in sql
        assert "ORDER BY bucket_at" in sql
        assert "timezone(:timezone,p.predicted_at)" in sql
        assert "m.inference_task='binary_classification'" in sql
        assert "m.is_active" in sql
        assert "a.instrument_code='AUQ'" in sql
        assert "a.trigger_reason->>'alertLevel'" in sql

    asyncio.run(scenario())
