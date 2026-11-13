package com.famlog.user.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.auth.service.SessionTokenService;
import com.famlog.common.security.AuthUser;
import com.famlog.user.dto.SessionResponse;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

/** 로그인 기기 관리 (사양서 4.1 AUTH-03, 11.3). */
@RestController
@RequestMapping("/api/v1/users/me/sessions")
@RequiredArgsConstructor
public class UserSessionController {

    private final SessionTokenService sessionTokenService;

    @Operation(summary = "로그인한 기기 목록", description = "최근 사용 순. current=true가 지금 기기")
    @GetMapping
    List<SessionResponse> list(AuthUser user) {
        return sessionTokenService.listActiveSessions(user.userId()).stream()
                .map(session -> SessionResponse.from(session, user.sessionId()))
                .toList();
    }

    @Operation(summary = "기기 로그아웃", description = "그 기기의 다음 API 호출부터 401. 내 세션이 아니면 404")
    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(AuthUser user, @PathVariable long sessionId) {
        sessionTokenService.revokeOwnSession(user.userId(), sessionId);
    }
}
