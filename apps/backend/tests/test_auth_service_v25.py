from __future__ import annotations

import base64
import asyncio
from dataclasses import replace
from datetime import datetime, timezone
from typing import Any
from uuid import UUID, uuid4

import pytest

from app.core.security.crypto import AesGcmKeyring, aad_for
from app.core.security.passwords import hash_password
from app.core.security.tokens import hash_refresh_token, verify_access_token
from app.maintenance.seed_vp012_demo import DEMO_EMAIL, DEMO_PATIENT_ID
from app.services.auth import (
    AuthenticationError,
    AuthorizationError,
    AuthService,
    DemoLifecycleUnavailable,
)
from app.models.records import (
    AuthSessionRecord,
    ConsentRecord,
    RotationResult,
    SystemSettingsRecord,
    UserRecord,
)
from app.repositories.postgres import RepositoryConflictError, V25Repository
from app.schemas.auth import (
    AdminSignupInput,
    ChangePasswordInput,
    ConsentInput,
    LoginInput,
    PatientSignupInput,
)


NOW = datetime.now(timezone.utc)


def consent(**changes: Any) -> ConsentInput:
    values = {
        "tos": True, "privacy": True, "sensitive": True,
        "biosignal": True, "aiAnalysis": True, "notification": False,
        "reportGeneration": True, "tosVersion": "1", "privacyVersion": "1",
        "consentFormVersion": "1",
    }
    values.update(changes)
    return ConsentInput.model_validate(values)


class FakeRepository(V25Repository):
    def __init__(self) -> None:
        self.users: dict[UUID, UserRecord] = {}
        self.consents: dict[UUID, list[ConsentRecord]] = {}
        self.sessions: dict[str, AuthSessionRecord] = {}
        self.settings: SystemSettingsRecord | None = None
        self.audits: list[dict[str, Any]] = []
        self.patient_create: dict[str, Any] | None = None

    async def create_patient(self, **values: Any) -> UserRecord:
        if await self.user_by_email(values["email"]):
            raise RepositoryConflictError("Account already exists")
        user = UserRecord(values["user_id"], values["email"], values["password_hash"], "patient", "active", False, NOW)
        self.users[user.id] = user
        self.patient_create = dict(values)
        await self.append_consent(user.id, values["consent"])
        return user

    async def create_admin(self, *, user_id: UUID, email: str, password_hash: str) -> UserRecord:
        if await self.user_by_email(email):
            raise RepositoryConflictError("Account already exists")
        user = UserRecord(user_id, email, password_hash, "admin", "active", False, NOW)
        self.users[user.id] = user
        return user

    async def user_by_email(self, email: str) -> UserRecord | None:
        return next((u for u in self.users.values() if u.email == email), None)

    async def user_by_id(self, user_id: UUID) -> UserRecord | None:
        return self.users.get(user_id)

    async def create_auth_session(self, session: AuthSessionRecord, device: dict[str, Any]) -> None:
        self.sessions[session.refresh_token_hash] = session

    async def auth_session_is_active(self, *, user_id: UUID, session_id: UUID, now: datetime) -> bool:
        return any(
            item.id == session_id and item.user_id == user_id and item.revoked_at is None
            and item.replaced_by_session_id is None and item.expires_at > now
            for item in self.sessions.values()
        )

    async def rotate_auth_session(self, *, presented_hash: str, new_session: AuthSessionRecord, device: dict[str, Any], now: datetime) -> RotationResult:
        old = self.sessions.get(presented_hash)
        if old is None:
            return RotationResult("invalid")
        if old.revoked_at is not None or old.replaced_by_session_id is not None:
            for key, item in list(self.sessions.items()):
                if item.token_family_id == old.token_family_id:
                    self.sessions[key] = replace(item, revoked_at=item.revoked_at or now)
            return RotationResult("replayed")
        user = self.users[old.user_id]
        if old.expires_at <= now or user.status != "active":
            return RotationResult("invalid")
        effective = replace(
            new_session,
            user_id=old.user_id,
            token_family_id=old.token_family_id,
            parent_session_id=old.id,
        )
        self.sessions[new_session.refresh_token_hash] = effective
        self.sessions[presented_hash] = replace(old, revoked_at=now, replaced_by_session_id=effective.id)
        return RotationResult("rotated", user)

    async def revoke_refresh(self, refresh_hash: str, *, reason: str, now: datetime) -> None:
        if refresh_hash in self.sessions:
            self.sessions[refresh_hash] = replace(self.sessions[refresh_hash], revoked_at=now)

    async def revoke_user_sessions(self, user_id: UUID, *, reason: str, now: datetime) -> None:
        for key, item in list(self.sessions.items()):
            if item.user_id == user_id:
                self.sessions[key] = replace(item, revoked_at=item.revoked_at or now)

    async def update_password(self, user_id: UUID, password_hash: str) -> None:
        self.users[user_id] = replace(self.users[user_id], password_hash=password_hash, must_change_password=False)

    async def append_consent(self, user_id: UUID, value: ConsentInput) -> ConsentRecord:
        row = ConsentRecord(
            id=uuid4(), user_id=user_id, collected_at=datetime.now(timezone.utc),
            **value.model_dump(),
        )
        self.consents.setdefault(user_id, []).append(row)
        return row

    async def current_consent(self, user_id: UUID) -> ConsentRecord | None:
        rows = self.consents.get(user_id, [])
        return rows[-1] if rows else None

    async def ensure_system_settings(self, admin_signup_code_hash: str) -> SystemSettingsRecord:
        if self.settings is None:
            self.settings = SystemSettingsRecord(True, 3600, admin_signup_code_hash)
        return self.settings

    async def system_settings(self) -> SystemSettingsRecord | None:
        return self.settings

    async def audit(self, **values: Any) -> None:
        self.audits.append(values)


