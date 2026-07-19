from __future__ import annotations

"""Best-effort Postgres memory for prediction, alert, chat, and handoff events."""

import json
import logging
import os
from typing import Any

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine

LOGGER = logging.getLogger(__name__)

DDL_STATEMENTS = [
    """
    CREATE TABLE IF NOT EXISTS sessions (
        session_id TEXT PRIMARY KEY,
        source TEXT,
        first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        metadata JSONB NOT NULL DEFAULT '{}'::jsonb
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS prediction_events (
        id BIGSERIAL PRIMARY KEY,
        session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
        sequence BIGINT,
        class INTEGER NOT NULL,
        confidence DOUBLE PRECISION,
        timestamp_ms BIGINT,
        event_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        payload JSONB NOT NULL DEFAULT '{}'::jsonb,
        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS alert_decisions (
        id BIGSERIAL PRIMARY KEY,
        prediction_event_id BIGINT REFERENCES prediction_events(id) ON DELETE SET NULL,
        session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
        alert_level TEXT NOT NULL,
        alert_action TEXT NOT NULL,
        window_mean DOUBLE PRECISION,
        trigger_reason TEXT,
        alert_required BOOLEAN NOT NULL DEFAULT FALSE,
        payload JSONB NOT NULL DEFAULT '{}'::jsonb,
        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS conversation_turns (
        id BIGSERIAL PRIMARY KEY,
        session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
        role TEXT NOT NULL,
        content TEXT NOT NULL,
        alert_context JSONB NOT NULL DEFAULT '{}'::jsonb,
        llm_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS craving_slots (
        session_id TEXT PRIMARY KEY REFERENCES sessions(session_id) ON DELETE CASCADE,
        slots JSONB NOT NULL DEFAULT '{}'::jsonb,
        missing_slots JSONB NOT NULL DEFAULT '[]'::jsonb,
        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS handoff_reports (
        id BIGSERIAL PRIMARY KEY,
        session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
        report_markdown TEXT NOT NULL,
        missing_slots JSONB NOT NULL DEFAULT '[]'::jsonb,
        source_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
    )
    """,
    "CREATE INDEX IF NOT EXISTS prediction_events_session_created_idx ON prediction_events(session_id, created_at DESC)",
    "CREATE INDEX IF NOT EXISTS alert_decisions_session_created_idx ON alert_decisions(session_id, created_at DESC)",
    "CREATE INDEX IF NOT EXISTS conversation_turns_session_created_idx ON conversation_turns(session_id, created_at DESC)",
]


