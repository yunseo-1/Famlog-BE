package com.famlog.place.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;
import com.famlog.place.dto.PlaceDtos.AliasListResponse;
import com.famlog.place.dto.PlaceDtos.AliasResponse;
import com.famlog.place.dto.PlaceDtos.CreateAliasRequest;
import com.famlog.place.service.PlaceAliasService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 별칭 장소 (사양서 4.4 PLC-06, 11.3). 그룹 경로라 GroupPathAccessFilter 가 구성원 여부를 먼저 확인한다. */
@RestController
@RequiredArgsConstructor
public class PlaceAliasController {

    private final PlaceAliasService aliasService;

    @Operation(summary = "별칭 장소 목록", description = "별칭 이름 순")
    @GetMapping("/api/v1/groups/{groupId}/place-aliases")
    AliasListResponse listPlaceAliases(AuthUser user, @PathVariable long groupId) {
        return aliasService.list(groupId, user.userId());
    }

    @Operation(summary = "별칭 장소 만들기", description = "\"할머니 댁\"처럼 검색이 안 되는 곳. 공유한 장소 연결·주소 메모는 선택. "
            + "같은 별칭이 있으면 409 ALIAS_DUPLICATED")
    @PostMapping("/api/v1/groups/{groupId}/place-aliases")
    @ResponseStatus(HttpStatus.CREATED)
    AliasResponse createPlaceAlias(AuthUser user, @PathVariable long groupId,
            @Valid @RequestBody CreateAliasRequest request) {
        return aliasService.create(groupId, user.userId(), request);
    }

    @Operation(summary = "별칭 장소 삭제", description = "만든 사람이나 ADMIN 이상. 다른 그룹 별칭이면 404")
    @DeleteMapping("/api/v1/groups/{groupId}/place-aliases/{aliasId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePlaceAlias(AuthUser user, @PathVariable long groupId, @PathVariable long aliasId) {
        aliasService.delete(groupId, aliasId, user.userId());
    }
}
