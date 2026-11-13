package com.famlog.auth.jwt;

import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;

/**
 * 설정의 키 문자열을 ES256 JWK로 바꾼다. 설정이 잘못되면 기동을 멈춘다 (키 없이 뜨는 서버를 막는다).
 *
 * @param signingKey   새 토큰 서명용 활성 키 (개인 키 포함)
 * @param verification 검증용 공개 키 전체 (활성 키 + 교체 중인 이전 키)
 */
public record JwtKeys(ECKey signingKey, JWKSet verification) {

    public static JwtKeys from(AuthProperties.Jwt config) {
        if (config == null || config.keys() == null || config.keys().isEmpty()) {
            throw new IllegalStateException("famlog.auth.jwt.keys 가 비어 있습니다. 서명 키를 설정하세요.");
        }
        if (config.activeKid() == null || config.activeKid().isBlank()) {
            throw new IllegalStateException("famlog.auth.jwt.active-kid 가 비어 있습니다.");
        }
        Set<String> kids = new HashSet<>();
        List<ECKey> publicKeys = new ArrayList<>();
        ECKey signingKey = null;
        for (AuthProperties.Key key : config.keys()) {
            if (key.kid() == null || key.kid().isBlank() || !kids.add(key.kid())) {
                throw new IllegalStateException("JWT 키의 kid 가 비어 있거나 중복됩니다: " + key.kid());
            }
            ECKey jwk = toJwk(key);
            publicKeys.add(jwk.toPublicJWK());
            if (key.kid().equals(config.activeKid())) {
                if (!jwk.isPrivate()) {
                    throw new IllegalStateException("활성 키(" + key.kid() + ")에 private-key 가 없습니다.");
                }
                signingKey = jwk;
            }
        }
        if (signingKey == null) {
            throw new IllegalStateException("active-kid(" + config.activeKid() + ")에 해당하는 키가 없습니다.");
        }
        return new JwtKeys(signingKey, new JWKSet(new ArrayList<>(publicKeys)));
    }

    private static ECKey toJwk(AuthProperties.Key key) {
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            ECPublicKey publicKey = (ECPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(decode(key.publicKey())));
            if (!Curve.P_256.equals(Curve.forECParameterSpec(publicKey.getParams()))) {
                throw new IllegalStateException("ES256 키는 P-256 곡선이어야 합니다: " + key.kid());
            }
            ECKey.Builder builder = new ECKey.Builder(Curve.P_256, publicKey)
                    .keyID(key.kid())
                    .algorithm(JWSAlgorithm.ES256)
                    .keyUse(KeyUse.SIGNATURE);
            if (key.privateKey() != null && !key.privateKey().isBlank()) {
                builder.privateKey((ECPrivateKey) factory.generatePrivate(
                        new PKCS8EncodedKeySpec(decode(key.privateKey()))));
            }
            return builder.build();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // 원인 메시지에 키 내용이 섞일 수 있어 kid 만 남긴다
            throw new IllegalStateException("JWT 키를 읽을 수 없습니다: " + key.kid());
        }
    }

    private static byte[] decode(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("JWT 키 값이 비어 있습니다.");
        }
        return Base64.getMimeDecoder().decode(base64.strip());
    }
}
