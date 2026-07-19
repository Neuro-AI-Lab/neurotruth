from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_0003_is_additive_and_contains_required_constraints() -> None:
    migration = (ROOT / "alembic/versions/20260715_0003_intervention_first.py").read_text(encoding="utf-8")
    assert 'revision = "20260715_0003"' in migration
    assert 'down_revision = "20260715_0002"' in migration
    assert "CREATE TABLE public.state_inferences" in migration
    assert "fk_state_inferences_session_patient" in migration
    assert "fk_state_inferences_prediction_patient" in migration
    assert "interaction_phase" in migration and "dialogue_state_encrypted" in migration
    assert "presentation_order" in migration and "evidence_refs" in migration
    assert "state_inference_agent" in migration
    assert "uq_state_inferences_patient_scope_event" in migration
    assert "raise RuntimeError" in migration


def test_0004_only_extends_phase_and_client_message_idempotency() -> None:
    migration = (ROOT / "alembic/versions/20260716_0004_free_dialogue.py").read_text(encoding="utf-8")
    assert 'revision = "20260716_0004"' in migration
    assert 'down_revision = "20260715_0003"' in migration
    assert "'free_dialogue'" in migration
    assert "uq_messages_session_client_message_id" in migration
    assert "generation_metadata->>'clientMessageId'" in migration
    assert "WHERE role='user'" in migration
    assert "UPDATE public.sessions" not in migration
    assert "DELETE FROM" not in migration


def test_new_session_implementation_does_not_write_legacy_slots_or_memory() -> None:
    service = (ROOT / "app/services/session.py").read_text(encoding="utf-8")
    assert "upsert_session_slot" not in service
    assert "add_memory_snapshot" not in service
    assert '"slots"' not in service
    assert "handoffReady" not in service and "missingSlots" not in service


def test_forbidden_migration_files_do_not_contain_intervention_first_revision() -> None:
    for filename in ("20260715_0001_v25_baseline.py", "20260715_0002_dgx_rppg.py"):
        source = (ROOT / "alembic/versions" / filename).read_text(encoding="utf-8")
        assert "20260715_0003" not in source
        assert "state_inferences" not in source


def test_admin_timeline_exposes_message_reveal_handle_without_sensitive_content() -> None:
    repository = (ROOT / "app/repositories/postgres.py").read_text(encoding="utf-8")
    timeline_block = repository.rsplit("async def patient_timeline", 1)[1].split("async def sensitive_resource", 1)[0]
    assert "FROM messages m JOIN sessions s" in timeline_block
    assert '"resourceType": "message"' in timeline_block and '"resourceId"' in timeline_block
    assert "content_encrypted" not in timeline_block and "content" not in timeline_block


def test_repository_excludes_legacy_timeout_and_serializes_report_versions() -> None:
    repository = (ROOT / "app/repositories/postgres.py").read_text(encoding="utf-8")
    sweep = repository.rsplit("async def abandon_inactive_sessions", 1)[1].split("async def add_assessment", 1)[0]
    assert "interaction_phase IS NOT NULL" in sweep
    reports = repository.rsplit("async def create_report_job", 1)[1].split("async def finish_report_job", 1)[0]
    assert "pg_advisory_xact_lock" in reports and "report:" in reports


def test_all_patient_session_mutations_apply_ai_consent_gate() -> None:
    routes = (ROOT / "app/api/v1/routes/session.py").read_text(encoding="utf-8")
    assert routes.count("await _ai_consent(runtime, user)") >= 5
    service = (ROOT / "app/services/session.py").read_text(encoding="utf-8")
    for signature in ("async def open", "async def message", "async def assessment", "async def finish", "async def request_report"):
        block = service.split(signature, 1)[1].split("async def ", 1)[0]
        assert 'require_consent(patient.id, "ai_analysis")' in block
