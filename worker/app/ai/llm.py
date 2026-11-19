"""LLM 제공자 경계 (사양서 7.2 "교체 가능": LlmClient 인터페이스 뒤에 제공자를 숨긴다).

S16 은 가짜 구현만 있다. 실제 제공자·프롬프트(prompts/extract_v1.md)는 S18 에서 붙인다.
프롬프트·응답 원문은 로그·저장 금지 (사양서 8.7). 모델·프롬프트 버전과 토큰 수만 돌려준다.
"""

from dataclasses import dataclass
from typing import Protocol

from .schemas import ExtractPayload


@dataclass(frozen=True)
class LlmResponse:
    text: str  # 모델이 낸 JSON 문자열 (검증 전)
    model_version: str
    prompt_version: str
    input_tokens: int
    output_tokens: int


class LlmClient(Protocol):
    def extract(self, payload: ExtractPayload) -> LlmResponse: ...


class FakeLlmClient:
    """후보 없음으로 답한다. 파이프라인이 끝까지 흐르는지 볼 때 쓴다 (외부 호출 없음)"""

    def __init__(self, text: str = '{"candidates": []}'):
        self.text = text

    def extract(self, payload: ExtractPayload) -> LlmResponse:
        return LlmResponse(self.text, model_version="fake", prompt_version="none", input_tokens=0, output_tokens=0)


def create_client(provider: str) -> LlmClient:
    if provider == "fake":
        return FakeLlmClient()
    raise ValueError(f"지원하지 않는 LLM 제공자: {provider}")
