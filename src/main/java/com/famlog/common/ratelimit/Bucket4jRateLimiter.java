package com.famlog.common.ratelimit;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import lombok.RequiredArgsConstructor;

/** Bucket4j + Redis 토큰 버킷. "기간당 N회"를 기간마다 N개 한꺼번에 채우는 방식으로 센다. */
@RequiredArgsConstructor
public class Bucket4jRateLimiter implements RateLimiter {

    private final ProxyManager<String> proxyManager;

    @Override
    public Result tryConsume(String key, long capacity, Duration period) {
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(capacity).refillIntervally(capacity, period).build())
                .build();
        ConsumptionProbe probe = proxyManager.builder().build(key, () -> configuration)
                .tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Result(true, 0);
        }
        long seconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
        return new Result(false, Math.max(1, seconds + 1));
    }
}
