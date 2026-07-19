import math
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class SensorSample(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sensor: str
    timestampMs: int
    value: float = Field(allow_inf_nan=False)

    @field_validator("value", mode="before")
    @classmethod
    def reject_non_finite_value(cls, value: object) -> object:
        try:
            finite = math.isfinite(float(value))
        except (TypeError, ValueError, OverflowError):
            return value
        return value if finite else "non-finite sensor value"


class SensorSync(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: str = Field(min_length=1, max_length=128)
    fillMode: str = Field(min_length=1, max_length=128)
    ppgHz: int = Field(gt=0, le=10_000)
    ppgSamplesPerChannel: int = Field(ge=0, le=1_000_000)
    edaHz: int = Field(gt=0, le=10_000)
    edaSamples: int = Field(ge=0, le=1_000_000)


class SensorWindow(BaseModel):
    model_config = ConfigDict(extra="forbid")
    clientWindowId: UUID
    sessionStartedAtMs: int
    sequence: int
    sentAtMs: int
    windowStartMs: int
    windowEndMs: int
    windowMs: int = Field(default=20_000, ge=19_500, le=20_500)
    samples: list[SensorSample] = Field(default_factory=list)
    sync: SensorSync | None = None

    @model_validator(mode="after")
    def validate_window_timing(self) -> "SensorWindow":
        if self.windowEndMs <= self.windowStartMs:
            raise ValueError("windowEndMs must be greater than windowStartMs")
        if self.windowMs != self.windowEndMs - self.windowStartMs:
            raise ValueError("windowMs must equal windowEndMs - windowStartMs")
        return self
