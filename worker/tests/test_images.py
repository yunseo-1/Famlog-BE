"""사진 처리: GPS 제거(SEC-T6), 회전 적용, 촬영 시각, 썸네일 3단계."""

from datetime import datetime
from io import BytesIO
from zoneinfo import ZoneInfo

import pytest
from PIL import ExifTags, Image

from app.media import Rejected
from app.media.images import process_image
from tests.conftest import LAT, LNG, image_bytes

MAX_PIXELS = 100_000_000


def reopen(data: bytes) -> Image.Image:
    image = Image.open(BytesIO(data))
    image.load()
    return image


def assert_no_location(data: bytes) -> None:
    image = reopen(data)
    exif = image.getexif()
    assert not exif.get_ifd(ExifTags.IFD.GPSInfo)
    assert len(exif) == 0
    assert "exif" not in image.info and "xmp" not in image.info
    assert b"GPS" not in data


def test_JPEG_원본에서_GPS와_메타데이터가_지워진다(jpeg_with_gps):
    assert Image.open(BytesIO(jpeg_with_gps)).getexif().get_ifd(ExifTags.IFD.GPSInfo)

    result = process_image(jpeg_with_gps, extract_location=False, max_pixels=MAX_PIXELS)

    assert result.mime == "image/jpeg"
    assert_no_location(result.data)
    assert result.location is None


def test_HEIC는_GPS를_지운_JPEG로_바뀐다():
    heic = image_bytes("HEIF")
    assert Image.open(BytesIO(heic)).getexif().get_ifd(ExifTags.IFD.GPSInfo)

    result = process_image(heic, extract_location=False, max_pixels=MAX_PIXELS)

    assert result.mime == "image/jpeg"
    assert reopen(result.data).format == "JPEG"
    assert_no_location(result.data)


@pytest.mark.parametrize("fmt, mime", [("PNG", "image/png"), ("WEBP", "image/webp")])
def test_PNG_WebP도_형식은_그대로_메타데이터만_지운다(fmt, mime):
    result = process_image(image_bytes(fmt), extract_location=False, max_pixels=MAX_PIXELS)

    assert result.mime == mime
    assert_no_location(result.data)


def test_투명한_PNG는_투명도를_유지한다():
    result = process_image(image_bytes("PNG", mode="RGBA"), extract_location=False, max_pixels=MAX_PIXELS)

    assert reopen(result.data).mode == "RGBA"


def test_EXIF_회전을_픽셀에_적용한다(jpeg_with_gps):
    # 40x20 사진이 "90도 돌려 보기"(orientation 6)로 저장돼 있다
    result = process_image(jpeg_with_gps, extract_location=False, max_pixels=MAX_PIXELS)

    assert (result.width, result.height) == (20, 40)
    assert reopen(result.data).size == (20, 40)


def test_촬영_시각을_시간대와_함께_읽는다(jpeg_with_gps):
    result = process_image(jpeg_with_gps, extract_location=False, max_pixels=MAX_PIXELS)

    assert result.taken_at == datetime(2026, 11, 14, 11, 30, tzinfo=ZoneInfo("Asia/Seoul"))


def test_시간대가_없으면_한국_시간으로_본다():
    image = Image.new("RGB", (4, 4))
    exif = Image.Exif()
    exif[ExifTags.IFD.Exif] = {0x9003: "2026:11:14 11:30:00"}
    out = BytesIO()
    image.save(out, format="JPEG", exif=exif.tobytes())

    result = process_image(out.getvalue(), extract_location=False, max_pixels=MAX_PIXELS)

    assert result.taken_at.isoformat() == "2026-11-14T11:30:00+09:00"


def test_위치는_요청받았을_때만_읽는다(jpeg_with_gps):
    with_location = process_image(jpeg_with_gps, extract_location=True, max_pixels=MAX_PIXELS)
    without = process_image(jpeg_with_gps, extract_location=False, max_pixels=MAX_PIXELS)

    assert with_location.location == pytest.approx((LAT, LNG), abs=1e-4)
    assert without.location is None
    # 위치를 읽어도 원본 파일에는 남기지 않는다
    assert_no_location(with_location.data)


def test_썸네일은_세_단계_WebP이고_원본보다_키우지_않는다():
    result = process_image(image_bytes("JPEG", size=(3000, 1500)), extract_location=False, max_pixels=MAX_PIXELS)

    sizes = {variant: reopen(data).size for variant, data in result.thumbnails.items()}
    assert sizes == {"THUMB_S": (256, 128), "THUMB_M": (720, 360), "THUMB_L": (1440, 720)}
    for data in result.thumbnails.values():
        assert reopen(data).format == "WEBP"
        assert_no_location(data)

    small = process_image(image_bytes("JPEG", size=(100, 50)), extract_location=False, max_pixels=MAX_PIXELS)
    assert reopen(small.thumbnails["THUMB_L"]).size == (100, 50)


def test_사진이_아니면_거부한다():
    with pytest.raises(Rejected) as rejected:
        process_image(b"MZ\x90\x00\x03\x00\x00\x00 not an image", extract_location=False, max_pixels=MAX_PIXELS)
    assert rejected.value.reason == "NOT_AN_IMAGE"


def test_너무_큰_해상도는_거부한다():
    with pytest.raises(Rejected) as rejected:
        process_image(image_bytes("PNG", size=(200, 200)), extract_location=False, max_pixels=1000)
    assert rejected.value.reason == "TOO_LARGE"
