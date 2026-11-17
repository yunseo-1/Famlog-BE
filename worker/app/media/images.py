"""사진 처리 (사양서 8.6, 9.1).

- 원본은 다시 인코딩한다: EXIF 회전을 적용하고 메타데이터(EXIF·XMP·GPS)를 모두 버린다.
  다른 가족이 내려받는 원본에 위치가 남지 않고, 이미지로 위장한 내용도 그대로 저장되지 않는다.
  JPEG·HEIC → JPEG(품질 95), PNG → PNG, WebP → WebP(품질 95). 색 프로필(ICC)은 유지한다.
- 썸네일: 긴 변 256 / 720 / 1440px WebP(품질 80). 원본보다 키우지 않는다.
- 촬영 시각은 결과로 돌려주고, 위치는 요청받았을 때만 읽는다.
"""

from dataclasses import dataclass, field
from datetime import datetime
from io import BytesIO

import pillow_heif
from PIL import Image, ImageOps, UnidentifiedImageError

from . import Rejected, exif

pillow_heif.register_heif_opener()

THUMBNAIL_SIZES = {"THUMB_S": 256, "THUMB_M": 720, "THUMB_L": 1440}


@dataclass
class ProcessedImage:
    data: bytes
    mime: str
    width: int
    height: int
    taken_at: datetime | None = None
    location: tuple[float, float] | None = None
    thumbnails: dict[str, bytes] = field(default_factory=dict)


def process_image(data: bytes, extract_location: bool, max_pixels: int) -> ProcessedImage:
    Image.MAX_IMAGE_PIXELS = max_pixels
    try:
        image = Image.open(BytesIO(data))
        image.load()
    except Image.DecompressionBombError as e:
        raise Rejected("TOO_LARGE") from e
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError) as e:
        raise Rejected("NOT_AN_IMAGE") from e
    if image.width * image.height > max_pixels:
        raise Rejected("TOO_LARGE")

    source_format = image.format
    metadata = image.getexif()
    taken = exif.taken_at(metadata)
    location = exif.gps(metadata) if extract_location else None
    icc_profile = image.info.get("icc_profile")

    upright = ImageOps.exif_transpose(image)
    clean = _without_metadata(upright)
    encoded, mime = _encode_original(clean, source_format, icc_profile)
    return ProcessedImage(
        data=encoded,
        mime=mime,
        width=clean.width,
        height=clean.height,
        taken_at=taken,
        location=location,
        thumbnails=make_thumbnails(clean),
    )


def make_thumbnails(image: Image.Image) -> dict[str, bytes]:
    base = image if image.mode in ("RGB", "RGBA") else image.convert("RGBA" if _has_alpha(image) else "RGB")
    thumbnails = {}
    for variant, size in THUMBNAIL_SIZES.items():
        thumbnail = base.copy()
        thumbnail.thumbnail((size, size), Image.Resampling.LANCZOS)
        out = BytesIO()
        thumbnail.save(out, format="WEBP", quality=80)
        thumbnails[variant] = out.getvalue()
    return thumbnails


def _without_metadata(image: Image.Image) -> Image.Image:
    """픽셀만 남긴 새 이미지 (info 의 exif·xmp 등은 버린다)"""
    mode = image.mode
    if mode not in ("RGB", "RGBA", "L", "LA"):
        mode = "RGBA" if _has_alpha(image) else "RGB"
    clean = image.convert(mode) if mode != image.mode else image.copy()
    clean.info = {}
    return clean


def _encode_original(image: Image.Image, source_format: str | None, icc_profile: bytes | None) -> tuple[bytes, str]:
    out = BytesIO()
    extra = {"icc_profile": icc_profile} if icc_profile else {}
    if source_format == "PNG":
        image.save(out, format="PNG", **extra)
        return out.getvalue(), "image/png"
    if source_format == "WEBP":
        image.save(out, format="WEBP", quality=95, **extra)
        return out.getvalue(), "image/webp"
    rgb = image.convert("RGB") if image.mode != "RGB" and image.mode != "L" else image
    rgb.save(out, format="JPEG", quality=95, **extra)
    return out.getvalue(), "image/jpeg"


def _has_alpha(image: Image.Image) -> bool:
    return image.mode in ("RGBA", "LA", "PA") or (image.mode == "P" and "transparency" in image.info)
