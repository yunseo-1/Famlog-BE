package com.famlog.admin.service;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.famlog.candidate.service.AiCostGuard;
import com.famlog.candidate.service.AiProperties;
import com.famlog.common.stream.PendingReclaimer;
import com.famlog.media.service.MediaProperties;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 운영 게이지 (사양서 7.2 비용 관리, 12장): 이번 달(UTC) AI 비용·상한, 결과를 기다리는 AI 요청, DLQ 길이, 살아 있는 워커 수.
 * Prometheus 가 긁을 때마다 계산한다 (DB·Redis 조회 몇 번). 계산이 실패하면 NaN
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OpsMetrics implements MeterBinder {

    /** 워커가 30초마다 90초 만료로 남기는 키 (worker/app/main.py) */
    static final String WORKER_ALIVE_PATTERN = "famlog:worker:alive:*";

    private final AiCostGuard costGuard;
    private final AiProperties aiProperties;
    private final MediaProperties mediaProperties;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redis;
    private final Clock clock;

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "famlog.ai.cost.month.usd", "이번 달(UTC) AI 비용", () -> costGuard
                .monthlyCost(YearMonth.from(Instant.now(clock).atZone(ZoneOffset.UTC))).doubleValue());
        gauge(registry, "famlog.ai.budget.usd", "AI 월 비용 상한", () -> aiProperties.limits().monthlyBudgetUsd()
                .doubleValue());
        gauge(registry, "famlog.ai.jobs.queued", "결과를 기다리는 AI 요청", () -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_jobs WHERE status = 'QUEUED'", Long.class).doubleValue());
        for (String dlq : List.of(mediaProperties.streams().jobs() + ":dlq", aiProperties.streams().jobs() + ":dlq",
                PendingReclaimer.dlqOf(mediaProperties.streams().results()),
                PendingReclaimer.dlqOf(aiProperties.streams().results()))) {
            Gauge.builder("famlog.stream.dlq.length", () -> value(() -> {
                Long size = redis.opsForStream().size(dlq);
                return size == null ? 0 : size.doubleValue();
            })).description("DLQ 스트림 길이").tag("stream", dlq).register(registry);
        }
        gauge(registry, "famlog.worker.alive", "최근 90초 안에 신호를 보낸 워커 수", this::aliveWorkers);
    }

    /** KEYS 대신 SCAN (Redis 를 막지 않게) */
    private double aliveWorkers() {
        ScanOptions options = ScanOptions.scanOptions().match(WORKER_ALIVE_PATTERN).count(100).build();
        try (Cursor<String> keys = redis.scan(options)) {
            return keys.stream().count();
        }
    }

    private static void gauge(MeterRegistry registry, String name, String description, Supplier<Double> value) {
        Gauge.builder(name, () -> value(value)).description(description).register(registry);
    }

    private static double value(Supplier<Double> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            log.warn("운영 게이지를 계산하지 못했습니다: error={}", e.getClass().getSimpleName());
            return Double.NaN;
        }
    }
}
