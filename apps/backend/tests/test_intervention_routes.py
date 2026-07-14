import asyncio
from typing import Any

import pytest
from fastapi import HTTPException

from app import main
from app.ai.bedrock_agents import QUESTION_FREE_ACKNOWLEDGEMENT


class FakeMemory:
    def __init__(self) -> None:
        self.turns: list[dict[str, Any]] = []
        self.slots: list[dict[str, Any]] = []
        self.reports: list[dict[str, Any]] = []

    async def record_conversation_turn(self, **kwargs: Any) -> None:
        self.turns.append(kwargs)

    async def upsert_craving_slots(
        self,
        session_id: str,
        slots: dict[str, Any],
        missing_slots: list[Any] | None = None,
    ) -> None:
        self.slots.append(
            {"session_id": session_id, "slots": slots, "missing_slots": missing_slots}
        )

    async def handoff_context(self, session_id: str) -> dict[str, Any]:
        return {
            "alertEvents": [{"alertLevel": "required"}],
            "predictionSummary": {"count": 1, "meanClass": 2.0},
        }

    async def current_slots(self, session_id: str) -> dict[str, Any]:
        return {"trigger": "stress"}

    async def conversation_history(self, session_id: str) -> list[dict[str, Any]]:
        return [{"role": "user", "content": "craving feels strong"}]

    async def record_handoff_report(self, **kwargs: Any) -> None:
        self.reports.append(kwargs)


def test_intervention_chat_forwards_alert_context_and_persists_turns(monkeypatch) -> None:
    fake_memory = FakeMemory()
    calls: list[dict[str, Any]] = []

    async def fake_ai_chat(payload: dict[str, Any]) -> dict[str, Any]:
        calls.append({"helper": "chat", "payload": payload})
        return {
            "response": "Can we pause and observe the craving for a moment?",
            "text": "Can we pause and observe the craving for a moment?",
            "model": "us.anthropic.claude-sonnet-4-6",
        }

    async def fake_ai_slots(payload: dict[str, Any]) -> dict[str, Any]:
        calls.append({"helper": "slots", "payload": payload})
        return {
            "slots": {
                "intensity": "high",
                "duration": "20 minutes",
                "coping_attempt": "walk",
                "diagnosis": "alcohol use disorder",
            },
            "missingSlots": ["recent_alcohol_use", "location"],
        }

    monkeypatch.setattr(main.prediction_service, "memory", fake_memory)
    monkeypatch.setattr(main, "_ai_chat_respond", fake_ai_chat)
    monkeypatch.setattr(main, "_ai_slots_extract", fake_ai_slots)

    result = asyncio.run(
        main.intervention_chat(
            main.InterventionChatRequest(
                sessionId="session-1",
                message="I want to drink",
                alert={"alertLevel": "required"},
                slots={"trigger": "stress", "location": "home"},
            )
        )
    )

    assert [call["helper"] for call in calls] == ["chat", "slots"]
    assert calls[0]["payload"] == {
        "sessionId": "session-1",
        "message": "I want to drink",
        "conversationHistory": [],
        "slots": {"trigger": "stress"},
        "alertContext": {"alertLevel": "required"},
    }
    assert calls[1]["payload"] == {
        "sessionId": "session-1",
        "conversationHistory": [
            {"role": "user", "content": "I want to drink"},
            {
                "role": "assistant",
                "content": "Can we pause and observe the craving for a moment?",
            },
        ],
        "currentSlots": {"trigger": "stress"},
    }
    assert result["sessionId"] == "session-1"
    assert result["response"] == "Can we pause and observe the craving for a moment?"
    assert result["mergedSlots"] == {
        "trigger": "stress",
        "intensity": "high",
        "duration": "20 minutes",
        "coping_attempt": "walk",
    }
    assert "location" not in result["mergedSlots"]
    assert "diagnosis" not in result["mergedSlots"]
    assert result["missingSlots"] == ["recent_alcohol_use"]
    # Handoff readiness is true when at least 3 slots are filled and no more than
    # one craving slot is still missing.
    assert result["handoffReady"] is True
    assert [turn["role"] for turn in fake_memory.turns] == ["user", "assistant"]
    assert fake_memory.slots[0]["slots"] == result["mergedSlots"]
    assert fake_memory.slots[0]["missing_slots"] == ["recent_alcohol_use"]


