from __future__ import annotations

import asyncio
import json
import os
from typing import Any
from urllib import error as urllib_error
from urllib import request as urllib_request
from urllib.parse import quote

import boto3


DEFAULT_MODEL_ID = "openai.gpt-5.5"


def parse_json_object(text: str) -> dict[str, Any] | None:
    try:
        parsed = json.loads(text)
        return parsed if isinstance(parsed, dict) else None
    except json.JSONDecodeError:
        pass

    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end <= start:
        return None

    try:
        parsed = json.loads(text[start : end + 1])
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


class BedrockClaudeAdapter:
    def __init__(self, model_id: str | None = None) -> None:
        self.model_id = model_id or os.getenv("BEDROCK_MODEL_ID", DEFAULT_MODEL_ID)
        self.region_name = os.getenv("AWS_REGION") or os.getenv("AWS_DEFAULT_REGION") or None
        self._session = boto3.Session(region_name=self.region_name)
        self._client = None

    @property
    def client(self):
        if self._client is None:
            self._client = self._session.client("bedrock-runtime")
        return self._client

    async def complete(
        self,
        *,
        system: str,
        messages: list[dict[str, str]],
        max_tokens: int = 700,
        temperature: float = 0.4,
    ) -> str:
        return await asyncio.to_thread(
            self._complete_sync,
            system=system,
            messages=messages,
            max_tokens=max_tokens,
            temperature=temperature,
        )

    def _complete_sync(
        self,
        *,
        system: str,
        messages: list[dict[str, str]],
        max_tokens: int,
        temperature: float,
    ) -> str:
        if self.model_id.startswith("openai."):
            response = self._complete_with_mantle(
                system=system,
                messages=messages,
                max_tokens=max_tokens,
            )
            return self._extract_mantle_response_text(response)

        payload = {
            "system": [{"text": system}],
            "messages": [
                {
                    "role": message["role"],
                    "content": [{"text": message["content"]}],
                }
                for message in messages
            ],
            "inferenceConfig": {
                "maxTokens": max_tokens,
                "temperature": temperature,
            },
        }
        if os.getenv("AWS_BEARER_TOKEN_BEDROCK"):
            response = self._complete_with_bearer_token(payload)
        else:
            response = self.client.converse(modelId=self.model_id, **payload)
        return self._extract_response_text(response)

    def _complete_with_mantle(
        self,
        *,
        system: str,
        messages: list[dict[str, str]],
        max_tokens: int,
    ) -> dict[str, Any]:
        token = os.getenv("AWS_BEARER_TOKEN_BEDROCK", "").strip()
        if not token:
            raise RuntimeError(
                "AWS_BEARER_TOKEN_BEDROCK is required for Bedrock OpenAI models"
            )

        region_name = self.region_name or "us-east-1"
        url = f"https://bedrock-mantle.{region_name}.api.aws/openai/v1/responses"
        payload = {
            "model": self.model_id,
            "instructions": system,
            "input": [
                {"role": message["role"], "content": message["content"]}
                for message in messages
            ],
            "max_output_tokens": max_tokens,
        }
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(
            "utf-8"
        )
        timeout_seconds = float(os.getenv("BEDROCK_TIMEOUT_SECONDS", "60"))
        request = urllib_request.Request(
            url,
            data=body,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib_request.urlopen(request, timeout=timeout_seconds) as response:
                response_body = response.read()
        except urllib_error.HTTPError as exc:
            raise RuntimeError(
                f"Bedrock Mantle request failed with HTTP {exc.code}"
            ) from exc
        except urllib_error.URLError as exc:
            raise RuntimeError("Bedrock Mantle request failed") from exc

        try:
            parsed = json.loads(response_body.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise RuntimeError("Bedrock Mantle returned malformed JSON") from exc
        if not isinstance(parsed, dict):
            raise RuntimeError("Bedrock Mantle returned malformed JSON")
        return parsed

    def _complete_with_bearer_token(self, payload: dict[str, Any]) -> dict[str, Any]:
        token = os.getenv("AWS_BEARER_TOKEN_BEDROCK", "").strip()
        region_name = self.region_name or "us-east-1"
        model_id = quote(self.model_id, safe="")
        url = f"https://bedrock-runtime.{region_name}.amazonaws.com/model/{model_id}/converse"
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(
            "utf-8"
        )
        timeout_seconds = float(os.getenv("BEDROCK_TIMEOUT_SECONDS", "60"))
        request = urllib_request.Request(
            url,
            data=body,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib_request.urlopen(request, timeout=timeout_seconds) as response:
                response_body = response.read().decode("utf-8")
        except urllib_error.HTTPError as exc:
            error_body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(
                f"Bedrock bearer request failed with HTTP {exc.code}: {error_body[:300]}"
            ) from exc
        return json.loads(response_body)

    @staticmethod
    def _extract_response_text(response: dict[str, Any]) -> str:
        output = response.get("output") or {}
        message = output.get("message") or {}
        parts = message.get("content") or []
        text_parts = [
            part.get("text", "")
            for part in parts
            if isinstance(part, dict) and isinstance(part.get("text"), str)
        ]
        return "".join(text_parts).strip()

    @staticmethod
    def _extract_mantle_response_text(response: dict[str, Any]) -> str:
        output = response.get("output")
        if not isinstance(output, list):
            raise RuntimeError("Bedrock Mantle returned malformed output")

        text_parts: list[str] = []
        for item in output:
            if not isinstance(item, dict):
                continue
            content = item.get("content")
            if not isinstance(content, list):
                continue
            for part in content:
                if not isinstance(part, dict):
                    continue
                text = part.get("text")
                if isinstance(text, str):
                    text_parts.append(text)

        text = "".join(text_parts).strip()
        if not text:
            raise RuntimeError("Bedrock Mantle returned empty text output")
        return text
