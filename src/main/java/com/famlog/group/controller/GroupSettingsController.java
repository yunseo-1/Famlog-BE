package com.famlog.group.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;
import com.famlog.group.dto.GroupDtos.GroupSettingsResponse;
import com.famlog.group.dto.GroupDtos.UpdateGroupSettingsRequest;
import com.famlog.group.service.GroupSettingsService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 그룹 설정 (사양서 4.1 SET-01). /groups/{groupId}/** 는 GroupPathAccessFilter가 구성원 여부를 먼저 확인한다. */
@RestController
@RequiredArgsConstructor
public class GroupSettingsController {

    private final GroupSettingsService settingsService;

    @Operation(summary = "그룹 설정", description = "AI 분석 켜짐 여부. 설정한 적이 없으면 꺼짐")
    @GetMapping("/api/v1/groups/{groupId}/settings")
    GroupSettingsResponse get(AuthUser user, @PathVariable long groupId) {
        return settingsService.get(groupId, user.userId());
    }

    @Operation(summary = "그룹 설정 바꾸기", description = "ADMIN 이상. AI 분석을 켜면 그 뒤 메시지부터 분석한다 (켜기 전 대화는 보내지 않는다)")
    @PatchMapping("/api/v1/groups/{groupId}/settings")
    GroupSettingsResponse update(AuthUser user, @PathVariable long groupId,
            @Valid @RequestBody UpdateGroupSettingsRequest request) {
        return settingsService.update(groupId, user.userId(), request);
    }
}
