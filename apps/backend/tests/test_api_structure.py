from __future__ import annotations

import ast
from pathlib import Path

from app.core.config import SecuritySettings
from app.main import app
from tests.test_sse_payload import EXPECTED_PUBLIC_OPERATIONS


def test_ai_feature_settings_are_typed_default_false() -> None:
    fields = SecuritySettings.model_fields
    assert fields["state_summary_ai_enabled"].annotation is bool
    assert fields["state_summary_ai_enabled"].default is False
    assert fields["state_summary_ai_enabled"].alias == "STATE_SUMMARY_AI_ENABLED"
    assert fields["report_ai_enabled"].annotation is bool
    assert fields["report_ai_enabled"].default is False
    assert fields["report_ai_enabled"].alias == "REPORT_AI_ENABLED"


def test_runtime_forwards_typed_feature_settings_to_session_service() -> None:
    runtime_path = Path(__file__).parents[1] / "app" / "core" / "runtime.py"
    tree = ast.parse(runtime_path.read_text(encoding="utf-8"))
    calls = [
        node for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id == "SessionService"
    ]
    assert len(calls) == 1
    forwarded = {keyword.arg: ast.unparse(keyword.value) for keyword in calls[0].keywords}
    assert forwarded["state_summary_ai_enabled"] == "getattr(settings, 'state_summary_ai_enabled', False)"
    assert forwarded["report_ai_enabled"] == "getattr(settings, 'report_ai_enabled', False)"

    dashboard_calls = [
        node for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id == "DashboardService"
    ]
    assert len(dashboard_calls) == 1
    dashboard_forwarded = {
        keyword.arg: ast.unparse(keyword.value) for keyword in dashboard_calls[0].keywords
    }
    assert dashboard_forwarded["state_summary_ai_enabled"] == (
        "getattr(settings, 'state_summary_ai_enabled', False)"
    )


def test_public_operations_and_report_202_contract_are_unchanged() -> None:
    schema = app.openapi()
    operations = {
        (method, path)
        for path, definition in schema["paths"].items()
        for method in definition
        if method in {"get", "post", "patch", "delete", "put"}
    }
    assert operations == EXPECTED_PUBLIC_OPERATIONS
    assert schema["paths"]["/api/sessions/{session_id}/reports"]["post"]["responses"].get("202")
    assert not any("/v1" in path for _, path in operations)


def test_layered_sources_have_no_legacy_application_imports() -> None:
    backend = Path(__file__).parents[1]
    forbidden = tuple("app." + suffix for suffix in ("v25", "inference", "settings", "security", "ai"))
    found: list[tuple[str, str]] = []
    for root in (backend / "app", backend / "tests"):
        for path in root.rglob("*.py"):
            tree = ast.parse(path.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                names = []
                if isinstance(node, ast.Import):
                    names = [alias.name for alias in node.names]
                elif isinstance(node, ast.ImportFrom) and node.module:
                    names = [node.module]
                for name in names:
                    if name.startswith(forbidden):
                        found.append((str(path.relative_to(backend)), name))
    assert found == []
    assert not (backend / "app" / "v25").exists()
