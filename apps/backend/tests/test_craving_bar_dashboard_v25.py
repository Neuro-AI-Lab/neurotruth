from __future__ import annotations

import asyncio
import base64
from datetime import date, datetime, timezone
from decimal import Decimal
from inspect import getsource
from uuid import uuid4

import pytest

from app.core.security.crypto import AesGcmKeyring
from app.services.dashboard import (
    DashboardService,
    DashboardTimezoneError,
)
from app.repositories.postgres import SqlAlchemyV25Repository


class Repo:
    def __init__(self, rows):
        self.rows = rows
        self.calls = []

    async def craving_dashboard_rows(self, *args):
        self.calls.append(args)
        return self.rows


class Storage:
    pass


def ring() -> AesGcmKeyring:
    return AesGcmKeyring.from_config(
        "v1:" + base64.b64encode(b"k" * 32).decode(), "v1",
    )


def rows():
    return {
        "current": {
            "probability": Decimal("0.812345"),
            "predicted_at": datetime(2026, 7, 16, 11, 59, tzinfo=timezone.utc),
        },
        "hourly": [{
            "local_hour": 10,
            "average_probability": Decimal("0.72"),
            "minimum_probability": Decimal("0.41"),
            "maximum_probability": Decimal("0.91"),
            "sample_count": 3590,
            "low_count": 100,
            "observe_count": 490,
            "caution_count": 1000,
            "high_count": 2000,
        }],
        "prediction_days": [
            {"local_date": date(2026, 7, 15), "prediction_count": 10},
            {"local_date": date(2026, 7, 16), "prediction_count": 20},
        ],
        "alert_days": [{
            "local_date": date(2026, 7, 16),
            "recommend_count": 2,
            "required_count": 1,
        }],
        "auq": [{
            "bucket_key": 9,
            "average_normalized_score": Decimal("0.63"),
            "sample_count": 2,
        }],
    }


def test_craving_dashboard_returns_complete_local_buckets_and_distinguishes_zero_from_no_data() -> None:
    async def scenario():
        repo = Repo(rows())
        service = DashboardService(repo, ring(), Storage())
        result = await service.craving_dashboard(
            uuid4(),
            "Asia/Seoul",
            "7d",
            "today",
            now=datetime(2026, 7, 16, 12, tzinfo=timezone.utc),
        )
        assert result["currentCraving"]["probability"] == pytest.approx(0.812345)
        assert len(result["hourlyCraving"]["buckets"]) == 24
        assert result["hourlyCraving"]["buckets"][10]["averageProbability"] == pytest.approx(0.72)
        assert result["hourlyCraving"]["buckets"][10]["stageCounts"] == {
            "low": 100,
            "observe": 490,
            "caution": 1000,
            "high": 2000,
        }
        assert sum(result["hourlyCraving"]["buckets"][10]["stageCounts"].values()) == 3590
        assert result["hourlyCraving"]["buckets"][11]["averageProbability"] is None
        assert result["hourlyCraving"]["buckets"][11]["sampleCount"] == 0
        assert result["hourlyCraving"]["buckets"][11]["stageCounts"] == {
            "low": 0,
            "observe": 0,
            "caution": 0,
            "high": 0,
        }
        assert len(result["dailyEvents"]["buckets"]) == 7
        previous = next(
            item for item in result["dailyEvents"]["buckets"]
            if item["localDate"] == "2026-07-15"
        )
        assert previous["hasPredictionData"] is True and previous["totalCount"] == 0
        empty = result["dailyEvents"]["buckets"][0]
        assert empty["hasPredictionData"] is False and empty["totalCount"] == 0
        assert len(result["auq"]["buckets"]) == 24
        assert result["auq"]["buckets"][9]["averageNormalizedScore"] == pytest.approx(0.63)
        _, zone, day_start, day_end, _, _, unit = repo.calls[0]
        assert zone == "Asia/Seoul" and unit == "hour"
        assert day_start.hour == 15 and day_end.hour == 15
    asyncio.run(scenario())


def test_daily_auq_and_thirty_day_event_ranges_are_complete() -> None:
    async def scenario():
        payload = rows()
        payload["auq"] = [{
            "bucket_key": date(2026, 7, 16),
            "average_normalized_score": Decimal("1.2"),
            "sample_count": 1,
        }]
        result = await DashboardService(Repo(payload), ring(), Storage()).craving_dashboard(
            uuid4(),
            "Etc/GMT+5",
            "30d",
            "7d",
            now=datetime(2026, 7, 16, 12, tzinfo=timezone.utc),
        )
        assert len(result["dailyEvents"]["buckets"]) == 30
        assert result["auq"]["bucketUnit"] == "day"
        assert len(result["auq"]["buckets"]) == 7
        assert result["auq"]["buckets"][-1]["averageNormalizedScore"] == 1.0
        assert "localDate" in result["auq"]["buckets"][-1]
    asyncio.run(scenario())


def test_dst_wall_clock_contract_always_returns_24_labels_and_merges_repository_hour() -> None:
    async def scenario():
        payload = rows()
        payload["hourly"] = [{
            "local_hour": 1,
            "average_probability": Decimal("0.5"),
            "minimum_probability": Decimal("0.2"),
            "maximum_probability": Decimal("0.8"),
            "sample_count": 7200,
            "low_count": 1000,
            "observe_count": 2000,
            "caution_count": 3000,
            "high_count": 1200,
        }]
        result = await DashboardService(Repo(payload), ring(), Storage()).craving_dashboard(
            uuid4(),
            "America/New_York",
            "7d",
            "today",
            now=datetime(2026, 11, 1, 17, tzinfo=timezone.utc),
        )
        buckets = result["hourlyCraving"]["buckets"]
        assert len(buckets) == 24
        assert [datetime.fromisoformat(item["localStart"]).hour for item in buckets] == list(range(24))
        assert buckets[1]["sampleCount"] == 7200
    asyncio.run(scenario())


def test_hourly_stage_sql_uses_exact_non_overlapping_boundaries() -> None:
    source = getsource(SqlAlchemyV25Repository.craving_dashboard_rows)
    assert "p.continuous_value < 0.25" in source
    assert "p.continuous_value >= 0.25 AND p.continuous_value < 0.50" in source
    assert "p.continuous_value >= 0.50 AND p.continuous_value < 0.75" in source
    assert "p.continuous_value >= 0.75" in source


def test_invalid_timezone_is_rejected() -> None:
    async def scenario():
        with pytest.raises(DashboardTimezoneError):
            await DashboardService(Repo(rows()), ring(), Storage()).craving_dashboard(
                uuid4(), "Not/AZone", "7d", "today",
            )
    asyncio.run(scenario())