def test_ai_chat_repairs_one_repeated_non_safety_question(monkeypatch) -> None:
    calls: list[dict[str, Any]] = []
    responses = iter(
        [
            "알겠습니다. 지금 갈망의 강도는 어느 정도인가요?",
            "지금 곁에서 도움을 청할 수 있는 분이 있나요?",
        ]
    )

    async def fake_complete(**kwargs: Any) -> str:
        calls.append(kwargs)
        return next(responses)

    monkeypatch.setattr(main.bedrock_adapter, "complete", fake_complete)

    result = asyncio.run(
        main._ai_chat_respond(
            {
                "sessionId": "session-repair",
                "message": "7 정도예요",
                "conversationHistory": [
                    {
                        "role": "assistant",
                        "content": "지금 갈망 강도는 어느 정도인가요?",
                    },
                    {"role": "user", "content": "7 정도예요"},
                ],
                "slots": {"intensity": "7 (환자: '7 정도예요')"},
                "alertContext": {},
            }
        )
    )

    assert len(calls) == 2
    assert calls[0]["max_tokens"] == calls[1]["max_tokens"] == 700
    assert calls[0]["temperature"] == calls[1]["temperature"] == 0.4
    assert "의미상 반복" in calls[1]["messages"][-1]["content"]
    assert result["response"] == "지금 곁에서 도움을 청할 수 있는 분이 있나요?"
    assert set(result) == {"sessionId", "response", "text", "model"}


def test_ai_chat_does_not_repair_distinct_question_or_question_free_text(
    monkeypatch,
) -> None:
    calls: list[dict[str, Any]] = []

    async def fake_complete(**kwargs: Any) -> str:
        calls.append(kwargs)
        return "말해 주셔서 감사합니다. 지금 어디에 계신가요?"

    monkeypatch.setattr(main.bedrock_adapter, "complete", fake_complete)
    history = [
        {"role": "assistant", "content": "지금 갈망 강도는 어느 정도인가요?"}
    ]

    distinct = asyncio.run(
        main._ai_chat_respond(
            {
                "sessionId": "session-distinct",
                "message": "집이에요",
                "conversationHistory": history,
                "slots": {},
                "alertContext": {},
            }
        )
    )

    assert distinct["response"].endswith("계신가요?")
    assert len(calls) == 1

    calls.clear()

    async def fake_statement(**kwargs: Any) -> str:
        calls.append(kwargs)
        return "말해 주셔서 감사합니다. 잠시 호흡을 가다듬어 보셔도 좋습니다."

    monkeypatch.setattr(main.bedrock_adapter, "complete", fake_statement)
    statement = asyncio.run(
        main._ai_chat_respond(
            {
                "sessionId": "session-statement",
                "message": "알겠어요",
                "conversationHistory": history,
                "slots": {},
                "alertContext": {},
            }
        )
    )

    assert "?" not in statement["response"]
    assert len(calls) == 1


def test_ai_chat_does_not_repair_repeated_safety_question(monkeypatch) -> None:
    calls: list[dict[str, Any]] = []

    async def fake_complete(**kwargs: Any) -> str:
        calls.append(kwargs)
        return "지금 자해할 생각이 있으신가요?"

    monkeypatch.setattr(main.bedrock_adapter, "complete", fake_complete)

    result = asyncio.run(
        main._ai_chat_respond(
            {
                "sessionId": "session-safety",
                "message": "잘 모르겠어요",
                "conversationHistory": [
                    {
                        "role": "assistant",
                        "content": "지금 자해할 생각이 있으신가요?",
                    }
                ],
                "slots": {},
                "alertContext": {},
            }
        )
    )

    assert result["response"] == "지금 자해할 생각이 있으신가요?"
    assert len(calls) == 1


def test_ai_chat_repair_failure_returns_question_free_success(monkeypatch) -> None:
    calls = 0

    async def fake_complete(**kwargs: Any) -> str:
        nonlocal calls
        calls += 1
        if calls == 1:
            return "지금 갈망의 강도는 어느 정도인가요?"
        raise RuntimeError("optional repair failed")

    monkeypatch.setattr(main.bedrock_adapter, "complete", fake_complete)

    result = asyncio.run(
        main._ai_chat_respond(
            {
                "sessionId": "session-repair-failure",
                "message": "7 정도예요",
                "conversationHistory": [
                    {
                        "role": "assistant",
                        "content": "지금 갈망 강도는 어느 정도인가요?",
                    }
                ],
                "slots": {"intensity": "7"},
                "alertContext": {},
            }
        )
    )

    assert calls == 2
    assert result["response"] == QUESTION_FREE_ACKNOWLEDGEMENT


def test_ai_chat_repeated_repair_uses_local_non_question_fallback(monkeypatch) -> None:
    calls = 0

    async def fake_complete(**kwargs: Any) -> str:
        nonlocal calls
        calls += 1
        if calls == 1:
            return "지금 갈망의 강도는 어느 정도인가요?"
        return "말해 주셔서 감사합니다. 지금 갈망 강도는 어느 정도인가요?"

    monkeypatch.setattr(main.bedrock_adapter, "complete", fake_complete)

    result = asyncio.run(
        main._ai_chat_respond(
            {
                "sessionId": "session-repeated-repair",
                "message": "7 정도예요",
                "conversationHistory": [
                    {
                        "role": "assistant",
                        "content": "지금 갈망 강도는 어느 정도인가요?",
                    }
                ],
                "slots": {"intensity": "7"},
                "alertContext": {},
            }
        )
    )

    assert calls == 2
    assert result["response"] == "말해 주셔서 감사합니다."
    assert "?" not in result["response"]


