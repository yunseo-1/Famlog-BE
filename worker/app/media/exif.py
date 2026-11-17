"""EXIF 읽기: 촬영 시각, GPS. 값을 로그에 남기지 않는다 (사양서 9.8)."""

from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from PIL import ExifTags, Image

SEOUL = ZoneInfo("Asia/Seoul")

_DATETIME_ORIGINAL = 0x9003
_DATETIME_DIGITIZED = 0x9004
_OFFSET_TIME_ORIGINAL = 0x9011
_OFFSET_TIME = 0x9010
_DATETIME = 0x0132
_GPS_LAT_REF, _GPS_LAT, _GPS_LNG_REF, _GPS_LNG = 1, 2, 3, 4


def taken_at(exif: Image.Exif) -> datetime | None:
    """촬영 시각. EXIF에 시간대가 없으면 한국 시간으로 본다 (날짜 해석 기준 Asia/Seoul)."""
    ifd = exif.get_ifd(ExifTags.IFD.Exif)
    raw = ifd.get(_DATETIME_ORIGINAL) or ifd.get(_DATETIME_DIGITIZED) or exif.get(_DATETIME)
    if not raw:
        return None
    try:
        local = datetime.strptime(str(raw).strip("\x00 "), "%Y:%m:%d %H:%M:%S")
    except ValueError:
        return None
    offset = ifd.get(_OFFSET_TIME_ORIGINAL) or ifd.get(_OFFSET_TIME)
    tz = _parse_offset(str(offset)) if offset else None
    return local.replace(tzinfo=tz or SEOUL)


def gps(exif: Image.Exif) -> tuple[float, float] | None:
    """(위도, 경도). 없거나 잘못됐으면 None"""
    info = exif.get_ifd(ExifTags.IFD.GPSInfo)
    try:
        lat = _degrees(info[_GPS_LAT], info.get(_GPS_LAT_REF, "N"))
        lng = _degrees(info[_GPS_LNG], info.get(_GPS_LNG_REF, "E"))
    except (KeyError, TypeError, ValueError, ZeroDivisionError):
        return None
    if not (-90 <= lat <= 90 and -180 <= lng <= 180):
        return None
    return lat, lng


def _degrees(dms, ref) -> float:
    d, m, s = (float(part) for part in dms)
    value = d + m / 60 + s / 3600
    return -value if str(ref).upper() in ("S", "W") else value


def _parse_offset(value: str) -> timezone | None:
    value = value.strip("\x00 ")
    try:
        sign = -1 if value[0] == "-" else 1
        hours, minutes = value[1:].split(":")
        return timezone(sign * timedelta(hours=int(hours), minutes=int(minutes)))
    except (ValueError, IndexError):
        return None
