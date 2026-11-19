package com.famlog.place.kakao;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 로컬 키워드 검색 (GET /v2/local/search/keyword.json). 키는 서버에만 있다 (사양서 11.3 PLC-01).
 * 검색어·좌표는 로그에 남기지 않는다 (사양서 8.6, 9.8).
 */
public interface KakaoLocalClient {

    /** 한 쪽에 받는 개수 (카카오 최대 15) */
    int PAGE_SIZE = 15;

    /**
     * @param x 경도 (없으면 정확도 순)
     * @param y 위도
     */
    SearchResult search(String query, Double x, Double y, int page);

    record SearchResult(List<Document> documents, boolean end) {
    }

    /** 카카오 응답의 장소 하나 (필요한 값만). 좌표는 문자열로 온다 */
    record Document(String id, @JsonProperty("place_name") String placeName,
            @JsonProperty("category_name") String categoryName,
            @JsonProperty("category_group_code") String categoryGroupCode, String phone,
            @JsonProperty("address_name") String addressName,
            @JsonProperty("road_address_name") String roadAddressName, String x, String y,
            @JsonProperty("place_url") String placeUrl, String distance) {
    }
}
