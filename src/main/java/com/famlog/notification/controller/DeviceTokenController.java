package com.famlog.notification.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;
import com.famlog.notification.dto.NotificationDtos.RegisterTokenRequest;
import com.famlog.notification.service.DeviceTokenService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 푸시 토큰 (사양서 11.3 NOTI-01). 토큰은 지금 로그인한 기기(세션)에 묶인다. */
@RestController
@RequestMapping("/api/v1/devices/token")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @Operation(summary = "푸시 토큰 등록·갱신",
            description = "앱 시작·토큰 갱신(onNewToken) 때 부른다. 이 기기의 이전 토큰은 바뀌고, 같은 토큰이 다른 계정에 있으면 옮겨진다. "
                    + "로그아웃·원격 로그아웃하면 서버가 지운다")
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void register(AuthUser user, @Valid @RequestBody RegisterTokenRequest request) {
        deviceTokenService.register(user.userId(), user.sessionId(), request.token(), request.appVersion());
    }

    @Operation(summary = "푸시 토큰 삭제", description = "이 기기로 푸시를 받지 않는다. 없으면 그대로 204")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unregister(AuthUser user) {
        deviceTokenService.unregister(user.sessionId());
    }
}
