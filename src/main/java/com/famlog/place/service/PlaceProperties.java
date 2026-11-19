package com.famlog.place.service;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.famlog.place.domain.PlaceCategory;

/**
 * 장소 설정 (application.yml famlog.place, 사양서 4.4 PLC-01~06, 8.6).
 *
 * @param kakaoBaseUrl       카카오 로컬 API 주소
 * @param kakaoRestApiKey    카카오 REST API 키 (환경별 설정, 비어 있으면 검색이 500). 로그에 남기지 않는다
 * @param documentCacheTtl   검색 결과의 장소 하나를 공유할 수 있도록 남겨 두는 시간
 * @param searchCacheTtl     위치 없이 검색한 결과 목록을 캐시하는 시간
 * @param searchRateCapacity 사용자당 searchRatePeriod 동안 검색할 수 있는 횟수
 * @param nearbyEnabled      내 주변 검색 (위치기반서비스 신고 확인 전에는 끌 수 있다, 사양서 8.6)
 * @param categoryMap        카카오 분류 코드(category_group_code) → 앱 카테고리. 없는 코드는 ETC
 */
@ConfigurationProperties("famlog.place")
public record PlaceProperties(String kakaoBaseUrl, String kakaoRestApiKey, Duration documentCacheTtl,
        Duration searchCacheTtl, long searchRateCapacity, Duration searchRatePeriod, boolean nearbyEnabled,
        int defaultPageSize, int maxPageSize, Map<String, PlaceCategory> categoryMap) {

    public PlaceProperties {
        categoryMap = categoryMap == null ? Map.of() : Map.copyOf(categoryMap);
    }

    public PlaceCategory categoryOf(String kakaoGroupCode) {
        return kakaoGroupCode == null ? PlaceCategory.ETC : categoryMap.getOrDefault(kakaoGroupCode, PlaceCategory.ETC);
    }
}
