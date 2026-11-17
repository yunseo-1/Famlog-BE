"""테스트용 사진·동영상과 가짜 Redis·저장소."""

import shutil
import subprocess
from io import BytesIO
from pathlib import Path

import pytest
from PIL import ExifTags, Image

# 서울시청 근처
LAT, LNG = 37.5665, 126.978


def exif_with_gps(orientation: int = 1) -> Image.Exif:
    exif = Image.Exif()
    exif[0x0112] = orientation
    exif[ExifTags.IFD.Exif] = {0x9003: "2026:11:14 11:30:00", 0x9011: "+09:00"}
    exif[ExifTags.IFD.GPSInfo] = {
        1: "N", 2: (37.0, 33.0, 59.4),
        3: "E", 4: (126.0, 58.0, 40.8),
    }
    return exif


def image_bytes(fmt: str, size=(40, 20), orientation: int = 1, mode: str = "RGB") -> bytes:
    image = Image.new(mode, size, (200, 30, 30) if mode == "RGB" else (200, 30, 30, 128))
    out = BytesIO()
    image.save(out, format=fmt, exif=exif_with_gps(orientation).tobytes())
    return out.getvalue()


@pytest.fixture
def jpeg_with_gps() -> bytes:
    return image_bytes("JPEG", orientation=6)


requires_ffmpeg = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg 필요 (Docker 테스트 이미지)")


@pytest.fixture
def mp4_with_gps(tmp_path: Path) -> Path:
    """2초 320x240 영상 + 소리, 위치·촬영 시각 메타데이터 포함"""
    return make_video(tmp_path / "gps.mp4", "libx264", "aac", "mp4")


def make_video(path: Path, video_codec: str, audio_codec: str | None, container: str) -> Path:
    """2초 320x240 영상, 위치·촬영 시각 메타데이터 포함. audio_codec 이 None 이면 소리 없음"""
    audio = ["-f", "lavfi", "-i", "sine=duration=2"] if audio_codec else []
    codecs = ["-c:v", video_codec, "-pix_fmt", "yuv420p"] + (["-c:a", audio_codec] if audio_codec else [])
    subprocess.run([
        "ffmpeg", "-y", "-v", "error",
        "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=10", *audio,
        "-metadata", "location=+37.5665+126.9780/",
        "-metadata", "creation_time=2026-11-14T02:30:00Z",
        *codecs, "-shortest", "-f", container, str(path),
    ], check=True, capture_output=True)
    return path


@pytest.fixture
def mp4_with_pcm_audio(tmp_path: Path) -> Path:
    """mp4 에 담을 수 없는 소리(AMR-NB 와 같은 경우). 테스트 이미지 FFmpeg 에 AMR 인코더가 없어 PCM 으로 대신한다"""
    return make_video(tmp_path / "pcm.mp4", "libx264", "pcm_s16le", "mov")


def probe_tags(path: Path) -> dict:
    import json

    result = subprocess.run(
        ["ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", str(path)],
        check=True, capture_output=True,
    )
    info = json.loads(result.stdout)
    tags = {k.lower(): v for k, v in info["format"].get("tags", {}).items()}
    for stream in info["streams"]:
        tags.update({k.lower(): v for k, v in stream.get("tags", {}).items()})
    return tags


class FakeRedis:
    """MediaConsumer 가 쓰는 명령만 흉내 낸다"""

    def __init__(self):
        self.streams: dict[str, list[dict]] = {}
        self.acked: list = []

    def xadd(self, stream, fields):
        self.streams.setdefault(stream, []).append(dict(fields))
        return f"{len(self.streams[stream])}-0"

    def xack(self, stream, group, message_id):
        self.acked.append(message_id)

    def xgroup_create(self, *args, **kwargs):
        return True

    def entries(self, stream) -> list[dict]:
        return self.streams.get(stream, [])


class FakeStorage:
    def __init__(self, objects: dict[str, bytes] | None = None):
        self.objects = dict(objects or {})
        self.content_types: dict[str, str] = {}

    def download(self, key, path):
        if key not in self.objects:
            raise KeyError(key)
        Path(path).write_bytes(self.objects[key])

    def put_bytes(self, key, data, content_type):
        self.objects[key] = data
        self.content_types[key] = content_type

    def put_file(self, key, path, content_type):
        self.put_bytes(key, Path(path).read_bytes(), content_type)

    def delete(self, key):
        self.objects.pop(key, None)
