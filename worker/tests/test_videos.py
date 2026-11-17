"""동영상 처리: 위치 메타데이터 제거(SEC-T6), 길이·해상도, 미리보기 썸네일, 5분 제한."""

from datetime import datetime, timezone
from io import BytesIO

import pytest
from PIL import Image

from app.media import Rejected
from app.media.videos import probe, process_video
from tests.conftest import LAT, LNG, probe_tags, requires_ffmpeg

pytestmark = requires_ffmpeg


def test_원본에서_위치와_메타데이터가_지워진다(mp4_with_gps, tmp_path):
    assert "location" in probe_tags(mp4_with_gps)

    result = process_video(mp4_with_gps, tmp_path, "video/mp4", extract_location=False, max_seconds=300)

    tags = probe_tags(result.path)
    assert not any("location" in key or "xyz" in key for key in tags)
    assert "creation_time" not in tags
    assert b"+37.5665" not in result.path.read_bytes()
    assert result.location is None


def test_영상과_소리는_다시_인코딩하지_않고_남긴다(mp4_with_gps, tmp_path):
    result = process_video(mp4_with_gps, tmp_path, "video/mp4", extract_location=False, max_seconds=300)

    codecs = sorted(stream["codec_name"] for stream in probe(result.path)["streams"])
    assert codecs == ["aac", "h264"]
    assert result.mime == "video/mp4"


def test_길이_해상도_촬영_시각을_읽는다(mp4_with_gps, tmp_path):
    result = process_video(mp4_with_gps, tmp_path, "video/mp4", extract_location=False, max_seconds=300)

    assert (result.width, result.height, result.duration_sec) == (320, 240, 2)
    assert result.taken_at == datetime(2026, 11, 14, 2, 30, tzinfo=timezone.utc)


def test_위치는_요청받았을_때만_읽는다(mp4_with_gps, tmp_path):
    result = process_video(mp4_with_gps, tmp_path, "video/mp4", extract_location=True, max_seconds=300)

    assert result.location == pytest.approx((LAT, LNG), abs=1e-4)
    assert "location" not in probe_tags(result.path)


def test_미리보기_썸네일_세_단계를_만든다(mp4_with_gps, tmp_path):
    result = process_video(mp4_with_gps, tmp_path, "video/mp4", extract_location=False, max_seconds=300)

    assert set(result.thumbnails) == {"THUMB_S", "THUMB_M", "THUMB_L"}
    assert Image.open(BytesIO(result.thumbnails["THUMB_S"])).size == (256, 192)
    assert Image.open(BytesIO(result.thumbnails["THUMB_L"])).size == (320, 240)


def test_5분을_넘으면_거부한다(mp4_with_gps, tmp_path):
    with pytest.raises(Rejected) as rejected:
        process_video(mp4_with_gps, tmp_path, "video/mp4", extract_location=False, max_seconds=1)
    assert rejected.value.reason == "TOO_LONG"


def test_동영상이_아니면_거부한다(tmp_path):
    fake = tmp_path / "fake.mp4"
    fake.write_bytes(b"\x00\x00\x00\x18ftypmp42 but not really a video")

    with pytest.raises(Rejected) as rejected:
        process_video(fake, tmp_path, "video/mp4", extract_location=False, max_seconds=300)
    assert rejected.value.reason == "NOT_A_VIDEO"
