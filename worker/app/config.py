"""환경 변수 설정. 스트림 이름은 API(MediaProperties.Streams)와 같아야 한다."""

import os
import socket
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_password: str | None = None

    s3_endpoint: str | None = None  # 비우면 AWS 기본 (운영)
    s3_region: str = "ap-northeast-2"
    s3_access_key: str | None = None
    s3_secret_key: str | None = None
    s3_bucket: str = "famlog-media"

    jobs_stream: str = "famlog:media"
    results_stream: str = "famlog:media-results"
    dlq_stream: str = "famlog:media:dlq"
    group: str = "media-workers"
    consumer: str = socket.gethostname()

    max_attempts: int = 3  # 사양서 20 S9: 실패 재시도 3회 후 DLQ
    max_video_seconds: int = 300  # 동영상 5분 (CHAT-05)
    max_image_pixels: int = 100_000_000  # 압축 폭탄 방지

    @classmethod
    def from_env(cls) -> "Settings":
        env = os.environ
        return cls(
            redis_host=env.get("REDIS_HOST", cls.redis_host),
            redis_port=int(env.get("REDIS_PORT", cls.redis_port)),
            redis_password=env.get("REDIS_PASSWORD") or None,
            s3_endpoint=env.get("S3_ENDPOINT") or None,
            s3_region=env.get("S3_REGION", cls.s3_region),
            s3_access_key=env.get("S3_ACCESS_KEY") or None,
            s3_secret_key=env.get("S3_SECRET_KEY") or None,
            s3_bucket=env.get("S3_BUCKET_MEDIA", cls.s3_bucket),
            max_video_seconds=int(env.get("MEDIA_MAX_VIDEO_SECONDS", cls.max_video_seconds)),
        )
