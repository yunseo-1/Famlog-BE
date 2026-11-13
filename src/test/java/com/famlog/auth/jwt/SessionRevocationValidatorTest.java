package com.famlog.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.oauth2.jwt.Jwt;

import com.famlog.auth.service.SessionRevocationStore;

class SessionRevocationValidatorTest {

    private final SessionRevocationStore store = mock(SessionRevocationStore.class);
    private final SessionRevocationValidator validator = new SessionRevocationValidator(store);

    @Test
    void 폐기되지_않은_세션이면_통과() {
        given(store.isRevoked(7L)).willReturn(false);

        assertThat(validator.validate(jwt("7")).hasErrors()).isFalse();
    }

    @Test
    void 폐기된_세션이면_거부() {
        given(store.isRevoked(7L)).willReturn(true);

        assertThat(validator.validate(jwt("7")).hasErrors()).isTrue();
    }

    @Test
    void Redis를_확인할_수_없으면_거부한다_fail_closed() {
        given(store.isRevoked(7L)).willThrow(new RedisConnectionFailureException("down"));

        assertThat(validator.validate(jwt("7")).hasErrors()).isTrue();
    }

    @Test
    void 세션_ID가_숫자가_아니면_거부() {
        assertThat(validator.validate(jwt("abc")).hasErrors()).isTrue();
    }

    private static Jwt jwt(String sid) {
        return Jwt.withTokenValue("t")
                .header("alg", "ES256")
                .subject("1")
                .claim(FamlogJwtClaims.SESSION_ID, sid)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
