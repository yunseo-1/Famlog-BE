package com.famlog.place.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.error.RateLimitedException;
import com.famlog.common.ratelimit.RateLimiter;
import com.famlog.group.service.GroupAccessChecker;
import com.famlog.place.domain.LocationAccessLog;
import com.famlog.place.domain.LocationPurpose;
import com.famlog.place.domain.Place;
import com.famlog.place.dto.PlaceDtos.SearchItem;
import com.famlog.place.dto.PlaceDtos.SearchResponse;
import com.famlog.place.kakao.KakaoAddress;
import com.famlog.place.kakao.KakaoLocalClient;
import com.famlog.place.kakao.KakaoLocalClient.Document;
import com.famlog.place.kakao.KakaoLocalClient.SearchResult;
import com.famlog.place.repository.LocationAccessLogRepository;
import com.famlog.place.repository.PlaceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 카카오 로컬 검색 프록시 (사양서 11.3 PLC-01). 키는 서버에만 있다.
 * <ul>
 * <li>검색 결과의 장소는 하나씩 Redis 에 남겨 두고, 공유할 때 그 값을 쓴다 (앱이 보낸 이름·좌표를 믿지 않는다)</li>
 * <li>위치(x, y) 없이 검색한 결과 목록은 잠깐 캐시한다. 위치가 있는 검색은 목록을 캐시하지 않는다 (위치를 남기지 않게)</li>
 * <li>위치를 함께 보내면 개인위치정보 이용으로 보고 location_access_logs 에 남긴다 (사양서 8.6)</li>
 * </ul>
 * 검색어·좌표는 로그에 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceSearchService {

    /** 카카오는 45쪽까지 준다 */
    public static final int MAX_PAGE = 45;

    private static final String DOCUMENT_KEY = "place:kakao-doc:";
    private static final String SEARCH_KEY = "place:search:";

    private final KakaoLocalClient kakao;
    private final StringRedisTemplate redis;
    private final RateLimiter rateLimiter;
    private final GroupAccessChecker accessChecker;
    private final PlaceRepository placeRepository;
    private final LocationAccessLogRepository accessLogRepository;
    private final PlaceProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public SearchResponse search(long userId, String query, Double x, Double y, int page, Long groupId) {
        if ((x == null) != (y == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "x 와 y 는 함께 보내 주세요.");
        }
        if (groupId != null) {
            accessChecker.requireMember(groupId, userId);
        }
        checkRate(userId);
        boolean located = x != null;
        if (located) {
            accessLogRepository.save(new LocationAccessLog(userId, LocationPurpose.PLACE_SEARCH, Instant.now(clock)));
        }
        String normalized = query.strip();
        SearchResult result = located ? kakao.search(normalized, x, y, page) : cachedSearch(normalized, page);
        List<Document> documents = result.documents().stream()
                .filter(doc -> doc.id() != null && doc.addressName() != null && !doc.addressName().isBlank())
                .toList();
        documents.forEach(this::remember);

        Map<String, Long> shared = groupId == null ? Map.of()
                : placeRepository.findByGroupIdAndKakaoPlaceIdIn(groupId, documents.stream().map(Document::id).toList())
                        .stream().collect(Collectors.toMap(Place::getKakaoPlaceId, Place::getId));
        List<SearchItem> items = documents.stream()
                .map(doc -> toItem(doc, located, shared.get(doc.id())))
                .toList();
        return new SearchResponse(items, !result.end() && page < MAX_PAGE);
    }

    /** 최근 검색 결과에 있던 장소 (공유용). 없거나 오래됐으면 empty */
    public Optional<Document> recentDocument(String kakaoPlaceId) {
        return read(DOCUMENT_KEY + kakaoPlaceId, Document.class);
    }

    /** 카카오 장소를 저장할 값으로 바꾼다 */
    public Place.KakaoInfo infoOf(Document doc) {
        KakaoAddress region = KakaoAddress.parse(doc.addressName());
        return new Place.KakaoInfo(doc.id(), truncate(doc.placeName(), 100), truncate(categoryDetail(doc), 100),
                truncate(addressOf(doc), 200), region.region1(), region.region2(), region.region3(),
                Double.parseDouble(doc.y()), Double.parseDouble(doc.x()), truncate(blankToNull(doc.phone()), 30),
                truncate(blankToNull(doc.placeUrl()), 300));
    }

    private SearchItem toItem(Document doc, boolean located, Long sharedPlaceId) {
        Place.KakaoInfo info = infoOf(doc);
        Integer distance = located && doc.distance() != null && !doc.distance().isBlank()
                ? Integer.valueOf(doc.distance()) : null;
        return new SearchItem(info.kakaoPlaceId(), info.name(), info.categoryDetail(),
                properties.categoryOf(doc.categoryGroupCode()), info.address(), info.region1(), info.region2(),
                info.region3(), info.lat(), info.lng(), info.phone(), info.placeUrl(), distance, sharedPlaceId);
    }

    private SearchResult cachedSearch(String query, int page) {
        String key = SEARCH_KEY + sha256(query) + ":" + page;
        Optional<SearchResult> cached = read(key, SearchResult.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        SearchResult result = kakao.search(query, null, null, page);
        write(key, result, properties.searchCacheTtl());
        return result;
    }

    private void remember(Document doc) {
        write(DOCUMENT_KEY + doc.id(), doc, properties.documentCacheTtl());
    }

    /** Redis 장애 때는 캐시 없이 동작한다 (공유만 "다시 검색" 이 된다) */
    private <T> Optional<T> read(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(jsonMapper.readValue(json, type));
        } catch (RuntimeException e) {
            log.warn("장소 캐시를 읽지 못했습니다: error={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void write(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, jsonMapper.writeValueAsString(value), ttl);
        } catch (RuntimeException e) {
            log.warn("장소 캐시를 쓰지 못했습니다: error={}", e.getClass().getSimpleName());
        }
    }

    /** Redis 장애 시에는 제한 없이 통과 (다른 요청 제한과 같은 정책) */
    private void checkRate(long userId) {
        RateLimiter.Result result;
        try {
            result = rateLimiter.tryConsume("rate-limit:place-search:" + userId, properties.searchRateCapacity(),
                    properties.searchRatePeriod());
        } catch (RuntimeException e) {
            log.warn("장소 검색 제한 확인 실패로 제한 없이 통과시킵니다: error={}", e.getClass().getSimpleName());
            return;
        }
        if (!result.allowed()) {
            throw new RateLimitedException("검색을 너무 자주 하고 있어요. 잠시 후 다시 검색해 주세요.",
                    result.retryAfterSeconds());
        }
    }

    /** "음식점 > 한식 > 냉면" → "냉면" */
    private static String categoryDetail(Document doc) {
        if (doc.categoryName() == null || doc.categoryName().isBlank()) {
            return null;
        }
        String[] parts = doc.categoryName().split(">");
        return parts[parts.length - 1].strip();
    }

    private static String addressOf(Document doc) {
        return doc.roadAddressName() != null && !doc.roadAddressName().isBlank() ? doc.roadAddressName()
                : doc.addressName();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
