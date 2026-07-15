from __future__ import annotations

import hashlib
import secrets
from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import UUID

from jose import JWTError, jwt


JWT_ALGORITHM = "HS256"


class InvalidAccessToken(ValueError):
    pass


def create_access_token(
    *,
    subject: UUID | str,
    session_id: UUID | str,
    role: str,
    signing_key: str,
    ttl: timedelta = timedelta(minutes=15),
    now: datetime | None = None,
) -> tuple[str, int]:
    if role not in {"patient", "admin"}:
        raise ValueError("Invalid token role")
    if len(signing_key.encode("utf-8")) < 32:
        raise ValueError("JWT signing key must be at least 32 bytes")
    issued = now or datetime.now(timezone.utc)
    expires = issued + ttl
    payload: dict[str, Any] = {
        "sub": str(subject),
        "sid": str(UUID(str(session_id))),
        "role": role,
        "iat": int(issued.timestamp()),
        "exp": int(expires.timestamp()),
        "jti": secrets.token_urlsafe(18),
        "typ": "access",
    }
    return jwt.encode(payload, signing_key, algorithm=JWT_ALGORITHM), int(ttl.total_seconds())


def verify_access_token(token: str, *, signing_key: str) -> dict[str, Any]:
    try:
        payload = jwt.decode(token, signing_key, algorithms=[JWT_ALGORITHM])
        UUID(str(payload["sub"]))
        UUID(str(payload["sid"]))
        if payload.get("role") not in {"patient", "admin"} or payload.get("typ") != "access":
            raise InvalidAccessToken("Access token is invalid")
        return payload
    except (JWTError, KeyError, ValueError) as exc:
        raise InvalidAccessToken("Access token is invalid") from exc


def issue_refresh_token() -> str:
    return secrets.token_urlsafe(48)


def hash_refresh_token(token: str) -> str:
    if not token:
        raise ValueError("Refresh token is required")
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def refresh_token_matches(token: str, expected_hash: str) -> bool:
    """Compare a presented opaque token without data-dependent string timing."""

    if len(expected_hash) != 64:
        return False
    return secrets.compare_digest(hash_refresh_token(token), expected_hash)