@pytest.fixture
def service() -> tuple[AuthService, FakeRepository, AesGcmKeyring]:
    repo = FakeRepository()
    key = base64.b64encode(b"k" * 32).decode()
    ring = AesGcmKeyring.from_config(f"v1:{key}", "v1")
    return AuthService(repo, ring, jwt_signing_key="j" * 32), repo, ring


async def _patient_self_signup_encrypts_name_and_issues_contract(service: Any) -> None:
    auth, repo, ring = service
    result = await auth.patient_signup(PatientSignupInput.model_validate({
        "email": "Patient@Example.com", "password": "correct horse battery staple",
        "name": "홍길동", "birthYear": 1990, "gender": "prefer_not_to_say",
        "consent": consent().model_dump(by_alias=True),
    }))
    user_id = UUID(result["user"]["id"])
    assert result["user"]["email"] == "patient@example.com"
    assert result["user"]["role"] == "patient"
    assert result["expiresIn"] == 900
    assert result["accessToken"] and result["refreshToken"]
    assert result["consent"]["biosignal"] is True
    assert result["consent"]["aiAnalysis"] is True
    claims = verify_access_token(result["accessToken"], signing_key="j" * 32)
    assert await repo.auth_session_is_active(
        user_id=user_id, session_id=UUID(claims["sid"]), now=datetime.now(timezone.utc)
    )
    assert b"\xed\x99\x8d\xea\xb8\xb8\xeb\x8f\x99" not in repo.patient_create["name_encrypted"]
    aad = aad_for(table="patient_profiles", column="name_encrypted", patient_id=str(user_id), record_id=str(user_id))
    assert ring.decrypt(repo.patient_create["name_encrypted"], aad=aad).decode() == "홍길동"
    assert len(repo.consents[user_id]) == 1
    assert repo.audits[-1]["action"] == "patient.signup"


def test_patient_self_signup_encrypts_name_and_issues_contract(service: Any) -> None:
    asyncio.run(_patient_self_signup_encrypts_name_and_issues_contract(service))


