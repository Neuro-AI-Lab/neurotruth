"""Fresh NeuroTruth V2.5 18-table baseline.

This revision intentionally has no legacy-data migration path. The SQL reference
is mounted read-only in Compose and remains the human-reviewable schema authority.
"""

from __future__ import annotations

import os
import re
from pathlib import Path

from alembic import op


revision = "20260715_0001"
down_revision = None
branch_labels = None
depends_on = None


def _resolve_schema_path(migration_file: Path, configured: str | None) -> Path:
    """Find the schema without assuming a fixed repository/container depth."""
    if configured:
        candidate = Path(configured).expanduser()
        if candidate.is_file():
            return candidate
        raise RuntimeError("The configured schema SQL reference is unavailable")

    for parent in migration_file.resolve().parents:
        candidate = parent / "neurotruth_schema_v2_5.sql"
        if candidate.is_file():
            return candidate
    raise RuntimeError("The schema SQL reference is unavailable")


def _schema_path() -> Path:
    return _resolve_schema_path(Path(__file__), os.getenv("NEUROTRUTH_SCHEMA_SQL"))


def _schema_body(source: str) -> str:
    """Remove only the reference file's outer transaction wrapper.

    PL/pgSQL function bodies contain their own BEGIN/END tokens, so splitting on
    semicolons or performing unanchored replacement would corrupt valid SQL.
    Alembic already owns the migration transaction.
    """
    begin = re.search(r"(?im)^\s*BEGIN;\s*$", source)
    commit = re.search(r"(?im)^\s*COMMIT;\s*\Z", source)
    if begin is None or commit is None or begin.end() >= commit.start():
        raise RuntimeError("The V2.5 schema SQL must have one outer BEGIN/COMMIT wrapper")
    body = source[: begin.start()] + source[begin.end() : commit.start()]
    if not body.strip():
        raise RuntimeError("The V2.5 schema SQL body is empty")
    return body


def upgrade() -> None:
    bind = op.get_bind()
    if bind.dialect.name != "postgresql":
        raise RuntimeError("The V2.5 baseline supports PostgreSQL only")
    sql = _schema_body(_schema_path().read_text(encoding="utf-8-sig"))
    # Execute the authoritative document in one psycopg2 call. Naive statement
    # splitting breaks the dollar-quoted PL/pgSQL trigger function.
    bind.exec_driver_sql(sql)


def downgrade() -> None:
    raise RuntimeError("V2.5 rollback restores the preserved legacy volume")
