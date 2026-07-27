from __future__ import annotations

from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from typing import Any
from uuid import UUID, uuid4

from fastapi import FastAPI
from fastapi.testclient import TestClient
from jose import jwt

from app.core.security.tokens import create_access_token
from app.services.auth import (
    AuthService,
    AuthenticationError,
    DemoLifecycleUnavailable,
)
from app.models.records import UserRecord
from app.repositories.postgres import RepositoryConflictError
from app.api.v1.routes.auth import router
from app.core.runtime import BackendRuntime


class FakeSettings:
    jwt_signing_key = "j" * 32

    def assert_request_transport(self, url: str) -> None:
        return None


class FakeRepository:
    def __init__(self, user: UserRecord, session_id: UUID, *, active: bool = True) -> None:
        self.user = user
        self.session_id = session_id
        self.active = active

    async def user_by_id(self, user_id: UUID) -> UserRecord | None:
        return self.user if self.user.id == user_id else None

    async def auth_session_is_active(self, *, user_id: UUID, session_id: UUID, now: datetime) -> bool:
        return self.active and user_id == self.user.id and session_id == self.session_id


class FakeService:
    def __init__(self, user: UserRecord) -> None:
        self.user = user
        self.calls: list[tuple[str, Any]] = []
        self.signup_error: Exception | None = None
        self.lifecycle_error: DemoLifecycleUnavailable | None = None

    def response(self) -> dict[str, Any]:
        return {"user": AuthService._public_user(self.user), "accessToken": "access", "refreshToken": "refresh", "expiresIn": 900,
                "consent": {"biosignal": True} if self.user.role == "patient" else None}

    async def patient_signup(self, body: Any) -> dict[str, Any]:
        if self.signup_error:
            raise self.signup_error
        self.calls.append(("patient_signup", body))
        return self.response()

    async def admin_signup(self, body: Any) -> dict[str, Any]:
        self.calls.append(("admin_signup", body))
        return self.response()

    async def login(self, body: Any) -> dict[str, Any]:
        if self.lifecycle_error:
            raise self.lifecycle_error
        if body.password == "bad-password":
            raise AuthenticationError("Invalid credentials")
        self.calls.append(("login", body))
        return self.response()

    async def refresh(self, token: str, device: dict[str, Any]) -> dict[str, Any]:
        self.calls.append(("refresh", token))
        return self.response()

    async def logout(self, token: str) -> None:
        if self.lifecycle_error:
            raise self.lifecycle_error
        self.calls.append(("logout", token))

    async def change_password(self, user_id: UUID, body: Any) -> None:
        self.calls.append(("change_password", user_id))

    async def current_consent(self, user_id: UUID) -> dict[str, Any]:
        return {"biosignal": True}

    async def append_consent(self, user_id: UUID, body: Any) -> dict[str, Any]:
        self.calls.append(("consent", user_id))
        return {"id": str(uuid4()), "biosignal": body.biosignal, "voice": body.voice}

    _public_user = staticmethod(AuthService._public_user)


class FakeProfileAdminService:
    def __init__(self, name: str | None) -> None:
        self.name = name

    async def own_profile_name(self, user: UserRecord) -> str | None:
        return self.name


def build_client(
    *,
    must_change_password: bool = False,
    session_active: bool = True,
    profile_name: str | None = None,
) -> tuple[TestClient, FakeService, UserRecord, str]:
    user = UserRecord(
        id=uuid4(), email="patient@example.com", password_hash="unused", role="patient",
        status="active", must_change_password=must_change_password,
        created_at=datetime.now(timezone.utc),
    )
    service = FakeService(user)
    session_id = uuid4()
    app = FastAPI()
    app.include_router(router)
    app.state.backend_runtime = BackendRuntime(
        ready=True, settings=FakeSettings(),
        repository=FakeRepository(user, session_id, active=session_active),
        service=service,
        admin_service=FakeProfileAdminService(profile_name) if profile_name is not None else None,
        error_code=None,
    )
    token, _ = create_access_token(subject=user.id, session_id=session_id, role=user.role, signing_key="j" * 32)
    return TestClient(app), service, user, token


CONSENT = {
    "tos": True, "privacy": True, "sensitive": True, "biosignal": True,
    "voice": True, "aiAnalysis": True, "notification": False, "reportGeneration": True,
    "tosVersion": "1", "privacyVersion": "1", "consentFormVersion": "1",
}


def test_public_signup_login_refresh_contract_and_sanitized_errors() -> None:
    client, service, _, _ = build_client()
    signup = client.post("/api/auth/patient/signup", json={
        "email": "patient@example.com", "password": "correct horse battery staple", "consent": CONSENT,
    })
    assert signup.status_code == 200
    assert set(signup.json()) == {"user", "accessToken", "refreshToken", "expiresIn", "consent"}
    assert signup.json()["consent"] == {"biosignal": True}
    assert client.post("/api/auth/login", json={
        "email": "woosik.jeong@neurotruth.kr",
        "password": "correct horse battery staple",
    }).status_code == 200
    assert client.post("/api/auth/login", json={"email": "patient@example.com", "password": "bad-password"}).status_code == 401
    refreshed = client.post("/api/auth/refresh", json={"refreshToken": "token"}).json()
    assert refreshed["expiresIn"] == 900 and refreshed["consent"] == {"biosignal": True}
    assert client.post("/api/auth/logout", json={"refreshToken": "token"}).status_code == 204
    expired, _ = create_access_token(
        subject=uuid4(), session_id=uuid4(), role="patient", signing_key="j" * 32,
        ttl=timedelta(seconds=-1),
    )
    assert client.post("/api/auth/logout", headers={"Authorization": f"Bearer {expired}"},
                       json={"refreshToken": "expired-access-refresh"}).status_code == 204
    assert ("logout", "token") in service.calls and ("logout", "expired-access-refresh") in service.calls

    service.signup_error = RepositoryConflictError("internal database constraint")
    conflict = client.post("/api/auth/patient/signup", json={
        "email": "patient@example.com", "password": "correct horse battery staple", "consent": CONSENT,
    })
    assert conflict.status_code == 409
    assert conflict.json() == {"detail": "Account already exists"}


