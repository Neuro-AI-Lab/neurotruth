from __future__ import annotations

import json
from typing import Any

from app.agents.bedrock import BedrockClaudeAdapter
from app.agents.intervention import validate_generated_text
from app.prompts.state_summary import STATE_PROMPT_VERSION, STATE_SUMMARY_SYSTEM_PROMPT


class StateSummaryAgent:
    def __init__(self, adapter: BedrockClaudeAdapter | None = None) -> None:
        self.adapter = adapter or BedrockClaudeAdapter()
        self.model_name = self.adapter.model_id
        self.model_version = self.adapter.model_id
        self.prompt_version = STATE_PROMPT_VERSION

    async def summarize_state(self, evidence: dict[str, Any]) -> str:
        text = await self.adapter.complete(
            system=STATE_SUMMARY_SYSTEM_PROMPT,
            messages=[{"role": "user", "content": json.dumps(evidence, ensure_ascii=False)}],
            max_tokens=450,
            temperature=0.1,
        )
        text = text.strip()
        if not validate_generated_text(text):
            raise ValueError("state_summary_rejected")
        return text
