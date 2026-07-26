from __future__ import annotations

from pathlib import Path
from urllib.parse import urlparse

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.core.security.crypto import AesGcmKeyring


class SecuritySettings(BaseSettings):
    """Fail-closed V2.5 runtime configuration."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = Field(alias="DATABASE_URL")
    data_encryption_keys_b64: str = Field(alias="DATA_ENCRYPTION_KEYS_B64")
    data_encryption_current_key_id: str = Field(alias="DATA_ENCRYPTION_CURRENT_KEY_ID")
    jwt_signing_key: str = Field(alias="JWT_SIGNING_KEY")
    admin_signup_code: SecretStr = Field(alias="ADMIN_SIGNUP_CODE")
    sensor_storage_root: Path = Field(alias="SENSOR_STORAGE_ROOT")
    environment: str = Field(default="development", alias="APP_ENV")
    allow_insecure_http: bool = Field(default=False, alias="ALLOW_INSECURE_HTTP")
    access_token_minutes: int = Field(default=15, alias="ACCESS_TOKEN_MINUTES", ge=1, le=60)
    refresh_token_days: int = Field(default=30, alias="REFRESH_TOKEN_DAYS", ge=1, le=90)
    state_summary_ai_enabled: bool = Field(default=False, alias="STATE_SUMMARY_AI_ENABLED")
    report_ai_enabled: bool = Field(default=False, alias="REPORT_AI_ENABLED")
    demo_scenario_enabled: bool = Field(default=False, alias="DEMO_SCENARIO_ENABLED")
    rppg_enabled: bool = Field(default=True, alias="RPPG_ENABLED")
    rppg_base_url: str = Field(default="http://192.168.68.50:8000", alias="RPPG_BASE_URL")
    rppg_connect_timeout_seconds: float = Field(default=10, alias="RPPG_CONNECT_TIMEOUT_SECONDS", gt=0, le=60)
    rppg_read_timeout_seconds: float = Field(default=180, alias="RPPG_READ_TIMEOUT_SECONDS", gt=0, le=900)
    rppg_max_upload_mib: int = Field(default=40, alias="RPPG_MAX_UPLOAD_MIB", ge=1, le=100)
    rppg_max_concurrency: int = Field(default=1, alias="RPPG_MAX_CONCURRENCY", ge=1, le=8)
    rppg_storage_root: Path | None = Field(default=None, alias="RPPG_STORAGE_ROOT")
    rppg_tmpfs_root: Path = Field(default=Path("/dev/shm/neurotruth-rppg"), alias="RPPG_TMPFS_ROOT")
    rppg_ffprobe_path: str = Field(default="ffprobe", alias="RPPG_FFPROBE_PATH")

    @model_validator(mode="after")
    def validate_security(self) -> "SecuritySettings":
        if not self.database_url.startswith(("postgresql+asyncpg://", "postgresql://")):
            raise ValueError("DATABASE_URL must be PostgreSQL")
        if len(self.jwt_signing_key.encode("utf-8")) < 32:
            raise ValueError("JWT_SIGNING_KEY must be at least 32 bytes")
        if len(self.admin_signup_code.get_secret_value()) < 16:
            raise ValueError("ADMIN_SIGNUP_CODE must be at least 16 characters")
        AesGcmKeyring.from_config(
            self.data_encryption_keys_b64,
            self.data_encryption_current_key_id,
        )
        if self.environment.lower() not in {"development", "test", "production"}:
            raise ValueError("APP_ENV must be development, test, or production")
        if self.environment.lower() == "production" and self.allow_insecure_http:
            raise ValueError("Insecure HTTP cannot be enabled in production")
        if self.rppg_enabled:
            if self.rppg_storage_root is None:
                raise ValueError("RPPG_STORAGE_ROOT is required when rPPG is enabled")
            parsed = urlparse(self.rppg_base_url)
            if parsed.scheme not in {"http", "https"} or not parsed.netloc:
                raise ValueError("RPPG_BASE_URL must be HTTP or HTTPS")
        return self

    def keyring(self) -> AesGcmKeyring:
        return AesGcmKeyring.from_config(
            self.data_encryption_keys_b64,
            self.data_encryption_current_key_id,
        )

    def assert_request_transport(self, url: str) -> None:
        scheme = urlparse(url).scheme.lower()
        if scheme == "https":
            return
        if scheme == "http" and self.environment.lower() != "production" and self.allow_insecure_http:
            return
        raise ValueError("HTTPS is required")
