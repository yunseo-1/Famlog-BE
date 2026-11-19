package com.famlog.place.domain;

/** 개인위치정보를 쓴 기능 (사양서 8.6 location_access_logs.purpose) */
public enum LocationPurpose {
    /** 내 주변 장소 (PLC-04) */
    NEARBY,
    /** 사진 위치 (CHAT-05, 지금은 꺼져 있다) */
    PHOTO_LOCATION,
    /** 장소 검색에 현재 위치(x, y)를 함께 보낸 경우 */
    PLACE_SEARCH
}
