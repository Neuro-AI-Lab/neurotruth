from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from dataclasses import asdict, dataclass
from typing import Any, Callable, Sequence
from uuid import UUID

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine


CONFIRMATION = "DELETE-LEGACY-3CLASS-PREDICTIONS"
ADVISORY_LOCK_KEY = "neurotruth-purge-legacy-3class-predictions"

_LEGACY_MODEL_PREDICATE = """
    model.inference_task = 'multiclass_classification'
    OR (
      EXISTS (
        SELECT 1
        FROM jsonb_array_elements(
          CASE WHEN jsonb_typeof(model.output_schema->'classes') = 'array'
               THEN model.output_schema->'classes' ELSE '[]'::jsonb END
        ) AS class_item
        WHERE lower(COALESCE(class_item->>'code', trim(both '"' from class_item::text))) = 'low'
      )
      AND EXISTS (
        SELECT 1
        FROM jsonb_array_elements(
          CASE WHEN jsonb_typeof(model.output_schema->'classes') = 'array'
               THEN model.output_schema->'classes' ELSE '[]'::jsonb END
        ) AS class_item
        WHERE lower(COALESCE(class_item->>'code', trim(both '"' from class_item::text))) = 'mid'
      )
      AND EXISTS (
        SELECT 1
        FROM jsonb_array_elements(
          CASE WHEN jsonb_typeof(model.output_schema->'classes') = 'array'
               THEN model.output_schema->'classes' ELSE '[]'::jsonb END
        ) AS class_item
        WHERE lower(COALESCE(class_item->>'code', trim(both '"' from class_item::text))) = 'high'
      )
    )
"""

_LEGACY_MODELS_SQL = text(
    f"""
    SELECT model.id,
           COALESCE(
             model.config->>'artifact_sha256',
             model.config->>'artifactSha256',
             model.config#>>'{{artifact,sha256}}'
           ) AS artifact_sha256
    FROM model_versions AS model
    WHERE {_LEGACY_MODEL_PREDICATE}
    ORDER BY model.created_at, model.id
    """
)

_COUNTS_SQL = text(
    """
    SELECT
      (SELECT count(*) FROM craving_predictions prediction
       WHERE prediction.model_version_id = ANY(CAST(:model_ids AS uuid[]))) AS prediction_count,
      (SELECT count(*) FROM craving_alerts alert
       JOIN craving_predictions prediction ON prediction.id = alert.trigger_prediction_id
       WHERE prediction.model_version_id = ANY(CAST(:model_ids AS uuid[]))) AS alert_count,
      (SELECT count(*) FROM state_inferences inference
       JOIN craving_predictions prediction ON prediction.id = inference.trigger_prediction_id
       WHERE prediction.model_version_id = ANY(CAST(:model_ids AS uuid[]))) AS state_inference_count
    """
)

_DELETE_ALERTS_SQL = text(
    """
    DELETE FROM craving_alerts AS alert
    USING craving_predictions AS prediction
    WHERE alert.trigger_prediction_id = prediction.id
      AND prediction.model_version_id = ANY(CAST(:model_ids AS uuid[]))
    """
)

_DELETE_STATE_INFERENCES_SQL = text(
    """
    DELETE FROM state_inferences AS inference
    USING craving_predictions AS prediction
    WHERE inference.trigger_prediction_id = prediction.id
      AND prediction.model_version_id = ANY(CAST(:model_ids AS uuid[]))
    """
)

_DELETE_PREDICTIONS_SQL = text(
    """
    DELETE FROM craving_predictions AS prediction
    WHERE prediction.model_version_id = ANY(CAST(:model_ids AS uuid[]))
    """
)

_INSERT_AUDIT_SQL = text(
    """
    INSERT INTO audit_logs
      (actor_id, actor_role, action, resource_type, resource_id, metadata)
    VALUES
      (NULL, 'system', 'legacy_3class_predictions_purged',
       'craving_prediction', NULL, CAST(:metadata AS jsonb))
    """
)


@dataclass(frozen=True)
class PurgeReport:
    mode: str
    legacy_model_count: int
    prediction_count: int
    alert_count: int
    state_inference_count: int
    legacy_models: list[dict[str, str | None]]