def test_intervention_handoff_uses_memory_context_and_persists_report(monkeypatch) -> None:
    fake_memory = FakeMemory()
    calls: list[dict[str, Any]] = []

    async def fake_ai_handoff(payload: dict[str, Any]) -> dict[str, Any]:
        calls.append({"helper": "handoff", "payload": payload})
        return {"reportMarkdown": "# Handoff", "missingSlots": ["recent_alcohol_use"]}

    monkeypatch.setattr(main.prediction_service, "memory", fake_memory)
    monkeypatch.setattr(main, "_ai_handoff_generate", fake_ai_handoff)

    result = asyncio.run(
        main.intervention_handoff(main.HandoffRequest(sessionId="session-2"))
    )

    assert calls[0]["helper"] == "handoff"
    assert calls[0]["payload"]["slots"] == {"trigger": "stress"}
    assert calls[0]["payload"]["alertEvents"] == [{"alertLevel": "required"}]
    assert calls[0]["payload"]["predictionSummary"] == {"count": 1, "meanClass": 2.0}
    assert result["sessionId"] == "session-2"
    assert fake_memory.reports[0]["report_markdown"] == "# Handoff"


def test_async_handoff_job_transitions_and_persists_snapshot(monkeypatch) -> None:
    fake_memory = FakeMemory()
    registry = main.HandoffJobRegistry(max_entries=4, ttl_seconds=60)
    started = asyncio.Event()
    release = asyncio.Event()
    captured: list[dict[str, Any]] = []

    async def fake_ai_handoff(payload: dict[str, Any]) -> dict[str, Any]:
        captured.append(payload)
        started.set()
        await release.wait()
        return {"reportMarkdown": "# Async Handoff", "missingSlots": []}

    monkeypatch.setattr(main.prediction_service, "memory", fake_memory)
    monkeypatch.setattr(main, "handoff_job_registry", registry)
    monkeypatch.setattr(main, "_ai_handoff_generate", fake_ai_handoff)

    async def scenario() -> None:
        body = main.HandoffRequest(
            sessionId="session-async",
            slots={"trigger": "stress"},
            conversationHistory=[{"role": "user", "content": "original"}],
            alertEvents=[{"alertLevel": "required"}],
            predictionSummary={"count": 1},
        )
        accepted = await main.submit_intervention_handoff_job(body)

        assert accepted["sessionId"] == "session-async"
        assert accepted["status"] == "queued"
        assert accepted["jobId"]

        body.conversationHistory[0]["content"] = "mutated after submission"
        await started.wait()
        running = await main.intervention_handoff_job_status(accepted["jobId"])
        assert running == {
            "jobId": accepted["jobId"],
            "sessionId": "session-async",
            "status": "running",
        }

        release.set()
        for _ in range(10):
            completed = await main.intervention_handoff_job_status(accepted["jobId"])
            if completed["status"] == "completed":
                break
            await asyncio.sleep(0)

        assert completed["result"]["reportMarkdown"] == "# Async Handoff"
        assert completed["result"]["sessionId"] == "session-async"
        assert captured[0]["conversationHistory"][0]["content"] == "original"
        assert fake_memory.reports[0]["session_id"] == "session-async"
        assert fake_memory.reports[0]["report_markdown"] == "# Async Handoff"
        await registry.shutdown()

    asyncio.run(scenario())

    route = next(
        route
        for route in main.app.routes
        if getattr(route, "path", None) == "/api/intervention/handoff/jobs"
    )
    assert route.status_code == 202


def test_async_handoff_failure_is_sanitized(monkeypatch) -> None:
    fake_memory = FakeMemory()
    registry = main.HandoffJobRegistry(max_entries=4, ttl_seconds=60)

    async def failing_handoff(payload: dict[str, Any]) -> dict[str, Any]:
        raise RuntimeError("AWS_BEARER_TOKEN_BEDROCK=secret raw provider payload")

    monkeypatch.setattr(main.prediction_service, "memory", fake_memory)
    monkeypatch.setattr(main, "handoff_job_registry", registry)
    monkeypatch.setattr(main, "_ai_handoff_generate", failing_handoff)

    async def scenario() -> None:
        accepted = await main.submit_intervention_handoff_job(
            main.HandoffRequest(sessionId="session-failed", slots={})
        )
        for _ in range(10):
            failed = await main.intervention_handoff_job_status(accepted["jobId"])
            if failed["status"] == "failed":
                break
            await asyncio.sleep(0)

        assert failed == {
            "jobId": accepted["jobId"],
            "sessionId": "session-failed",
            "status": "failed",
            "error": "Handoff generation failed",
        }
        assert "secret" not in str(failed)
        assert fake_memory.reports == []
        await registry.shutdown()

    asyncio.run(scenario())


