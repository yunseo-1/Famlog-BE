package com.famlog.auth.jwt;

import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import com.famlog.auth.service.SessionRevocationStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 폐기된 세션의 Access 토큰을 거부한다. Redis를 확인할 수 없으면 거부한다 (보안 기능이므로 fail-closed).
 */
@Slf4j
@RequiredArgsConstructor
public class SessionRevocationValidator implements OAuth2TokenValidator<Jwt> {

    private final SessionRevocationStore store;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String sid = AccessTokenValidators.stringClaim(jwt, FamlogJwtClaims.SESSION_ID);
        if (sid == null) {
            return AccessTokenValidators.failure(OAuth2ErrorCodes.INVALID_TOKEN, "sid 클레임이 없습니다");
        }
        try {
            if (store.isRevoked(Long.parseLong(sid))) {
                return AccessTokenValidators.failure(OAuth2ErrorCodes.INVALID_TOKEN, "폐기된 세션입니다");
            }
            return OAuth2TokenValidatorResult.success();
        } catch (NumberFormatException e) {
            return AccessTokenValidators.failure(OAuth2ErrorCodes.INVALID_TOKEN, "sid 클레임이 올바르지 않습니다");
        } catch (RuntimeException e) {
            log.warn("세션 폐기 여부를 확인하지 못해 요청을 거부합니다: {}", e.getClass().getSimpleName());
            return AccessTokenValidators.failure(OAuth2ErrorCodes.INVALID_TOKEN, "세션 상태를 확인할 수 없습니다");
        }
    }
}