class LegacyPredictionPurger:
    """Preview or atomically remove records derived from legacy 3-class models."""

    def __init__(self, engine: AsyncEngine) -> None:
        self.engine = engine

    async def dry_run(self) -> PurgeReport:
        async with self.engine.connect() as connection:
            return await self._inspect(connection, mode="dry-run")

    async def purge(self) -> PurgeReport:
        async with self.engine.begin() as connection:
            await connection.execute(
                text("SELECT pg_advisory_xact_lock(hashtext(:key))"),
                {"key": ADVISORY_LOCK_KEY},
            )
            preview = await self._inspect(connection, mode="confirmed")
            model_ids = [UUID(model["id"]) for model in preview.legacy_models]

            deleted_alerts = await self._delete_count(
                connection, _DELETE_ALERTS_SQL, model_ids
            )
            deleted_inferences = await self._delete_count(
                connection, _DELETE_STATE_INFERENCES_SQL, model_ids
            )
            deleted_predictions = await self._delete_count(
                connection, _DELETE_PREDICTIONS_SQL, model_ids
            )
            expected = (
                preview.alert_count,
                preview.state_inference_count,
                preview.prediction_count,
            )
            actual = (deleted_alerts, deleted_inferences, deleted_predictions)
            if actual != expected:
                raise RuntimeError("legacy_cleanup_count_mismatch")

            metadata = {
                "legacyModels": preview.legacy_models,
                "deletedCounts": {
                    "cravingAlerts": deleted_alerts,
                    "stateInferences": deleted_inferences,
                    "cravingPredictions": deleted_predictions,
                },
            }
            await connection.execute(
                _INSERT_AUDIT_SQL,
                {"metadata": json.dumps(metadata, separators=(",", ":"))},
            )
            return preview

    async def _inspect(self, connection: Any, *, mode: str) -> PurgeReport:
        rows = (await connection.execute(_LEGACY_MODELS_SQL)).mappings().all()
        models = [
            {
                "id": str(row["id"]),
                "artifactSha256": row.get("artifact_sha256"),
            }
            for row in rows
        ]
        if not models:
            counts = {
                "prediction_count": 0,
                "alert_count": 0,
                "state_inference_count": 0,
            }
        else:
            count_row = (
                await connection.execute(
                    _COUNTS_SQL,
                    {"model_ids": [UUID(model["id"]) for model in models]},
                )
            ).mappings().one()
            counts = dict(count_row)
        return PurgeReport(
            mode=mode,
            legacy_model_count=len(models),
            prediction_count=int(counts["prediction_count"]),
            alert_count=int(counts["alert_count"]),
            state_inference_count=int(counts["state_inference_count"]),
            legacy_models=models,
        )

    @staticmethod
    async def _delete_count(
        connection: Any, statement: Any, model_ids: list[UUID]
    ) -> int:
        if not model_ids:
            return 0
        result = await connection.execute(statement, {"model_ids": model_ids})
        return int(result.rowcount)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Preview or remove legacy three-class prediction records."
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Preview affected records without writing (the default).",
    )
    parser.add_argument(
        "--confirm",
        metavar="TOKEN",
        help=f"Execute only when TOKEN is exactly {CONFIRMATION}.",
    )
    return parser


def _database_url() -> str:
    value = os.getenv("DATABASE_URL", "").strip()
    if value.startswith("postgresql://"):
        value = "postgresql+asyncpg://" + value.removeprefix("postgresql://")
    if not value.startswith("postgresql+asyncpg://"):
        raise RuntimeError("DATABASE_URL must use PostgreSQL")
    return value


async def _run(*, execute: bool, engine_factory: Callable[..., AsyncEngine]) -> PurgeReport:
    engine = engine_factory(_database_url(), pool_pre_ping=True)
    try:
        purger = LegacyPredictionPurger(engine)
        return await (purger.purge() if execute else purger.dry_run())
    finally:
        await engine.dispose()


def run_cli(
    argv: Sequence[str] | None = None,
    *,
    engine_factory: Callable[..., AsyncEngine] = create_async_engine,
) -> int:
    args = _parser().parse_args(argv)
    if args.dry_run and args.confirm is not None:
        print("--dry-run and --confirm cannot be combined", file=sys.stderr)
        return 2
    if args.confirm is not None and args.confirm != CONFIRMATION:
        print("confirmation token did not match; no database connection was made", file=sys.stderr)
        return 2
    try:
        report = asyncio.run(
            _run(execute=args.confirm == CONFIRMATION, engine_factory=engine_factory)
        )
    except Exception:
        # The operator can inspect the container logs/DB separately; CLI output
        # must not echo connection strings, credentials, or provider internals.
        print(
            json.dumps({"status": "failed", "errorCode": "legacy_cleanup_failed"}),
            file=sys.stderr,
        )
        return 1
    print(json.dumps({"status": "ok", **asdict(report)}, separators=(",", ":")))
    return 0


def main() -> None:
    raise SystemExit(run_cli())


if __name__ == "__main__":
    main()
