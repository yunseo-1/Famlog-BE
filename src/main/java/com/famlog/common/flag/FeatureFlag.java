package com.famlog.common.flag;

/**
 * 관리자 도구에서 켜고 끄는 기능 (사양서 8.6 출시 전 위치 기능 스위치, 14장). DB(feature_flags)에 값이 없으면 설정값을 따른다
 */
public enum FeatureFlag {
    /** 내 주변 검색 (famlog.place.nearby-enabled) */
    PLACE_NEARBY("place.nearby"),
    /** 사진 위치 저장 (famlog.media.photo-location-enabled) */
    MEDIA_PHOTO_LOCATION("media.photo-location"),
    /** AI 분석 전체 비상 끄기. 꺼지면 새 분석 요청을 만들지 않는다, 그룹 설정은 그대로 (famlog.ai.enabled) */
    AI_ANALYSIS("ai.analysis");

    private final String key;

    FeatureFlag(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static FeatureFlag ofKey(String key) {
        for (FeatureFlag flag : values()) {
            if (flag.key.equals(key)) {
                return flag;
            }
        }
        return null;
    }
}
