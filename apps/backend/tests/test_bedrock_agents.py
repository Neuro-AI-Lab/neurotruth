from io import BytesIO
import json
from urllib import error as urllib_error

import pytest

from app.ai import bedrock_agents
from app.ai.bedrock_agents import (
    CHAT_SYSTEM_PROMPT,
    QUESTION_FREE_ACKNOWLEDGEMENT,
    SLOTS_SYSTEM_PROMPT,
    BedrockClaudeAdapter,
    build_chat_messages,
    filter_craving_slots,
    is_safety_exempt_response,
    missing_slot_keys,
    normalize_question,
    parse_json_object,
    question_free_fallback,
    recent_assistant_questions,
    repeats_recent_question,
)


def test_parse_json_object_accepts_wrapped_json() -> None:
    parsed = parse_json_object('Here is the JSON: {"trigger":"stress"}')

    assert parsed == {"trigger": "stress"}


def test_filter_craving_slots_removes_unknown_keys() -> None:
    slots = filter_craving_slots(
        {"trigger": "stress", "diagnosis": "alcohol use disorder"}
    )

    assert slots == {"trigger": "stress"}


def test_missing_slot_keys_reports_empty_allowed_slots() -> None:
    missing = missing_slot_keys({"trigger": "stress", "duration": ""})

    assert "trigger" not in missing
    assert "duration" in missing
    assert "intensity" in missing


def test_chat_context_includes_accumulated_slots_and_missing_slots() -> None:
    messages = build_chat_messages(
        message="지금은 괜찮아요",
        conversation_history=[],
        slots={"trigger": "스트레스", "diagnosis": "not allowed"},
        alert_context={"alertLevel": "required"},
    )

    context = messages[-2]["content"]
    assert '"currentSlots":{"trigger":"스트레스"}' in context
    assert '"missingSlots":[' in context
    assert '"trigger"' not in context.split('"missingSlots":', 1)[1]
    assert '"duration"' in context
    assert "diagnosis" not in context


def test_prompts_treat_negative_unknown_and_refusal_as_completed() -> None:
    assert "answered it positively or negatively" in CHAT_SYSTEM_PROMPT
    assert "said they did not know" in CHAT_SYSTEM_PROMPT
    assert "declined" in CHAT_SYSTEM_PROMPT
    assert "ask at most one concise question" in CHAT_SYSTEM_PROMPT
    assert "explicit" in SLOTS_SYSTEM_PROMPT
    assert "negative, unknown, or refusal" in SLOTS_SYSTEM_PROMPT
    assert "immediately preceding single-topic question" in SLOTS_SYSTEM_PROMPT
    assert "short verbatim user quote" in SLOTS_SYSTEM_PROMPT
    assert "unchanged, unsupported, or merely weaker inferred value" in (
        SLOTS_SYSTEM_PROMPT
    )
    assert "user-role evidence explicitly" in SLOTS_SYSTEM_PROMPT
    assert "corrects or updates that same slot" in SLOTS_SYSTEM_PROMPT


def test_question_normalization_removes_acknowledgement_and_formatting() -> None:
    assert normalize_question("그렇군요, 지금 갈망 강도는 어느 정도인가요?") == (
        normalize_question("지금 갈망 강도는 어느 정도인가요？")
    )


def test_repetition_uses_only_three_most_recent_assistant_turns() -> None:
    history = [
        {"role": "assistant", "content": "처음 질문인가요?"},
        {"role": "user", "content": "답변"},
        {"role": "assistant", "content": "두 번째 질문인가요?"},
        {"role": "assistant", "content": "세 번째 질문인가요?"},
        {"role": "assistant", "content": "네 번째 질문인가요?"},
    ]

    assert recent_assistant_questions(history) == [
        "두 번째 질문인가요?",
        "세 번째 질문인가요?",
        "네 번째 질문인가요?",
    ]
    assert repeats_recent_question("처음 질문인가요?", history) is False
    assert repeats_recent_question("알겠습니다. 네 번째 질문인가요?", history) is True


def test_repetition_detects_close_paraphrase_but_not_distinct_question() -> None:
    history = [
        {"role": "assistant", "content": "지금 갈망 강도는 어느 정도인가요?"}
    ]

    assert repeats_recent_question(
        "알겠습니다. 지금 갈망의 강도는 어느 정도인가요?", history
    )
    assert not repeats_recent_question("지금 어디에 계신가요?", history)


def test_safety_question_is_exempt_from_repetition_suppression() -> None:
    history = [
        {"role": "assistant", "content": "지금 자해할 생각이 있으신가요?"}
    ]
    response = "지금 자해할 생각이 있으신가요?"

    assert is_safety_exempt_response(response)
    assert not repeats_recent_question(response, history)


def test_question_free_fallback_preserves_statement_or_uses_fixed_text() -> None:
    history = [
        {"role": "assistant", "content": "지금 갈망 강도는 어느 정도인가요?"}
    ]

    assert question_free_fallback(
        "말해 주셔서 감사합니다. 지금 갈망의 강도는 어느 정도인가요?", history
    ) == "말해 주셔서 감사합니다."
    assert question_free_fallback(
        "지금 갈망의 강도는 어느 정도인가요?", history
    ) == QUESTION_FREE_ACKNOWLEDGEMENT


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
