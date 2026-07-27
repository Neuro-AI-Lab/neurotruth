from __future__ import annotations

import asyncio
import base64
import copy
import json
from datetime import date, datetime, timedelta, timezone
from uuid import UUID

import pytest

from app.core.security.crypto import AesGcmKeyring, DecryptionError, aad_for
from app.maintenance.seed_vp012_demo import (
    DEMO_EMAIL,
    DEMO_PATIENT_ID,
    DEMO_SEED,
    PROVISION_CONFIRMATION,
    RECENT_HOUR_TRACE_SOURCE,
    SEOUL,
    DemoSeedError,
    Vp012DemoSeeder,
    prepare_demo,
    run_cli,
)


def keyring() -> AesGcmKeyring:
    encoded = base64.b64encode(b"d" * 32).decode()
    return AesGcmKeyring.from_config(f"demo:{encoded}", "demo")


def fixed_now() -> datetime:
    return datetime(2026, 7, 26, 12, 0, tzinfo=timezone.utc)


def test_prepared_dataset_is_deterministic_bounded_and_non_monotonic() -> None:
    first = prepare_demo(date(2026, 7, 26), keyring(), now=fixed_now())
    second = prepare_demo(date(2026, 7, 26), keyring(), now=fixed_now())
    assert first.start_date == date(2026, 3, 29)
    assert first.anchor_date == date(2026, 7, 26)
    assert [row["id"] for row in first.predictions] == [
        row["id"] for row in second.predictions
    ]
    assert len(first.predictions) == 119 * 24 + 360
    assert len({row["id"] for row in first.predictions}) == len(first.predictions)
    local_dates = {
        row["predicted_at"].astimezone(SEOUL).date() for row in first.predictions
    }
    assert len(local_dates) == 120
    assert max(row["predicted_at"] for row in first.predictions) <= fixed_now()
    recent = sorted(
        (
            row for row in first.predictions
            if fixed_now() - timedelta(hours=1) < row["predicted_at"] <= fixed_now()
        ),
        key=lambda row: row["predicted_at"],
    )
    assert len(recent) == 360
    assert all(
        later["predicted_at"] - earlier["predicted_at"] == timedelta(seconds=10)
        for earlier, later in zip(recent, recent[1:])
    )
    recent_stage_sequence = [
        json.loads(row["output_metadata"])["stage"] for row in recent
    ]
    recent_stage_runs = 1 + sum(
        current != previous
        for previous, current in zip(recent_stage_sequence, recent_stage_sequence[1:])
    )
    assert recent_stage_runs == 16
    assert [row["continuous_value"] for row in recent[:3]] == [
        0.999551, 0.797834, 0.596117,
    ]
    assert [row["continuous_value"] for row in recent[-3:]] == [
        0.636578, 0.640273, 0.643968,
    ]
    assert min(row["continuous_value"] for row in recent) == 0.057622
    assert max(row["continuous_value"] for row in recent) == 0.999551
    assert all(row["continuous_value"] < 0.75 for row in recent[-3:])
    assert all(
        json.loads(row["output_metadata"])["traceSource"]
        == RECENT_HOUR_TRACE_SOURCE
        for row in recent
    )
    assert all(
        json.loads(row["output_metadata"])["traceTransformation"]
        == "ma10_order_preserved_time_normalized_60m"
        for row in recent
    )
    stages = {
        json.loads(row["output_metadata"])["stage"] for row in first.predictions
    }
    assert stages == {"low", "observe", "caution", "high"}
    monthly = []
    for offset in range(0, 120, 30):
        start = first.start_date + timedelta(days=offset)
        end = start + timedelta(days=30)
        values = [
            row["continuous_value"] for row in first.predictions
            if start <= row["predicted_at"].astimezone(SEOUL).date() < end
        ]
        monthly.append(sum(values) / len(values))
    assert monthly != sorted(monthly)
    assert monthly != sorted(monthly, reverse=True)
    ordered = sorted(first.predictions, key=lambda row: (row["predicted_at"], row["id"]))
    indexes = {row["id"]: index for index, row in enumerate(ordered)}
    for alert in first.alerts:
        index = indexes[alert["trigger_prediction_id"]]
        run = ordered[index - 2:index + 1]
        assert len(run) == 3
        assert all(row["continuous_value"] >= 0.75 for row in run)
        assert all(
            later["predicted_at"] - earlier["predicted_at"] <= timedelta(seconds=20)
            for earlier, later in zip(run, run[1:])
        )
    assert all(
        later["triggered_at"] - earlier["triggered_at"] >= timedelta(minutes=15)
        for earlier, later in zip(first.alerts, first.alerts[1:])
    )
    assert all(0 <= row["score"] <= 48 for row in first.assessments)


