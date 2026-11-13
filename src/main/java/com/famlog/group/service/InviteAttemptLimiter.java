package com.famlog.group.service;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.famlog.common.error.RateLimitedException;

import lombok.extern.slf4j.Slf4j;

/**
 * 초대 코드 연속 실패 잠금 (사양서 4.1 GRP-02 "5회 실패 10분 잠금", 9.9 SEC-T5).
 * 사용자 기준으로 센다. 성공하면 초기화한다. 분당 5회 요청 제한(RateLimitFilter)과는 별개다.
 * Redis 장애 시에는 잠금 없이 진행한다: 코드 공간이 약 1조 가지이고 요청 제한도 있어 짧은 장애 동안의 위험이 낮다.
 */
@Slf4j
@Component
public class InviteAttemptLimiter {

    private static final String FAILURES = "invite:failures:";
    private static final String LOCK = "invite:lock:";

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final Duration lockDuration;

    public InviteAttemptLimiter(StringRedisTemplate redis, GroupProperties properties) {
        this.redis = redis;
        this.maxFailures = properties.inviteMaxFailures();
        this.lockDuration = properties.inviteLockDuration();
    }

    /** 잠겨 있으면 429 + Retry-After */
    public void checkNotLocked(long userId) {
        Long ttl;
        try {
            ttl = redis.getExpire(LOCK + userId);
        } catch (RuntimeException e) {
            log.warn("초대 코드 잠금 상태를 확인하지 못했습니다: {}", e.getClass().getSimpleName());
            return;
        }
        if (ttl != null && ttl > 0) {
            throw new RateLimitedException("초대 코드를 여러 번 틀렸어요. 잠시 후 다시 시도해 주세요.", ttl);
        }
    }

    /** 실패를 기록한다. 연속 실패 횟수가 한도에 닿으면 잠근다 */
    public void recordFailure(long userId) {
        try {
            Long failures = redis.opsForValue().increment(FAILURES + userId);
            redis.expire(FAILURES + userId, lockDuration);
            if (failures != null && failures >= maxFailures) {
                redis.opsForValue().set(LOCK + userId, "1", lockDuration);
                redis.delete(FAILURES + userId);
            }
        } catch (RuntimeException e) {
            log.warn("초대 코드 실패 횟수를 기록하지 못했습니다: {}", e.getClass().getSimpleName());
        }
    }

    public void reset(long userId) {
        try {
            redis.delete(FAILURES + userId);
        } catch (RuntimeException e) {
            log.warn("초대 코드 실패 횟수를 초기화하지 못했습니다: {}", e.getClass().getSimpleName());
        }
    }
}
