package com.famlog.place.controller;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;
import com.famlog.place.domain.PlaceCategory;
import com.famlog.place.domain.PlaceMarkKind;
import com.famlog.place.dto.PlaceDtos.NearbyListResponse;
import com.famlog.place.dto.PlaceDtos.PlaceListResponse;
import com.famlog.place.dto.PlaceDtos.PlaceMarksResponse;
import com.famlog.place.dto.PlaceDtos.PlaceResponse;
import com.famlog.place.dto.PlaceDtos.RegionCountResponse;
import com.famlog.place.dto.PlaceDtos.SearchResponse;
import com.famlog.place.dto.PlaceDtos.SharePlaceRequest;
import com.famlog.place.dto.PlaceDtos.UpdatePlaceRequest;
import com.famlog.place.service.PlaceQueryService;
import com.famlog.place.service.PlaceQueryService.Bounds;
import com.famlog.place.service.PlaceQueryService.Filter;
import com.famlog.place.service.PlaceSearchService;
import com.famlog.place.service.PlaceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * 장소 (사양서 4.4 PLC-01~05, 11.3). /groups/{groupId}/** 는 GroupPathAccessFilter 가 구성원 여부를 먼저 확인하고,
 * /places/{id} 는 서비스가 장소의 그룹으로 확인한다 (다른 그룹이면 404).
 * 좌표 파라미터(x·y·lat·lng)는 로그에 남기지 않는다 (사양서 8.6).
 */
@RestController
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceSearchService searchService;
    private final PlaceService placeService;
    private final PlaceQueryService queryService;

    @Operation(summary = "장소 검색 (카카오 로컬)", description = "한 쪽 15개. x·y(현재 위치나 지도 중심)를 주면 가까운 순. "
            + "groupId 를 주면 그 그룹에 이미 공유된 장소에 sharedPlaceId 를 넣는다. 사용자당 분당 30회")
    @GetMapping("/api/v1/places/search")
    SearchResponse searchPlaces(AuthUser user, @RequestParam @NotBlank @Size(max = 100) String query,
            @Parameter(description = "경도") @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180")
            Double x,
            @Parameter(description = "위도") @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90")
            Double y,
            @RequestParam(defaultValue = "1") @Min(1) @Max(PlaceSearchService.MAX_PAGE) int page,
            @RequestParam(required = false) Long groupId) {
        return searchService.search(user.userId(), query, x, y, page, groupId);
    }

    @Operation(summary = "장소 공유", description = "검색 결과의 kakaoPlaceId 로 공유한다 (검색 후 1시간 안, 지나면 400). "
            + "같은 그룹에 이미 있으면 409 PLACE_ALREADY_SHARED. shareToChat(기본 true)이면 채팅방에 장소 카드(PLACE)")
    @PostMapping("/api/v1/groups/{groupId}/places")
    @ResponseStatus(HttpStatus.CREATED)
    PlaceResponse sharePlace(AuthUser user, @PathVariable long groupId,
            @Valid @RequestBody SharePlaceRequest request) {
        return placeService.share(groupId, user.userId(), request);
    }

    @Operation(summary = "장소 목록", description = "최신 공유 순, 커서 페이징. region1(시·도)·region2(시·군·구, region1 필요), "
            + "sharedBy·category(여러 개), 지도 영역(swLat·swLng·neLat·neLng 모두)을 함께 걸 수 있다")
    @GetMapping("/api/v1/groups/{groupId}/places")
    PlaceListResponse listPlaces(AuthUser user, @PathVariable long groupId,
            @RequestParam(required = false) String region1,
            @RequestParam(required = false) String region2,
            @Parameter(description = "공유한 사람 userId 여러 개") @RequestParam(required = false) Set<Long> sharedBy,
            @RequestParam(required = false) Set<PlaceCategory> category,
            @RequestParam(required = false) Double swLat, @RequestParam(required = false) Double swLng,
            @RequestParam(required = false) Double neLat, @RequestParam(required = false) Double neLng,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer size) {
        Filter filter = new Filter(region1, region2, sharedBy, category, Bounds.of(swLat, swLng, neLat, neLng));
        return queryService.list(groupId, user.userId(), filter, cursor, size);
    }

    @Operation(summary = "지역별 장소 개수", description = "시·도 → 시·군·구 배지. sharedBy·category 를 목록과 같이 걸 수 있다")
    @GetMapping("/api/v1/groups/{groupId}/places/regions")
    RegionCountResponse placeRegions(AuthUser user, @PathVariable long groupId,
            @RequestParam(required = false) Set<Long> sharedBy,
            @RequestParam(required = false) Set<PlaceCategory> category) {
        return queryService.regions(groupId, user.userId(), sharedBy, category);
    }

    @Operation(summary = "내 주변 장소", description = "가까운 순 최대 50곳. 현재 위치는 저장하지 않는다. "
            + "기능이 꺼져 있으면 404 (앱은 버튼을 숨긴다)")
    @GetMapping("/api/v1/groups/{groupId}/places/nearby")
    NearbyListResponse nearbyPlaces(AuthUser user, @PathVariable long groupId,
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
            @RequestParam(defaultValue = "3") @DecimalMin("1") @DecimalMax("20") double radiusKm) {
        return queryService.nearby(groupId, user.userId(), lat, lng, radiusKm);
    }

    @Operation(summary = "장소 하나", description = "다른 그룹 장소면 404")
    @GetMapping("/api/v1/places/{placeId}")
    PlaceResponse getPlace(AuthUser user, @PathVariable long placeId) {
        return placeService.get(placeId, user.userId());
    }

    @Operation(summary = "장소 수정", description = "category·oneLineReview·rating 중 보낸 키만 바뀐다 "
            + "(oneLineReview·rating 은 null 이면 지운다). 공유한 사람이나 ADMIN 이상 (403)")
    @PatchMapping("/api/v1/places/{placeId}")
    PlaceResponse updatePlace(AuthUser user, @PathVariable long placeId,
            @Valid @RequestBody UpdatePlaceRequest request) {
        return placeService.update(placeId, user.userId(), request);
    }

    @Operation(summary = "장소 삭제", description = "공유한 사람이나 ADMIN 이상. 일정·별칭의 연결은 비워지고, 채팅 카드는 남는다")
    @DeleteMapping("/api/v1/places/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePlace(AuthUser user, @PathVariable long placeId) {
        placeService.delete(placeId, user.userId());
    }

    @Operation(summary = "다녀옴·가고 싶어요 표시", description = "내 표시를 켠다 (이미 켜져 있어도 200)")
    @PutMapping("/api/v1/places/{placeId}/marks/{kind}")
    PlaceMarksResponse markPlace(AuthUser user, @PathVariable long placeId, @PathVariable PlaceMarkKind kind) {
        return placeService.mark(placeId, user.userId(), kind);
    }

    @Operation(summary = "다녀옴·가고 싶어요 해제", description = "내 표시를 끈다 (이미 꺼져 있어도 200)")
    @DeleteMapping("/api/v1/places/{placeId}/marks/{kind}")
    PlaceMarksResponse unmarkPlace(AuthUser user, @PathVariable long placeId, @PathVariable PlaceMarkKind kind) {
        return placeService.unmark(placeId, user.userId(), kind);
    }
}
