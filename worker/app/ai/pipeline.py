"""분석 요청 하나를 처리한다: LLM 호출 → 출력 스키마·값 검증 (사양서 7.2, 9.7).

- 출력이 스키마에 맞지 않거나(출력 길이 초과 포함), 근거 메시지가 분석 구간 밖이면 버린다 (FAILED INVALID_OUTPUT, 재시도 안 함)
- 모델이 안전 정책으로 답하지 않으면 FAILED LLM_REFUSED (재시도 안 함)
- LLM 호출 자체가 실패하면 예외를 그대로 올린다 (소비자가 재시도·DLQ)
"""

import json
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
        "cache_read_tokens": response.cache_read_tokens,
        "cache_write_tokens": response.cache_write_tokens,
        "latency_ms": latency_ms,
    }
    if response.refused:
        return ExtractResult(job_id=job.job_id, status="FAILED", error_code="LLM_REFUSED", **usage)
    try:
        output = _validate(job, response.text)
    except InvalidOutput:
        return ExtractResult(job_id=job.job_id, status="FAILED", error_code="INVALID_OUTPUT", **usage)
    return ExtractResult(job_id=job.job_id, status="DONE", candidates=output.candidates, **usage)


def _validate(job: ExtractJob, text: str) -> LlmOutput:
    try:
        output = LlmOutput.model_validate(_drop_empty_fields(json.loads(text)))
    except (ValueError, ValidationError) as e:
        raise InvalidOutput from e
    allowed = {message.id for message in job.payload.messages}
    for candidate in output.candidates:
        if any(evidence.message_id not in allowed for evidence in candidate.evidence):
            raise InvalidOutput
    return output


def _drop_empty_fields(data):
    """구조화 출력은 모든 유형의 필드를 null·빈 목록으로 채워 보낸다 (llm.OUTPUT_SCHEMA). 값이 있는 필드만 남긴다"""
    if isinstance(data, dict) and isinstance(data.get("candidates"), list):
        for candidate in data["candidates"]:
            fields = candidate.get("fields") if isinstance(candidate, dict) else None
            if isinstance(fields, dict):
                candidate["fields"] = {k: v for k, v in fields.items() if v is not None and v != []}
    return data
