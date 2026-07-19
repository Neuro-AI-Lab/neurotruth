from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any
from uuid import UUID

@dataclass(frozen=True)
class UserRecord:
    id: UUID
    email: str
    password_hash: str
    role: str
    status: str
    must_change_password: bool
    created_at: datetime


@dataclass(frozen=True)
class ConsentRecord:
    id: UUID
    user_id: UUID
    tos: bool
    privacy: bool
    sensitive: bool
    biosignal: bool
    ai_analysis: bool
    notification: bool
    report_generation: bool
    tos_version: str
    privacy_version: str
    consent_form_version: str
    collected_at: datetime
    camera_rppg: bool = False
    face_video_retention: bool = False
    voice: bool = False


@dataclass(frozen=True)
class AuthSessionRecord:
    id: UUID
    user_id: UUID
    token_family_id: UUID
    refresh_token_hash: str
    expires_at: datetime
    parent_session_id: UUID | None = None
    revoked_at: datetime | None = None
    replaced_by_session_id: UUID | None = None


@dataclass(frozen=True)
class RotationResult:
    status: str
    user: UserRecord | None = None


@dataclass(frozen=True)
class SystemSettingsRecord:
    interventions_enabled: bool
    chat_timeout_seconds: int
    admin_signup_code_hash: str


@dataclass(frozen=True)
class SensorResultRecord:
    recording_id: UUID
    prediction_id: UUID | None
    alert_id: UUID | None
    client_window_id: UUID
    checksum_sha256: str
    prediction: dict[str, Any]
