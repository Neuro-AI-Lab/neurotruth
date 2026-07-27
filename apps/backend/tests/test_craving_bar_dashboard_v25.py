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
    DashboardRangeError,
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

    async def craving_probability_rows(self, patient_id, since, until, bucket_seconds, max_points):
        self.calls.append((patient_id, since, until, bucket_seconds, max_points))
        return [{
            "bucket_at": since,
            "average_probability": Decimal("0.75"),
            "sample_count": 1,
        }]

    async def craving_calendar_rows(self, *args):
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
            "average_score": Decimal("30.24"),
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
        assert result["auq"]["buckets"][9]["averageScore"] == pytest.approx(30.24)
        _, zone, day_start, day_end, _, _, unit = repo.calls[0]
        assert zone == "Asia/Seoul" and unit == "hour"
        assert day_start.hour == 15 and day_end.hour == 15
    asyncio.run(scenario())


def test_daily_auq_and_thirty_day_event_ranges_are_complete() -> None:
    async def scenario():
        payload = rows()
        payload["auq"] = [{
            "bucket_key": date(2026, 7, 16),
            "average_score": Decimal("52"),
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
        assert result["auq"]["buckets"][-1]["averageScore"] == 48.0
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
    assert "((a.raw_score-a.scale_min)/NULLIF(a.scale_max-a.scale_min,0))*48.0" in source


def test_legacy_scaled_auq_row_is_exposed_on_canonical_zero_to_48_scale() -> None:
    async def scenario():
        payload = rows()
        payload["auq"] = [{
            "bucket_key": 4,
            "average_normalized_score": Decimal("0.5"),
            "sample_count": 1,
        }]
        result = await DashboardService(Repo(payload), ring(), Storage()).craving_dashboard(
            uuid4(), "Asia/Seoul", "7d", "today",
            now=datetime(2026, 7, 16, 12, tzinfo=timezone.utc),
        )
        assert result["auq"]["buckets"][4]["averageNormalizedScore"] == 0.5
        assert result["auq"]["buckets"][4]["averageScore"] == 24.0

    asyncio.run(scenario())


def test_one_hour_probability_series_uses_ten_second_buckets_and_360_point_cap() -> None:
    async def scenario():
        repo = Repo(rows())
        result = await DashboardService(repo, ring(), Storage()).craving_probability_series(
            uuid4(), "1h",
        )
        assert result["range"] == "1h"
        assert result["bucketSeconds"] == 10
        assert len(result["points"]) == 1
        assert result["points"][0]["averageCravingProbability"] == 0.75
        assert repo.calls[-1][3:] == (10, 360)

    asyncio.run(scenario())


def test_invalid_timezone_is_rejected() -> None:
    async def scenario():
        with pytest.raises(DashboardTimezoneError):
            await DashboardService(Repo(rows()), ring(), Storage()).craving_dashboard(
                uuid4(), "Not/AZone", "7d", "today",
            )
    asyncio.run(scenario())


def test_day_calendar_returns_complete_hour_buckets_and_zero_event_distinction() -> None:
    async def scenario():
        payload = {
            "stages": [{
                "bucket_key": 10,
                "sample_count": 3,
                "low_count": 1,
                "observe_count": 1,
                "caution_count": 0,
                "high_count": 1,
            }],
            "alerts": [{"bucket_key": 10, "event_count": 1}],
            "auq": [{
                "bucket_key": 10,
                "average_score": Decimal("24.5"),
                "response_count": 2,
            }],
        }
        repo = Repo(payload)
        result = await DashboardService(repo, ring(), Storage()).craving_calendar(
            uuid4(), "Asia/Seoul", "day", date(2026, 7, 16),
        )
        assert result["view"] == "day" and result["bucketUnit"] == "hour"
        assert len(result["buckets"]) == 24
        bucket = result["buckets"][10]
        assert bucket["hasPredictionData"] is True
        assert bucket["stageCounts"] == {
            "low": 1, "observe": 1, "caution": 0, "high": 1,
        }
        assert bucket["eventCount"] == 1
        assert bucket["auqAverageScore"] == 24.5
        assert bucket["auqResponseCount"] == 2
        empty = result["buckets"][11]
        assert empty["hasPredictionData"] is False
        assert empty["eventCount"] == 0
        _, zone, start, end, unit = repo.calls[0]
        assert zone == "Asia/Seoul" and unit == "hour"
        assert (end - start).total_seconds() == 24 * 60 * 60

    asyncio.run(scenario())


def test_month_calendar_uses_actual_month_length_and_local_dates() -> None:
    async def scenario():
        payload = {
            "stages": [{
                "bucket_key": date(2028, 2, 29),
                "sample_count": 1,
                "low_count": 0,
                "observe_count": 0,
                "caution_count": 0,
                "high_count": 1,
            }],
            "alerts": [],
            "auq": [],
        }
        result = await DashboardService(Repo(payload), ring(), Storage()).craving_calendar(
            uuid4(), "Asia/Seoul", "month", date(2028, 2, 12),
        )
        assert result["bucketUnit"] == "day"
        assert len(result["buckets"]) == 29
        assert result["buckets"][-1]["localDate"] == "2028-02-29"
        assert result["buckets"][-1]["stageCounts"]["high"] == 1
        assert result["period"]["localStart"].startswith("2028-02-01")
        assert result["period"]["localEnd"].startswith("2028-03-01")

    asyncio.run(scenario())


def test_week_calendar_normalizes_to_monday_and_returns_seven_cross_month_days() -> None:
    async def scenario():
        payload = {
            "stages": [{
                "bucket_key": date(2026, 3, 1),
                "sample_count": 2,
                "low_count": 0,
                "observe_count": 1,
                "caution_count": 0,
                "high_count": 1,
            }],
            "alerts": [{"bucket_key": date(2026, 3, 1), "event_count": 1}],
            "auq": [{
                "bucket_key": date(2026, 3, 1),
                "average_score": Decimal("20"),
                "response_count": 1,
            }],
        }
        repo = Repo(payload)
        result = await DashboardService(repo, ring(), Storage()).craving_calendar(
            uuid4(), "Asia/Seoul", "week", date(2026, 2, 26),
        )
        assert result["view"] == "week" and result["bucketUnit"] == "day"
        assert len(result["buckets"]) == 7
        assert [row["localDate"] for row in result["buckets"]] == [
            "2026-02-23", "2026-02-24", "2026-02-25", "2026-02-26",
            "2026-02-27", "2026-02-28", "2026-03-01",
        ]
        assert result["buckets"][-1]["stageCounts"]["high"] == 1
        assert result["buckets"][-1]["eventCount"] == 1
        assert result["buckets"][-1]["auqAverageScore"] == 20.0
        assert result["period"]["localStart"].startswith("2026-02-23")
        assert result["period"]["localEnd"].startswith("2026-03-02")
        _, zone, start, end, unit = repo.calls[0]
        assert zone == "Asia/Seoul" and unit == "day"
        assert (end - start).total_seconds() == 7 * 24 * 60 * 60

    asyncio.run(scenario())


def test_week_calendar_uses_timezone_aware_boundaries_across_dst() -> None:
    async def scenario():
        repo = Repo({"stages": [], "alerts": [], "auq": []})
        result = await DashboardService(repo, ring(), Storage()).craving_calendar(
            uuid4(), "America/New_York", "week", date(2026, 11, 1),
        )
        assert result["period"]["localStart"].startswith("2026-10-26")
        assert result["period"]["localEnd"].startswith("2026-11-02")
        assert len(result["buckets"]) == 7
        _, _, start, end, unit = repo.calls[0]
        assert unit == "day"
        assert (end - start).total_seconds() == 7 * 24 * 60 * 60 + 60 * 60

    asyncio.run(scenario())


def test_calendar_dst_day_uses_24_wall_clock_labels() -> None:
    async def scenario():
        payload = {"stages": [], "alerts": [], "auq": []}
        repo = Repo(payload)
        result = await DashboardService(repo, ring(), Storage()).craving_calendar(
            uuid4(), "America/New_York", "day", date(2026, 11, 1),
        )
        assert len(result["buckets"]) == 24
        assert [datetime.fromisoformat(row["localStart"]).hour for row in result["buckets"]] == list(range(24))
        _, _, start, end, _ = repo.calls[0]
        assert (end - start).total_seconds() == 25 * 60 * 60

    asyncio.run(scenario())


def test_calendar_invalid_timezone_and_view_are_rejected() -> None:
    async def scenario():
        service = DashboardService(Repo({"stages": [], "alerts": [], "auq": []}), ring(), Storage())
        with pytest.raises(DashboardTimezoneError):
            await service.craving_calendar(uuid4(), "Not/AZone", "day", date.today())
        with pytest.raises(DashboardRangeError):
            await service.craving_calendar(uuid4(), "Asia/Seoul", "year", date.today())

    asyncio.run(scenario())


def test_calendar_sql_keeps_non_overlapping_stage_boundaries() -> None:
    source = getsource(SqlAlchemyV25Repository.craving_calendar_rows)
    assert "demoDisplayWeight" in source
    assert "sum({display_weight}) AS sample_count" in source
    assert "p.continuous_value < 0.25" in source
    assert "p.continuous_value >= 0.25 AND p.continuous_value < 0.50" in source
    assert "p.continuous_value >= 0.50 AND p.continuous_value < 0.75" in source
    assert "p.continuous_value >= 0.75" in source
