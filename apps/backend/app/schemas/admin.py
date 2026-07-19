from pydantic import BaseModel, ConfigDict, Field, field_validator


class ReasonBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    reason: str = Field(min_length=1, max_length=1000)

    @field_validator("reason")
    @classmethod
    def non_blank_reason(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("Reason is required")
        return value


class TemporaryPasswordBody(ReasonBody):
    temporaryPassword: str = Field(min_length=12, max_length=1024)


class DeleteBody(ReasonBody):
    confirmation: str


class SettingsPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    interventionsEnabled: bool | None = None
    chatTimeoutSeconds: int | None = Field(default=None, ge=60, le=86400)
    adminSignupCode: str | None = Field(default=None, min_length=16, max_length=1024)
