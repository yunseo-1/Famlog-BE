package com.famlog.user.dto;

import java.time.Instant;

import com.famlog.auth.domain.Platform;
import com.famlog.auth.domain.UserSession;

/**
 * 로그인 기기 (AUTH-03).
 *
 * @param lastUsedAt 마지막 토큰 재발급 시각 (Access 수명 30분 단위의 근사값)
 * @param current    지금 이 요청을 보낸 기기
 */
public record SessionResponse(long sessionId, String deviceName, Platform platform, Instant lastUsedAt,
        Instant createdAt, boolean current) {

    public static SessionResponse from(UserSession session, long currentSessionId) {
        return new SessionResponse(session.getId(), session.getDeviceName(), session.getPlatform(),
                session.getLastUsedAt(), session.getCreatedAt(), session.getId() == currentSessionId);
    }
}