def test_async_handoff_unknown_and_expired_jobs_return_not_found(monkeypatch) -> None:
    now = [100.0]
    registry = main.HandoffJobRegistry(
        max_entries=4,
        ttl_seconds=5,
        clock=lambda: now[0],
    )
    monkeypatch.setattr(main, "handoff_job_registry", registry)

    async def immediate(payload: dict[str, Any]) -> dict[str, Any]:
        return {"reportMarkdown": "# Done"}

    async def scenario() -> None:
        with pytest.raises(HTTPException) as unknown:
            await main.intervention_handoff_job_status("unknown-job")
        assert unknown.value.status_code == 404

        accepted = await registry.submit(
            session_id="session-expired",
            payload={"sessionId": "session-expired"},
            runner=immediate,
        )
        for _ in range(10):
            completed = await registry.status(accepted["jobId"])
            if completed and completed["status"] == "completed":
                break
            await asyncio.sleep(0)
        assert completed and completed["status"] == "completed"

        now[0] += 5
        with pytest.raises(HTTPException) as expired:
            await main.intervention_handoff_job_status(accepted["jobId"])
        assert expired.value.status_code == 404
        await registry.shutdown()

    asyncio.run(scenario())


def test_async_handoff_registry_rejects_when_active_capacity_is_full() -> None:
    registry = main.HandoffJobRegistry(max_entries=1, ttl_seconds=60)
    release = asyncio.Event()

    async def blocked(payload: dict[str, Any]) -> dict[str, Any]:
        await release.wait()
        return {}

    async def scenario() -> None:
        await registry.submit(
            session_id="session-one",
            payload={},
            runner=blocked,
        )
        with pytest.raises(HTTPException) as full:
            await registry.submit(
                session_id="session-two",
                payload={},
                runner=blocked,
            )
        assert full.value.status_code == 503
        assert full.value.detail == "Handoff job capacity reached"
        await registry.shutdown()

    asyncio.run(scenario())


def test_async_handoff_immediate_shutdown_fails_queued_job_and_closes_registry() -> None:
    registry = main.HandoffJobRegistry(max_entries=2, ttl_seconds=60)
    runner_started = False

    async def blocked(payload: dict[str, Any]) -> dict[str, Any]:
        nonlocal runner_started
        runner_started = True
        await asyncio.Event().wait()
        return {}

    async def scenario() -> None:
        accepted = await registry.submit(
            session_id="session-shutdown",
            payload={},
            runner=blocked,
        )
        await registry.shutdown()

        failed = await registry.status(accepted["jobId"])
        assert failed == {
            "jobId": accepted["jobId"],
            "sessionId": "session-shutdown",
            "status": "failed",
            "error": "Handoff generation failed",
        }
        assert runner_started is False

        with pytest.raises(HTTPException) as closed:
            await registry.submit(
                session_id="session-after-shutdown",
                payload={},
                runner=blocked,
            )
        assert closed.value.status_code == 503
        assert closed.value.detail == "Handoff job registry is shutting down"

    asyncio.run(scenario())


def test_async_handoff_runner_timeout_fails_and_releases_capacity() -> None:
    registry = main.HandoffJobRegistry(
        max_entries=1,
        ttl_seconds=60,
        run_timeout_seconds=0.01,
    )

    async def hung(payload: dict[str, Any]) -> dict[str, Any]:
        await asyncio.Event().wait()
        return {}

    async def immediate(payload: dict[str, Any]) -> dict[str, Any]:
        return {"reportMarkdown": "# Recovered"}

    async def scenario() -> None:
        timed_out = await registry.submit(
            session_id="session-timeout",
            payload={},
            runner=hung,
        )
        for _ in range(20):
            status = await registry.status(timed_out["jobId"])
            if status and status["status"] == "failed":
                break
            await asyncio.sleep(0.005)

        assert status == {
            "jobId": timed_out["jobId"],
            "sessionId": "session-timeout",
            "status": "failed",
            "error": "Handoff generation failed",
        }

        recovered = await registry.submit(
            session_id="session-recovered",
            payload={},
            runner=immediate,
        )
        for _ in range(10):
            recovered_status = await registry.status(recovered["jobId"])
            if recovered_status and recovered_status["status"] == "completed":
                break
            await asyncio.sleep(0)

        assert recovered_status and recovered_status["status"] == "completed"
        assert recovered_status["result"] == {"reportMarkdown": "# Recovered"}
        assert await registry.status(timed_out["jobId"]) is None
        await registry.shutdown()

    asyncio.run(scenario())
