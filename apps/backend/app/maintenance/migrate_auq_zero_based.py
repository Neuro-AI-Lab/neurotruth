from __future__ import annotations

import argparse
import asyncio
import json
import math
import os
import sys
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any, Callable, Sequence
from uuid import UUID

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine

from app.core.security.crypto import AesGcmKeyring, EncryptedEnvelope, aad_for


CONFIRMATION = "CONVERT-AUQ-TO-0-48"
ADVISORY_LOCK_KEY = "neurotruth-convert-auq-zero-based"


class AuqMigrationError(RuntimeError):
    """Raised when any AUQ row fails the all-or-nothing conversion contract."""


@dataclass(frozen=True)
class MigrationReport:
    mode: str
    assessed_rows: int
    converted_rows: int
    current_rows: int


def _rows_sql(*, lock: bool) -> Any:
    suffix = " FOR UPDATE OF a" if lock else ""
    return text(f"""
        SELECT a.id,a.session_id,s.patient_id,a.instrument_version,
               a.answers_encrypted,a.encryption_key_version,a.raw_score,
               a.scale_min,a.scale_max,a.scoring_metadata
        FROM craving_assessments a
        JOIN sessions s ON s.id=a.session_id
        WHERE a.instrument_code='AUQ'
        ORDER BY a.completed_at,a.id{suffix}
    """)


_UPDATE_SQL = text("""
    UPDATE craving_assessments
    SET instrument_version='2.0',answers_encrypted=:answers,
        encryption_key_version=:key_version,raw_score=:raw_score,
        scale_min=0,scale_max=48,scoring_metadata=CAST(:metadata AS jsonb)
    WHERE id=:id
""")

_AUDIT_SQL = text("""
    INSERT INTO audit_logs
      (actor_id,actor_role,action,resource_type,resource_id,metadata)
    VALUES
      (NULL,'system','auq.zero_based_migrated','craving_assessment',NULL,
       CAST(:metadata AS jsonb))
""")


class AuqZeroBasedMigrator:
    """Validate all AUQ rows and atomically convert legacy encrypted answers."""

    def __init__(self, engine: AsyncEngine, keyring: AesGcmKeyring) -> None:
        self.engine = engine
        self.keyring = keyring

    async def dry_run(self) -> MigrationReport:
        async with self.engine.connect() as connection:
            rows = await self._rows(connection, lock=False)
            conversions = [self._prepare(row) for row in rows]
        return self._report("dry-run", rows, conversions)

    async def convert(self) -> MigrationReport:
        async with self.engine.begin() as connection:
            await connection.execute(
                text("SELECT pg_advisory_xact_lock(hashtext(:key))"),
                {"key": ADVISORY_LOCK_KEY},
            )
            rows = await self._rows(connection, lock=True)
            conversions = [self._prepare(row) for row in rows]
            changed = [item for item in conversions if item is not None]
            for item in changed:
                await connection.execute(_UPDATE_SQL, item)
            if changed:
                await connection.execute(_AUDIT_SQL, {
                    "metadata": json.dumps({
                        "convertedRows": len(changed),
                        "sourceContract": {
                            "versions": ["1", "1.0"],
                            "itemRange": [1, 7],
                            "totalRange": [8, 56],
                        },
                        "targetContract": {
                            "version": "2.0",
                            "itemRange": [0, 6],
                            "totalRange": [0, 48],
                        },
                    }, separators=(",", ":")),
                })
        return self._report("confirmed", rows, conversions)

    @staticmethod
    async def _rows(connection: Any, *, lock: bool) -> list[dict[str, Any]]:
        result = await connection.execute(_rows_sql(lock=lock))
        return [dict(row) for row in result.mappings().all()]

    def _prepare(self, row: dict[str, Any]) -> dict[str, Any] | None:
        row_id = UUID(str(row["id"]))
        patient_id = UUID(str(row["patient_id"]))
        packed = bytes(row["answers_encrypted"])
        envelope = EncryptedEnvelope.unpack(packed)
        if envelope.key_id != str(row["encryption_key_version"]):
            raise AuqMigrationError("auq_encryption_key_version_mismatch")
        try:
            plaintext = self.keyring.decrypt(packed, aad=aad_for(
                table="craving_assessments",
                column="answers_encrypted",
                patient_id=str(patient_id),
                record_id=str(row_id),
            ))
            answers = json.loads(plaintext)
        except Exception as exc:
            raise AuqMigrationError("auq_answers_invalid") from exc
        if not isinstance(answers, dict) or not isinstance(row.get("scoring_metadata"), dict):
            raise AuqMigrationError("auq_contract_invalid")

        version = str(row["instrument_version"])
        minimum = self._whole_number(row["scale_min"])
        maximum = self._whole_number(row["scale_max"])
        score = self._whole_number(row["raw_score"])
        if version == "2.0" and (minimum, maximum) == (0, 48):
            self._validate_answers(answers, minimum=0, maximum=6, score=score)
            return None
        if version not in {"1", "1.0"} or (minimum, maximum) != (8, 56):
            raise AuqMigrationError("auq_legacy_contract_invalid")
        responses, scored = self._validate_answers(
            answers, minimum=1, maximum=7, score=score,
        )
        converted_answers = {
            **answers,
            "responses": [value - 1 for value in responses],
            "scoredItems": [value - 1 for value in scored],
            "rawTotalScore": score - 8,
        }
        metadata = {
            **row["scoring_metadata"],
            "zeroBasedMigration": {
                "sourceVersion": version,
                "sourceScaleMin": minimum,
                "sourceScaleMax": maximum,
                "sourceRawScore": score,
                "targetVersion": "2.0",
                "convertedAt": datetime.now(timezone.utc).isoformat(),
            },
        }
        encrypted = self.keyring.encrypt(
            json.dumps(
                converted_answers, ensure_ascii=False, separators=(",", ":"),
            ).encode("utf-8"),
            aad=aad_for(
                table="craving_assessments",
                column="answers_encrypted",
                patient_id=str(patient_id),
                record_id=str(row_id),
            ),
        ).pack()
        return {
            "id": row_id,
            "answers": encrypted,
            "key_version": self.keyring.current_key_id,
            "raw_score": score - 8,
            "metadata": json.dumps(metadata, ensure_ascii=False, separators=(",", ":")),
        }

    @classmethod
    def _validate_answers(
        cls, answers: dict[str, Any], *, minimum: int, maximum: int, score: int,
    ) -> tuple[list[int], list[int]]:
        responses = answers.get("responses")
        scored = answers.get("scoredItems")
        if not isinstance(responses, list) or not isinstance(scored, list):
            raise AuqMigrationError("auq_answer_arrays_missing")
        if len(responses) != 8 or len(scored) != 8:
            raise AuqMigrationError("auq_answer_count_invalid")
        values = [cls._whole_number(value) for value in responses]
        scored_values = [cls._whole_number(value) for value in scored]
        if (
            values != scored_values
            or any(not minimum <= value <= maximum for value in values)
            or sum(scored_values) != score
            or cls._whole_number(answers.get("rawTotalScore")) != score
        ):
            raise AuqMigrationError("auq_answer_score_mismatch")
        return values, scored_values

    @staticmethod
    def _whole_number(value: Any) -> int:
        if isinstance(value, bool) or value is None:
            raise AuqMigrationError("auq_numeric_value_invalid")
        if isinstance(value, Decimal):
            if value != value.to_integral_value():
                raise AuqMigrationError("auq_numeric_value_invalid")
            return int(value)
        if not isinstance(value, (int, float)) or not math.isfinite(float(value)):
            raise AuqMigrationError("auq_numeric_value_invalid")
        if float(value) != int(value):
            raise AuqMigrationError("auq_numeric_value_invalid")
        return int(value)

    @staticmethod
    def _report(
        mode: str, rows: list[dict[str, Any]], conversions: list[dict[str, Any] | None],
    ) -> MigrationReport:
        changed = sum(item is not None for item in conversions)
        return MigrationReport(mode, len(rows), changed, len(rows) - changed)


