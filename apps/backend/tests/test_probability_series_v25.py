from __future__ import annotations

import asyncio
from datetime import timedelta
from uuid import uuid4

from app.services.dashboard import DashboardService


class ProbabilityRepository:
    def __init__(self, rows_factory=None) -> None:
        self.calls = []
        self.rows_factory = rows_factory

    async def craving_probability_rows(self, patient_id, since, until, bucket_seconds, max_points):
        self.calls.append((patient_id, since, until, bucket_seconds, max_points))
        if self.rows_factory is not None:
            return self.rows_factory(since, until)
        return [
            {
                "bucket_at": since + timedelta(seconds=1),
                "average_probability": 0.7132458,
                "sample_count": 57,
            }
        ]


def test_probability_ranges_use_fixed_server_buckets() -> None:
    async def scenario() -> None:
        repository = ProbabilityRepository()
        service = DashboardService(repository, None, None)
        patient = uuid4()
        expected = {"10m": (1, 600), "24h": (60, 1440), "7d": (600, 1008), "30d": (1800, 1440)}
        for range_code, (bucket, maximum) in expected.items():
            result = await service.craving_probability_series(patient, range_code)
            assert result["range"] == range_code
            assert result["bucketSeconds"] == bucket
            assert result["points"] == [{
                "at": (repository.calls[-1][1] + timedelta(seconds=1)).isoformat(),
                "averageCravingProbability": 0.713246,
                "sampleCount": 57,
            }]
            assert repository.calls[-1][0] == patient
            assert repository.calls[-1][2] >= repository.calls[-1][1]
            assert repository.calls[-1][3:] == (bucket, maximum)

    asyncio.run(scenario())


def test_series_excludes_future_rows_and_caps_most_recent_points_in_ascending_order() -> None:
    async def scenario() -> None:
        def rows(since, until):
            valid = [
                {
                    "bucket_at": since + timedelta(milliseconds=900 * index),
                    "average_probability": index / 1000,
                    "sample_count": 1,
                }
                for index in range(605)
            ]
            return list(reversed(valid)) + [{
                "bucket_at": until + timedelta(seconds=1),
                "average_probability": 0.99,
                "sample_count": 1,
            }]

        service = DashboardService(ProbabilityRepository(rows), None, None)
        result = await service.craving_probability_series(uuid4(), "10m")
        points = result["points"]
        assert len(points) == 600
        assert points == sorted(points, key=lambda item: item["at"])
        assert all(result["from"] <= point["at"] <= result["to"] for point in points)
        assert points[0]["averageCravingProbability"] == 0.005
        assert points[-1]["averageCravingProbability"] == 0.604

    asyncio.run(scenario())


def test_series_exposes_sensor_linked_prediction_for_ppg_preview() -> None:
    async def scenario() -> None:
        prediction_id = uuid4()

        def rows(since, _until):
            return [{
                "bucket_at": since + timedelta(seconds=10),
                "average_probability": 0.4,
                "sample_count": 1,
                "prediction_id": prediction_id,
            }]

        result = await DashboardService(
            ProbabilityRepository(rows), None, None,
        ).craving_probability_series(uuid4(), "1h")

        assert result["points"][0]["predictionId"] == str(prediction_id)

    asyncio.run(scenario())
