"""멈춘 대기 메시지(PEL) 회수.

워커는 처리 후 항상 ACK 하므로 PEL 에 남는 것은 처리 중 프로세스가 죽은 요청뿐이다.
min_idle_ms 넘게 확인되지 않은 요청을 이 워커로 가져와 다시 처리하고, 이미 max_deliveries 번 전달됐으면
(같은 요청이 계속 워커를 죽이는 경우) 다시 처리하지 않고 give_up 으로 넘긴다.
"""

import logging
from typing import Callable

log = logging.getLogger(__name__)


def reclaim_pending(redis, stream: str, group: str, consumer: str, min_idle_ms: int, max_deliveries: int,
                    handle: Callable[[str, dict[str, str]], None],
                    give_up: Callable[[str, dict[str, str]], None], count: int = 10) -> int:
    """회수해 처리(또는 포기)한 개수를 돌려준다"""
    pending = redis.xpending_range(stream, group, min="-", max="+", count=count, idle=min_idle_ms)
    handled = 0
    for entry in pending or []:
        message_id = entry["message_id"]
        delivered = int(entry["times_delivered"])
        claimed = redis.xclaim(stream, group, consumer, min_idle_ms, [message_id])
        if not claimed:
            continue  # 다른 워커가 먼저 가져갔다
        claimed_id, fields = claimed[0]
        if fields is None:  # 스트림에서 이미 지워진 항목
            redis.xack(stream, group, claimed_id)
            continue
        fields = _decode(fields)
        if delivered >= max_deliveries:
            log.error("대기 메시지가 %s번 전달돼도 처리되지 않아 DLQ로 보냅니다: stream=%s", delivered, stream)
            give_up(claimed_id, fields)
        else:
            log.warning("멈춘 대기 메시지를 다시 처리합니다: stream=%s delivered=%s", stream, delivered)
            handle(claimed_id, fields)
        handled += 1
    return handled


def _decode(fields: dict) -> dict[str, str]:
    return {
        (k.decode() if isinstance(k, bytes) else k): (v.decode() if isinstance(v, bytes) else v)
        for k, v in fields.items()
    }
