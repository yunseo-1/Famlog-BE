"""LLM 제공자 경계 (사양서 7.2 "교체 가능": LlmClient 인터페이스 뒤에 제공자를 숨긴다).

- fake: 후보 없음으로 답한다 (테스트·CI, 외부 호출 없음)
- anthropic: Claude API. 지시문은 버전 파일(prompts/extract_v2.md)이고, 바뀌지 않는 시스템 지시문에 캐시 표시를 단다.
  대화는 사용자 메시지의 데이터 블록(<members>·<context>·<messages>)에만 넣는다 (사양서 9.7)

프롬프트·응답 원문은 로그·저장 금지 (사양서 8.7). 모델·프롬프트 버전과 토큰 수만 돌려준다.
"""

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from ..config import Settings
from .schemas import ExtractPayload

PROMPT_VERSION = "extract_v2"
PROMPT_PATH = Path(__file__).resolve().parents[2] / "prompts" / f"{PROMPT_VERSION}.md"


@dataclass(frozen=True)
class LlmResponse:
    text: str  # 모델이 낸 JSON 문자열 (검증 전)
    model_version: str
    prompt_version: str
    input_tokens: int  # 캐시를 거치지 않은 입력
    output_tokens: int
    cache_read_tokens: int = 0
    cache_write_tokens: int = 0
    refused: bool = False  # 안전 정책으로 답하지 않았다 (재시도하지 않는다)


class LlmClient(Protocol):
    def extract(self, payload: ExtractPayload) -> LlmResponse: ...


class FakeLlmClient:
    """정해 둔 답을 낸다. 파이프라인이 끝까지 흐르는지 볼 때 쓴다 (외부 호출 없음)"""

    def __init__(self, text: str = '{"candidates": []}'):
        self.text = text

    def extract(self, payload: ExtractPayload) -> LlmResponse:
        return LlmResponse(self.text, model_version="fake", prompt_version="none", input_tokens=0, output_tokens=0)


def _text_props(*names: str) -> dict:
    return {name: {"type": "string"} for name in names}


def _list_props(*names: str) -> dict:
    return {name: {"type": "array", "items": {"type": "string"}} for name in names}


_FIELDS = {
    "SCHEDULE": {**_text_props("title", "dateText", "startTimeText", "endTimeText", "locationText"),
                 **_list_props("attendeeTexts")},
    "TODO": {**_text_props("title", "dueText"), **_list_props("assigneeTexts")},
    "ANNIVERSARY": _text_props("title", "dateText", "personText"),
    "PLACE": _text_props("name", "regionHint", "opinion"),
    "SCHEDULE_CHANGE": _text_props("title", "fromDateText", "fromTimeText", "dateText", "startTimeText"),
    "TODO_DONE": _text_props("title"),
}

# 구조화 출력용 JSON 스키마. 유형별 anyOf 는 6개가 되자 API 한도를 넘어서(문법 크기·선택 필드·union 개수),
# fields 는 모든 유형의 필드를 가진 객체 하나로 두고 모두 필수(글자는 null 허용)로 한다.
# 다른 유형의 필드는 null·빈 목록으로 오고, pipeline 이 지운 뒤 schemas.LlmOutput(pydantic)이 유형별 필드·길이·개수를 검사한다
_ALL_FIELDS = {name: ({"type": ["string", "null"]} if prop["type"] == "string" else prop)
               for props in _FIELDS.values() for name, prop in props.items()}

OUTPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "candidates": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "type": {"type": "string", "enum": list(_FIELDS)},
                    "fields": {"type": "object", "properties": _ALL_FIELDS, "required": list(_ALL_FIELDS),
                               "additionalProperties": False},
                    "confidence": {"type": "number"},
                    "evidence": {"type": "array", "items": {
                        "type": "object",
                        "properties": {"messageId": {"type": "integer"},
                                       "spans": {"type": "array", "items": {"type": "string"}}},
                        "required": ["messageId", "spans"],
                        "additionalProperties": False,
                    }},
                },
                "required": ["type", "fields", "confidence", "evidence"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["candidates"],
    "additionalProperties": False,
}


def user_message(payload: ExtractPayload) -> str:
    """대화는 데이터 블록에만 넣는다 (사양서 9.7)"""

    def block(name: str, value) -> str:
        return f"<{name}>\n{json.dumps(value, ensure_ascii=False)}\n</{name}>"

    data = payload.model_dump(by_alias=True)
    return "\n".join([
        f"기준 시간대: {payload.timezone}",
        block("members", data["members"]),
        block("context", data["context"]),
        block("messages", data["messages"]),
    ])


class AnthropicLlmClient:
    """Claude API. 생각(thinking)은 끄고, 시스템 지시문은 5분 캐시, 출력은 OUTPUT_SCHEMA 로 강제한다"""

    def __init__(self, settings: Settings, client=None, prompt: str | None = None):
        if client is None:
            if not settings.anthropic_api_key:
                raise ValueError("LLM_PROVIDER=anthropic 에는 ANTHROPIC_API_KEY 가 필요합니다")
            import anthropic

            client = anthropic.Anthropic(api_key=settings.anthropic_api_key,
                                         timeout=settings.llm_timeout_seconds, max_retries=2)
        self.client = client
        self.model = settings.llm_model
        self.max_tokens = settings.llm_max_tokens
        self.prompt = prompt if prompt is not None else PROMPT_PATH.read_text(encoding="utf-8")

    def extract(self, payload: ExtractPayload) -> LlmResponse:
        response = self.client.messages.create(
            model=self.model,
            max_tokens=self.max_tokens,
            system=[{"type": "text", "text": self.prompt, "cache_control": {"type": "ephemeral"}}],
            messages=[{"role": "user", "content": user_message(payload)}],
            thinking={"type": "disabled"},
            output_config={"format": {"type": "json_schema", "schema": OUTPUT_SCHEMA}},
        )
        usage = response.usage
        text = "".join(block.text for block in response.content if block.type == "text")
        return LlmResponse(
            text=text,
            model_version=response.model,
            prompt_version=PROMPT_VERSION,
            input_tokens=usage.input_tokens,
            output_tokens=usage.output_tokens,
            cache_read_tokens=usage.cache_read_input_tokens or 0,
            cache_write_tokens=usage.cache_creation_input_tokens or 0,
            refused=response.stop_reason == "refusal",
        )


def create_client(settings: Settings) -> LlmClient:
    if settings.llm_provider == "fake":
        return FakeLlmClient()
    if settings.llm_provider == "anthropic":
        return AnthropicLlmClient(settings)
    raise ValueError(f"지원하지 않는 LLM 제공자: {settings.llm_provider}")
