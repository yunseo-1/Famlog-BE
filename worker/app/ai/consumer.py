"""AI 분석 요청 스트림 소비 (사양서 7.2 장애 격리: 재시도 3회 후 DLQ).

미디어 소비자(app/consumer.py)와 같은 규칙에 더해:
- 처리한 요청은 ACK 한 뒤 스트림에서 지운다 (대화가 Redis 에 남지 않게, 사양서 8.7)
- DLQ 에는 jobId·attempt·오류 종류만 넣는다 (대화 본문 없음)
로그에는 jobId·상태·예외 종류만 남긴다 (본문·프롬프트·응답 없음).
"""

import logging

from pydantic import ValidationError

from ..config import Settings
from ..reclaim import reclaim_pending
from .llm import LlmClient
from .pipeline import process
from .schemas import ExtractJob, ExtractResult

log = logging.getLogger(__name__)


class AiConsumer:
    def __init__(self, redis, settings: Settings, client: LlmClient):
        self.redis = redis
        self.settings = settings
        self.client = client

    def ensure_group(self) -> None:
        try:
            self.redis.xgroup_create(self.settings.ai_jobs_stream, self.settings.ai_group, id="0", mkstream=True)
        except Exception as e:  # 이미 있으면 BUSYGROUP
            if "BUSYGROUP" not in str(e):
                raise

    def run_once(self, block_ms: int = 2000) -> int:
        response = self.redis.xreadgroup(
            self.settings.ai_group,
            self.settings.consumer,
            {self.settings.ai_jobs_stream: ">"},
            count=10,
            block=block_ms,
        )
        handled = 0
        for _stream, messages in response or []:
            for message_id, fields in messages:
                self.handle(message_id, _decode(fields))
                handled += 1
        return handled

    def reclaim_once(self) -> int:
        """처리 중 죽은 워커가 남긴 요청을 다시 처리한다. 계속 실패하면 DLQ 로 보내고 FAILED 를 알린다"""
        return reclaim_pending(self.redis, self.settings.ai_jobs_stream, self.settings.ai_group,
                               self.settings.consumer, self.settings.reclaim_idle_ms, self.settings.max_attempts,
                               self.handle, self._give_up)

    def _give_up(self, message_id, fields: dict[str, str]) -> None:
        try:
            self.redis.xadd(self.settings.ai_dlq_stream, _dlq_fields(fields, "STUCK"))
            job_id = _safe_job_id(fields)
            if job_id:
                failed = ExtractResult(job_id=int(job_id), status="FAILED", error_code="STUCK")
                self.redis.xadd(self.settings.ai_results_stream, failed.to_fields())
        finally:
            self.redis.xack(self.settings.ai_jobs_stream, self.settings.ai_group, message_id)
            self.redis.xdel(self.settings.ai_jobs_stream, message_id)

    def handle(self, message_id, fields: dict[str, str]) -> None:
        try:
            try:
                job = ExtractJob.model_validate(fields)
            except (ValidationError, ValueError):
                log.warning("형식이 잘못된 분석 요청을 DLQ로 보냅니다: jobId=%s", _safe_job_id(fields))
                self.redis.xadd(self.settings.ai_dlq_stream, _dlq_fields(fields, "INVALID_JOB"))
                return
            try:
                result = process(job, self.client)
            except Exception as e:  # LLM·네트워크 등 일시 오류
                self._retry_or_give_up(job, fields, e)
                return
            log.info("분석 완료: jobId=%s status=%s candidates=%s", job.job_id, result.status, len(result.candidates))
            self.redis.xadd(self.settings.ai_results_stream, result.to_fields())
        finally:
            self.redis.xack(self.settings.ai_jobs_stream, self.settings.ai_group, message_id)
            self.redis.xdel(self.settings.ai_jobs_stream, message_id)

    def _retry_or_give_up(self, job: ExtractJob, fields: dict[str, str], error: Exception) -> None:
        if job.attempt < self.settings.max_attempts:
            log.warning("분석 실패, 다시 시도합니다: jobId=%s attempt=%s error=%s",
                        job.job_id, job.attempt, type(error).__name__)
            self.redis.xadd(self.settings.ai_jobs_stream, job.next_attempt_fields(fields["payload"]))
            return
        log.error("분석을 %s번 실패해 DLQ로 보냅니다: jobId=%s error=%s", job.attempt, job.job_id, type(error).__name__)
        self.redis.xadd(self.settings.ai_dlq_stream, _dlq_fields(fields, type(error).__name__))
        failed = ExtractResult(job_id=job.job_id, status="FAILED", error_code="LLM_FAILED")
        self.redis.xadd(self.settings.ai_results_stream, failed.to_fields())


def _dlq_fields(fields: dict[str, str], error: str) -> dict[str, str]:
    return {"jobId": _safe_job_id(fields), "attempt": str(fields.get("attempt", "")), "error": error}


def _safe_job_id(fields: dict[str, str]) -> str:
    value = str(fields.get("jobId", ""))
    return value if value.isdigit() else ""


def _decode(fields: dict) -> dict[str, str]:
    return {
        (k.decode() if isinstance(k, bytes) else k): (v.decode() if isinstance(v, bytes) else v)
        for k, v in fields.items()
    }
