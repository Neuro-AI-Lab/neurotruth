from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


class RevealVideoBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    reason: str = Field(min_length=1, max_length=1000)

    @field_validator("reason")
    @classmethod
    def reason_required(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Reason is required")
        return value.strip()


class DeleteCaptureBody(RevealVideoBody):
    confirmCaptureId: UUID
