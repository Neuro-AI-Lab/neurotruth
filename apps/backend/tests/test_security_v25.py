from __future__ import annotations

import base64
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

import pytest
from jose import jwt

from app.core.security.crypto import (
    AesGcmKeyring,
    DecryptionError,
    EncryptionConfigurationError,
    aad_for,
)
from app.core.security.passwords import hash_password, verify_password
from app.core.security.tokens import (
    InvalidAccessToken,
    create_access_token,
    hash_refresh_token,
    issue_refresh_token,
    refresh_token_matches,
    verify_access_token,
)
from app.core.config import SecuritySettings


def _encoded_key(byte: int) -> str:
    return base64.b64encode(bytes([byte]) * 32).decode("ascii")


def test_aes_gcm_round_trip_uses_unique_nonce_and_aad() -> None:
    ring = AesGcmKeyring.from_config(
        f'{{"v1":"{_encoded_key(1)}","v2":"{_encoded_key(2)}"}}',
        "v2",
    )
    aad = aad_for(table="messages", column="content_encrypted", patient_id=str(uuid4()), record_id=str(uuid4()))
    first = ring.encrypt("민감한 원문".encode(), aad=aad)
    second = ring.encrypt("민감한 원문".encode(), aad=aad)

    assert first.key_id == "v2"
    assert first.nonce != second.nonce
    assert ring.decrypt(first.pack(), aad=aad).decode() == "민감한 원문"
    with pytest.raises(DecryptionError):
        ring.decrypt(first.pack(), aad=aad + b"tampered")


def test_aes_gcm_reads_old_key_and_rejects_unknown_key() -> None:
    old = AesGcmKeyring({"v1": bytes([1]) * 32}, "v1")
    aad = b"bound"
    envelope = old.encrypt(b"value", aad=aad)
    rotated = AesGcmKeyring({"v1": bytes([1]) * 32, "v2": bytes([2]) * 32}, "v2")
    assert rotated.decrypt(envelope, aad=aad) == b"value"
    with pytest.raises(DecryptionError):
        AesGcmKeyring({"v2": bytes([2]) * 32}, "v2").decrypt(envelope, aad=aad)


def test_invalid_key_lengths_fail_closed() -> None:
    with pytest.raises(EncryptionConfigurationError):
        AesGcmKeyring.from_config(f"v1:{base64.b64encode(b'short').decode()}", "v1")


def test_argon2id_password_hash() -> None:
    encoded = hash_password("correct horse battery staple")
    assert encoded.startswith("$argon2id$")
    assert verify_password("correct horse battery staple", encoded)
    assert not verify_password("incorrect-password", encoded)


def test_access_and_refresh_token_primitives() -> None:
    user_id = uuid4()
    session_id = uuid4()
    now = datetime.now(timezone.utc)
    token, expires_in = create_access_token(
        subject=user_id,
        session_id=session_id,
        role="patient",
        signing_key="x" * 32,
        ttl=timedelta(minutes=15),
        now=now,
    )
    claims = verify_access_token(token, signing_key="x" * 32)
    assert claims["sub"] == str(user_id)
    assert claims["sid"] == str(session_id)
    assert claims["role"] == "patient"
    assert expires_in == 900
    with pytest.raises(InvalidAccessToken):
        verify_access_token(token, signing_key="y" * 32)

    unsigned_claims = jwt.get_unverified_claims(token)
    missing_sid = dict(unsigned_claims)
    missing_sid.pop("sid")
    malformed_sid = {**unsigned_claims, "sid": "not-a-uuid"}
    with pytest.raises(InvalidAccessToken):
        verify_access_token(jwt.encode(missing_sid, "x" * 32, algorithm="HS256"), signing_key="x" * 32)
    with pytest.raises(InvalidAccessToken):
        verify_access_token(jwt.encode(malformed_sid, "x" * 32, algorithm="HS256"), signing_key="x" * 32)

    one = issue_refresh_token()
    two = issue_refresh_token()
    assert one != two
    assert len(hash_refresh_token(one)) == 64
    assert refresh_token_matches(one, hash_refresh_token(one))
    assert not refresh_token_matches(two, hash_refresh_token(one))


def test_settings_require_secure_material_and_guard_http() -> None:
    required = {
        "DATABASE_URL": "postgresql+asyncpg://user:pass@localhost/db",
        "DATA_ENCRYPTION_KEYS_B64": f"v1:{_encoded_key(7)}",
        "DATA_ENCRYPTION_CURRENT_KEY_ID": "v1",
        "JWT_SIGNING_KEY": "z" * 32,
        "ADMIN_SIGNUP_CODE": "admin-signup-code-value",
        "SENSOR_STORAGE_ROOT": Path("sensor-data"),
    }
    with patch.dict(os.environ, {}, clear=True):
        settings = SecuritySettings(
            _env_file=None,
            **required,
            APP_ENV="development",
            ALLOW_INSECURE_HTTP=True,
            RPPG_ENABLED=False,
        )
        settings.assert_request_transport("http://127.0.0.1:8000/health")
        settings.assert_request_transport("https://example.invalid/health")

        default_rppg = SecuritySettings(
            _env_file=None,
            **required,
            RPPG_STORAGE_ROOT=Path("rppg-data"),
        )
        assert default_rppg.rppg_enabled is True
        explicitly_disabled_rppg = SecuritySettings(
            _env_file=None,
            **required,
            RPPG_ENABLED=False,
        )
        assert explicitly_disabled_rppg.rppg_enabled is False

        with pytest.raises(ValueError):
            SecuritySettings(
                _env_file=None,
                **{**required, "DATABASE_URL": "postgresql://user:pass@localhost/db"},
                APP_ENV="production",
                ALLOW_INSECURE_HTTP=True,
                RPPG_ENABLED=False,
            )
