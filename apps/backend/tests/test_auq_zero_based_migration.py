from __future__ import annotations

import asyncio
import base64
import copy
import json
from decimal import Decimal
from uuid import uuid4

import pytest

from app.core.security.crypto import AesGcmKeyring, EncryptedEnvelope, aad_for
from app.maintenance.migrate_auq_zero_based import (
    AuqMigrationError,
    AuqZeroBasedMigrator,
    run_cli,
)


def keyring(current: str = "v2") -> AesGcmKeyring:
    config = ",".join((
        "v1:" + base64.b64encode(b"a" * 32).decode(),
        "v2:" + base64.b64encode(b"b" * 32).decode(),
    ))
    return AesGcmKeyring.from_config(config, current)


def legacy_row(*, answers: dict | None = None) -> dict:
    record_id, session_id, patient_id = uuid4(), uuid4(), uuid4()
    payload = answers or {
        "responses": [1, 2, 3, 4, 5, 6, 7, 1],
        "scoredItems": [1, 2, 3, 4, 5, 6, 7, 1],
        "rawTotalScore": 29,
        "capturedAtMs": 1_784_160_000_000,
    }
    packed = keyring("v1").encrypt(
        json.dumps(payload, separators=(",", ":")).encode(),
        aad=aad_for(
            table="craving_assessments", column="answers_encrypted",
            patient_id=str(patient_id), record_id=str(record_id),
        ),
    ).pack()
    return {
        "id": record_id,
        "session_id": session_id,
        "patient_id": patient_id,
        "instrument_version": "1.0",
        "answers_encrypted": packed,
        "encryption_key_version": "v1",
        "raw_score": Decimal("29"),
        "scale_min": Decimal("8"),
        "scale_max": Decimal("56"),
        "scoring_metadata": {"adaptation": "ko-research"},
    }


class Result:
    def __init__(self, rows=None): self.rows = rows or []
    def mappings(self): return self
    def all(self): return self.rows


class Connection:
    def __init__(self, engine): self.engine = engine

    async def execute(self, statement, params=None):
        sql = " ".join(str(statement).split())
        params = params or {}
        if "SELECT a.id,a.session_id" in sql:
            return Result(copy.deepcopy(self.engine.rows))
        if sql.startswith("UPDATE craving_assessments"):
            self.engine.update_calls += 1
            if self.engine.fail_update_at == self.engine.update_calls:
                raise RuntimeError("simulated_update_failure")
            row = next(row for row in self.engine.rows if row["id"] == params["id"])
            row.update(
                instrument_version="2.0",
                answers_encrypted=params["answers"],
                encryption_key_version=params["key_version"],
                raw_score=params["raw_score"],
                scale_min=0,
                scale_max=48,
                scoring_metadata=json.loads(params["metadata"]),
            )
            return Result()
        if sql.startswith("INSERT INTO audit_logs"):
            self.engine.audits.append(json.loads(params["metadata"]))
            return Result()
        return Result()


class Context:
    def __init__(self, engine, transactional):
        self.engine, self.transactional = engine, transactional

    async def __aenter__(self):
        self.snapshot = (copy.deepcopy(self.engine.rows), copy.deepcopy(self.engine.audits))
        return Connection(self.engine)

    async def __aexit__(self, exc_type, _exc, _tb):
        if exc_type is not None and self.transactional:
            self.engine.rows, self.engine.audits = self.snapshot


class Engine:
    def __init__(self, rows, *, fail_update_at=None):
        self.rows = copy.deepcopy(rows)
        self.audits = []
        self.update_calls = 0
        self.fail_update_at = fail_update_at

    def connect(self): return Context(self, False)
    def begin(self): return Context(self, True)


def decrypt_answers(row: dict) -> dict:
    return json.loads(keyring().decrypt(
        row["answers_encrypted"],
        aad=aad_for(
            table="craving_assessments", column="answers_encrypted",
            patient_id=str(row["patient_id"]), record_id=str(row["id"]),
        ),
    ))


def test_dry_run_validates_encrypted_rows_without_writing() -> None:
    async def scenario():
        original = legacy_row()
        engine = Engine([original])
        report = await AuqZeroBasedMigrator(engine, keyring()).dry_run()
        assert (report.mode, report.assessed_rows, report.converted_rows) == ("dry-run", 1, 1)
        assert engine.rows[0]["instrument_version"] == "1.0"
        assert engine.rows[0]["answers_encrypted"] == original["answers_encrypted"]
        assert engine.audits == []

    asyncio.run(scenario())


def test_confirm_reencrypts_with_new_nonce_audits_and_is_idempotent() -> None:
    async def scenario():
        original = legacy_row()
        original_nonce = EncryptedEnvelope.unpack(original["answers_encrypted"]).nonce
        engine = Engine([original])
        migrator = AuqZeroBasedMigrator(engine, keyring())
        report = await migrator.convert()
        row = engine.rows[0]
        assert (report.converted_rows, report.current_rows) == (1, 0)
        assert (row["instrument_version"], row["raw_score"], row["scale_min"], row["scale_max"]) == ("2.0", 21, 0, 48)
        assert row["encryption_key_version"] == "v2"
        assert EncryptedEnvelope.unpack(row["answers_encrypted"]).nonce != original_nonce
        answers = decrypt_answers(row)
        assert answers["responses"] == [0, 1, 2, 3, 4, 5, 6, 0]
        assert answers["scoredItems"] == answers["responses"]
        assert answers["rawTotalScore"] == 21
        assert row["scoring_metadata"]["zeroBasedMigration"]["sourceScaleMin"] == 8
        assert engine.audits[0]["convertedRows"] == 1

        repeated = await migrator.convert()
        assert (repeated.converted_rows, repeated.current_rows) == (0, 1)
        assert len(engine.audits) == 1

    asyncio.run(scenario())


@pytest.mark.parametrize("failure", ["tampered", "nonstandard"])
def test_invalid_row_aborts_before_any_write(failure: str) -> None:
    async def scenario():
        good, bad = legacy_row(), legacy_row()
        if failure == "tampered":
            packed = bytearray(bad["answers_encrypted"])
            packed[-1] ^= 1
            bad["answers_encrypted"] = bytes(packed)
        else:
            bad["raw_score"] = Decimal("30")
        engine = Engine([good, bad])
        with pytest.raises(AuqMigrationError):
            await AuqZeroBasedMigrator(engine, keyring()).convert()
        assert all(row["instrument_version"] == "1.0" for row in engine.rows)
        assert engine.update_calls == 0 and engine.audits == []

    asyncio.run(scenario())


def test_database_failure_rolls_back_prior_update_and_audit() -> None:
    async def scenario():
        engine = Engine([legacy_row(), legacy_row()], fail_update_at=2)
        with pytest.raises(RuntimeError, match="simulated_update_failure"):
            await AuqZeroBasedMigrator(engine, keyring()).convert()
        assert all(row["instrument_version"] == "1.0" for row in engine.rows)
        assert engine.audits == []

    asyncio.run(scenario())


def test_cli_rejects_wrong_confirmation_before_opening_database(capsys) -> None:
    def forbidden(*_args, **_kwargs):
        raise AssertionError("database must not be opened")

    assert run_cli(["--confirm", "WRONG"], engine_factory=forbidden) == 2
    assert "confirmation token did not match" in capsys.readouterr().err
