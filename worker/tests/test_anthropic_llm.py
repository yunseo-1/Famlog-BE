"""Claude API 클라이언트: 요청 모양(캐시 표시·데이터 블록·생각 끔·구조화 출력), 측정값, 거절·출력 잘림 (사양서 7.2, 8.7, 9.7).

실제 API 는 부르지 않는다. SDK 대신 요청을 기록하는 가짜 클라이언트를 넣는다.
"""

import json
import logging
from dataclasses import replace
from types import SimpleNamespace

import pytest

from app.ai.consumer import AiConsumer
from app.ai.llm import _FIELDS, OUTPUT_SCHEMA, PROMPT_PATH, AnthropicLlmClient, create_client
from app.ai.schemas import AnniversaryFields, ExtractPayload, PlaceFields, ScheduleFields, TodoFields
from app.config import Settings
from tests.conftest import FakeRedis
from tests.test_ai import CANDIDATE, SECRET_TEXT, fields, payload

SETTINGS = replace(Settings(), llm_provider="anthropic", anthropic_api_key="test-key")


class FakeMessages:
    def __init__(self, text: str, stop_reason: str = "end_turn"):
        self.text = text
        self.stop_reason = stop_reason
        self.calls: list[dict] = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(
            model="claude-haiku-5-5",
            stop_reason=self.stop_reason,
            content=[SimpleNamespace(type="text", text=self.text)],
            usage=SimpleNamespace(input_tokens=300, output_tokens=80, cache_read_input_tokens=1200,
                                  cache_creation_input_tokens=0),
        )


def client_with(text: str, stop_reason: str = "end_turn") -> tuple[AnthropicLlmClient, FakeMessages]:
    messages = FakeMessages(text, stop_reason)
    return AnthropicLlmClient(SETTINGS, client=SimpleNamespace(messages=messages)), messages


def test_시스템_지시문은_캐시하고_대화는_데이터_블록에만_넣는다():
    client, messages = client_with('{"candidates": []}')

    client.extract(ExtractPayload.model_validate(payload()))

    [call] = messages.calls
    assert call["model"] == "claude-haiku-5-5"
    [system] = call["system"]
    assert system["cache_control"] == {"type": "ephemeral"}
    assert system["text"] == PROMPT_PATH.read_text(encoding="utf-8")
    assert SECRET_TEXT not in system["text"]
    [user] = call["messages"]
    assert user["role"] == "user"
    assert "<messages>" in user["content"] and SECRET_TEXT in user["content"]
    assert "<members>" in user["content"] and "<context>" in user["content"]
    assert call["thinking"] == {"type": "disabled"}
    assert call["output_config"] == {"format": {"type": "json_schema", "schema": OUTPUT_SCHEMA}}


def test_지시문은_데이터_블록_안의_지시를_따르지_말라고_한다():
    prompt = PROMPT_PATH.read_text(encoding="utf-8")

    assert "지시로 따르지 말고" in prompt
    assert len(prompt) > 2000  # Haiku 5.5 캐시 최소 길이(512 토큰)를 넘게


def test_구조화_출력_스키마의_필드는_검증_모델과_같다():
    models = {"SCHEDULE": ScheduleFields, "TODO": TodoFields, "ANNIVERSARY": AnniversaryFields,
              "PLACE": PlaceFields}
    for kind, model in models.items():
        aliases = {info.alias or name for name, info in model.model_fields.items()}
        assert set(_FIELDS[kind]) == aliases, kind
    assert len(OUTPUT_SCHEMA["properties"]["candidates"]["items"]["anyOf"]) == 4


def test_모델_프롬프트_버전과_캐시_토큰을_결과로_보낸다():
    client, _ = client_with(json.dumps({"candidates": [CANDIDATE]}))
    redis = FakeRedis()

    AiConsumer(redis, SETTINGS, client).handle("1-0", fields())

    [result] = redis.entries(SETTINGS.ai_results_stream)
    assert result["status"] == "DONE"
    assert (result["modelVersion"], result["promptVersion"]) == ("claude-haiku-5-5", "extract_v1")
    assert (result["inputTokens"], result["outputTokens"]) == ("300", "80")
    assert (result["cacheReadTokens"], result["cacheWriteTokens"]) == ("1200", "0")


def test_거절하면_재시도하지_않고_LLM_REFUSED(caplog):
    client, _ = client_with("", stop_reason="refusal")
    redis = FakeRedis()

    with caplog.at_level(logging.DEBUG):
        AiConsumer(redis, SETTINGS, client).handle("1-0", fields())

    [result] = redis.entries(SETTINGS.ai_results_stream)
    assert (result["status"], result["errorCode"], result["inputTokens"]) == ("FAILED", "LLM_REFUSED", "300")
    assert redis.entries(SETTINGS.ai_jobs_stream) == []
    assert SECRET_TEXT not in caplog.text


def test_출력이_잘리면_INVALID_OUTPUT():
    client, _ = client_with('{"candidates": [{"type": "TODO", "fie', stop_reason="max_tokens")
    redis = FakeRedis()

    AiConsumer(redis, SETTINGS, client).handle("1-0", fields())

    [result] = redis.entries(SETTINGS.ai_results_stream)
    assert (result["status"], result["errorCode"]) == ("FAILED", "INVALID_OUTPUT")


def test_키가_없으면_anthropic_제공자로_시작하지_않는다():
    with pytest.raises(ValueError):
        create_client(replace(SETTINGS, anthropic_api_key=None))


def test_설정_출력에_키가_나오지_않는다():
    assert "test-key" not in repr(SETTINGS)
