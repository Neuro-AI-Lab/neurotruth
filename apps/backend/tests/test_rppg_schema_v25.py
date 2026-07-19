from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_rppg_is_additive_0002_and_baseline_remains_parent() -> None:
    migration = (ROOT / "alembic/versions/20260715_0002_dgx_rppg.py").read_text(encoding="utf-8")
    assert 'down_revision = "20260715_0001"' in migration
    assert "CREATE TABLE public.rppg_captures" in migration
    assert "CREATE TABLE public.rppg_analysis_jobs" in migration
    assert "ADD COLUMN rppg_analysis_job_id" in migration
    assert "camera_rppg boolean NOT NULL DEFAULT false" in migration
    assert "face_video_retention boolean NOT NULL DEFAULT false" in migration
    assert "rppg_model" in migration
    assert "uq_rppg_jobs_one_active_capture" in migration


def test_runtime_requires_voice_rppg_20s_head_after_free_dialogue_revision() -> None:
    runtime = (ROOT / "app/core/runtime.py").read_text(encoding="utf-8")
    assert 'REQUIRED_REVISION = "20260717_0005"' in runtime
    migration = (ROOT / "alembic/versions/20260717_0005_voice_rppg_20s.py").read_text(encoding="utf-8")
    assert 'down_revision = "20260716_0004"' in migration
    assert "duration_ms BETWEEN 9500 AND 10500" in migration
    assert "duration_ms BETWEEN 19500 AND 20500" in migration


def test_quality_retry_jobs_persist_rppg_model_foreign_key() -> None:
    repository = (ROOT / "app/repositories/rppg.py").read_text(encoding="utf-8")
    assert "model_version_id=:rppg_model_version_id" in repository
