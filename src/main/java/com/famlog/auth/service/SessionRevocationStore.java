package com.famlog.auth.service;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.famlog.auth.jwt.AuthProperties;

/**
 * 폐기된 세션 목록 (Redis). Access JWT는 30분간 유효하므로, 폐기된 세션의 토큰을 즉시 막기 위해
 * Access 수명 동안 sid를 기록해 두고 요청마다 확인한다 (사양서 9.2, 18.1 AUTH-03).
 */
@Component
public class SessionRevocationStore {

    private static final String KEY_PREFIX = "auth:revoked-session:";
    private static final Duration MARGIN = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public SessionRevocationStore(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.ttl = properties.accessTokenTtl().plus(MARGIN);
    }

    public void markRevoked(long sessionId) {
        redis.opsForValue().set(KEY_PREFIX + sessionId, "1", ttl);
    }

    public boolean isRevoked(long sessionId) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + sessionId));
    }
}
