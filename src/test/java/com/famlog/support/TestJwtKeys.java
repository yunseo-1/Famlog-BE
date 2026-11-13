package com.famlog.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;
import java.time.Instant;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * 테스트용 ES256 키. 실행할 때마다 새로 만들고 저장소에는 키를 두지 않는다.
 * <ul>
 * <li>ACTIVE: 서버의 활성 서명 키</li>
 * <li>PREVIOUS: 교체 전 키 (서버에는 공개 키만 등록 → 검증만 가능)</li>
 * <li>UNREGISTERED: 서버가 모르는 키 (위조 토큰 테스트용)</li>
 * </ul>
 */
public final class TestJwtKeys {

    public static final String ACTIVE_KID = "test-active";
    public static final String PREVIOUS_KID = "test-previous";
    public static final String ISSUER = "famlog";

    public static final KeyPair ACTIVE = generate();
    public static final KeyPair PREVIOUS = generate();
    public static final KeyPair UNREGISTERED = generate();

    private TestJwtKeys() {
    }

    public static String privateKey(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    public static String publicKey(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    /** 서버 발급기를 거치지 않고 원하는 키·클레임으로 직접 서명한다 */
    public static String sign(KeyPair keyPair, String kid, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(kid).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new ECDSASigner((ECPrivateKey) keyPair.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 정상 클레임 (30분 유효) */
    public static JWTClaimsSet.Builder validClaims(long userId, long sessionId) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(Long.toString(userId))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(1800)))
                .claim("sid", Long.toString(sessionId));
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
