"""Redis Streams 소비 (사양서 13.1, 15장: 소비·재시도·DLQ).

- 소비 그룹으로 읽으므로 워커가 여러 개여도 요청마다 하나만 처리한다.
- 실패하면 attempt 를 올려 다시 넣고, 3번째도 실패하면 DLQ 스트림에 옮기고 FAILED 결과를 보낸다.
- 형식이 틀린 요청은 재시도하지 않고 DLQ 로 보낸다.
- 처리한 요청은 성공·실패와 관계없이 ACK 한다 (재시도는 새 메시지로).
로그에는 mediaId·상태·예외 종류만 남긴다 (경로·좌표·키 없음).
"""

import logging
from collections.abc import Callable

from pydantic import ValidationError

from .config import Settings
from .reclaim import reclaim_pending
from .schemas import MediaJob, MediaResult

log = logging.getLogger(__name__)


class MediaConsumer:
    def __init__(self, redis, settings: Settings, handler: Callable[[MediaJob], MediaResult]):
        self.redis = redis
        self.settings = settings
        self.handler = handler

    def ensure_group(self) -> None:
        try:
            self.redis.xgroup_create(self.settings.jobs_stream, self.settings.group, id="0", mkstream=True)
        except Exception as e:  # 이미 있으면 BUSYGROUP
            if "BUSYGROUP" not in str(e):
                raise

    def run_once(self, block_ms: int = 5000) -> int:
        """요청을 한 번 읽어 처리하고 처리한 개수를 돌려준다"""
        response = self.redis.xreadgroup(
            self.settings.group,
            self.settings.consumer,
            {self.settings.jobs_stream: ">"},
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
        return reclaim_pending(self.redis, self.settings.jobs_stream, self.settings.group, self.settings.consumer,
                               self.settings.reclaim_idle_ms, self.settings.max_attempts, self.handle, self._give_up)

    def _give_up(self, message_id, fields: dict[str, str]) -> None:
        try:
            self.redis.xadd(self.settings.dlq_stream, {**fields, "error": "STUCK"})
            media_id = fields.get("mediaId", "")
            if media_id.isdigit():
                failed = MediaResult(media_id=int(media_id), status="FAILED", reason="PROCESSING_FAILED")
                self.redis.xadd(self.settings.results_stream, failed.to_fields())
        finally:
            self.redis.xack(self.settings.jobs_stream, self.settings.group, message_id)

    def handle(self, message_id, fields: dict[str, str]) -> None:
        try:
            try:
                job = MediaJob.model_validate(fields)
            except ValidationError:
                log.warning("형식이 잘못된 처리 요청을 DLQ로 보냅니다")
                self.redis.xadd(self.settings.dlq_stream, {**fields, "error": "INVALID_JOB"})
                return
            try:
                result = self.handler(job)
            except Exception as e:  # 저장소·네트워크 등 일시 오류
                self._retry_or_give_up(job, fields, e)
                return
            self.redis.xadd(self.settings.results_stream, result.to_fields())
        finally:
            self.redis.xack(self.settings.jobs_stream, self.settings.group, message_id)

    def _retry_or_give_up(self, job: MediaJob, fields: dict[str, str], error: Exception) -> None:
        if job.attempt < self.settings.max_attempts:
            log.warning("미디어 처리 실패, 다시 시도합니다: mediaId=%s attempt=%s error=%s",
                        job.media_id, job.attempt, type(error).__name__)
            self.redis.xadd(self.settings.jobs_stream, job.next_attempt().to_fields())
            return
        log.error("미디어 처리를 %s번 실패해 DLQ로 보냅니다: mediaId=%s error=%s",
                  job.attempt, job.media_id, type(error).__name__)
        self.redis.xadd(self.settings.dlq_stream, {**fields, "error": type(error).__name__})
        failed = MediaResult(media_id=job.media_id, status="FAILED", reason="PROCESSING_FAILED")
        self.redis.xadd(self.settings.results_stream, failed.to_fields())


def _decode(fields: dict) -> dict[str, str]:
    return {
        (k.decode() if isinstance(k, bytes) else k): (v.decode() if isinstance(v, bytes) else v)
        for k, v in fields.items()
    }
