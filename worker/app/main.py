"""워커 실행: python -m app.main"""

import logging
import signal
import time

import redis

from .ai.consumer import AiConsumer
from .ai.llm import create_client
from .config import Settings
from .consumer import MediaConsumer
from .health import HEARTBEAT
from .processor import process
from .storage import Storage

log = logging.getLogger("famlog.worker")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s : %(message)s")
    settings = Settings.from_env()
    client = redis.Redis(host=settings.redis_host, port=settings.redis_port, password=settings.redis_password,
                         decode_responses=True)
    storage = Storage(settings)
    consumer = MediaConsumer(client, settings, lambda job: process(job, storage, settings))
    ai_consumer = AiConsumer(client, settings, create_client(settings.llm_provider))

    running = True

    def stop(*_):
        nonlocal running
        running = False

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    while running:
        try:
            consumer.ensure_group()
            ai_consumer.ensure_group()
            break
        except redis.RedisError as e:
            log.warning("Redis에 연결하지 못했습니다, 다시 시도합니다: %s", type(e).__name__)
            time.sleep(3)

    log.info("워커 시작: media=%s ai=%s llm=%s", settings.jobs_stream, settings.ai_jobs_stream,
             settings.llm_provider)
    while running:
        HEARTBEAT.touch()
        try:
            consumer.run_once(block_ms=2000)
            ai_consumer.run_once(block_ms=2000)
        except redis.RedisError as e:
            log.warning("Redis 읽기 실패: %s", type(e).__name__)
            time.sleep(3)
    log.info("워커 종료")


if __name__ == "__main__":
    main()
