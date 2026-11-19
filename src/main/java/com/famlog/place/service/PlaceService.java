package com.famlog.place.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.chat.service.ChatService;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.source.ItemSources;
import com.famlog.group.domain.GroupRole;
import com.famlog.group.service.GroupAccessChecker;
import com.famlog.place.domain.Place;
import com.famlog.place.domain.PlaceCategory;
import com.famlog.place.domain.PlaceMark;
import com.famlog.place.domain.PlaceMarkKind;
import com.famlog.place.dto.PlaceDtos.PlaceMarksResponse;
import com.famlog.place.dto.PlaceDtos.PlaceResponse;
import com.famlog.place.dto.PlaceDtos.SharePlaceRequest;
import com.famlog.place.dto.PlaceDtos.UpdatePlaceRequest;
import com.famlog.place.kakao.KakaoLocalClient.Document;
import com.famlog.place.repository.PlaceMarkRepository;
import com.famlog.place.repository.PlaceRepository;

import lombok.RequiredArgsConstructor;

/**
 * 장소 공유·수정·삭제와 다녀옴·가고 싶어요 (사양서 4.4 PLC-01·05, 2.2 권한, 9.3 인가).
 * <ul>
 * <li>공유는 최근 검색 결과에 있던 카카오 장소만 (이름·주소·좌표는 서버가 가진 검색 결과 값)</li>
 * <li>같은 그룹에 같은 카카오 장소는 하나 (409 PLACE_ALREADY_SHARED)</li>
 * <li>/places/{id}: 다른 그룹(또는 나간 그룹) 장소면 404. 고치고 지우는 건 공유한 사람이나 ADMIN 이상</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PlaceService {

    private final PlaceRepository placeRepository;
    private final PlaceMarkRepository markRepository;
    private final PlaceSearchService searchService;
    private final GroupAccessChecker accessChecker;
    private final ChatService chatService;
    private final ItemSources itemSources;

    @Transactional
    public PlaceResponse share(long groupId, long userId, SharePlaceRequest request) {
        accessChecker.requireMember(groupId, userId);
        Document doc = searchService.recentDocument(request.kakaoPlaceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "검색한 지 오래된 장소예요. 다시 검색해서 골라 주세요."));
        if (placeRepository.existsByGroupIdAndKakaoPlaceId(groupId, doc.id())) {
            throw new BusinessException(ErrorCode.PLACE_ALREADY_SHARED);
        }
        Place place;
        try {
            place = placeRepository.saveAndFlush(new Place(groupId, userId, searchService.infoOf(doc),
                    request.category(), request.rating(), blankToNull(request.oneLineReview())));
        } catch (DataIntegrityViolationException e) {
            // 같은 장소를 거의 동시에 공유한 경우
            throw new BusinessException(ErrorCode.PLACE_ALREADY_SHARED);
        }
        if (!Boolean.FALSE.equals(request.shareToChat())) {
            chatService.postPlace(groupId, userId, place.getName(), cardOf(place));
        }
        return PlaceResponse.of(place, List.of(), null);
    }

    /** @param created 새로 공유했는지 (false 면 이미 공유된 장소에 연결) */
    public record Registered(Place place, boolean created) {
    }

    /**
     * AI 후보 승인으로 공유한다 (사양서 6.2 ⑤). 후보에 저장해 둔 카카오 장소 값을 쓴다.
     * 이미 우리 가족이 공유한 장소면 새로 만들지 않고 그 장소를 돌려준다. 채팅 장소 카드는 올리지 않는다 (승인 시스템 메시지가 대신한다)
     */
    @Transactional
    public Registered registerFromCandidate(long groupId, long userId, Place.KakaoInfo info, PlaceCategory category,
            String oneLineReview) {
        accessChecker.requireMember(groupId, userId);
        List<Place> existing = placeRepository.findByGroupIdAndKakaoPlaceIdIn(groupId, List.of(info.kakaoPlaceId()));
        if (!existing.isEmpty()) {
            return new Registered(existing.get(0), false);
        }
        return new Registered(placeRepository.saveAndFlush(new Place(groupId, userId, info, category, null,
                blankToNull(oneLineReview))), true);
    }

    @Transactional(readOnly = true)
    public PlaceResponse get(long placeId, long userId) {
        Place place = find(placeId);
        accessChecker.requireMemberOrNotFound(place.getGroupId(), userId);
        return PlaceResponse.of(place, markRepository.findByPlaceIdIn(List.of(placeId)),
                itemSources.find(ItemSources.PLACE, placeId));
    }

    @Transactional
    public PlaceResponse update(long placeId, long userId, UpdatePlaceRequest request) {
        Place place = editable(placeId, userId);
        if (request.getCategory() != null) {
            place.changeCategory(request.getCategory());
        }
        if (request.getRating() != null) {
            place.changeRating(request.getRating().orElse(null));
        }
        if (request.getOneLineReview() != null) {
            place.changeOneLineReview(request.getOneLineReview().map(PlaceService::blankToNull).orElse(null));
        }
        placeRepository.saveAndFlush(place);
        return PlaceResponse.of(place, markRepository.findByPlaceIdIn(List.of(placeId)),
                itemSources.find(ItemSources.PLACE, placeId));
    }

    /** 일정·별칭의 연결은 비워지고 표시는 함께 지워진다 (FK). 채팅의 장소 카드는 남는다 */
    @Transactional
    public void delete(long placeId, long userId) {
        placeRepository.delete(editable(placeId, userId));
        itemSources.removeAll(ItemSources.PLACE, placeId);
    }

    /** 내 표시 켜기. 이미 켜져 있어도 그대로 성공 */
    @Transactional
    public PlaceMarksResponse mark(long placeId, long userId, PlaceMarkKind kind) {
        Place place = visible(placeId, userId);
        PlaceMark.Key key = new PlaceMark.Key(place.getId(), userId, kind);
        if (!markRepository.existsById(key)) {
            markRepository.saveAndFlush(new PlaceMark(place.getId(), userId, kind));
        }
        return PlaceMarksResponse.of(markRepository.findByPlaceIdIn(List.of(placeId)));
    }

    /** 내 표시 끄기. 이미 꺼져 있어도 그대로 성공 */
    @Transactional
    public PlaceMarksResponse unmark(long placeId, long userId, PlaceMarkKind kind) {
        Place place = visible(placeId, userId);
        markRepository.deleteById(new PlaceMark.Key(place.getId(), userId, kind));
        markRepository.flush();
        return PlaceMarksResponse.of(markRepository.findByPlaceIdIn(List.of(placeId)));
    }

    private Place visible(long placeId, long userId) {
        Place place = find(placeId);
        accessChecker.requireMemberOrNotFound(place.getGroupId(), userId);
        return place;
    }

    private Place editable(long placeId, long userId) {
        Place place = find(placeId);
        GroupRole role = accessChecker.requireMemberOrNotFound(place.getGroupId(), userId);
        if (!place.isEditableBy(userId, role)) {
            throw new BusinessException(ErrorCode.GROUP_ROLE_REQUIRED, "내가 공유한 장소만 고치거나 지울 수 있어요.");
        }
        return place;
    }

    private Place find(long placeId) {
        return placeRepository.findById(placeId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /** 채팅 장소 카드: 공유한 순간의 값 (나중에 장소를 고치거나 지워도 카드는 그대로) */
    private static Map<String, Object> cardOf(Place place) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("placeId", place.getId());
        card.put("name", place.getName());
        card.put("category", place.getCategory().name());
        card.put("address", place.getAddress());
        card.put("region1", place.getRegion1());
        card.put("region2", place.getRegion2());
        card.put("rating", place.getRating());
        return card;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