def test_sensitive_values_use_exact_aad_and_wrong_aad_is_rejected() -> None:
    prepared = prepare_demo(date(2026, 7, 26), keyring(), now=fixed_now())
    assert keyring().decrypt(
        prepared.patient["name_encrypted"],
        aad=aad_for(
            table="patient_profiles", column="name_encrypted",
            patient_id=str(DEMO_PATIENT_ID), record_id=str(DEMO_PATIENT_ID),
        ),
    ).decode() == "정우식"
    message = prepared.messages[0]
    plaintext = keyring().decrypt(
        message["content"],
        aad=aad_for(
            table="messages", column="content_encrypted",
            patient_id=str(DEMO_PATIENT_ID), record_id=str(message["id"]),
        ),
    ).decode()
    assert "식당" in plaintext
    with pytest.raises(DecryptionError):
        keyring().decrypt(
            message["content"],
            aad=aad_for(
                table="messages", column="content_encrypted",
                patient_id=str(DEMO_PATIENT_ID), record_id=str(UUID(int=1)),
            ),
        )


class Rows:
    def __init__(self, values=None):
        self.values = values or []

    def mappings(self):
        return self

    def all(self):
        return self.values

    def one(self):
        return self.values[0]

    def one_or_none(self):
        return self.values[0] if self.values else None


class FakeConnection:
    def __init__(self, engine):
        self.engine = engine

    async def scalar(self, statement, _params=None):
        if "demo:model" in str(statement):
            return self.engine.model_id
        return None

    async def execute(self, statement, params=None):
        sql = " ".join(str(statement).split())
        if "demo:identity" in sql:
            return Rows([copy.deepcopy(self.engine.identity)] if self.engine.identity else [])
        if "demo:manifest" in sql:
            return Rows([{
                "action": self.engine.manifest_action,
                "metadata": copy.deepcopy(self.engine.manifest),
            }] if self.engine.manifest else [])
        if "demo:logout-owner" in sql:
            return Rows([copy.deepcopy(self.engine.logout_owner)] if self.engine.logout_owner else [])
        if "demo:file-paths" in sql:
            return Rows(copy.deepcopy(self.engine.file_paths))
        if "demo:counts" in sql:
            values = {name: len(rows) for name, rows in self.engine.rows.items()}
            predictions = self.engine.rows["craving_predictions"]
            values["first_prediction"] = (
                min(row["predicted_at"] for row in predictions).astimezone(SEOUL).date()
                if predictions else None
            )
            values["last_prediction"] = (
                max(row["predicted_at"] for row in predictions).astimezone(SEOUL).date()
                if predictions else None
            )
            return Rows([values])
        if "demo:insert-user" in sql:
            self.engine.identity = {
                "id": params["id"], "email": params["email"], "role": "patient",
                "status": "active", "pseudonymous_id": None,
            }
        elif "demo:insert-profile" in sql:
            self.engine.identity["pseudonymous_id"] = params["pseudonymous_id"]
        elif "demo:insert-audit" in sql:
            self.engine.manifest = json.loads(params["metadata"])
            self.engine.manifest_action = params["action"]
        elif "demo:insert-" in sql and isinstance(params, list):
            marker = sql.split("demo:insert-", 1)[1].split(" ", 1)[0].rstrip("*/")
            table = {
                "predictions": "craving_predictions",
                "alerts": "craving_alerts",
                "sessions": "sessions",
                "messages": "messages",
                "assessments": "craving_assessments",
                "interventions": "interventions",
                "inferences": "state_inferences",
                "reports": "session_reports",
            }[marker]
            existing = {row["id"] for row in self.engine.rows[table]}
            self.engine.rows[table].extend(
                copy.deepcopy(row) for row in params if row["id"] not in existing
            )
        elif "demo:delete-audit" in sql:
            self.engine.delete_audits.append(json.loads(params["metadata"]))
            self.engine.manifest = None
            self.engine.manifest_action = None
        elif "demo:delete" in sql:
            if sql.endswith("DELETE FROM users WHERE id=:patient_id"):
                self.engine.identity = None
            for rows in self.engine.rows.values():
                rows.clear()
        return Rows()


