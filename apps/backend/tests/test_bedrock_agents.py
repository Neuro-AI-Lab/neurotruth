from io import BytesIO
import json
from urllib import error as urllib_error

import pytest

from app.agents import bedrock as bedrock_agents
from app.agents.bedrock import (
    BedrockClaudeAdapter,
    parse_json_object,
)


def test_parse_json_object_accepts_wrapped_json() -> None:
    parsed = parse_json_object('Here is the JSON: {"trigger":"stress"}')

    assert parsed == {"trigger": "stress"}


def test_bedrock_adapter_parses_mocked_converse_response(monkeypatch) -> None:
    class FakeClient:
        def converse(self, **kwargs):
            assert kwargs["modelId"] == "test-model"
            return {
                "output": {
                    "message": {"content": [{"text": "hello "}, {"text": "world"}]}
                }
            }

    monkeypatch.delenv("AWS_BEARER_TOKEN_BEDROCK", raising=False)
    adapter = BedrockClaudeAdapter(model_id="test-model")
    adapter._client = FakeClient()

    result = adapter._complete_sync(
        system="system",
        messages=[{"role": "user", "content": "message"}],
        max_tokens=10,
        temperature=0.0,
    )

    assert result == "hello world"


class FakeUrlResponse:
    def __init__(self, payload: bytes) -> None:
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        return None

    def read(self) -> bytes:
        return self.payload


def test_openai_model_uses_mantle_responses_request(monkeypatch) -> None:
    captured = {}

    def fake_urlopen(request, *, timeout):
        captured["request"] = request
        captured["timeout"] = timeout
        return FakeUrlResponse(
            json.dumps(
                {
                    "output": [
                        {
                            "type": "message",
                            "content": [
                                {"type": "output_text", "text": "첫째 "},
                                {"type": "output_text", "text": "둘째"},
                            ],
                        }
                    ]
                }
            ).encode("utf-8")
        )

    monkeypatch.setenv("AWS_BEARER_TOKEN_BEDROCK", "secret-test-token")
    monkeypatch.setenv("AWS_REGION", "us-east-1")
    monkeypatch.setenv("BEDROCK_TIMEOUT_SECONDS", "23")
    monkeypatch.setattr(bedrock_agents.urllib_request, "urlopen", fake_urlopen)
    adapter = BedrockClaudeAdapter(model_id="openai.gpt-5.5")

    result = adapter._complete_sync(
        system="system instruction",
        messages=[
            {"role": "user", "content": "first"},
            {"role": "assistant", "content": "second"},
        ],
        max_tokens=123,
        temperature=0.7,
    )

    request = captured["request"]
    body = json.loads(request.data.decode("utf-8"))
    assert request.full_url == (
        "https://bedrock-mantle.us-east-1.api.aws/openai/v1/responses"
    )
    assert request.get_method() == "POST"
    assert request.headers["Authorization"] == "Bearer secret-test-token"
    assert request.headers["Content-type"] == "application/json"
    assert captured["timeout"] == 23.0
    assert body == {
        "model": "openai.gpt-5.5",
        "instructions": "system instruction",
        "input": [
            {"role": "user", "content": "first"},
            {"role": "assistant", "content": "second"},
        ],
        "max_output_tokens": 123,
    }
    assert "temperature" not in body
    assert "secret-test-token" not in request.data.decode("utf-8")
    assert result == "첫째 둘째"


def test_openai_model_requires_bearer_token(monkeypatch) -> None:
    monkeypatch.delenv("AWS_BEARER_TOKEN_BEDROCK", raising=False)
    adapter = BedrockClaudeAdapter(model_id="openai.gpt-5.5")

    with pytest.raises(RuntimeError, match="AWS_BEARER_TOKEN_BEDROCK is required"):
        adapter._complete_sync(
            system="system",
            messages=[],
            max_tokens=10,
            temperature=0.0,
        )


def test_mantle_http_error_is_sanitized(monkeypatch) -> None:
    def failing_urlopen(request, *, timeout):
        raise urllib_error.HTTPError(
            request.full_url,
            403,
            "forbidden",
            hdrs=None,
            fp=BytesIO(b"provider body containing secret-test-token"),
        )

    monkeypatch.setenv("AWS_BEARER_TOKEN_BEDROCK", "secret-test-token")
    monkeypatch.setattr(bedrock_agents.urllib_request, "urlopen", failing_urlopen)
    adapter = BedrockClaudeAdapter(model_id="openai.gpt-5.5")

    with pytest.raises(RuntimeError, match="HTTP 403") as exc_info:
        adapter._complete_sync(
            system="system",
            messages=[],
            max_tokens=10,
            temperature=0.0,
        )

    assert "secret-test-token" not in str(exc_info.value)
    assert "provider body" not in str(exc_info.value)


@pytest.mark.parametrize(
    ("provider_payload", "error_message"),
    [
        (b"not-json", "malformed JSON"),
        (json.dumps({"output": {}}).encode("utf-8"), "malformed output"),
        (
            json.dumps(
                {"output": [{"content": [{"type": "output_text", "text": ""}]}]}
            ).encode("utf-8"),
            "empty text output",
        ),
    ],
)
def test_mantle_rejects_malformed_or_empty_output(
    monkeypatch, provider_payload, error_message
) -> None:
    monkeypatch.setenv("AWS_BEARER_TOKEN_BEDROCK", "secret-test-token")
    monkeypatch.setattr(
        bedrock_agents.urllib_request,
        "urlopen",
        lambda request, *, timeout: FakeUrlResponse(provider_payload),
    )
    adapter = BedrockClaudeAdapter(model_id="openai.gpt-5.5")

    with pytest.raises(RuntimeError, match=error_message):
        adapter._complete_sync(
            system="system",
            messages=[],
            max_tokens=10,
            temperature=0.0,
        )
