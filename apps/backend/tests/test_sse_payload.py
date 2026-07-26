from app.services.prediction import public_sse_event
from app.main import app


EXPECTED_PUBLIC_OPERATIONS = {
    ("delete", "/api/admin/patients/{patient_id}"),
    ("delete", "/api/admin/rppg/captures/{capture_id}"),
    ("get", "/api/admin/patients"),
    ("get", "/api/admin/patients/{patient_id}/dashboard"),
    ("get", "/api/admin/patients/{patient_id}/timeline"),
    ("get", "/api/admin/rppg/captures"),
    ("get", "/api/admin/settings"),
    ("get", "/api/me"),
    ("get", "/api/me/craving-calendar"),
    ("get", "/api/me/craving-dashboard"),
    ("get", "/api/me/craving-probability-series"),
    ("get", "/api/me/dashboard"),
    ("get", "/api/me/predictions/{prediction_id}/ppg-preview"),
    ("get", "/api/predictions/stream"),
    ("get", "/api/rppg/jobs/{job_id}"),
    ("get", "/api/rppg/status"),
    ("get", "/api/sessions/{session_id}"),
    ("get", "/api/sessions/{session_id}/reports"),
    ("get", "/api/stt/status"),
    ("get", "/health"),
    ("get", "/model/status"),
    ("get", "/ready"),
    ("patch", "/api/admin/settings"),
    ("patch", "/api/me"),
    ("post", "/api/admin/patients/{patient_id}/temporary-password"),
    ("post", "/api/admin/resources/{resource_type}/{resource_id}/reveal"),
    ("post", "/api/admin/rppg/captures/{capture_id}/reveal-video"),
    ("post", "/api/auth/admin/signup"),
    ("post", "/api/auth/change-password"),
    ("post", "/api/auth/login"),
    ("post", "/api/auth/logout"),
    ("post", "/api/auth/patient/signup"),
    ("post", "/api/auth/refresh"),
    ("post", "/api/me/consents"),
    ("post", "/api/rppg/jobs"),
    ("post", "/api/rppg/jobs/{job_id}/retry"),
    ("post", "/api/sensor-windows"),
    ("post", "/api/sessions"),
    ("post", "/api/sessions/{session_id}/assessments"),
    ("post", "/api/sessions/{session_id}/finish"),
    ("post", "/api/sessions/{session_id}/messages"),
    ("post", "/api/sessions/{session_id}/reports"),
    ("post", "/api/sessions/{session_id}/transcriptions"),
}


def test_layered_router_preserves_public_operation_set_without_v1_prefix() -> None:
    operations = {
        (method, path)
        for path, definition in app.openapi()["paths"].items()
        for method in definition
        if method in {"get", "post", "patch", "delete", "put"}
    }
    assert operations == EXPECTED_PUBLIC_OPERATIONS
    assert not any(path.startswith("/v1") or "/v1/" in path for _, path in operations)


def test_public_sse_event_preserves_legacy_class_and_strips_internal_keys() -> None:
    event = {
        "class": 1,
        "timestampMs": 123,
        "confidence": 0.8,
        "sequence": 9,
        "alertLevel": "recommend",
        "alertAction": "recommend_intervention",
        "windowMean": 0.7,
        "triggerReason": "window_mean_recommend",
        "alertRequired": False,
        "sessionId": "session-9",
        "_lat": {"server_ms": 10},
        "_readyPerf": 42.0,
    }

    public = public_sse_event(event)

    assert public["class"] == 1
    assert public["timestampMs"] == 123
    assert public["confidence"] == 0.8
    assert public["sequence"] == 9
    assert public["alertLevel"] == "recommend"
    assert public["alertAction"] == "recommend_intervention"
    assert public["windowMean"] == 0.7
    assert public["triggerReason"] == "window_mean_recommend"
    assert public["alertRequired"] is False
    assert public["sessionId"] == "session-9"
    assert "_lat" not in public
    assert "_readyPerf" not in public
