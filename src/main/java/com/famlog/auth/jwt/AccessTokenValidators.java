package com.famlog.auth.jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Access JWT 검증 규칙. 오류 코드로 만료(AUTH_TOKEN_EXPIRED)와 그 밖의 무효(AUTH_INVALID)를 구분한다.
 */
public final class AccessTokenValidators {

    /** 만료. 앱은 이 경우에만 Refresh 재발급을 시도한다 */
    public static final String TOKEN_EXPIRED = "famlog_token_expired";

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private AccessTokenValidators() {
    }

    /** exp 필수, 서버 시계 오차 30초까지 허용 */
    public static OAuth2TokenValidator<Jwt> expiry(Clock clock) {
        return jwt -> {
            Instant expiresAt = jwt.getExpiresAt();
            if (expiresAt == null) {
                return failure(OAuth2ErrorCodes.INVALID_TOKEN, "exp 클레임이 없습니다");
            }
            if (Instant.now(clock).isAfter(expiresAt.plus(CLOCK_SKEW))) {
                return failure(TOKEN_EXPIRED, "토큰이 만료됐습니다");
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    /** sub(사용자 ID)와 sid(세션 ID)가 숫자로 들어 있어야 한다 */
    public static OAuth2TokenValidator<Jwt> requiredClaims() {
        return jwt -> {
            if (!isLong(jwt.getSubject()) || !isLong(stringClaim(jwt, FamlogJwtClaims.SESSION_ID))) {
                return failure(OAuth2ErrorCodes.INVALID_TOKEN, "sub 또는 sid 클레임이 올바르지 않습니다");
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    static String stringClaim(Jwt jwt, String name) {
        Object value = jwt.getClaims().get(name);
        return value == null ? null : value.toString();
    }

    static OAuth2TokenValidatorResult failure(String code, String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
    }

    private static boolean isLong(String value) {
        if (value == null) {
            return false;
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
