"""분석 요청 하나를 처리한다: LLM 호출 → 출력 스키마·값 검증 (사양서 7.2, 9.7).

- 출력이 스키마에 맞지 않거나, 근거 메시지가 분석 구간 밖이면 버린다 (FAILED INVALID_OUTPUT, 재시도 안 함)
- LLM 호출 자체가 실패하면 예외를 그대로 올린다 (소비자가 재시도·DLQ)
"""

import time

from pydantic import ValidationError

from .llm import LlmClient
from .schemas import ExtractJob, ExtractResult, LlmOutput


class InvalidOutput(Exception):
    pass


def process(job: ExtractJob, client: LlmClient) -> ExtractResult:
    started = time.monotonic()
    response = client.extract(job.payload)
    latency_ms = int((time.monotonic() - started) * 1000)
    usage = {
        "model_version": response.model_version,
        "prompt_version": response.prompt_version,
        "input_tokens": response.input_tokens,
        "output_tokens": response.output_tokens,
        "latency_ms": latency_ms,
    }
    try:
        output = _validate(job, response.text)
    except InvalidOutput:
        return ExtractResult(job_id=job.job_id, status="FAILED", error_code="INVALID_OUTPUT", **usage)
    return ExtractResult(job_id=job.job_id, status="DONE", candidates=output.candidates, **usage)


def _validate(job: ExtractJob, text: str) -> LlmOutput:
    try:
        output = LlmOutput.model_validate_json(text)
    except ValidationError as e:
        raise InvalidOutput from e
    allowed = {message.id for message in job.payload.messages}
    for candidate in output.candidates:
        if any(evidence.message_id not in allowed for evidence in candidate.evidence):
            raise InvalidOutput
    return output