class Context:
    def __init__(self, engine, transactional):
        self.engine = engine
        self.transactional = transactional

    async def __aenter__(self):
        self.snapshot = copy.deepcopy((
            self.engine.identity, self.engine.manifest, self.engine.rows,
            self.engine.manifest_action, self.engine.delete_audits,
        ))
        return FakeConnection(self.engine)

    async def __aexit__(self, exc_type, _exc, _tb):
        if exc_type and self.transactional:
            (
                self.engine.identity, self.engine.manifest, self.engine.rows,
                self.engine.manifest_action, self.engine.delete_audits,
            ) = self.snapshot


class FakeEngine:
    def __init__(self):
        self.model_id = UUID("11111111-1111-1111-1111-111111111111")
        self.identity = None
        self.manifest = None
        self.manifest_action = None
        self.logout_owner = None
        self.file_paths = []
        self.delete_audits = []
        self.rows = {
            name: [] for name in (
                "craving_predictions", "craving_alerts", "sessions", "messages",
                "craving_assessments", "interventions", "state_inferences",
                "session_reports",
            )
        }

    def connect(self):
        return Context(self, False)

    def begin(self):
        return Context(self, True)


def test_dry_run_has_no_writes_seed_rerun_is_idempotent_and_status_is_sanitized(
    monkeypatch,
) -> None:
    async def scenario():
        engine = FakeEngine()
        seeder = Vp012DemoSeeder(engine, keyring())
        monkeypatch.setattr(
            "app.maintenance.seed_vp012_demo.prepare_demo",
            lambda anchor, keys: prepare_demo(anchor, keys, now=fixed_now()),
        )
        monkeypatch.setattr(
            "app.maintenance.seed_vp012_demo.hash_password",
            lambda _value: "argon2-demo-hash",
        )
        dry = await seeder.dry_run(date(2026, 7, 26), "long-demo-password")
        assert dry.exists is False and engine.identity is None
        first = await seeder.seed(date(2026, 7, 26), "long-demo-password")
        counts = copy.deepcopy(first.table_counts)
        repeated = await seeder.seed(date(2026, 7, 26), "long-demo-password")
        status = await seeder.status()
        assert repeated.table_counts == counts == status.table_counts
        assert status.email == DEMO_EMAIL
        assert status.patient_id == str(DEMO_PATIENT_ID)
        assert (status.start_date, status.end_date) == ("2026-03-29", "2026-07-26")
        assert "password" not in json.dumps(status.__dict__).lower()
        assert engine.manifest["seed"] == DEMO_SEED

    asyncio.run(scenario())


def test_delete_targets_only_reserved_demo_identity(monkeypatch) -> None:
    async def scenario():
        engine = FakeEngine()
        seeder = Vp012DemoSeeder(engine, keyring())
        monkeypatch.setattr(
            "app.maintenance.seed_vp012_demo.prepare_demo",
            lambda anchor, keys: prepare_demo(anchor, keys, now=fixed_now()),
        )
        monkeypatch.setattr(
            "app.maintenance.seed_vp012_demo.hash_password",
            lambda _value: "argon2-demo-hash",
        )
        await seeder.seed(date(2026, 7, 26), "long-demo-password")
        deleted = await seeder.delete()
        assert deleted.exists is True
        assert engine.identity is None
        assert engine.delete_audits[0]["seed"] == DEMO_SEED

        engine.identity = {
            "id": DEMO_PATIENT_ID, "email": "real@example.com", "role": "patient",
            "status": "active", "pseudonymous_id": "REAL",
        }
        with pytest.raises(DemoSeedError, match="demo_identity_conflict"):
            await seeder.delete()

    asyncio.run(scenario())


