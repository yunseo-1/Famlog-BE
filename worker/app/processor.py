"""처리 요청 하나: 원본을 받아 GPS를 지운 원본으로 바꾸고 썸네일을 올린 뒤 결과를 만든다.

같은 요청을 다시 처리해도 같은 키에 덮어쓰므로 결과가 같다 (재시도 안전).
처리할 수 없는 파일(Rejected)은 원본을 지우고 REJECTED 결과를 돌려준다 (재시도하지 않는다).
그 밖의 예외(저장소·네트워크)는 그대로 던진다 → 소비자가 재시도한다.
"""

import logging
import tempfile
from pathlib import Path
from typing import Protocol

from .config import Settings
from .media import Rejected
from .media.images import process_image
from .media.videos import process_video
from .schemas import MediaJob, MediaResult

log = logging.getLogger(__name__)


class ObjectStorage(Protocol):
    def download(self, key: str, path: Path) -> None: ...

    def put_bytes(self, key: str, data: bytes, content_type: str) -> None: ...

    def put_file(self, key: str, path: Path, content_type: str) -> None: ...

    def delete(self, key: str) -> None: ...


def thumbnail_key(job: MediaJob, variant: str) -> str:
    return f"{job.base_key}/{variant.lower()}.webp"


def process(job: MediaJob, storage: ObjectStorage, settings: Settings) -> MediaResult:
    with tempfile.TemporaryDirectory(prefix="famlog-media-") as tmp:
        workdir = Path(tmp)
        source = workdir / "source"
        storage.download(job.storage_key, source)
        try:
            result = _process(job, source, workdir, storage, settings)
        except Rejected as rejected:
            storage.delete(job.storage_key)
            log.info("미디어 거부: mediaId=%s reason=%s", job.media_id, rejected.reason)
            return MediaResult(media_id=job.media_id, status="REJECTED", reason=rejected.reason)
    log.info("미디어 처리 완료: mediaId=%s type=%s", job.media_id, job.media_type)
    return result


def _process(job: MediaJob, source: Path, workdir: Path, storage: ObjectStorage, settings: Settings) -> MediaResult:
    if job.media_type == "IMAGE":
        image = process_image(source.read_bytes(), job.extract_location, settings.max_image_pixels)
        _put_thumbnails(job, image.thumbnails, storage)
        storage.put_bytes(job.storage_key, image.data, image.mime)
        location = image.location
        return MediaResult(
            media_id=job.media_id,
            status="READY",
            mime=image.mime,
            size=len(image.data),
            width=image.width,
            height=image.height,
            taken_at=image.taken_at,
            variants={variant: thumbnail_key(job, variant) for variant in image.thumbnails},
            lat=location[0] if location else None,
            lng=location[1] if location else None,
        )

    video = process_video(source, workdir, job.mime, job.extract_location, settings.max_video_seconds)
    _put_thumbnails(job, video.thumbnails, storage)
    storage.put_file(job.storage_key, video.path, video.mime)
    location = video.location
    return MediaResult(
        media_id=job.media_id,
        status="READY",
        mime=video.mime,
        size=video.path.stat().st_size,
        width=video.width,
        height=video.height,
        duration_sec=video.duration_sec,
        taken_at=video.taken_at,
        variants={variant: thumbnail_key(job, variant) for variant in video.thumbnails},
        lat=location[0] if location else None,
        lng=location[1] if location else None,
    )


def _put_thumbnails(job: MediaJob, thumbnails: dict[str, bytes], storage: ObjectStorage) -> None:
    for variant, data in thumbnails.items():
        storage.put_bytes(thumbnail_key(job, variant), data, "image/webp")
