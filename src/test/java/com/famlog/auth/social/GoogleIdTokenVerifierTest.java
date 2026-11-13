package com.famlog.auth.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.user.domain.SocialProvider;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Google ID 토큰 검증 (사양서 9.1). Google 공개 키 대신 테스트 RSA 키로 서명하고,
 * 실제 설정과 같은 검증 규칙(GoogleIdTokenVerifier.validator)을 건다.
 */
class GoogleIdTokenVerifierTest {

    private static final String OUR_CLIENT_ID = "web-client.apps.googleusercontent.com";

    private static final KeyPair GOOGLE_KEY = rsa();
    private static final KeyPair OTHER_KEY = rsa();

    private final GoogleIdTokenVerifier verifier = verifierTrusting(GOOGLE_KEY);

    @Test
    void 정상_ID_토큰이면_sub를_회원번호로_쓴다() {
        String token = sign(GOOGLE_KEY, claims().build());

        assertThat(verifier.verify(token)).isEqualTo(new SocialIdentity(SocialProvider.GOOGLE, "google-sub-1"));
    }

    @Test
    void 다른_앱용_토큰_aud가_다르면_거부() {
        assertInvalid(sign(GOOGLE_KEY, claims().audience("other-app.apps.googleusercontent.com").build()));
    }

    @Test
    void 발급자가_Google이_아니면_거부() {
        assertInvalid(sign(GOOGLE_KEY, claims().issuer("https://evil.example.com").build()));
    }

    @Test
    void 만료된_토큰은_거부() {
        assertInvalid(sign(GOOGLE_KEY, claims()
                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                .build()));
    }

    @Test
    void Google_키가_아닌_키로_서명하면_거부() {
        assertInvalid(sign(OTHER_KEY, claims().build()));
    }

    @Test
    void 형식이_틀린_토큰은_거부() {
        assertInvalid("not-a-jwt");
    }

    @Test
    void 두_가지_iss_표기를_모두_받는다() {
        String token = sign(GOOGLE_KEY, claims().issuer("accounts.google.com").build());

        assertThat(verifier.verify(token).providerUserId()).isEqualTo("google-sub-1");
    }

    @Test
    void Google_공개_키를_가져오지_못하면_일시적_오류() {
        GoogleIdTokenVerifier unreachable = new GoogleIdTokenVerifier(token -> {
            throw new JwtException("Couldn't retrieve remote JWK set");
        }, true);

        assertThatThrownBy(() -> unreachable.verify("token"))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void 클라이언트_ID가_설정되지_않았으면_일시적_오류() {
        GoogleIdTokenVerifier unconfigured = new GoogleIdTokenVerifier(token -> {
            throw new AssertionError("호출되면 안 된다");
        }, false);

        assertThatThrownBy(() -> unconfigured.verify("token"))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_INVALID);
    }

    private static GoogleIdTokenVerifier verifierTrusting(KeyPair key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) key.getPublic()).build();
        decoder.setJwtValidator(GoogleIdTokenVerifier.validator(List.of(OUR_CLIENT_ID)));
        return new GoogleIdTokenVerifier(decoder, true);
    }

    private static JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience(OUR_CLIENT_ID)
                .subject("google-sub-1")
                .claim("email", "mom@example.com")
                .claim("name", "실명")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)));
    }

    private static String sign(KeyPair key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("g1").build(), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