def test_provision_login_logout_and_reprovision_are_repeatable(monkeypatch) -> None:
    async def scenario():
        engine = FakeEngine()
        seeder = Vp012DemoSeeder(engine, keyring())
        monkeypatch.setattr(
            "app.maintenance.seed_vp012_demo.prepare_demo",
            lambda anchor, keys, **_kwargs: prepare_demo(
                anchor, keys, now=fixed_now(),
            ),
        )
        monkeypatch.setattr(
            "app.maintenance.seed_vp012_demo.hash_password",
            lambda _value: "argon2-demo-hash",
        )

        provisioned = await seeder.provision("long-demo-password", now=fixed_now())
        assert provisioned.exists is True
        assert all(count == 0 for count in provisioned.table_counts.values())
        assert engine.manifest_action == "demo.vp012.armed"

        await seeder.start_for_login(
            user_id=DEMO_PATIENT_ID, email=DEMO_EMAIL, now=fixed_now(),
        )
        first_counts = (await seeder.status()).table_counts
        assert first_counts["craving_predictions"] > 0
        assert engine.manifest_action == "demo.vp012.started"
        await seeder.start_for_login(
            user_id=DEMO_PATIENT_ID, email=DEMO_EMAIL, now=fixed_now(),
        )
        assert (await seeder.status()).table_counts == first_counts

        engine.logout_owner = {"id": DEMO_PATIENT_ID, "email": DEMO_EMAIL}
        assert await seeder.delete_for_logout("refresh-hash") is True
        assert engine.identity is None
        assert engine.delete_audits

        await seeder.provision("long-demo-password", now=fixed_now())
        assert engine.manifest_action == "demo.vp012.armed"
        await seeder.start_for_login(
            user_id=DEMO_PATIENT_ID, email=DEMO_EMAIL, now=fixed_now(),
        )
        assert (await seeder.status()).table_counts == first_counts

    asyncio.run(scenario())


def test_logout_storage_failure_rolls_back_demo_account(monkeypatch) -> None:
    class FailingStorage:
        def delete(self, _path: str) -> None:
            raise OSError("private storage detail")

    async def scenario():
        engine = FakeEngine()
        seeder = Vp012DemoSeeder(
            engine, keyring(), sensor_storage=FailingStorage(),
        )
        monkeypatch.setattr(
            "app.maintenance.seed_vp012_demo.prepare_demo",
            lambda anchor, keys, **_kwargs: prepare_demo(
                anchor, keys, now=fixed_now(),
            ),
        )
        monkeypatch.setattr(
            "app.maintenance.seed_vp012_demo.hash_password",
            lambda _value: "argon2-demo-hash",
        )
        await seeder.provision("long-demo-password", now=fixed_now())
        await seeder.start_for_login(
            user_id=DEMO_PATIENT_ID, email=DEMO_EMAIL, now=fixed_now(),
        )
        counts = copy.deepcopy((await seeder.status()).table_counts)
        engine.logout_owner = {"id": DEMO_PATIENT_ID, "email": DEMO_EMAIL}
        engine.file_paths = [{"kind": "sensor", "storage_uri": "demo.ntg"}]

        with pytest.raises(OSError, match="private storage detail"):
            await seeder.delete_for_logout("refresh-hash")
        assert engine.identity is not None
        assert engine.manifest_action == "demo.vp012.started"
        assert (await seeder.status()).table_counts == counts

    asyncio.run(scenario())


def test_cli_rejects_bad_confirmation_and_weak_password_before_database(
    monkeypatch, capsys,
) -> None:
    def forbidden(*_args, **_kwargs):
        raise AssertionError("database must not be opened")

    assert run_cli(["--confirm", "WRONG"], engine_factory=forbidden) == 2
    assert run_cli(
        ["--provision-confirm", PROVISION_CONFIRMATION + "-WRONG"],
        engine_factory=forbidden,
    ) == 2
    monkeypatch.setenv("DEMO_PATIENT_PASSWORD", "short")
    monkeypatch.setenv("DATABASE_URL", "postgresql://unused")
    assert run_cli(["--dry-run"], engine_factory=forbidden) == 1
    output = capsys.readouterr()
    assert "no database connection" in output.err
    assert "short" not in output.err
