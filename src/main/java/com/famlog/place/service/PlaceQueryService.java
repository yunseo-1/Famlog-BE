package com.famlog.place.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.group.service.GroupAccessChecker;
import com.famlog.place.domain.LocationAccessLog;
import com.famlog.place.domain.LocationPurpose;
import com.famlog.place.domain.Place;
import com.famlog.place.domain.PlaceCategory;
import com.famlog.place.domain.PlaceMark;
import com.famlog.place.dto.PlaceDtos.NearbyListResponse;
import com.famlog.place.dto.PlaceDtos.NearbyPlaceResponse;
import com.famlog.place.dto.PlaceDtos.PlaceListResponse;
import com.famlog.place.dto.PlaceDtos.PlaceResponse;
import com.famlog.place.dto.PlaceDtos.Region1Count;
import com.famlog.place.dto.PlaceDtos.Region2Count;
import com.famlog.place.dto.PlaceDtos.RegionCountResponse;
import com.famlog.place.repository.LocationAccessLogRepository;
import com.famlog.place.repository.PlaceMarkRepository;
import com.famlog.place.repository.PlaceRepository;
import com.famlog.user.domain.ConsentType;
import com.famlog.user.repository.ConsentRepository;

import lombok.RequiredArgsConstructor;

/**
 * 장소 목록 (사양서 4.4 PLC-02·03·04).
 * <ul>
 * <li>목록: 시·도 → 시·군·구, 공유한 사람, 카테고리, 지도 화면 영역을 함께 걸 수 있다. 최신 공유 순 커서 페이징</li>
 * <li>지역별 개수: 공유한 사람·카테고리 필터를 같이 적용한 시·도/시·군·구별 개수 (배지)</li>
 * <li>내 주변: 위치기반서비스 동의(LOCATION)가 있어야 한다 (없으면 403). 현재 좌표는 이 요청에만 쓰고 저장·로그하지 않는다.
 * 쓸 때마다 location_access_logs 에 남긴다 (8.6)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PlaceQueryService {

    public static final int NEARBY_LIMIT = 50;

    private final PlaceRepository placeRepository;
    private final PlaceMarkRepository markRepository;
    private final LocationAccessLogRepository accessLogRepository;
    private final GroupAccessChecker accessChecker;
    private final ConsentRepository consentRepository;
    private final PlaceProperties properties;
    private final Clock clock;

    /** 검색 조건. bounds 는 지도 화면 영역 (없으면 null) */
    public record Filter(String region1, String region2, Set<Long> sharedBy, Set<PlaceCategory> categories,
            Bounds bounds) {
    }

    /** 지도 화면 영역: 남서쪽 ~ 북동쪽 */
    public record Bounds(double swLat, double swLng, double neLat, double neLng) {

        /** 4개를 모두 주거나 모두 빼야 한다 */
        public static Bounds of(Double swLat, Double swLng, Double neLat, Double neLng) {
            long given = Stream.of(swLat, swLng, neLat, neLng).filter(Objects::nonNull).count();
            if (given == 0) {
                return null;
            }
            if (given != 4) {
                throw invalid("지도 영역은 swLat·swLng·neLat·neLng 를 모두 보내 주세요.");
            }
            if (swLat > neLat || swLng > neLng) {
                throw invalid("지도 영역의 남서쪽이 북동쪽보다 커요.");
            }
            return new Bounds(swLat, swLng, neLat, neLng);
        }
    }

    @Transactional(readOnly = true)
    public PlaceListResponse list(long groupId, long userId, Filter filter, String cursor, Integer size) {
        accessChecker.requireMember(groupId, userId);
        if (filter.region2() != null && filter.region1() == null) {
            throw invalid("시·군·구를 고르려면 시·도(region1)도 보내 주세요.");
        }
        int limit = size == null ? properties.defaultPageSize() : Math.min(size, properties.maxPageSize());
        Specification<Place> spec = matching(groupId, filter, true);
        if (cursor != null) {
            long beforeId = decodeCursor(cursor);
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("id"), beforeId));
        }
        List<Place> found = placeRepository.findBy(spec,
                q -> q.sortBy(Sort.by(Sort.Direction.DESC, "id")).limit(limit + 1).all());
        boolean hasMore = found.size() > limit;
        List<Place> page = hasMore ? found.subList(0, limit) : found;
        String next = hasMore ? encodeCursor(page.getLast().getId()) : null;
        return new PlaceListResponse(withMarks(page), next);
    }

    /** 가족이 공유한 장소는 많아야 수백 곳이라 걸러 온 뒤 센다 */
    @Transactional(readOnly = true)
    public RegionCountResponse regions(long groupId, long userId, Set<Long> sharedBy, Set<PlaceCategory> categories) {
        accessChecker.requireMember(groupId, userId);
        List<Place> places = placeRepository.findAll(
                matching(groupId, new Filter(null, null, sharedBy, categories, null), false));
        Comparator<String> nullsLast = Comparator.nullsLast(Comparator.naturalOrder());
        Map<String, List<Place>> byRegion1 = places.stream()
                .collect(Collectors.groupingBy(Place::getRegion1, TreeMap::new, Collectors.toList()));
        List<Region1Count> items = new ArrayList<>();
        byRegion1.forEach((region1, inRegion1) -> {
            Map<String, Long> byRegion2 = new TreeMap<>(nullsLast);
            inRegion1.forEach(place -> byRegion2.merge(place.getRegion2(), 1L, Long::sum));
            List<Region2Count> children = byRegion2.entrySet().stream()
                    .map(entry -> new Region2Count(entry.getKey(), entry.getValue()))
                    .toList();
            items.add(new Region1Count(region1, inRegion1.size(), children));
        });
        return new RegionCountResponse(places.size(), items);
    }

    @Transactional
    public NearbyListResponse nearby(long groupId, long userId, double lat, double lng, double radiusKm) {
        if (!properties.nearbyEnabled()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        accessChecker.requireMember(groupId, userId);
        if (!consentRepository.existsByUserIdAndTypeAndWithdrawnAtIsNull(userId, ConsentType.LOCATION)) {
            throw new BusinessException(ErrorCode.LOCATION_CONSENT_REQUIRED);
        }
        accessLogRepository.save(new LocationAccessLog(userId, LocationPurpose.NEARBY, Instant.now(clock)));
        List<PlaceRepository.NearbyRow> rows = placeRepository.findNearby(groupId, lat, lng, radiusKm * 1000,
                NEARBY_LIMIT);
        Map<Long, Place> places = placeRepository.findAllById(rows.stream().map(PlaceRepository.NearbyRow::getId)
                .toList()).stream().collect(Collectors.toMap(Place::getId, Function.identity()));
        Map<Long, List<PlaceMark>> marks = marksOf(places.keySet());
        List<NearbyPlaceResponse> items = rows.stream()
                .filter(row -> places.containsKey(row.getId()))
                .map(row -> new NearbyPlaceResponse(
                        PlaceResponse.of(places.get(row.getId()), marks.getOrDefault(row.getId(), List.of())),
                        Math.round(row.getDistance())))
                .toList();
        return new NearbyListResponse(items);
    }

    private List<PlaceResponse> withMarks(List<Place> places) {
        Map<Long, List<PlaceMark>> marks = marksOf(places.stream().map(Place::getId).toList());
        return places.stream()
                .map(place -> PlaceResponse.of(place, marks.getOrDefault(place.getId(), List.of())))
                .toList();
    }

    private Map<Long, List<PlaceMark>> marksOf(Collection<Long> placeIds) {
        if (placeIds.isEmpty()) {
            return Map.of();
        }
        return markRepository.findByPlaceIdIn(placeIds).stream()
                .collect(Collectors.groupingBy(PlaceMark::getPlaceId));
    }

    private static Specification<Place> matching(long groupId, Filter filter, boolean withRegionAndBounds) {
        Specification<Place> spec = (root, query, cb) -> cb.equal(root.get("groupId"), groupId);
        if (withRegionAndBounds && filter.region1() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("region1"), filter.region1()));
        }
        if (withRegionAndBounds && filter.region2() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("region2"), filter.region2()));
        }
        if (filter.sharedBy() != null && !filter.sharedBy().isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("sharedBy").in(filter.sharedBy()));
        }
        if (filter.categories() != null && !filter.categories().isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("category").in(filter.categories()));
        }
        Bounds bounds = filter.bounds();
        if (withRegionAndBounds && bounds != null) {
            spec = spec.and((root, query, cb) -> cb.and(
                    cb.between(root.get("lat"), bounds.swLat(), bounds.neLat()),
                    cb.between(root.get("lng"), bounds.swLng(), bounds.neLng())));
        }
        return spec;
    }

    private static String encodeCursor(long id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("P." + id).getBytes(StandardCharsets.US_ASCII));
    }

    private static long decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            if (!raw.startsWith("P.")) {
                throw new IllegalArgumentException();
            }
            return Long.parseLong(raw.substring(2));
        } catch (RuntimeException e) {
            throw invalid("목록 위치(cursor)가 올바르지 않아요.");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
