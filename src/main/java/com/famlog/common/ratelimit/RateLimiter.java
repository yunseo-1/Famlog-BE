package com.famlog.common.ratelimit;

import java.time.Duration;

/** 키별 토큰 버킷. 구현은 Redis(Bucket4j)를 써서 서버가 여러 대여도 같은 한도를 공유한다. */
public interface RateLimiter {

    Result tryConsume(String key, long capacity, Duration period);

    /** @param retryAfterSeconds 거부됐을 때 다시 시도할 수 있기까지 남은 초 (최소 1) */
    record Result(boolean allowed, long retryAfterSeconds) {
    }
}