def _database_url() -> str:
    value = os.getenv("DATABASE_URL", "").strip()
    if value.startswith("postgresql://"):
        value = "postgresql+asyncpg://" + value.removeprefix("postgresql://")
    if not value.startswith("postgresql+asyncpg://"):
        raise RuntimeError("DATABASE_URL must use PostgreSQL")
    return value


def _keyring() -> AesGcmKeyring:
    return AesGcmKeyring.from_config(
        os.getenv("DATA_ENCRYPTION_KEYS_B64", ""),
        os.getenv("DATA_ENCRYPTION_CURRENT_KEY_ID", ""),
    )


async def _run(*, execute: bool, engine_factory: Callable[..., AsyncEngine]) -> MigrationReport:
    engine = engine_factory(_database_url(), pool_pre_ping=True)
    try:
        migrator = AuqZeroBasedMigrator(engine, _keyring())
        return await (migrator.convert() if execute else migrator.dry_run())
    finally:
        await engine.dispose()


def run_cli(
    argv: Sequence[str] | None = None,
    *,
    engine_factory: Callable[..., AsyncEngine] = create_async_engine,
) -> int:
    parser = argparse.ArgumentParser(description="Convert encrypted AUQ values from 1..7 to 0..6.")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--confirm", metavar="TOKEN")
    args = parser.parse_args(argv)
    if args.dry_run and args.confirm is not None:
        print("--dry-run and --confirm cannot be combined", file=sys.stderr)
        return 2
    if args.confirm is not None and args.confirm != CONFIRMATION:
        print("confirmation token did not match; no database connection was made", file=sys.stderr)
        return 2
    try:
        report = asyncio.run(_run(
            execute=args.confirm == CONFIRMATION, engine_factory=engine_factory,
        ))
    except Exception:
        print(json.dumps({"status": "failed", "errorCode": "auq_migration_failed"}), file=sys.stderr)
        return 1
    print(json.dumps({"status": "ok", **asdict(report)}, separators=(",", ":")))
    return 0


def main() -> None:
    raise SystemExit(run_cli())


if __name__ == "__main__":
    main()
