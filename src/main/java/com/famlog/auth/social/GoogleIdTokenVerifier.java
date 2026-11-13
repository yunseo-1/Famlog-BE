package com.famlog.auth.social;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.user.domain.SocialProvider;

import lombok.extern.slf4j.Slf4j;

/**
 * Google ID 토큰 검증 (사양서 9.1): Google 공개 키(JWKS)로 RS256 서명, iss, aud(우리 클라이언트 ID), exp를 확인하고
 * 회원 번호(sub)만 쓴다. 토큰의 email·name 클레임은 저장하지 않는다.
 */
@Slf4j
public class GoogleIdTokenVerifier implements SocialTokenVerifier {

    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final JwtDecoder decoder;
    private final boolean configured;

    /** @param decoder {@link #validator(Collection)}가 적용된 디코더 */
    public GoogleIdTokenVerifier(JwtDecoder decoder, boolean configured) {
        this.decoder = decoder;
        this.configured = configured;
    }

    public static OAuth2TokenValidator<Jwt> validator(Collection<String> clientIds) {
        Set<String> allowed = Set.copyOf(clientIds);
        return new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtTimestampValidator(),
                new JwtClaimValidator<String>(JwtClaimNames.ISS, ISSUERS::contains),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.stream().anyMatch(allowed::contains)),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank())));
    }

    @Override
    public SocialProvider provider() {
        return SocialProvider.GOOGLE;
    }

    @Override
    public SocialIdentity verify(String providerToken) {
        if (!configured) {
            log.warn("Google 클라이언트 ID(famlog.social.google.client-ids)가 설정되지 않아 Google 로그인을 처리할 수 없습니다");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        try {
            return new SocialIdentity(SocialProvider.GOOGLE, decoder.decode(providerToken).getSubject());
        } catch (BadJwtException e) {
            throw new BusinessException(ErrorCode.AUTH_INVALID);
        } catch (JwtException e) {
            // 공개 키(JWKS)를 가져오지 못한 경우 등 Google 쪽 문제
            log.warn("Google ID 토큰을 확인하지 못했습니다: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
