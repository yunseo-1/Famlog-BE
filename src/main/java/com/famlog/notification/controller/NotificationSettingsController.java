package com.famlog.notification.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;
import com.famlog.notification.dto.NotificationDtos.GroupPreferencesRequest;
import com.famlog.notification.dto.NotificationDtos.GroupPreferencesResponse;
import com.famlog.notification.dto.NotificationDtos.NotificationSettingsRequest;
import com.famlog.notification.dto.NotificationDtos.NotificationSettingsResponse;
import com.famlog.notification.service.NotificationSettingsService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 알림 설정 (사양서 4.1 SET-02). /groups/{groupId}/** 는 GroupPathAccessFilter가 구성원 여부를 먼저 확인한다. */
@RestController
@RequiredArgsConstructor
public class NotificationSettingsController {

    private final NotificationSettingsService settingsService;

    @Operation(summary = "내 알림 설정", description = "미리보기 숨기기·방해 금지 시간 (모든 그룹에 적용)")
    @GetMapping("/api/v1/users/me/notification-settings")
    NotificationSettingsResponse get(AuthUser user) {
        return settingsService.get(user.userId());
    }

    @Operation(summary = "내 알림 설정 바꾸기", description = "보낸 값만 바꾼다. 시작과 끝 시각이 같으면 400")
    @PatchMapping("/api/v1/users/me/notification-settings")
    NotificationSettingsResponse update(AuthUser user, @Valid @RequestBody NotificationSettingsRequest request) {
        return settingsService.update(user.userId(), request);
    }

    @Operation(summary = "이 그룹 알림·AI 분석 제외 설정")
    @GetMapping("/api/v1/groups/{groupId}/members/me/preferences")
    GroupPreferencesResponse groupPreferences(AuthUser user, @PathVariable long groupId) {
        return settingsService.groupPreferences(groupId, user.userId());
    }

    @Operation(summary = "이 그룹 알림·AI 분석 제외 바꾸기",
            description = "보낸 값만 바꾼다. 알림을 끄면 새 메시지 푸시가 오지 않고, AI 분석 제외를 켜면 내 메시지를 AI 분석에 보내지 않는다 "
                    + "(제외했던 동안의 메시지는 다시 켜도 보내지 않는다)")
    @PatchMapping("/api/v1/groups/{groupId}/members/me/preferences")
    GroupPreferencesResponse updateGroupPreferences(AuthUser user, @PathVariable long groupId,
            @Valid @RequestBody GroupPreferencesRequest request) {
        return settingsService.updateGroupPreferences(groupId, user.userId(), request);
    }
}
