"""처리 요청 하나: 원본 교체, 썸네일 키, 거부 시 원본 삭제, 재처리 안전."""

from io import BytesIO

import pytest
from PIL import ExifTags, Image

from app.config import Settings
from app.processor import process
from app.schemas import MediaJob
from tests.conftest import FakeStorage, requires_ffmpeg

KEY = "media/0b8f3c1e-2a4d-4c6e-9f10-1234567890ab/original"
SETTINGS = Settings()


def job(media_type="IMAGE", mime="image/jpeg", extract_location=False) -> MediaJob:
    return MediaJob(mediaId=7, storageKey=KEY, mediaType=media_type, mime=mime, extractLocation=extract_location)


def test_사진_원본을_GPS_없는_파일로_바꾸고_썸네일을_올린다(jpeg_with_gps):
    storage = FakeStorage({KEY: jpeg_with_gps})

    result = process(job(), storage, SETTINGS)

    assert result.status == "READY"
    assert result.size == len(storage.objects[KEY])
    assert not Image.open(BytesIO(storage.objects[KEY])).getexif().get_ifd(ExifTags.IFD.GPSInfo)
    base = KEY.rsplit("/", 1)[0]
    assert result.variants == {
        "THUMB_S": f"{base}/thumb_s.webp",
        "THUMB_M": f"{base}/thumb_m.webp",
        "THUMB_L": f"{base}/thumb_l.webp",
    }
    assert all(storage.content_types[key] == "image/webp" for key in result.variants.values())
    assert result.lat is None and result.lng is None


def test_같은_요청을_다시_처리해도_결과가_같다(jpeg_with_gps):
    storage = FakeStorage({KEY: jpeg_with_gps})
    first = process(job(), storage, SETTINGS)

    second = process(job(), storage, SETTINGS)

    assert (second.status, second.width, second.height, second.variants) == (
        first.status, first.width, first.height, first.variants)


def test_처리할_수_없는_파일은_원본을_지우고_REJECTED():
    storage = FakeStorage({KEY: b"MZ\x90\x00 not an image"})

    result = process(job(), storage, SETTINGS)

    assert (result.status, result.reason) == ("REJECTED", "NOT_AN_IMAGE")
    assert KEY not in storage.objects


def test_저장소_오류는_그대로_던져_재시도하게_한다():
    with pytest.raises(KeyError):
        process(job(), FakeStorage(), SETTINGS)


def test_결과_필드는_API가_읽는_문자열이다(jpeg_with_gps):
    fields = process(job(extract_location=True), FakeStorage({KEY: jpeg_with_gps}), SETTINGS).to_fields()

    assert set(fields) == {"mediaId", "status", "reason", "mime", "size", "width", "height", "durationSec",
                           "takenAt", "variants", "lat", "lng"}
    assert all(isinstance(value, str) for value in fields.values())
    assert fields["takenAt"] == "2026-11-14T11:30:00+09:00"
    assert fields["lat"].startswith("37.56")


@requires_ffmpeg
def test_동영상_원본을_메타데이터_없는_파일로_바꾼다(mp4_with_gps):
    storage = FakeStorage({KEY: mp4_with_gps.read_bytes()})

    result = process(job("VIDEO", "video/mp4"), storage, SETTINGS)

    assert result.status == "READY"
    assert result.duration_sec == 2
    assert b"+37.5665" not in storage.objects[KEY]
    assert storage.content_types[KEY] == "video/mp4"
