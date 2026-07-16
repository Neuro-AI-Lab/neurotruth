from __future__ import annotations

import re
import importlib.util
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
SCHEMA = REPO_ROOT / "neurotruth_schema_v2_5.sql"
SCHEMA_DEFINITION = REPO_ROOT / "neurotruth_schema_definition_v2_5.md"
DB_APP = REPO_ROOT / "apps" / "db"
MIGRATION = (
    REPO_ROOT
    / "apps"
    / "backend"
    / "alembic"
    / "versions"
    / "20260715_0001_v25_baseline.py"
)

EXPECTED_TABLES = {
    "users", "patient_profiles", "consent_snapshots", "model_versions",
    "sensor_recordings", "craving_predictions", "craving_alerts", "sessions",
    "messages", "message_audio_artifacts", "craving_assessments", "session_slots",
    "interventions", "memory_snapshots", "session_reports", "audit_logs",
    "auth_sessions", "system_settings",
}

EXPECTED_SLOT_KEYS = {
    "episode_trigger", "current_context", "alcohol_context", "drinking_status",
    "habit_pattern", "emotional_context", "physical_context", "alcohol_expectancy",
    "coping_context", "support_context", "user_goal", "safety_context",
    "additional_context",
}


def test_reference_schema_has_18_business_tables_and_required_constraints() -> None:
    assert SCHEMA.is_file()
    assert SCHEMA_DEFINITION.is_file()
    sql = SCHEMA.read_text(encoding="utf-8")
    tables = re.findall(r"^CREATE TABLE public\.([a-z_]+)", sql, re.MULTILINE)
    assert set(tables) == EXPECTED_TABLES
    assert "uq_sessions_one_active_per_patient" in sql
    assert "uq_sensor_recordings_patient_client_window" in sql
    assert "completion_status IN ('answered', 'unknown', 'declined')" in sql
    assert "answers_encrypted bytea" in sql
    assert "content_encrypted bytea" in sql
    assert "prompt_version varchar(64)" in sql
    assert "model_version_id uuid" in sql
    assert "encryption_key_version varchar(32)" in sql
    assert "encryption_nonce bytea" in sql
    assert "MinIO" not in sql and "S3" not in sql and "NAS" not in sql

    slot_check = re.search(
        r"CONSTRAINT ck_session_slots_key\s+CHECK \(slot_key IN \((.*?)\)\)",
        sql,
        re.DOTALL,
    )
    assert slot_check is not None
    assert set(re.findall(r"'([a-z_]+)'", slot_check.group(1))) == EXPECTED_SLOT_KEYS

    user_states = re.search(r"ck_users_status\s+CHECK \(status IN \((.*?)\)\)", sql, re.DOTALL)
    session_states = re.search(r"ck_sessions_status\s+CHECK \(status IN \((.*?)\)\)", sql, re.DOTALL)
    assert user_states and set(re.findall(r"'([a-z_]+)'", user_states.group(1))) == {
        "active", "disabled", "pending_deletion",
    }
    assert session_states and set(re.findall(r"'([a-z_]+)'", session_states.group(1))) == {
        "created", "in_progress", "completed", "report_ready", "closed", "abandoned",
    }


def test_init_sql_is_extensions_only() -> None:
    sql = (DB_APP / "init.sql").read_text(encoding="utf-8")
    assert "CREATE EXTENSION" in sql
    assert "CREATE TABLE" not in sql
    assert "CREATE INDEX" not in sql


def test_compose_uses_only_new_v25_and_encrypted_sensor_volumes() -> None:
    compose = (DB_APP / "docker-compose.yml").read_text(encoding="utf-8")
    assert "postgres_data_v25:/var/lib/postgresql/data" in compose
    assert "encrypted_sensor_data:/var/lib/neurotruth/sensors" in compose
    assert not re.search(r"^\s{2}postgres_data:\s*$", compose, re.MULTILINE)
    assert "alembic upgrade head" in compose
    assert "NEUROTRUTH_SCHEMA_SQL" in compose


def test_alembic_baseline_is_fresh_and_no_legacy_backfill() -> None:
    migration = MIGRATION.read_text(encoding="utf-8")
    assert 'revision = "20260715_0001"' in migration
    assert "down_revision = None" in migration
    assert "bind.exec_driver_sql(sql)" in migration
    assert 'bind.dialect.name != "postgresql"' in migration
    assert "split(" not in migration


def test_schema_authority_is_self_contained_in_repository() -> None:
    migration = MIGRATION.read_text(encoding="utf-8")
    compose = (DB_APP / "docker-compose.yml").read_text(encoding="utf-8")
    assert SCHEMA.parent == REPO_ROOT
    assert SCHEMA_DEFINITION.parent == REPO_ROOT
    assert "parents[" not in migration
    assert "for parent in migration_file.resolve().parents" in migration
    assert "../../../neurotruth_schema_v2_5.sql" not in compose
    assert "../../neurotruth_schema_v2_5.sql:/app/schema/neurotruth_schema_v2_5.sql:ro" in compose


def test_alembic_schema_path_prefers_configured_mount_at_shallow_container_depth(tmp_path) -> None:
    spec = importlib.util.spec_from_file_location("v25_baseline_path", MIGRATION)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    mounted_schema = tmp_path / "schema.sql"
    mounted_schema.write_text("BEGIN;\nSELECT 1;\nCOMMIT;\n", encoding="utf-8")

    resolved = module._resolve_schema_path(
        Path("/app/alembic/versions/20260715_0001_v25_baseline.py"),
        str(mounted_schema),
    )

    assert resolved == mounted_schema


def test_alembic_transaction_wrapper_parser_preserves_plpgsql() -> None:
    spec = importlib.util.spec_from_file_location("v25_baseline", MIGRATION)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    source = SCHEMA.read_text(encoding="utf-8-sig")
    body = module._schema_body(source)
    assert "CREATE TABLE public.users" in body
    assert "RETURNS trigger" in body
    assert re.search(r"(?m)^BEGIN$", body), "PL/pgSQL BEGIN must be preserved"
    assert not re.search(r"(?im)^\s*BEGIN;\s*$", body)
    assert not re.search(r"(?im)^\s*COMMIT;\s*$", body)