def test_bearer_me_consent_logout_and_change_password() -> None:
    client, service, user, token = build_client()
    headers = {"Authorization": f"Bearer {token}"}
    assert client.get("/api/me").status_code == 401
    me = client.get("/api/me", headers=headers)
    assert me.status_code == 200
    assert me.json()["user"]["id"] == str(user.id)
    assert me.json()["consent"] == {"biosignal": True}

    added = client.post("/api/me/consents", headers=headers, json=CONSENT)
    assert added.status_code == 201
    assert added.json()["biosignal"] is True
    assert added.json()["voice"] is True
    assert client.post("/api/auth/logout", headers=headers, json={"refreshToken": "refresh"}).status_code == 204
    assert client.post("/api/auth/change-password", headers=headers, json={
        "currentPassword": "current-password", "newPassword": "new correct horse battery staple",
    }).status_code == 204
    assert ("logout", "refresh") in service.calls


def test_demo_lifecycle_failures_are_sanitized_retryable_503() -> None:
    client, service, _, _ = build_client()
    service.lifecycle_error = DemoLifecycleUnavailable("demo_seed_failed")
    login = client.post("/api/auth/login", json={
        "email": "patient@example.com",
        "password": "correct horse battery staple",
    })
    assert login.status_code == 503
    assert login.json() == {
        "detail": {"code": "demo_seed_failed", "retryable": True},
    }

    service.lifecycle_error = DemoLifecycleUnavailable("demo_delete_failed")
    logout = client.post(
        "/api/auth/logout", json={"refreshToken": "refresh"},
    )
    assert logout.status_code == 503
    assert logout.json() == {
        "detail": {"code": "demo_delete_failed", "retryable": True},
    }


def test_me_returns_decrypted_patient_display_name() -> None:
    client, _, _, token = build_client(profile_name="정우식")
    response = client.get("/api/me", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200
    assert response.json()["user"]["name"] == "정우식"


def test_forced_password_allows_only_logout_and_change_password() -> None:
    client, _, _, token = build_client(must_change_password=True)
    headers = {"Authorization": f"Bearer {token}"}
    assert client.get("/api/me", headers=headers).status_code == 403
    assert client.post("/api/me/consents", headers=headers, json=CONSENT).status_code == 403
    assert client.post("/api/auth/logout", headers=headers, json={"refreshToken": "refresh"}).status_code == 204
    assert client.post("/api/auth/change-password", headers=headers, json={
        "currentPassword": "current-password", "newPassword": "new correct horse battery staple",
    }).status_code == 204


def test_access_dependency_rejects_missing_malformed_or_inactive_sid() -> None:
    client, _, _, token = build_client()
    claims = jwt.get_unverified_claims(token)
    missing = dict(claims); missing.pop("sid")
    malformed = {**claims, "sid": "not-a-uuid"}
    for bad_claims in (missing, malformed):
        encoded = jwt.encode(bad_claims, "j" * 32, algorithm="HS256")
        assert client.get("/api/me", headers={"Authorization": f"Bearer {encoded}"}).status_code == 401
    inactive_client, _, _, inactive_token = build_client(session_active=False)
    assert inactive_client.get("/api/me", headers={"Authorization": f"Bearer {inactive_token}"}).status_code == 401


def test_patch_me_rejects_unsupported_update_without_silent_drop() -> None:
    client, _, _, token = build_client()
    response = client.patch("/api/me", headers={"Authorization": f"Bearer {token}"}, json={"name": "new name"})
    assert response.status_code == 501
    assert response.json()["detail"] == "Profile updates are not available yet"


def test_public_consent_reports_persisted_voice_value() -> None:
    row = SimpleNamespace(
        id=uuid4(), tos=True, privacy=True, sensitive=True, biosignal=True, voice=True,
        ai_analysis=True, notification=False, report_generation=True,
        camera_rppg=False, face_video_retention=False,
        tos_version="1", privacy_version="1", consent_form_version="1",
        collected_at=datetime.now(timezone.utc),
    )
    assert AuthService._public_consent(row)["voice"] is True


def test_main_exposes_readiness_and_removes_placeholder_users_route() -> None:
    from app import main

    paths = {getattr(route, "path", None) for route in main.app.routes}
    assert "/ready" in paths
    assert "/api/auth/patient/signup" in paths
    assert "/api/me" in paths
    assert "/api/stt/status" in paths
    assert "/api/sessions/{session_id}/transcriptions" in paths
    assert "/api/users" not in paths
