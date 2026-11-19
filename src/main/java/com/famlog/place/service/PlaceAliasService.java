package com.famlog.place.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.group.domain.GroupRole;
import com.famlog.group.service.GroupAccessChecker;
import com.famlog.place.domain.Place;
import com.famlog.place.domain.PlaceAlias;
import com.famlog.place.dto.PlaceDtos.AliasListResponse;
import com.famlog.place.dto.PlaceDtos.AliasResponse;
import com.famlog.place.dto.PlaceDtos.CreateAliasRequest;
import com.famlog.place.dto.PlaceDtos.PlaceSummary;
import com.famlog.place.repository.PlaceAliasRepository;
import com.famlog.place.repository.PlaceRepository;

import lombok.RequiredArgsConstructor;

/**
 * 별칭 장소 (사양서 4.4 PLC-06). 그룹 안에서만 보이고, 같은 그룹에 같은 별칭은 하나.
 * 만드는 건 구성원 누구나, 지우는 건 만든 사람이나 ADMIN 이상.
 */
@Service
@RequiredArgsConstructor
public class PlaceAliasService {

    private final PlaceAliasRepository aliasRepository;
    private final PlaceRepository placeRepository;
    private final GroupAccessChecker accessChecker;

    @Transactional(readOnly = true)
    public AliasListResponse list(long groupId, long userId) {
        accessChecker.requireMember(groupId, userId);
        // DB 정렬 규칙(collation)에 따라 한글 순서가 달라져서 가나다 순(유니코드 순)으로 직접 정렬한다
        List<PlaceAlias> aliases = aliasRepository.findByGroupId(groupId).stream()
                .sorted(Comparator.comparing(PlaceAlias::getAlias))
                .toList();
        Map<Long, Place> places = placeRepository.findAllById(aliases.stream().map(PlaceAlias::getPlaceId)
                .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));
        return new AliasListResponse(aliases.stream()
                .map(alias -> AliasResponse.of(alias, summaryOf(places.get(alias.getPlaceId()))))
                .toList());
    }

    @Transactional
    public AliasResponse create(long groupId, long userId, CreateAliasRequest request) {
        accessChecker.requireMember(groupId, userId);
        String name = request.alias().strip();
        Place place = null;
        if (request.placeId() != null) {
            place = placeRepository.findById(request.placeId())
                    .filter(found -> found.getGroupId() == groupId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "연결할 장소는 우리 가족이 공유한 장소여야 해요."));
        }
        if (aliasRepository.existsByGroupIdAndAlias(groupId, name)) {
            throw new BusinessException(ErrorCode.ALIAS_DUPLICATED);
        }
        String address = request.addressText() == null || request.addressText().isBlank() ? null
                : request.addressText().strip();
        PlaceAlias alias;
        try {
            alias = aliasRepository.saveAndFlush(new PlaceAlias(groupId, userId, name, request.placeId(), address));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ALIAS_DUPLICATED);
        }
        return AliasResponse.of(alias, summaryOf(place));
    }

    /** 다른 그룹 별칭 id 면 404 */
    @Transactional
    public void delete(long groupId, long aliasId, long userId) {
        GroupRole role = accessChecker.requireMember(groupId, userId);
        PlaceAlias alias = aliasRepository.findById(aliasId)
                .filter(found -> found.getGroupId() == groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!alias.isDeletableBy(userId, role)) {
            throw new BusinessException(ErrorCode.GROUP_ROLE_REQUIRED, "내가 만든 별칭만 지울 수 있어요.");
        }
        aliasRepository.delete(alias);
    }

    private static PlaceSummary summaryOf(Place place) {
        return place == null ? null : PlaceSummary.from(place);
    }
}
