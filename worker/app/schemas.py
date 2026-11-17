"""Redis Streams 메시지 형식 (사양서 13.2 pydantic 스키마 검증).

필드 이름은 API 와 같다:
- MediaJob: com.famlog.media.service.MediaJobPublisher
- MediaResult: com.famlog.media.service.MediaResultHandler
스트림 필드는 모두 문자열이다. 값이 없으면 빈 문자열로 보낸다.
"""

import json
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

THUMBNAIL_VARIANTS = ("THUMB_S", "THUMB_M", "THUMB_L")


class MediaJob(BaseModel):
    model_config = ConfigDict(populate_by_name=True, frozen=True)

    media_id: int = Field(alias="mediaId", gt=0)
    storage_key: str = Field(alias="storageKey", pattern=r"^media/[0-9a-f-]{36}/original$")
    media_type: Literal["IMAGE", "VIDEO"] = Field(alias="mediaType")
    mime: str
    extract_location: bool = Field(alias="extractLocation", default=False)
    attempt: int = Field(default=1, ge=1)

    @property
    def base_key(self) -> str:
        return self.storage_key.rsplit("/", 1)[0]

    def next_attempt(self) -> "MediaJob":
        return self.model_copy(update={"attempt": self.attempt + 1})

    def to_fields(self) -> dict[str, str]:
        return {
            "mediaId": str(self.media_id),
            "storageKey": self.storage_key,
            "mediaType": self.media_type,
            "mime": self.mime,
            "extractLocation": "true" if self.extract_location else "false",
            "attempt": str(self.attempt),
        }


class MediaResult(BaseModel):
    media_id: int
    status: Literal["READY", "FAILED", "REJECTED"]
    reason: str | None = None
    mime: str | None = None
    size: int | None = None
    width: int | None = None
    height: int | None = None
    duration_sec: int | None = None
    taken_at: datetime | None = None
    variants: dict[str, str] = Field(default_factory=dict)
    # 개인위치정보. job.extract_location 일 때만 채운다 (사양서 8.6)
    lat: float | None = None
    lng: float | None = None

    def to_fields(self) -> dict[str, str]:
        def text(value) -> str:
            return "" if value is None else str(value)

        variants = json.dumps(self.variants, separators=(",", ":")) if self.variants else ""
        return {
            "mediaId": str(self.media_id),
            "status": self.status,
            "reason": text(self.reason),
            "mime": text(self.mime),
            "size": text(self.size),
            "width": text(self.width),
            "height": text(self.height),
            "durationSec": text(self.duration_sec),
            "takenAt": "" if self.taken_at is None else self.taken_at.isoformat(),
            "variants": variants,
            "lat": text(self.lat),
            "lng": text(self.lng),
        }
