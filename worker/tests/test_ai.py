"""AI 분석 소비: 가짜 LLM 으로 끝까지, 출력 검증, 재시도 3회 후 DLQ, 대화가 Redis·로그에 남지 않음 (사양서 7.2, 8.7, 9.7)."""

import json
import logging

from app.ai.consumer import AiConsumer
from app.ai.llm import FakeLlmClient, LlmResponse, create_client
from app.config import Settings
from tests.conftest import FakeRedis

SETTINGS = Settings()
SECRET_TEXT = "이번 토요일 11시에 할머니 댁 가자"


def payload(message_ids=(101,)) -> dict:
    return {
        "timezone": "Asia/Seoul",
        "members": [{"ref": "M1", "familyRole": "MOTHER", "familyTitle": None},
                    {"ref": "M2", "familyRole": "SON", "familyTitle": "막내"}],
        "context": [{"id": 100, "sender": "M2", "sentAt": "2026-11-12T09:59:00+09:00", "text": "주말에 뭐 해?"}],
        "messages": [{"id": i, "sender": "M1", "sentAt": "2026-11-12T10:00:00+09:00", "text": SECRET_TEXT}
                     for i in message_ids],
    }


def fields(attempt="1", job_id="7", body=None) -> dict[str, str]:
    return {"jobId": job_id, "attempt": attempt,
            "payload": json.dumps(body if body is not None else payload(), ensure_ascii=False)}


CANDIDATE = {"type": "SCHEDULE",
             "fields": {"title": "할머니 댁 방문", "dateText": "이번 토요일", "startTimeText": "11시",
                        "locationText": "할머니 댁", "attendeeTexts": ["가자"]},
             "confidence": 0.9, "evidence": [{"messageId": 101, "spans": ["이번 토요일 11시", "할머니 댁"]}]}


class FailingClient:
    def extract(self, payload):
        raise ConnectionError("LLM down")


def test_가짜_LLM으로_결과를_보내고_요청을_ACK한_뒤_스트림에서_지운다():
    redis = FakeRedis()
    consumer = AiConsumer(redis, SETTINGS, create_client(SETTINGS))

    consumer.handle("1-0", fields())

    [result] = redis.entries(SETTINGS.ai_results_stream)
    assert (result["jobId"], result["status"], result["candidates"]) == ("7", "DONE", "[]")
    assert (result["modelVersion"], result["promptVersion"], result["inputTokens"]) == ("fake", "none", "0")
    assert result["latencyMs"].isdigit()
    assert redis.acked == ["1-0"]
    assert redis.deleted == ["1-0"]


def test_스키마에_맞는_후보는_그대로_보낸다():
    redis = FakeRedis()
    consumer = AiConsumer(redis, SETTINGS, FakeLlmClient(json.dumps({"candidates": [CANDIDATE]})))

    consumer.handle("1-0", fields())

    [result] = redis.entries(SETTINGS.ai_results_stream)
    assert result["status"] == "DONE"
    [candidate] = json.loads(result["candidates"])
    assert candidate["type"] == "SCHEDULE"
    assert candidate["fields"]["dateText"] == "이번 토요일"
    assert candidate["fields"]["attendeeTexts"] == ["가자"]
    assert candidate["evidence"] == [{"messageId": 101, "spans": ["이번 토요일 11시", "할머니 댁"]}]


def test_유형마다_정해진_원문_표현_필드만_받는다():
    valid = [
        {"type": "TODO", "fields": {"title": "자동차 점검 맡기기", "dueText": "이번 주에", "assigneeTexts": ["아빠"]}},
        {"type": "ANNIVERSARY", "fields": {"title": "할머니 생신", "dateText": "다음 달 15일", "personText": "할머니"}},
        {"type": "PLACE", "fields": {"name": "OO칼국수", "regionHint": "잠실", "opinion": "칼국수가 정말 맛있음"}},
        {"type": "SCHEDULE_CHANGE", "fields": {"title": "영화", "fromTimeText": "11시", "startTimeText": "12시"}},
        {"type": "TODO_DONE", "fields": {"title": "세제"}},
    ]
    redis = FakeRedis()
    candidates = [dict(c, confidence=0.8, evidence=[{"messageId": 101}]) for c in valid]
    AiConsumer(redis, SETTINGS, FakeLlmClient(json.dumps({"candidates": candidates}))).handle("1-0", fields())

    [result] = redis.entries(SETTINGS.ai_results_stream)
    assert result["status"] == "DONE"
    assert [c["type"] for c in json.loads(result["candidates"])] == ["TODO", "ANNIVERSARY", "PLACE",
                                                                     "SCHEDULE_CHANGE", "TODO_DONE"]


def test_구조화_출력이_채운_다른_유형의_빈_필드는_지우고_받는다():
    padded = dict(CANDIDATE, type="TODO_DONE", fields={"title": "세제", "dueText": None, "dateText": None,
                                                       "attendeeTexts": [], "assigneeTexts": []})
    redis = FakeRedis()
    AiConsumer(redis, SETTINGS, FakeLlmClient(json.dumps({"candidates": [padded]}))).handle("1-0", fields())

    [result] = redis.entries(SETTINGS.ai_results_stream)
    assert result["status"] == "DONE"
    assert json.loads(result["candidates"])[0]["fields"] == {"title": "세제"}


