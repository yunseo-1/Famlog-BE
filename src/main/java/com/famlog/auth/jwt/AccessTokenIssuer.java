package com.famlog.auth.jwt;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Access JWT 발급: sub=사용자 ID, sid=세션 ID, 30분 (사양서 9.2). */
@Component
public class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final String activeKid;
    private final Clock clock;

    public AccessTokenIssuer(JwtEncoder encoder, AuthProperties properties, JwtKeys keys, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.activeKid = keys.signingKey().getKeyID();
        this.clock = clock;
    }

    public AccessToken issue(long userId, long sessionId) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(Long.toString(userId))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(FamlogJwtClaims.SESSION_ID, Long.toString(sessionId))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(activeKid).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt);
    }

    public record AccessToken(String value, Instant expiresAt) {
    }
}
