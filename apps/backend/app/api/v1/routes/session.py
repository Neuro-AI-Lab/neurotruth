from __future__ import annotations

from typing import Annotated, Any, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from app.api.v1.dependencies import get_runtime, patient_user
from app.core.runtime import BackendRuntime
from app.models.records import UserRecord
from app.schemas.session import AssessmentBody, MessageBody, SessionCreate
from app.services.auth import AuthorizationError
from app.services.session import SessionAgentError, SessionError, SessionNotFound, SessionStateError


router = APIRouter(prefix="/api/sessions")


def _validate_auq_v2(body: AssessmentBody) -> None:
    if body.instrumentCode != "AUQ":
        return
    answers = body.answers
    responses = answers.get("responses")
    scored = answers.get("scoredItems")
    valid_items = (
        isinstance(responses, list)
        and isinstance(scored, list)
        and len(responses) == 8
        and len(scored) == 8
        and all(type(value) is int and 0 <= value <= 6 for value in responses)
        and all(type(value) is int and 0 <= value <= 6 for value in scored)
    )
    valid_total = (
        valid_items
        and responses == scored
        and float(body.rawScore).is_integer()
        and int(body.rawScore) == sum(scored)
        and (
            "rawTotalScore" not in answers
            or type(answers["rawTotalScore"]) is int
            and answers["rawTotalScore"] == int(body.rawScore)
        )
    )
    if not (
        body.version == "2.0"
        and body.scaleMin == 0
        and body.scaleMax == 48
        and valid_total
    ):
        raise HTTPException(status_code=422, detail={
            "code": "invalid_auq_scale",
            "message": "AUQ requires eight matching 0..6 items and a 0..48 total",
        })


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


async def _ai_consent(runtime: BackendRuntime, user: UserRecord) -> None:
    try: await runtime.service.require_consent(user.id, "ai_analysis")
    except AuthorizationError as exc: _map(exc)


@router.post("")
async def create_session(body: SessionCreate, runtime: Annotated[BackendRuntime, Depends(get_runtime)],
                         user: Annotated[UserRecord, Depends(patient_user)]) -> dict[str, Any]:
    await _ai_consent(runtime, user)
    return await runtime.session_service.open(user, body.sessionType, body.triggerAlertId)


@router.get("/{session_id}")
async def get_session(session_id: UUID, runtime: Annotated[BackendRuntime, Depends(get_runtime)],
                      user: Annotated[UserRecord, Depends(patient_user)]) -> dict[str, Any]:
    try: return await runtime.session_service.get(user, session_id)
    except SessionError as exc: _map(exc)


@router.post("/{session_id}/messages")
async def post_message(session_id: UUID, body: MessageBody,
                       runtime: Annotated[BackendRuntime, Depends(get_runtime)],
                       user: Annotated[UserRecord, Depends(patient_user)]) -> dict[str, Any]:
    await _ai_consent(runtime, user)
    try:
        return await runtime.session_service.message(
            user, session_id, body.content.strip(), body.clientMessageId, body.inputModality,
        )
    except SessionError as exc: _map(exc)


@router.post("/{session_id}/assessments", status_code=201)
async def assessment(session_id: UUID, body: AssessmentBody,
                     runtime: Annotated[BackendRuntime, Depends(get_runtime)],
                     user: Annotated[UserRecord, Depends(patient_user)]) -> dict[str, Any]:
    await _ai_consent(runtime, user)
    _validate_auq_v2(body)
    if body.scaleMax <= body.scaleMin or not body.scaleMin <= body.rawScore <= body.scaleMax:
        raise HTTPException(status_code=422, detail={"code": "invalid_assessment_score", "message": "Assessment score is outside its scale"})
    try: return await runtime.session_service.assessment(user, session_id, body.model_dump())
    except SessionError as exc: _map(exc)


@router.post("/{session_id}/finish")
async def finish(session_id: UUID, runtime: Annotated[BackendRuntime, Depends(get_runtime)],
                 user: Annotated[UserRecord, Depends(patient_user)]) -> dict[str, Any]:
    await _ai_consent(runtime, user)
    try: return await runtime.session_service.finish(user, session_id)
    except SessionError as exc: _map(exc)


@router.post("/{session_id}/reports", status_code=202)
async def request_report(session_id: UUID, runtime: Annotated[BackendRuntime, Depends(get_runtime)],
                         user: Annotated[UserRecord, Depends(patient_user)]) -> dict[str, Any]:
    await _ai_consent(runtime, user)
    try: return await runtime.session_service.request_report(user, session_id)
    except (SessionNotFound, SessionStateError, AuthorizationError) as exc: _map(exc)


@router.get("/{session_id}/reports")
async def reports(session_id: UUID, runtime: Annotated[BackendRuntime, Depends(get_runtime)],
                  user: Annotated[UserRecord, Depends(patient_user)]) -> list[dict[str, Any]]:
    try: return await runtime.session_service.reports(user, session_id)
    except SessionNotFound as exc: _map(exc)