async def _admin_signup_requires_bootstrapped_code(service: Any) -> None:
    auth, repo, _ = service
    await auth.bootstrap_admin_code("administrator-bootstrap-code")
    with pytest.raises(AuthenticationError):
        await auth.admin_signup(AdminSignupInput(email="admin@example.com", password="correct horse battery staple", signupCode="wrong"))
    result = await auth.admin_signup(AdminSignupInput(
        email="admin@example.com", password="correct horse battery staple",
        signupCode="administrator-bootstrap-code",
    ))
    assert result["user"]["role"] == "admin"
    assert result["consent"] is None
    assert repo.settings and repo.settings.admin_signup_code_hash.startswith("$argon2id$")


def test_admin_signup_requires_bootstrapped_code(service: Any) -> None:
    asyncio.run(_admin_signup_requires_bootstrapped_code(service))


async def _login_refresh_rotation_and_replay_revokes_family(service: Any) -> None:
    auth, repo, _ = service
    await auth.patient_signup(PatientSignupInput(
        email="patient@example.com", password="correct horse battery staple", consent=consent()
    ))
    login = await auth.login(LoginInput(
        email="patient@example.com", password="correct horse battery staple", device={"model": "phone"}
    ))
    assert login["consent"]["biosignal"] is True
    assert login["consent"]["reportGeneration"] is True
    first_refresh = login["refreshToken"]
    first_claims = verify_access_token(login["accessToken"], signing_key="j" * 32)
    family = repo.sessions[hash_refresh_token(first_refresh)].token_family_id
    rotated = await auth.refresh(first_refresh)
    assert rotated["consent"] == login["consent"]
    rotated_claims = verify_access_token(rotated["accessToken"], signing_key="j" * 32)
    assert rotated["refreshToken"] != first_refresh
    user_id = UUID(login["user"]["id"])
    assert not await repo.auth_session_is_active(
        user_id=user_id, session_id=UUID(first_claims["sid"]), now=datetime.now(timezone.utc)
    )
    assert await repo.auth_session_is_active(
        user_id=user_id, session_id=UUID(rotated_claims["sid"]), now=datetime.now(timezone.utc)
    )
    with pytest.raises(AuthenticationError):
        await auth.refresh(first_refresh)
    family_sessions = [item for item in repo.sessions.values() if item.token_family_id == family]
    assert len(family_sessions) == 2
    assert all(item.revoked_at is not None for item in family_sessions)


def test_login_refresh_rotation_and_replay_revokes_family(service: Any) -> None:
    asyncio.run(_login_refresh_rotation_and_replay_revokes_family(service))


async def _logout_and_password_change_revoke_sessions(service: Any) -> None:
    auth, repo, _ = service
    signup = await auth.patient_signup(PatientSignupInput(
        email="patient@example.com", password="correct horse battery staple", consent=consent()
    ))
    signup_claims = verify_access_token(signup["accessToken"], signing_key="j" * 32)
    signup_user_id = UUID(signup["user"]["id"])
    await auth.logout(signup["refreshToken"])
    assert any(item.revoked_at for item in repo.sessions.values())
    assert not await repo.auth_session_is_active(
        user_id=signup_user_id, session_id=UUID(signup_claims["sid"]), now=datetime.now(timezone.utc)
    )
    login = await auth.login(LoginInput(email="patient@example.com", password="correct horse battery staple"))
    user_id = UUID(login["user"]["id"])
    login_claims = verify_access_token(login["accessToken"], signing_key="j" * 32)
    await auth.change_password(user_id, ChangePasswordInput(
        currentPassword="correct horse battery staple", newPassword="new correct horse battery staple"
    ))
    assert all(item.revoked_at is not None for item in repo.sessions.values() if item.user_id == user_id)
    assert not await repo.auth_session_is_active(
        user_id=user_id, session_id=UUID(login_claims["sid"]), now=datetime.now(timezone.utc)
    )
    with pytest.raises(AuthenticationError):
        await auth.login(LoginInput(email="patient@example.com", password="correct horse battery staple"))
    assert (await auth.login(LoginInput(email="patient@example.com", password="new correct horse battery staple")))["user"]["id"] == str(user_id)


