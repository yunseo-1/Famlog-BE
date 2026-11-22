"""스트림 소비: 결과 전송, 재시도 3회 후 DLQ, 잘못된 요청."""

from app.config import Settings
from app.consumer import MediaConsumer
from app.schemas import MediaResult
from tests.conftest import FakeRedis

SETTINGS = Settings()
KEY = "media/0b8f3c1e-2a4d-4c6e-9f10-1234567890ab/original"


def fields(attempt="1") -> dict[str, str]:
    return {"mediaId": "7", "storageKey": KEY, "mediaType": "IMAGE", "mime": "image/jpeg",
            "extractLocation": "false", "attempt": attempt}


def test_처리_결과를_결과_스트림에_보내고_ACK한다():
    redis = FakeRedis()
    consumer = MediaConsumer(redis, SETTINGS, lambda job: MediaResult(media_id=job.media_id, status="READY"))

    consumer.handle("1-0", fields())

    [result] = redis.entries(SETTINGS.results_stream)
    assert (result["mediaId"], result["status"]) == ("7", "READY")
    assert redis.acked == ["1-0"]


def test_실패하면_attempt를_올려_다시_넣는다():
    redis = FakeRedis()
    consumer = MediaConsumer(redis, SETTINGS, failing)

    consumer.handle("1-0", fields("1"))

    [retry] = redis.entries(SETTINGS.jobs_stream)
    assert retry["attempt"] == "2"
    assert redis.entries(SETTINGS.results_stream) == []
    assert redis.acked == ["1-0"]


def test_세_번째도_실패하면_DLQ로_보내고_FAILED를_알린다():
    redis = FakeRedis()
    consumer = MediaConsumer(redis, SETTINGS, failing)

    consumer.handle("1-0", fields("1"))
    consumer.handle("2-0", redis.entries(SETTINGS.jobs_stream)[-1])
    consumer.handle("3-0", redis.entries(SETTINGS.jobs_stream)[-1])

    assert [entry["attempt"] for entry in redis.entries(SETTINGS.jobs_stream)] == ["2", "3"]
    [dead] = redis.entries(SETTINGS.dlq_stream)
    assert (dead["mediaId"], dead["error"]) == ("7", "ConnectionError")
    [result] = redis.entries(SETTINGS.results_stream)
    assert (result["status"], result["reason"]) == ("FAILED", "PROCESSING_FAILED")
    assert redis.acked == ["1-0", "2-0", "3-0"]


def test_형식이_잘못된_요청은_재시도하지_않고_DLQ로_보낸다():
    redis = FakeRedis()
    calls = []
    consumer = MediaConsumer(redis, SETTINGS, lambda job: calls.append(job))

    consumer.handle("1-0", {**fields(), "storageKey": "../../etc/passwd"})
    consumer.handle("2-0", {"mediaId": "abc"})

    assert calls == []
    assert [entry["error"] for entry in redis.entries(SETTINGS.dlq_stream)] == ["INVALID_JOB", "INVALID_JOB"]
    assert redis.entries(SETTINGS.jobs_stream) == []
    assert redis.acked == ["1-0", "2-0"]


def failing(job):
    raise ConnectionError("s3 down")


def test_처리_중_죽은_워커가_남긴_요청은_회수해_다시_처리한다():
    redis = FakeRedis()
    consumer = MediaConsumer(redis, SETTINGS, lambda job: MediaResult(media_id=job.media_id, status="READY"))
    redis.add_pending(SETTINGS.jobs_stream, "5-0", fields(), times_delivered=1)

    assert consumer.reclaim_once() == 1

    [result] = redis.entries(SETTINGS.results_stream)
    assert (result["mediaId"], result["status"]) == ("7", "READY")
    assert redis.acked == ["5-0"]


def test_계속_워커를_멈추게_하는_요청은_다시_처리하지_않고_DLQ로_보내고_FAILED를_알린다():
    redis = FakeRedis()
    calls = []
    consumer = MediaConsumer(redis, SETTINGS, lambda job: calls.append(job))
    redis.add_pending(SETTINGS.jobs_stream, "5-0", fields(), times_delivered=SETTINGS.max_attempts)

    consumer.reclaim_once()

    assert calls == []
    [dead] = redis.entries(SETTINGS.dlq_stream)
    assert (dead["mediaId"], dead["error"]) == ("7", "STUCK")
    [result] = redis.entries(SETTINGS.results_stream)
    assert (result["status"], result["reason"]) == ("FAILED", "PROCESSING_FAILED")
    assert redis.acked == ["5-0"]


def test_스트림에서_이미_지워진_대기_항목은_ACK만_한다():
    redis = FakeRedis()
    consumer = MediaConsumer(redis, SETTINGS, failing)
    redis.add_pending(SETTINGS.jobs_stream, "5-0", None, times_delivered=1)

    assert consumer.reclaim_once() == 0
    assert redis.acked == ["5-0"]
    assert redis.entries(SETTINGS.results_stream) == []
