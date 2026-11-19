package com.famlog.candidate.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.famlog.place.domain.Place;
import com.famlog.place.domain.PlaceAlias;
import com.famlog.place.kakao.KakaoLocalClient;
import com.famlog.place.kakao.KakaoLocalClient.Document;
import com.famlog.place.service.PlaceProperties;
import com.famlog.place.service.PlaceSearchService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 후보의 장소를 찾는다 (사양서 6.2 ② 장소).
 * <ul>
 * <li>장소(PLACE) 후보: 장소명 + 지역 힌트로 카카오 로컬 검색 → 상위 3개. 1개면 자동 선택. 실패하면 글자로만 둔다</li>
 * <li>일정 장소: 별칭 장소("할머니 댁", PLC-06)만 맞춰 본다. 카카오 검색은 하지 않는다 (호출 수 절약)</li>
 * </ul>
 * 검색어는 로그에 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidatePlaceResolver {

    static final int MAX_OPTIONS = 3;

    private final KakaoLocalClient kakao;
    private final PlaceSearchService placeSearchService;
    private final PlaceProperties properties;

    /** 띄어쓰기를 빼고 별칭과 똑같으면 그 별칭 */
    public Optional<PlaceAlias> matchAlias(String text, List<PlaceAlias> aliases) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String key = text.replaceAll("\\s+", "");
        return aliases.stream().filter(alias -> alias.getAlias().replaceAll("\\s+", "").equals(key)).findFirst();
    }

    /** 카카오 검색 상위 3개 (후보 fields.options 형태). 실패하면 빈 목록 */
    public List<Map<String, Object>> search(String name, String regionHint) {
        String query = regionHint == null || regionHint.isBlank() ? name.strip()
                : regionHint.strip() + " " + name.strip();
        List<Document> documents;
        try {
            documents = kakao.search(query, null, null, 1).documents();
        } catch (RuntimeException e) {
            log.warn("후보 장소 검색 실패: 장소는 글자로만 둡니다 ({})", e.getClass().getSimpleName());
            return List.of();
        }
        return documents.stream()
                .filter(doc -> doc.id() != null && doc.addressName() != null && !doc.addressName().isBlank())
                .limit(MAX_OPTIONS)
                .map(this::option)
                .toList();
    }

    private Map<String, Object> option(Document doc) {
        Place.KakaoInfo info = placeSearchService.infoOf(doc);
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("kakaoPlaceId", info.kakaoPlaceId());
        option.put("name", info.name());
        option.put("category", properties.categoryOf(doc.categoryGroupCode()));
        option.put("categoryDetail", info.categoryDetail());
        option.put("address", info.address());
        option.put("region1", info.region1());
        option.put("region2", info.region2());
        option.put("region3", info.region3());
        option.put("lat", info.lat());
        option.put("lng", info.lng());
        option.put("phone", info.phone());
        option.put("placeUrl", info.placeUrl());
        return option;
    }
}