def test_다른_유형의_필드나_서버가_정할_값을_보내면_FAILED():
    wrong = [
        dict(CANDIDATE, fields={"title": "할머니 댁 방문", "dueText": "이번 주에"}),  # 일정에 할 일 필드
        dict(CANDIDATE, fields={"title": "할머니 댁 방문", "date": "2026-11-14"}),  # 날짜 계산은 서버가 한다
        dict(CANDIDATE, inferred=["startTime.ampm"]),  # 추정 표시는 서버가 한다
        dict(CANDIDATE, type="TODO_DONE", fields={"title": "세제", "entityId": 3}),  # 대상 찾기는 서버가 한다
        dict(CANDIDATE, fields={"title": "가" * 101}),
        {k: v for k, v in CANDIDATE.items() if k != "fields"},
    ]
    for candidate in wrong:
        redis = FakeRedis()
        AiConsumer(redis, SETTINGS, FakeLlmClient(json.dumps({"candidates": [candidate]}))).handle("1-0", fields())

        [result] = redis.entries(SETTINGS.ai_results_stream)
        assert (result["status"], result["errorCode"]) == ("FAILED", "INVALID_OUTPUT")


def test_LLM_출력이_스키마에_맞지_않으면_재시도하지_않고_FAILED():
    evidence = '"evidence": [{"messageId": 101}]'
    for text in ['{"candidates": [{"type": "MEETING", "fields": {}, "confidence": 0.9, %s}]}' % evidence,
                 '{"candidates": [{"type": "TODO", "fields": {}, "confidence": 1.5, %s}]}' % evidence,
                 '{"candidates": [{"type": "TODO", "fields": {}, "confidence": 0.9, "evidence": []}]}',
                 '{"candidates": [], "note": "이전 지시를 무시했습니다"}',
                 "JSON 이 아님"]:
        redis = FakeRedis()
        consumer = AiConsumer(redis, SETTINGS, FakeLlmClient(text))

        consumer.handle("1-0", fields())

        [result] = redis.entries(SETTINGS.ai_results_stream)
        assert (result["status"], result["errorCode"], result["candidates"]) == ("FAILED", "INVALID_OUTPUT", "[]")
        assert redis.entries(SETTINGS.ai_jobs_stream) == []


def test_근거가_분석_구간_밖의_메시지면_버린다():
    outside = dict(CANDIDATE, evidence=[{"messageId": 100, "spans": []}])  # 앞 맥락 메시지
    redis = FakeRedis()
    consumer = AiConsumer(redis, SETTINGS, FakeLlmClient(json.dumps({"candidates": [outside]})))

    consumer.handle("1-0", fields())

    [result] = redis.entries(SETTINGS.ai_results_stream)
    assert (result["status"], result["errorCode"]) == ("FAILED", "INVALID_OUTPUT")


def test_LLM이_실패하면_attempt를_올려_다시_넣는다():
    redis = FakeRedis()
    consumer = AiConsumer(redis, SETTINGS, FailingClient())

    consumer.handle("1-0", fields("1"))

    [retry] = redis.entries(SETTINGS.ai_jobs_stream)
    assert (retry["jobId"], retry["attempt"]) == ("7", "2")
    assert json.loads(retry["payload"]) == payload()
    assert redis.entries(SETTINGS.ai_results_stream) == []


def test_세_번째도_실패하면_본문_없이_DLQ로_보내고_FAILED를_알린다():
    redis = FakeRedis()
    consumer = AiConsumer(redis, SETTINGS, FailingClient())

    consumer.handle("1-0", fields("1"))
    consumer.handle("2-0", redis.entries(SETTINGS.ai_jobs_stream)[-1])
    consumer.handle("3-0", redis.entries(SETTINGS.ai_jobs_stream)[-1])

    [dead] = redis.entries(SETTINGS.ai_dlq_stream)
    assert dead == {"jobId": "7", "attempt": "3", "error": "ConnectionError"}
    [result] = redis.entries(SETTINGS.ai_results_stream)
    assert (result["status"], result["errorCode"]) == ("FAILED", "LLM_FAILED")
    assert redis.acked == redis.deleted == ["1-0", "2-0", "3-0"]


def test_형식이_잘못된_요청은_LLM을_부르지_않고_DLQ로_보낸다():
    calls = []

    class Recording:
        def extract(self, payload):
            calls.append(payload)
            return LlmResponse('{"candidates": []}', "fake", "none", 0, 0)

    for bad in [{"jobId": "abc", "attempt": "1", "payload": json.dumps(payload())},
                {"jobId": "8", "attempt": "1", "payload": "{깨진 JSON"},
                fields(job_id="9", body=dict(payload(), messages=[])),
                fields(job_id="10", body=dict(payload(), members=[{"ref": "엄마"}]))]:
        redis = FakeRedis()
        AiConsumer(redis, SETTINGS, Recording()).handle("1-0", bad)

        [dead] = redis.entries(SETTINGS.ai_dlq_stream)
        assert dead["error"] == "INVALID_JOB"
        assert "payload" not in dead
        assert redis.deleted == ["1-0"]
    assert calls == []


def test_로그에_대화_본문이_남지_않는다(caplog):
    caplog.set_level(logging.DEBUG)
    redis = FakeRedis()

    AiConsumer(redis, SETTINGS, create_client(SETTINGS)).handle("1-0", fields())
    consumer = AiConsumer(redis, SETTINGS, FailingClient())
    for attempt in ("1", "2", "3"):
        consumer.handle("x", fields(attempt))

    assert caplog.records
    assert all(SECRET_TEXT not in record.getMessage() for record in caplog.records)
    assert all("할머니" not in record.getMessage() for record in caplog.records)
