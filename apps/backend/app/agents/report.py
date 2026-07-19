from __future__ import annotations

import json
from typing import Any

from app.agents.bedrock import BedrockClaudeAdapter, parse_json_object
from app.agents.intervention import validate_generated_text
from app.prompts.report import REPORT_PROMPT_VERSION, REPORT_SYSTEM_PROMPT


class ReportAgent:
    def __init__(self, adapter: BedrockClaudeAdapter | None = None) -> None:
        self.adapter = adapter or BedrockClaudeAdapter()
        self.model_name = self.adapter.model_id
        self.model_version = self.adapter.model_id
        self.prompt_version = REPORT_PROMPT_VERSION

    async def report(
        self, *, evidence: dict[str, Any], history: list[dict[str, str]], partial: bool
    ) -> dict[str, Any]:
        text = await self.adapter.complete(
            system=REPORT_SYSTEM_PROMPT,
            messages=[{"role": "user", "content": json.dumps({
                "partial": partial, "evidence": evidence, "history": history,
            }, ensure_ascii=False)}],
            max_tokens=1200,
            temperature=0.1,
        )
        parsed = parse_json_object(text)
        result = parsed or {"summary": text.strip(), "partial": partial}
        if not validate_generated_text(json.dumps(result, ensure_ascii=False)):
            raise ValueError("report_output_rejected")
        return result
