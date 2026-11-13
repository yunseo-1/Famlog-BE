package com.famlog.auth.jwt;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 인증 설정 (사양서 9.2). 키는 저장소에 커밋하지 않는다: local은 infra/.env, dev·prod는 환경 변수.
 *
 * @param issuer          JWT iss
 * @param accessTokenTtl  Access 토큰 수명 (30분)
 * @param refreshTokenTtl Refresh 토큰 수명 (14일, 재발급마다 새로 14일)
 * @param jwt             서명 키
 */
@ConfigurationProperties("famlog.auth")
public record AuthProperties(
        @DefaultValue("famlog") String issuer,
        @DefaultValue("30m") Duration accessTokenTtl,
        @DefaultValue("14d") Duration refreshTokenTtl,
        Jwt jwt) {

    /**
     * @param activeKid 새 토큰 서명에 쓰는 키의 kid
     * @param keys      검증에 쓰는 모든 키. 교체 중인 이전 키는 publicKey만 두면 된다
     */
    public record Jwt(String activeKid, List<Key> keys) {
    }

    /**
     * ES256(P-256) 키. base64(DER) 문자열.
     *
     * @param privateKey PKCS#8. 활성 키에만 필요
     * @param publicKey  X.509 SubjectPublicKeyInfo
     */
    public record Key(String kid, String privateKey, String publicKey) {
    }
}
