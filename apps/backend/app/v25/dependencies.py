from __future__ import annotations

from datetime import datetime, timezone
from typing import Annotated

from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.security.tokens import InvalidAccessToken, verify_access_token

from .models import UserRecord
from .runtime import V25Runtime


_bearer = HTTPBearer(auto_error=False)


def get_runtime(request: Request) -> V25Runtime:
    runtime = getattr(request.app.state, "v25_runtime", None)
    if runtime is None or not runtime.ready or runtime.service is None or runtime.repository is None or runtime.settings is None:
        raise HTTPException(status_code=503, detail="Service is not ready")
    try:
        runtime.settings.assert_request_transport(str(request.url))
    except ValueError as exc:
        raise HTTPException(status_code=426, detail="HTTPS is required") from exc
    return runtime


async def current_user(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
    runtime: Annotated[V25Runtime, Depends(get_runtime)],
) -> UserRecord:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=401, detail="Authentication required")
    try:
        claims = verify_access_token(credentials.credentials, signing_key=runtime.settings.jwt_signing_key)
        from uuid import UUID
        user_id, session_id = UUID(claims["sub"]), UUID(claims["sid"])
        user = await runtime.repository.user_by_id(user_id)
        session_active = await runtime.repository.auth_session_is_active(
            user_id=user_id, session_id=session_id, now=datetime.now(timezone.utc),
        )
    except (InvalidAccessToken, ValueError):
        user, session_active = None, False
    if user is None or user.status != "active" or not session_active:
        raise HTTPException(status_code=401, detail="Invalid access token")
    return user


async def password_ready_user(user: Annotated[UserRecord, Depends(current_user)]) -> UserRecord:
    if user.must_change_password:
        raise HTTPException(status_code=403, detail="Password change required")
    return user


async def patient_user(user: Annotated[UserRecord, Depends(password_ready_user)]) -> UserRecord:
    if user.role != "patient":
        raise HTTPException(status_code=403, detail="Patient role required")
    return user
