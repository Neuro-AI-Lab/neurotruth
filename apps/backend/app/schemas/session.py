from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


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
