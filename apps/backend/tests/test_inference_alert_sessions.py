import asyncio
import threading
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from app.ml.craving.model import ModelUnavailableError
from app.services.prediction import RealtimePredictionService
from app.services.prediction import RuntimePredictorAdapter


def test_authenticated_adapter_delegates_without_direct_model_bypass() -> None:
    async def scenario() -> None:
        model = SimpleNamespace(
            ready=True, model_name="Conv1DNet", model_version="fixture-v1",
            safe_artifact_uri=None, registration_config={},
            predict=Mock(side_effect=AssertionError("direct model bypass")),
        )
        queued_predict = AsyncMock(return_value={"class": 1})
        adapter = RuntimePredictorAdapter(SimpleNamespace(model=model, predict=queued_predict))
        payload = {"sequence": 1}

        assert await adapter.predict(payload) == {"class": 1}
        queued_predict.assert_awaited_once_with(payload)
        model.predict.assert_not_called()

    asyncio.run(scenario())


def test_prediction_service_routes_request_and_response_through_worker_queue() -> None:
    async def scenario() -> None:
        calls: list[dict] = []

        def model_predict(payload: dict) -> dict:
            calls.append(payload)
            return {"class": 1, "sequence": payload["sequence"], "timestampMs": 2000}

        service = RealtimePredictionService()
        service.model = SimpleNamespace(
            ready=True, last_debug={"preprocessMs": 2.0, "modelMs": 3.0},
            predict=model_predict, load=Mock(),
        )
        await service.start()
        payload = {"sequence": 7, "sentAtMs": 1000}
        pending = asyncio.create_task(RuntimePredictorAdapter(service).predict(payload))

        result = await asyncio.wait_for(pending, timeout=1)
        assert result["sequence"] == 7 and result["_lat"]["model_ms"] == 3.0
        assert calls and "_enqueuePerf" not in payload
        await service.stop()

    asyncio.run(scenario())


def test_stop_fails_requests_but_joins_model_thread_before_restart() -> None:
    entered, release = threading.Event(), threading.Event()
    concurrency_lock = threading.Lock()
    active_calls = 0
    max_concurrency = 0

    def model_predict(payload: dict) -> dict:
        nonlocal active_calls, max_concurrency
        with concurrency_lock:
            active_calls += 1
            max_concurrency = max(max_concurrency, active_calls)
        try:
            if payload["sequence"] == 1:
                entered.set()
                release.wait(timeout=2)
            return {"class": 1, "sequence": payload["sequence"], "timestampMs": 2000}
        finally:
            with concurrency_lock:
                active_calls -= 1

    service = RealtimePredictionService()
    service.model = SimpleNamespace(
        ready=True, last_debug={}, predict=model_predict, load=Mock(),
    )

    async def scenario() -> None:
        await service.start()
        inflight = asyncio.create_task(service.predict({"sequence": 1}))
        assert await asyncio.to_thread(entered.wait, 1)
        queued = asyncio.create_task(service.predict({"sequence": 2}))
        await asyncio.sleep(0)
        assert service.queue.qsize() == 1

        stopping = asyncio.create_task(service.stop())
        await asyncio.sleep(0)
        with pytest.raises(ModelUnavailableError):
            await service.predict({"sequence": 3})
        for request in (inflight, queued):
            with pytest.raises(ModelUnavailableError):
                await asyncio.wait_for(request, timeout=1)
        assert not stopping.done()

        stopping.cancel()
        with pytest.raises(asyncio.CancelledError):
            await stopping
        with pytest.raises(RuntimeError, match="stopping"):
            await service.start()

        release.set()
        await asyncio.wait_for(service.stop(), timeout=1)
        assert service.worker_task is None
        await service.start()
        assert (await service.predict({"sequence": 4}))["sequence"] == 4
        await service.stop()
        assert max_concurrency == 1

    asyncio.run(scenario())


def test_authenticated_predictor_observes_model_readiness_after_startup() -> None:
    model = SimpleNamespace(
        ready=False,
        model_name="Conv1DNet",
        model_version="moving-average-k5-test",
        safe_artifact_uri="model/weights/final_moving_average_k5/model_weights.pt",
        registration_config={},
    )
    adapter = RuntimePredictorAdapter(SimpleNamespace(model=model))

    assert adapter.ready is False
    model.ready = True

    assert adapter.ready is True
    assert adapter.model_name == "Conv1DNet"
    assert adapter.model_version == "moving-average-k5-test"
