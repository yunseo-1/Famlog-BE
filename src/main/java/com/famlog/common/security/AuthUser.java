package com.famlog.common.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.famlog.auth.jwt.FamlogJwtClaims;

/**
 * 인증된 요청의 사용자. 컨트롤러 메서드 파라미터로 받는다: {@code create(AuthUser user, ...)}.
 *
 * @param userId    users.id (JWT sub)
 * @param sessionId user_sessions.id (JWT sid)
 */
public record AuthUser(long userId, long sessionId) {

    public static Optional<AuthUser> from(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            Object sid = token.getToken().getClaims().get(FamlogJwtClaims.SESSION_ID);
            try {
                return Optional.of(new AuthUser(Long.parseLong(token.getToken().getSubject()),
                        Long.parseLong(String.valueOf(sid))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
