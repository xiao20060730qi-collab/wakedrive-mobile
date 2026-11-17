"""Authenticated, account-private monitoring report API."""
from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute
from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

if __package__:
    from backend.account_data import as_utc
    from backend.auth import require_user
else:
    from account_data import as_utc
    from auth import require_user


DAY_MS = 86400000
Reason = Annotated[str, StringConstraints(max_length=200)]
Milliseconds = Annotated[float, Field(ge=0, le=DAY_MS, allow_inf_nan=False)]
Source = Literal["camera", "video"]


class ReportModel(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class ReportSample(ReportModel):
    offsetMs: Milliseconds
    spanMs: Milliseconds
    validMs: Milliseconds
    closedMs: Milliseconds
    score: float | None = Field(ge=0, le=100)
    severity: Literal["none", "normal", "mild", "medium", "high"]
    reasons: list[Reason] = Field(max_length=5)

    @model_validator(mode="after")
    def validate_coverage(self):
        if self.closedMs > self.validMs or self.validMs > self.spanMs:
            raise ValueError("closedMs <= validMs <= spanMs is required")
        if self.validMs == 0:
            if self.score is not None or self.severity != "none":
                raise ValueError("Unknown samples require score=null and severity=none")
        elif self.score is None or self.severity == "none":
            raise ValueError("Valid samples require a score and known severity")
        return self


class ReportEvent(ReportModel):
    offsetMs: Milliseconds
    type: Literal["EYE_CLOSE", "YAWN", "PHONE_USE", "HEAD_DOWN", "HEAD_AWAY", "FACE_LOST", "DRINK"]
    severity: Literal["mild", "medium", "high"]
    reason: Reason
    ear: float | None = Field(default=None, ge=0, le=2)
    mar: float | None = Field(default=None, ge=0, le=5)
    closedDurationMs: float | None = Field(default=None, ge=0, le=600000)
    yawnDurationMs: float | None = Field(default=None, ge=0, le=600000)


class ReportSettings(ReportModel):
    earThreshold: float | None = Field(default=None, ge=.05, le=.8)
    marThreshold: float | None = Field(default=None, ge=.1, le=1)
    mildFrames: int | None = Field(default=None, ge=1, le=30)
    mediumFrames: int | None = Field(default=None, ge=1, le=60)
    heavyFrames: int | None = Field(default=None, ge=1, le=120)
    detectIntervalMs: int | None = Field(default=None, ge=100, le=10000)

    @model_validator(mode="after")
    def validate_threshold_order(self):
        ordered = [value for value in (self.mildFrames, self.mediumFrames, self.heavyFrames) if value is not None]
        if any(left > right for left, right in zip(ordered, ordered[1:])):
            raise ValueError("Fatigue frame thresholds must be nondecreasing")
        return self


class ReportCreate(ReportModel):
    clientId: str = Field(min_length=8, max_length=80, pattern=r"^[A-Za-z0-9_-]{8,80}$")
    source: Source
    title: str = Field(default="", max_length=120)
    startedAt: datetime
    endedAt: datetime
    samples: list[ReportSample] = Field(max_length=2000)
    events: list[ReportEvent] = Field(max_length=1000)
    settings: ReportSettings = Field(default_factory=ReportSettings)

    @property
    def duration_ms(self):
        return (self.endedAt - self.startedAt).total_seconds() * 1000

    @model_validator(mode="after")
    def validate_intervals(self):
        self.startedAt, self.endedAt = as_utc(self.startedAt), as_utc(self.endedAt)
        duration = self.duration_ms
        if not 0 <= duration <= DAY_MS:
            raise ValueError("Report duration must be between zero and 24 hours")
        previous_offset, previous_end = -1, 0
        for sample in self.samples:
            if sample.offsetMs <= previous_offset or sample.offsetMs < previous_end:
                raise ValueError("Samples must be strictly ordered and must not overlap")
            previous_offset = sample.offsetMs
            previous_end = sample.offsetMs + sample.spanMs
            if previous_end > duration + 1000:
                raise ValueError("Sample extends beyond report duration")
        if sum(sample.validMs for sample in self.samples) > duration:
            raise ValueError("Valid coverage cannot exceed report duration")
        if any(event.offsetMs > duration + 1000 for event in self.events):
            raise ValueError("Event extends beyond report duration")
        return self

    def validated_payload(self):
        data = self.model_dump(mode="json")
        data["settings"] = self.settings.model_dump(exclude_none=True)
        valid = sum(sample.validMs for sample in self.samples)
        data["summary"] = {
            "durationMs": self.duration_ms, "validMs": valid,
            "unknownMs": self.duration_ms - valid,
            "coverage": valid / self.duration_ms if self.duration_ms else 0,
            "peakScore": max((sample.score for sample in self.samples if sample.validMs), default=None),
            "eventCount": len(self.events),
            "severityCounts": {level: sum(event.severity == level for event in self.events)
                               for level in ("mild", "medium", "high")},
            "typeCounts": {kind: sum(event.type == kind for event in self.events)
                           for kind in ("EYE_CLOSE", "YAWN", "PHONE_USE", "HEAD_DOWN", "HEAD_AWAY", "FACE_LOST", "DRINK")},
        }
        return data


class ReportRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def validated_request(request: Request):
            try:
                return await handler(request)
            except RequestValidationError as exc:
                # FastAPI's default error renderer echoes nonfinite JSON input,
                # which itself cannot be encoded as a JSON error response.
                errors = [{key: error[key] for key in ("loc", "msg", "type")} for error in exc.errors()]
                raise HTTPException(422, detail=errors) from exc

        return validated_request


router = APIRouter(prefix="/api/reports", tags=["private reports"], route_class=ReportRoute)


@router.post("", status_code=201)
def create_report(body: ReportCreate, request: Request):
    user = require_user(request)
    return request.app.state.auth_store.create_report(user["id"], body.validated_payload())


@router.get("")
def list_reports(request: Request, source: Source | None = None,
                 limit: int = Query(default=50, ge=1, le=500), offset: int = Query(default=0, ge=0)):
    user = require_user(request)
    return request.app.state.auth_store.list_reports(user["id"], source=source, limit=limit, offset=offset)


@router.get("/{report_id}")
def get_report(report_id: str, request: Request):
    user = require_user(request)
    report = request.app.state.auth_store.get_report(user["id"], report_id)
    if report is None:
        raise HTTPException(404, "Report not found")
    return report
