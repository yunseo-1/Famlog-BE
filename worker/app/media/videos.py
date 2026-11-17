"""동영상 처리 (사양서 4.2 CHAT-05, 8.6).

- 길이·해상도는 ffprobe 로 읽고, 5분을 넘으면 거부한다.
- 원본은 다시 인코딩하지 않고(-c copy) 영상·소리 트랙만 남긴 채 메타데이터를 모두 지운다.
  iPhone 등의 위치(©xyz, com.apple.quicktime.location.ISO6709)와 위치 메타데이터 트랙이 함께 빠진다.
- 미리보기는 1초 지점(짧으면 가운데) 프레임으로 사진과 같은 썸네일 3단계를 만든다.
"""

import json
import re
import subprocess
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from PIL import Image

from . import Rejected
from .images import make_thumbnails

LOCATION_TAGS = ("com.apple.quicktime.location.iso6709", "location", "location-eng")
_ISO6709 = re.compile(r"^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)")
_TIMEOUT_SECONDS = 600


@dataclass
class ProcessedVideo:
    path: Path
    mime: str
    width: int
    height: int
    duration_sec: int
    taken_at: datetime | None = None
    location: tuple[float, float] | None = None
    thumbnails: dict[str, bytes] = field(default_factory=dict)


def probe(path: Path) -> dict:
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", str(path)],
        capture_output=True,
        timeout=_TIMEOUT_SECONDS,
        check=False,
    )
    if result.returncode != 0:
        raise Rejected("NOT_A_VIDEO")
    return json.loads(result.stdout or b"{}")


def process_video(source: Path, workdir: Path, mime: str, extract_location: bool, max_seconds: int) -> ProcessedVideo:
    info = probe(source)
    video = next((s for s in info.get("streams", []) if s.get("codec_type") == "video"), None)
    if video is None:
        raise Rejected("NOT_A_VIDEO")
    try:
        duration = float(info.get("format", {}).get("duration") or video.get("duration"))
    except (TypeError, ValueError) as e:
        raise Rejected("NOT_A_VIDEO") from e
    if duration > max_seconds:
        raise Rejected("TOO_LONG")

    width, height = int(video.get("width", 0)), int(video.get("height", 0))
    if _rotation(video) in (90, 270):
        width, height = height, width
    tags = _lower_keys(info.get("format", {}).get("tags", {}))

    container = "mov" if mime == "video/quicktime" else "mp4"
    cleaned = workdir / f"clean.{container}"
    _run([
        "ffmpeg", "-y", "-v", "error", "-i", str(source),
        "-map", "0:v:0", "-map", "0:a?", "-c", "copy",
        "-map_metadata", "-1", "-map_metadata:s:v", "-1", "-map_metadata:s:a", "-1", "-map_chapters", "-1",
        "-movflags", "+faststart", "-f", container, str(cleaned),
    ])

    poster = workdir / "poster.jpg"
    _run([
        "ffmpeg", "-y", "-v", "error", "-ss", f"{min(1.0, duration / 2):.3f}", "-i", str(source),
        "-frames:v", "1", "-q:v", "2", str(poster),
    ])
    with Image.open(poster) as frame:
        frame.load()
        thumbnails = make_thumbnails(frame.convert("RGB"))

    return ProcessedVideo(
        path=cleaned,
        mime="video/quicktime" if container == "mov" else "video/mp4",
        width=width,
        height=height,
        duration_sec=round(duration),
        taken_at=_creation_time(tags),
        location=_location(tags) if extract_location else None,
        thumbnails=thumbnails,
    )


def _run(command: list[str]) -> None:
    result = subprocess.run(command, capture_output=True, timeout=_TIMEOUT_SECONDS, check=False)
    if result.returncode != 0:
        raise Rejected("NOT_A_VIDEO")


def _rotation(stream: dict) -> int:
    for side_data in stream.get("side_data_list", []):
        if "rotation" in side_data:
            return abs(int(side_data["rotation"])) % 360
    try:
        return abs(int(stream.get("tags", {}).get("rotate", 0))) % 360
    except ValueError:
        return 0


def _lower_keys(tags: dict) -> dict[str, str]:
    return {str(key).lower(): str(value) for key, value in tags.items()}


def _creation_time(tags: dict[str, str]) -> datetime | None:
    raw = tags.get("com.apple.quicktime.creationdate") or tags.get("creation_time")
    if not raw:
        return None
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None


def _location(tags: dict[str, str]) -> tuple[float, float] | None:
    for key in LOCATION_TAGS:
        match = _ISO6709.match(tags.get(key, ""))
        if match:
            lat, lng = float(match.group(1)), float(match.group(2))
            if -90 <= lat <= 90 and -180 <= lng <= 180:
                return lat, lng
    return None
