import asyncio

from app.repositories.legacy_memory import DDL_STATEMENTS, PostgresMemory


def test_memory_schema_uses_idempotent_tables_and_expected_indexes() -> None:
    ddl = "\n".join(DDL_STATEMENTS)

    for table in (
        "sessions",
        "prediction_events",
        "alert_decisions",
        "conversation_turns",
        "craving_slots",
        "handoff_reports",
    ):
        assert f"CREATE TABLE IF NOT EXISTS {table}" in ddl

    for index in (
        "prediction_events_session_created_idx",
        "alert_decisions_session_created_idx",
        "conversation_turns_session_created_idx",
    ):
        assert f"CREATE INDEX IF NOT EXISTS {index}" in ddl


def test_memory_noops_when_database_url_is_absent() -> None:
    memory = PostgresMemory(database_url="")

    async def run() -> None:
        await memory.start()
        await memory.record_prediction_event(
            "session-1",
            {"class": 1, "timestampMs": 123},
            {"alertLevel": "none", "alertAction": "none", "alertRequired": False},
        )
        await memory.record_conversation_turn("session-1", "user", "hello")
        await memory.upsert_craving_slots("session-1", {"trigger": "stress"})
        await memory.record_handoff_report("session-1", "# Report")
        assert await memory.conversation_history("session-1") == []
        assert await memory.current_slots("session-1") == {}
        assert await memory.handoff_context("session-1") == {
            "alertEvents": [],
            "predictionSummary": {},
        }
        await memory.stop()

    asyncio.run(run())