class PostgresMemory:
    def __init__(self, database_url: str | None = None) -> None:
        self.database_url = database_url if database_url is not None else os.getenv("DATABASE_URL")
        self.engine: AsyncEngine | None = None

    @property
    def enabled(self) -> bool:
        return bool(self.database_url and self.engine is not None)

    async def start(self) -> None:
        if not self.database_url:
            return
        try:
            self.engine = create_async_engine(self.database_url, pool_pre_ping=True)
            async with self.engine.begin() as conn:
                for statement in DDL_STATEMENTS:
                    await conn.execute(text(statement))
        except Exception:
            LOGGER.warning("Postgres memory initialization failed; continuing without DB persistence", exc_info=True)
            await self.stop()

    async def stop(self) -> None:
        if self.engine is not None:
            await self.engine.dispose()
        self.engine = None

    async def record_prediction_event(
        self,
        session_id: str,
        event: dict[str, Any],
        alert: dict[str, Any],
    ) -> None:
        if not self.enabled:
            return
        try:
            await self.ensure_session(session_id, source="sensor")
            async with self.engine.begin() as conn:  # type: ignore[union-attr]
                result = await conn.execute(
                    text(
                        """
                        INSERT INTO prediction_events
                            (session_id, sequence, class, confidence, timestamp_ms, event_time, payload)
                        VALUES
                            (:session_id, :sequence, :class, :confidence, :timestamp_ms,
                             TO_TIMESTAMP(:timestamp_seconds), CAST(:payload AS JSONB))
                        RETURNING id
                        """
                    ),
                    {
                        "session_id": session_id,
                        "sequence": _optional_int(event.get("sequence")),
                        "class": int(event["class"]),
                        "confidence": event.get("confidence"),
                        "timestamp_ms": _optional_int(event.get("timestampMs")),
                        "timestamp_seconds": (float(event.get("timestampMs") or 0) / 1000.0),
                        "payload": _json(event),
                    },
                )
                prediction_event_id = result.scalar_one()
                await conn.execute(
                    text(
                        """
                        INSERT INTO alert_decisions
                            (prediction_event_id, session_id, alert_level, alert_action,
                             window_mean, trigger_reason, alert_required, payload)
                        VALUES
                            (:prediction_event_id, :session_id, :alert_level, :alert_action,
                             :window_mean, :trigger_reason, :alert_required, CAST(:payload AS JSONB))
                        """
                    ),
                    {
                        "prediction_event_id": prediction_event_id,
                        "session_id": session_id,
                        "alert_level": str(alert.get("alertLevel", "none")),
                        "alert_action": str(alert.get("alertAction", "none")),
                        "window_mean": alert.get("windowMean"),
                        "trigger_reason": alert.get("triggerReason"),
                        "alert_required": bool(alert.get("alertRequired", False)),
                        "payload": _json(alert),
                    },
                )
        except Exception:
            LOGGER.warning("Failed to persist prediction/alert memory", exc_info=True)

    async def record_conversation_turn(
        self,
        session_id: str,
        role: str,
        content: str,
        alert_context: dict[str, Any] | None = None,
        llm_payload: dict[str, Any] | None = None,
    ) -> None:
        if not self.enabled:
            return
        try:
            await self.ensure_session(session_id, source="intervention")
            async with self.engine.begin() as conn:  # type: ignore[union-attr]
                await conn.execute(
                    text(
                        """
                        INSERT INTO conversation_turns
                            (session_id, role, content, alert_context, llm_payload)
                        VALUES
                            (:session_id, :role, :content,
                             CAST(:alert_context AS JSONB), CAST(:llm_payload AS JSONB))
                        """
                    ),
                    {
                        "session_id": session_id,
                        "role": role,
                        "content": content,
                        "alert_context": _json(alert_context or {}),
                        "llm_payload": _json(llm_payload or {}),
                    },
                )
        except Exception:
            LOGGER.warning("Failed to persist conversation turn", exc_info=True)

    async def upsert_craving_slots(
        self,
        session_id: str,
        slots: dict[str, Any],
        missing_slots: list[Any] | None = None,
    ) -> None:
        if not self.enabled:
            return
        try:
            await self.ensure_session(session_id, source="intervention")
            async with self.engine.begin() as conn:  # type: ignore[union-attr]
                await conn.execute(
                    text(
                        """
                        INSERT INTO craving_slots (session_id, slots, missing_slots, updated_at)
                        VALUES (:session_id, CAST(:slots AS JSONB), CAST(:missing_slots AS JSONB), NOW())
                        ON CONFLICT (session_id) DO UPDATE SET
                            slots = EXCLUDED.slots,
                            missing_slots = EXCLUDED.missing_slots,
                            updated_at = NOW()
                        """
                    ),
                    {
                        "session_id": session_id,
                        "slots": _json(slots),
                        "missing_slots": _json(missing_slots or []),
                    },
                )
        except Exception:
            LOGGER.warning("Failed to persist craving slots", exc_info=True)

    async def record_handoff_report(
        self,
        session_id: str,
        report_markdown: str,
        missing_slots: list[Any] | None = None,
        source_payload: dict[str, Any] | None = None,
    ) -> None:
        if not self.enabled:
            return
        try:
            await self.ensure_session(session_id, source="intervention")
            async with self.engine.begin() as conn:  # type: ignore[union-attr]
                await conn.execute(
                    text(
                        """
                        INSERT INTO handoff_reports
                            (session_id, report_markdown, missing_slots, source_payload)
                        VALUES
                            (:session_id, :report_markdown,
                             CAST(:missing_slots AS JSONB), CAST(:source_payload AS JSONB))
                        """
                    ),
                    {
                        "session_id": session_id,
                        "report_markdown": report_markdown,
                        "missing_slots": _json(missing_slots or []),
                        "source_payload": _json(source_payload or {}),
                    },
                )
        except Exception:
            LOGGER.warning("Failed to persist handoff report", exc_info=True)

    async def ensure_session(
        self,
        session_id: str,
        source: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        if not self.enabled:
            return
        async with self.engine.begin() as conn:  # type: ignore[union-attr]
            await conn.execute(
                text(
                    """
                    INSERT INTO sessions (session_id, source, metadata)
                    VALUES (:session_id, :source, CAST(:metadata AS JSONB))
                    ON CONFLICT (session_id) DO UPDATE SET
                        last_seen_at = NOW(),
                        source = COALESCE(EXCLUDED.source, sessions.source)
                    """
                ),
                {
                    "session_id": session_id,
                    "source": source,
                    "metadata": _json(metadata or {}),
                },
            )

    async def conversation_history(self, session_id: str, limit: int = 40) -> list[dict[str, Any]]:
        if not self.enabled:
            return []
        try:
            async with self.engine.connect() as conn:  # type: ignore[union-attr]
                result = await conn.execute(
                    text(
                        """
                        SELECT role, content, created_at
                        FROM conversation_turns
                        WHERE session_id = :session_id
                        ORDER BY created_at DESC
                        LIMIT :limit
                        """
                    ),
                    {"session_id": session_id, "limit": limit},
                )
                rows = list(reversed(result.mappings().all()))
            return [
                {
                    "role": row["role"],
                    "content": row["content"],
                    "createdAt": row["created_at"].isoformat(),
                }
                for row in rows
            ]
        except Exception:
            LOGGER.warning("Failed to load conversation history", exc_info=True)
            return []

    async def current_slots(self, session_id: str) -> dict[str, Any]:
        if not self.enabled:
            return {}
        try:
            async with self.engine.connect() as conn:  # type: ignore[union-attr]
                result = await conn.execute(
                    text("SELECT slots FROM craving_slots WHERE session_id = :session_id"),
                    {"session_id": session_id},
                )
                value = result.scalar_one_or_none()
            return value or {}
        except Exception:
            LOGGER.warning("Failed to load craving slots", exc_info=True)
            return {}

    async def handoff_context(self, session_id: str) -> dict[str, Any]:
        if not self.enabled:
            return {"alertEvents": [], "predictionSummary": {}}
        try:
            async with self.engine.connect() as conn:  # type: ignore[union-attr]
                alerts = await conn.execute(
                    text(
                        """
                        SELECT alert_level, alert_action, window_mean, trigger_reason,
                               alert_required, created_at
                        FROM alert_decisions
                        WHERE session_id = :session_id
                        ORDER BY created_at DESC
                        LIMIT 20
                        """
                    ),
                    {"session_id": session_id},
                )
                summary = await conn.execute(
                    text(
                        """
                        SELECT COUNT(*) AS count, AVG(class) AS mean_class,
                               MAX(timestamp_ms) AS latest_timestamp_ms
                        FROM prediction_events
                        WHERE session_id = :session_id
                        """
                    ),
                    {"session_id": session_id},
                )
                summary_row = summary.mappings().one()
            alert_events = [
                {
                    "alertLevel": row["alert_level"],
                    "alertAction": row["alert_action"],
                    "windowMean": row["window_mean"],
                    "triggerReason": row["trigger_reason"],
                    "alertRequired": row["alert_required"],
                    "createdAt": row["created_at"].isoformat(),
                }
                for row in reversed(alerts.mappings().all())
            ]
            return {
                "alertEvents": alert_events,
                "predictionSummary": {
                    "count": int(summary_row["count"] or 0),
                    "meanClass": float(summary_row["mean_class"] or 0.0),
                    "latestTimestampMs": summary_row["latest_timestamp_ms"],
                },
            }
        except Exception:
            LOGGER.warning("Failed to load handoff context", exc_info=True)
            return {"alertEvents": [], "predictionSummary": {}}


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)


def _optional_int(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None
