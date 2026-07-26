from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import UUID, uuid4

from app.core.security.crypto import AesGcmKeyring, aad_for
from app.core.security.passwords import hash_password, verify_password
from app.core.security.tokens import create_access_token, hash_refresh_token, issue_refresh_token
from app.maintenance.seed_vp012_demo import (
    DEMO_EMAIL,
    DEMO_PATIENT_ID,
)

from app.models.records import (
    AuthSessionRecord,
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


class AuthenticationError(ValueError):
    pass


class AuthorizationError(ValueError):
    pass


class DemoLifecycleUnavailable(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class AuthService:
    def __init__(
        self,
        repository: V25Repository,
        keyring: AesGcmKeyring,
        *,
        jwt_signing_key: str,
        access_minutes: int = 15,
        refresh_days: int = 30,
        demo_scenario_enabled: bool = False,
        demo_lifecycle: Any | None = None,
    ) -> None:
        self.repository = repository
        self.keyring = keyring
        self.jwt_signing_key = jwt_signing_key
        self.access_ttl = timedelta(minutes=access_minutes)
        self.refresh_ttl = timedelta(days=refresh_days)
        self.demo_scenario_enabled = demo_scenario_enabled
        self.demo_lifecycle = demo_lifecycle

    async def bootstrap_admin_code(self, code: str) -> None:
        await self.repository.ensure_system_settings(hash_password(code))

    async def patient_signup(self, request: PatientSignupInput) -> dict[str, Any]:
        user_id = uuid4()
        encrypted_name = None
        if request.name:
            encrypted_name = self.keyring.encrypt(
                request.name.encode("utf-8"),
                aad=aad_for(table="patient_profiles", column="name_encrypted", patient_id=str(user_id), record_id=str(user_id)),
            ).pack()
        user = await self.repository.create_patient(
            user_id=user_id, email=self._email(request.email), password_hash=hash_password(request.password),
            name_encrypted=encrypted_name, encryption_key_version=self.keyring.current_key_id,
            birth_year=request.birth_year, gender=request.gender, consent=request.consent,
        )
        await self.repository.audit(actor_id=user.id, actor_role="patient", action="patient.signup", resource_type="user", resource_id=user.id)
        return await self._issue(user, {})

    async def admin_signup(self, request: AdminSignupInput) -> dict[str, Any]:
        settings = await self.repository.system_settings()
        if settings is None or not verify_password(request.signup_code, settings.admin_signup_code_hash):
            raise AuthenticationError("Invalid administrator signup code")
        user = await self.repository.create_admin(
            user_id=uuid4(), email=self._email(request.email), password_hash=hash_password(request.password)
        )
        await self.repository.audit(actor_id=user.id, actor_role="admin", action="admin.signup", resource_type="user", resource_id=user.id)
        return await self._issue(user, {})

    async def login(self, request: LoginInput) -> dict[str, Any]:
        user = await self.repository.user_by_email(self._email(request.email))
        if user is None or user.status != "active" or not verify_password(request.password, user.password_hash):
            raise AuthenticationError("Invalid credentials")
        if (
            self.demo_scenario_enabled
            and self.demo_lifecycle is not None
            and user.id == DEMO_PATIENT_ID
            and user.email.lower() == DEMO_EMAIL
        ):
            try:
                await self.demo_lifecycle.start_for_login(
                    user_id=user.id,
                    email=user.email,
                    now=datetime.now(timezone.utc),
                )
            except Exception as exc:
                raise DemoLifecycleUnavailable("demo_seed_failed") from exc
        await self.repository.audit(actor_id=user.id, actor_role=user.role, action="auth.login", resource_type="user", resource_id=user.id)
        return await self._issue(user, request.device)

    async def refresh(self, refresh_token: str, device: dict[str, Any] | None = None) -> dict[str, Any]:
        raw = issue_refresh_token()
        now = datetime.now(timezone.utc)
        new = AuthSessionRecord(
            id=uuid4(), user_id=UUID(int=0), token_family_id=UUID(int=0),
            refresh_token_hash=hash_refresh_token(raw), expires_at=now + self.refresh_ttl,
        )
        result = await self.repository.rotate_auth_session(
            presented_hash=hash_refresh_token(refresh_token), new_session=new,
            device=device or {}, now=now,
        )
        if result.status != "rotated" or result.user is None:
            raise AuthenticationError("Invalid refresh token")
        user = result.user
        access, expires = create_access_token(
            subject=user.id, session_id=new.id, role=user.role,
            signing_key=self.jwt_signing_key, ttl=self.access_ttl, now=now
        )
        return await self._auth_response(user, access, raw, expires)

    async def logout(self, refresh_token: str) -> None:
        refresh_hash = hash_refresh_token(refresh_token)
        if self.demo_scenario_enabled and self.demo_lifecycle is not None:
            try:
                if await self.demo_lifecycle.delete_for_logout(refresh_hash):
                    return
            except Exception as exc:
                raise DemoLifecycleUnavailable("demo_delete_failed") from exc
        await self.repository.revoke_refresh(refresh_hash, reason="logout", now=datetime.now(timezone.utc))

    async def change_password(self, user_id: UUID, request: ChangePasswordInput) -> None:
        user = await self.repository.user_by_id(user_id)
        if user is None or user.status != "active" or not verify_password(request.current_password, user.password_hash):
            raise AuthenticationError("Current password is invalid")
        await self.repository.update_password(user.id, hash_password(request.new_password))
        await self.repository.revoke_user_sessions(user.id, reason="password_changed", now=datetime.now(timezone.utc))
        await self.repository.audit(actor_id=user.id, actor_role=user.role, action="auth.password_changed", resource_type="user", resource_id=user.id)

    async def append_consent(self, user_id: UUID, consent: ConsentInput) -> dict[str, Any]:
        user = await self.repository.user_by_id(user_id)
        if user is None or user.role != "patient" or user.status != "active":
            raise AuthorizationError("Patient account is unavailable")
        row = await self.repository.append_consent(user_id, consent)
        await self.repository.audit(actor_id=user_id, actor_role="patient", action="consent.append", resource_type="consent_snapshot", resource_id=row.id)
        return self._public_consent(row)

    async def current_consent(self, user_id: UUID) -> dict[str, Any] | None:
        row = await self.repository.current_consent(user_id)
        return self._public_consent(row) if row else None

    async def require_consent(self, user_id: UUID, feature: str) -> None:
        allowed = {"biosignal", "voice", "ai_analysis", "notification", "report_generation", "camera_rppg", "face_video_retention"}
        if feature not in allowed:
            raise ValueError("Unknown consent feature")
        row = await self.repository.current_consent(user_id)
        if row is None or not (row.tos and row.privacy and row.sensitive) or not bool(getattr(row, feature)):
            raise AuthorizationError(f"Consent required for {feature}")

    async def _issue(self, user: UserRecord, device: dict[str, Any]) -> dict[str, Any]:
        now = datetime.now(timezone.utc)
        refresh = issue_refresh_token()
        session = AuthSessionRecord(
            id=uuid4(), user_id=user.id, token_family_id=uuid4(),
            refresh_token_hash=hash_refresh_token(refresh), expires_at=now + self.refresh_ttl,
        )
        await self.repository.create_auth_session(session, device)
        access, expires = create_access_token(
            subject=user.id, session_id=session.id, role=user.role,
            signing_key=self.jwt_signing_key, ttl=self.access_ttl, now=now
        )
        return await self._auth_response(user, access, refresh, expires)

    async def _auth_response(self, user: UserRecord, access: str, refresh: str, expires: int) -> dict[str, Any]:
        consent = await self.repository.current_consent(user.id) if user.role == "patient" else None
        return {
            "user": self._public_user(user),
            "accessToken": access,
            "refreshToken": refresh,
            "expiresIn": expires,
            "consent": self._public_consent(consent) if consent else None,
        }

    @staticmethod
    def _email(value: str) -> str:
        return str(value).strip().lower()

    @staticmethod
    def _public_user(user: UserRecord) -> dict[str, Any]:
        return {"id": str(user.id), "email": user.email, "role": user.role,
                "status": user.status, "mustChangePassword": user.must_change_password,
                "createdAt": user.created_at.isoformat()}

    @staticmethod
    def _public_consent(row: Any) -> dict[str, Any]:
        return {"id": str(row.id), "tos": row.tos, "privacy": row.privacy,
                "sensitive": row.sensitive, "biosignal": row.biosignal,
                "aiAnalysis": row.ai_analysis, "notification": row.notification,
                "reportGeneration": row.report_generation, "voice": bool(getattr(row, "voice", False)),
                "cameraRppg": row.camera_rppg,
                "faceVideoRetention": row.face_video_retention,
                "tosVersion": row.tos_version, "privacyVersion": row.privacy_version,
                "consentFormVersion": row.consent_form_version,
                "collectedAt": row.collected_at.isoformat()}
