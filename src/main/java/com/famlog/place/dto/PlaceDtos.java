package com.famlog.place.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.famlog.common.source.SourceRef;
import com.famlog.place.domain.Place;
import com.famlog.place.domain.PlaceAlias;
import com.famlog.place.domain.PlaceCategory;
import com.famlog.place.domain.PlaceMark;
import com.famlog.place.domain.PlaceMarkKind;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 장소·별칭 API 요청·응답 (사양서 11.3 PLC-01~06). */
public final class PlaceDtos {

    private PlaceDtos() {
    }

    /** 카카오 검색 결과 하나. 공유할 때는 kakaoPlaceId 만 보낸다 */
    public record SearchItem(String kakaoPlaceId, String name,
            @Schema(description = "카카오 세부 분류 (예: 냉면)") String categoryDetail,
            @Schema(description = "카카오 분류로 고른 카테고리 (공유할 때 바꿀 수 있다)") PlaceCategory suggestedCategory,
            @Schema(description = "도로명 주소, 없으면 지번 주소") String address, String region1, String region2,
            String region3, double lat, double lng, String phone, String placeUrl,
            @Schema(description = "x·y 로 검색했을 때 거리(m)") Integer distanceM,
            @Schema(description = "groupId 를 주었고 그 그룹에 이미 공유된 장소면 그 id") Long sharedPlaceId) {
    }

    /** @param hasMore 다음 page 가 있다 */
    public record SearchResponse(List<SearchItem> items, boolean hasMore) {
    }

    public record SharePlaceRequest(
            @Schema(description = "검색 결과의 kakaoPlaceId (검색 후 1시간 안에 공유)") @NotBlank @Size(max = 30)
            String kakaoPlaceId,
            @NotNull PlaceCategory category,
            @Schema(description = "한줄평") @Size(max = 100) String oneLineReview,
            @Schema(description = "별점 1~5 (선택)") @Min(1) @Max(5) Integer rating,
            @Schema(description = "채팅방에 장소 카드로 올린다 (기본 true)") Boolean shareToChat) {
    }

    /**
     * 수정: 보낸 키만 바뀐다. rating·oneLineReview 는 null 을 보내면 지운다. category 는 null 이면 그대로.
     * (키를 뺀 것과 null 을 구분하려고 record 대신 기본 생성자 클래스를 쓴다)
     */
    @Getter
    @NoArgsConstructor
    public static final class UpdatePlaceRequest {

        private PlaceCategory category;

        @Schema(description = "null 이면 지운다", type = "string", nullable = true)
        private Optional<@Size(max = 100) String> oneLineReview;

        @Schema(description = "1~5, null 이면 지운다", type = "integer", nullable = true)
        private Optional<@Min(1) @Max(5) Integer> rating;
    }

    /** @param visited 다녀온 구성원 userId, want 가고 싶은 구성원 userId (각각 id 순) */
    public record PlaceMarksResponse(List<Long> visited, List<Long> want) {

        public static PlaceMarksResponse of(List<PlaceMark> marks) {
            return new PlaceMarksResponse(users(marks, PlaceMarkKind.VISITED), users(marks, PlaceMarkKind.WANT));
        }

        private static List<Long> users(List<PlaceMark> marks, PlaceMarkKind kind) {
            return marks.stream().filter(mark -> mark.getKind() == kind).map(PlaceMark::getUserId)
                    .sorted(Comparator.naturalOrder()).toList();
        }
    }

    public record PlaceResponse(long id, long groupId, String kakaoPlaceId, String name, PlaceCategory category,
            String categoryDetail, String address, String region1, String region2, String region3, double lat,
            double lng, String phone, String placeUrl, Integer rating, String oneLineReview, long sharedBy,
            PlaceMarksResponse marks, Instant createdAt, Instant updatedAt, SourceRef source) {

        public static PlaceResponse of(Place place, List<PlaceMark> marks, SourceRef source) {
            return new PlaceResponse(place.getId(), place.getGroupId(), place.getKakaoPlaceId(), place.getName(),
                    place.getCategory(), place.getCategoryDetail(), place.getAddress(), place.getRegion1(),
                    place.getRegion2(), place.getRegion3(), place.getLat(), place.getLng(), place.getPhone(),
                    place.getPlaceUrl(), place.getRating(), place.getOneLineReview(), place.getSharedBy(),
                    PlaceMarksResponse.of(marks), place.getCreatedAt(), place.getUpdatedAt(), source);
        }
    }

    /** 최신 공유 순 */
    public record PlaceListResponse(List<PlaceResponse> items, String nextCursor) {
    }

    /** @param distanceM 기준점에서 거리(m, 반올림) */
    public record NearbyPlaceResponse(PlaceResponse place, long distanceM) {
    }

    /** 가까운 순 (최대 50) */
    public record NearbyListResponse(List<NearbyPlaceResponse> items) {
    }

    /** @param region2 시·군·구 (세종처럼 없으면 null) */
    public record Region2Count(String region2, long count) {
    }

    public record Region1Count(String region1, long count, List<Region2Count> children) {
    }

    /** 시·도 이름 순, 그 안의 시·군·구 이름 순 (없음은 맨 뒤) */
    public record RegionCountResponse(long total, List<Region1Count> items) {
    }

    /** 일정·별칭에 붙이는 장소 요약 */
    public record PlaceSummary(long id, String name, String address, PlaceCategory category) {

        public static PlaceSummary from(Place place) {
            return new PlaceSummary(place.getId(), place.getName(), place.getAddress(), place.getCategory());
        }
    }

    public record CreateAliasRequest(
            @Schema(example = "할머니 댁") @NotBlank @Size(max = 30) String alias,
            @Schema(description = "연결할 공유 장소 (이 그룹 장소, 선택)") Long placeId,
            @Schema(description = "주소 메모 (선택)") @Size(max = 200) String addressText) {
    }

    public record AliasResponse(long id, String alias,
            @Schema(description = "연결한 장소 (없거나 지워졌으면 null)") PlaceSummary place, String addressText,
            long createdBy, Instant createdAt) {

        public static AliasResponse of(PlaceAlias alias, PlaceSummary place) {
            return new AliasResponse(alias.getId(), alias.getAlias(), place, alias.getAddressText(),
                    alias.getCreatedBy(), alias.getCreatedAt());
        }
    }

    /** 별칭 이름 순 */
    public record AliasListResponse(List<AliasResponse> items) {
    }
}
