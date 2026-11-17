"""Docker healthcheck: 소비 루프가 최근 60초 안에 돌았는지 (main 이 HEARTBEAT 파일을 갱신한다)."""

import sys
import time
from pathlib import Path

HEARTBEAT = Path("/tmp/famlog-worker-heartbeat")
MAX_AGE_SECONDS = 60


def main() -> int:
    try:
        age = time.time() - HEARTBEAT.stat().st_mtime
    except FileNotFoundError:
        return 1
    return 0 if age < MAX_AGE_SECONDS else 1


if __name__ == "__main__":
    sys.exit(main())
