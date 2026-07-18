from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator


def _camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=_camel, populate_by_name=True, extra="forbid")


class ConsentInput(ApiModel):
    tos: bool
    privacy: bool
    sensitive: bool
    biosignal: bool = False
    voice: bool = False
    ai_analysis: bool = False
    notification: bool = False
    report_generation: bool = False
    camera_rppg: bool = False
    face_video_retention: bool = False
    tos_version: str = Field(min_length=1, max_length=32)
    privacy_version: str = Field(min_length=1, max_length=32)
    consent_form_version: str = Field(min_length=1, max_length=32)

    @field_validator("tos", "privacy", "sensitive")
    @classmethod
    def required_consent(cls, value: bool) -> bool:
        if not value:
            raise ValueError("Required consent must be accepted")
        return value


class PatientSignupInput(ApiModel):
    email: EmailStr
    password: str = Field(min_length=12, max_length=1024)
    name: str | None = Field(default=None, max_length=200)
    birth_year: int | None = Field(default=None, ge=1900, le=2100)
    gender: str | None = None
    consent: ConsentInput


class AdminSignupInput(ApiModel):
    email: EmailStr
    password: str = Field(min_length=12, max_length=1024)
    signup_code: str = Field(min_length=1, max_length=1024)


class LoginInput(ApiModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=1024)
    device: dict[str, Any] = Field(default_factory=dict)


class ChangePasswordInput(ApiModel):
    current_password: str = Field(min_length=1, max_length=1024)
    new_password: str = Field(min_length=12, max_length=1024)


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
