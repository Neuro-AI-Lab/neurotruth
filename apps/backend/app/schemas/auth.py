from __future__ import annotations

from typing import Any

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


class RefreshBody(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    refresh_token: str = Field(alias="refreshToken", min_length=1)
    device: dict[str, Any] = Field(default_factory=dict)


class LogoutBody(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    refresh_token: str = Field(alias="refreshToken", min_length=1)


class MePatchBody(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    name: str | None = Field(default=None, max_length=200)
    birth_year: int | None = Field(default=None, alias="birthYear", ge=1900, le=2100)
    gender: str | None = None
