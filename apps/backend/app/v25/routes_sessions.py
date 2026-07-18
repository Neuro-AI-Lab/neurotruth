from __future__ import annotations

from typing import Annotated, Any, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from .auth_service import AuthorizationError
from .dependencies import get_runtime, patient_user
from .models import UserRecord
from .runtime import V25Runtime
from .session_service import SessionAgentError, SessionError, SessionNotFound, SessionStateError


router = APIRouter(prefix="/api/sessions")


class SessionCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sessionType: Literal["alert_checkin", "manual_checkin", "scheduled_checkin"]
    triggerAlertId: UUID | None = None


class MessageBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    clientMessageId: UUID | None = None
    content: str = Field(min_length=1, max_length=10_000)
    inputModality: Literal["text", "voice"] = "text"


class AssessmentBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    instrumentCode: str = Field(min_length=1, max_length=32)
    version: str = Field(min_length=1, max_length=32)
    phase: Literal["pre_intervention", "post_intervention", "followup"]
    attemptNo: int = Field(ge=1)
    answers: dict[str, Any]
    rawScore: float
    scaleMin: float
    scaleMax: float


def _map(exc: Exception) -> None:
    def detail(code: str, message: str) -> dict[str, str]: return {"code": code, "message": message}
    if isinstance(exc, SessionNotFound): raise HTTPException(status_code=404, detail=detail(exc.code, "Session not found")) from exc
    if isinstance(exc, SessionAgentError):
        raise HTTPException(status_code=502, detail={
            "code": exc.code,
            "clientMessageId": str(exc.client_message_id) if exc.client_message_id else None,
            "userMessageId": str(exc.user_message_id),
            "retryable": exc.retryable,
            "attemptsRemaining": exc.attempts_remaining,
        }) from exc
    if isinstance(exc, SessionStateError): raise HTTPException(status_code=409, detail=detail(exc.code, str(exc))) from exc
    if isinstance(exc, AuthorizationError): raise HTTPException(status_code=403, detail=str(exc)) from exc
    raise exc


async def _ai_consent(runtime: V25Runtime, user: UserRecord) -> None:
    try: await runtime.service.require_consent(user.id, "ai_analysis")
    except AuthorizationError as exc: _map(exc)


@router.post("")
async def create_session(body: SessionCreate, runtime: Annotated[V25Runtime, Depends(get_runtime)],
                         user: Annotated[UserRecord, Depends(patient_user)]) -> dict[str, Any]:
    await _ai_consent(runtime, user)
    return await runtime.session_service.open(user, body.sessionType, body.triggerAlertId)


@router.get("/{session_id}")
async def get_session(session_id: UUID, runtime: Annotated[V25Runtime, Depends(get_runtime)],
                      user: Annotated[UserRecord, Depends(patient_user)]) -> dict[str, Any]:
    try: return await runtime.session_service.get(user, session_id)
    except SessionError as exc: _map(exc)


@router.post("/{session_id}/messages")
async def post_message(session_id: UUID, body: MessageBody,
                       runtime: Annotated[V25Runtime, Depends(get_runtime)],
                       user: Annotated[UserRecord, Depends(patient_user)]) -> dict[str, Any]:
    await _ai_consent(runtime, user)
    try:
        return await runtime.session_service.message(
            user, session_id, body.content.strip(), body.clientMessageId, body.inputModality,
        )
    except SessionError as exc: _map(exc)


@router.post("/{session_id}/assessments", status_code=201)
async def assessment(session_id: UUID, body: AssessmentBody,
                     runtime: Annotated[V25Runtime, Depends(get_runtime)],
                     user: Annotated[UserRecord, Depends(patient_user)]) -> dict[str, Any]:
    await _ai_consent(runtime, user)
    if body.scaleMax <= body.scaleMin or not body.scaleMin <= body.rawScore <= body.scaleMax:
        raise HTTPException(status_code=422, detail={"code": "invalid_assessment_score", "message": "Assessment score is outside its scale"})
    try: return await runtime.session_service.assessment(user, session_id, body.model_dump())
    except SessionError as exc: _map(exc)


@router.post("/{session_id}/finish")
async def finish(session_id: UUID, runtime: Annotated[V25Runtime, Depends(get_runtime)],
                 user: Annotated[UserRecord, Depends(patient_user)]) -> dict[str, Any]:
    await _ai_consent(runtime, user)
    try: return await runtime.session_service.finish(user, session_id)
    except SessionError as exc: _map(exc)


@router.post("/{session_id}/reports", status_code=202)
async def request_report(session_id: UUID, runtime: Annotated[V25Runtime, Depends(get_runtime)],
                         user: Annotated[UserRecord, Depends(patient_user)]) -> dict[str, Any]:
    await _ai_consent(runtime, user)
    try: return await runtime.session_service.request_report(user, session_id)
    except (SessionNotFound, SessionStateError, AuthorizationError) as exc: _map(exc)


@router.get("/{session_id}/reports")
async def reports(session_id: UUID, runtime: Annotated[V25Runtime, Depends(get_runtime)],
                  user: Annotated[UserRecord, Depends(patient_user)]) -> list[dict[str, Any]]:
    try: return await runtime.session_service.reports(user, session_id)
    except SessionNotFound as exc: _map(exc)
