package com.famlog.user.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;
import com.famlog.group.dto.GroupDtos.CurrentGroupRequest;
import com.famlog.group.service.GroupService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 현재 가족 그룹 (사양서 4.1 GRP-03). 다음 로그인 때 user.lastGroupId 로 돌려준다. */
@RestController
@RequiredArgsConstructor
public class CurrentGroupController {

    private final GroupService groupService;

    @Operation(summary = "현재 그룹 바꾸기", description = "구성원인 그룹만. 아니면 403 GROUP_NOT_MEMBER")
    @PutMapping("/api/v1/users/me/current-group")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void change(AuthUser user, @Valid @RequestBody CurrentGroupRequest request) {
        groupService.changeCurrentGroup(user.userId(), request.groupId());
    }
}
