package com.famlog.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.famlog.support.TestJwtKeys;

/** 키 설정이 잘못되면 서버가 뜨지 않아야 한다. */
class JwtKeysTest {

    private static final AuthProperties.Key ACTIVE = new AuthProperties.Key("k1",
            TestJwtKeys.privateKey(TestJwtKeys.ACTIVE), TestJwtKeys.publicKey(TestJwtKeys.ACTIVE));
    private static final AuthProperties.Key PREVIOUS = new AuthProperties.Key("k0", null,
            TestJwtKeys.publicKey(TestJwtKeys.PREVIOUS));

    @Test
    void 활성_키로_서명하고_모든_키로_검증한다() {
        JwtKeys keys = JwtKeys.from(new AuthProperties.Jwt("k1", List.of(ACTIVE, PREVIOUS)));

        assertThat(keys.signingKey().getKeyID()).isEqualTo("k1");
        assertThat(keys.signingKey().isPrivate()).isTrue();
        assertThat(keys.verification().getKeys()).extracting(key -> key.getKeyID()).containsExactly("k1", "k0");
        assertThat(keys.verification().getKeys()).noneMatch(key -> key.isPrivate());
    }

    @Test
    void 키가_없으면_기동_실패() {
        assertThatThrownBy(() -> JwtKeys.from(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtKeys.from(new AuthProperties.Jwt("k1", List.of())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 활성_kid가_없거나_목록에_없으면_기동_실패() {
        assertThatThrownBy(() -> JwtKeys.from(new AuthProperties.Jwt("", List.of(ACTIVE))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtKeys.from(new AuthProperties.Jwt("k9", List.of(ACTIVE))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 활성_키에_개인_키가_없으면_기동_실패() {
        assertThatThrownBy(() -> JwtKeys.from(new AuthProperties.Jwt("k0", List.of(ACTIVE, PREVIOUS))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private-key");
    }

    @Test
    void kid가_중복되면_기동_실패() {
        AuthProperties.Key duplicate = new AuthProperties.Key("k1", null, TestJwtKeys.publicKey(TestJwtKeys.PREVIOUS));

        assertThatThrownBy(() -> JwtKeys.from(new AuthProperties.Jwt("k1", List.of(ACTIVE, duplicate))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void P256이_아닌_키는_거부한다() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair p384 = generator.generateKeyPair();
        AuthProperties.Key key = new AuthProperties.Key("k384",
                Base64.getEncoder().encodeToString(p384.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(p384.getPublic().getEncoded()));

        assertThatThrownBy(() -> JwtKeys.from(new AuthProperties.Jwt("k384", List.of(key))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("P-256");
    }

    @Test
    void 키를_읽지_못해도_오류_메시지에_키_내용을_넣지_않는다() {
        String broken = "QUJDREVGR0hJSktMTU5PUA==";
        AuthProperties.Key key = new AuthProperties.Key("bad", broken, broken);

        assertThatThrownBy(() -> JwtKeys.from(new AuthProperties.Jwt("bad", List.of(key))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad")
                .hasMessageNotContaining(broken)
                .hasNoCause();
    }
}