def test_logout_and_password_change_revoke_sessions(service: Any) -> None:
    asyncio.run(_logout_and_password_change_revoke_sessions(service))


class FakeDemoLifecycle:
    def __init__(self) -> None:
        self.started = 0
        self.deleted = 0
        self.fail_start = False
        self.fail_delete = False
        self.delete_match = True

    async def start_for_login(self, **_values: Any) -> None:
        if self.fail_start:
            raise RuntimeError("provider details must stay hidden")
        self.started += 1

    async def delete_for_logout(self, _refresh_hash: str) -> bool:
        if self.fail_delete:
            raise RuntimeError("database details must stay hidden")
        self.deleted += 1
        return self.delete_match


def test_demo_lifecycle_is_guarded_and_failures_issue_no_tokens(service: Any) -> None:
    async def scenario() -> None:
        _, repo, ring = service
        lifecycle = FakeDemoLifecycle()
        auth = AuthService(
            repo, ring, jwt_signing_key="j" * 32,
            demo_scenario_enabled=True, demo_lifecycle=lifecycle,
        )
        user = UserRecord(
            DEMO_PATIENT_ID, DEMO_EMAIL, hash_password("long-demo-password"),
            "patient", "active", False, NOW,
        )
        repo.users[user.id] = user
        repo.consents[user.id] = []

        lifecycle.fail_start = True
        with pytest.raises(DemoLifecycleUnavailable, match="demo_seed_failed"):
            await auth.login(LoginInput(
                email=DEMO_EMAIL, password="long-demo-password",
            ))
        assert repo.sessions == {}
        assert lifecycle.started == 0

        lifecycle.fail_start = False
        login = await auth.login(LoginInput(
            email=DEMO_EMAIL, password="long-demo-password",
        ))
        assert lifecycle.started == 1
        lifecycle.fail_delete = True
        with pytest.raises(DemoLifecycleUnavailable, match="demo_delete_failed"):
            await auth.logout(login["refreshToken"])
        assert repo.sessions[hash_refresh_token(login["refreshToken"])].revoked_at is None
        lifecycle.fail_delete = False
        await auth.logout(login["refreshToken"])
        assert lifecycle.deleted == 1

        disabled = AuthService(
            repo, ring, jwt_signing_key="j" * 32,
            demo_scenario_enabled=False, demo_lifecycle=lifecycle,
        )
        before = lifecycle.started
        await disabled.login(LoginInput(
            email=DEMO_EMAIL, password="long-demo-password",
        ))
        assert lifecycle.started == before

        ordinary = UserRecord(
            uuid4(), "ordinary@example.com", hash_password("ordinary-password"),
            "patient", "active", False, NOW,
        )
        repo.users[ordinary.id] = ordinary
        repo.consents[ordinary.id] = []
        lifecycle.delete_match = False
        ordinary_login = await auth.login(LoginInput(
            email=ordinary.email, password="ordinary-password",
        ))
        assert lifecycle.started == before
        await auth.logout(ordinary_login["refreshToken"])
        assert repo.sessions[
            hash_refresh_token(ordinary_login["refreshToken"])
        ].revoked_at is not None

    asyncio.run(scenario())


async def _append_only_consent_and_independent_feature_gate(service: Any) -> None:
    auth, repo, _ = service
    signup = await auth.patient_signup(PatientSignupInput(
        email="patient@example.com", password="correct horse battery staple", consent=consent()
    ))
    user_id = UUID(signup["user"]["id"])
    await auth.require_consent(user_id, "biosignal")
    updated = await auth.append_consent(user_id, consent(biosignal=False, aiAnalysis=True))
    assert updated["biosignal"] is False
    assert len(repo.consents[user_id]) == 2
    with pytest.raises(AuthorizationError):
        await auth.require_consent(user_id, "biosignal")
    await auth.require_consent(user_id, "ai_analysis")


def test_append_only_consent_and_independent_feature_gate(service: Any) -> None:
    asyncio.run(_append_only_consent_and_independent_feature_gate(service))
