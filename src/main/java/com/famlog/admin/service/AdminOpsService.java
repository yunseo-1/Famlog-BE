package com.famlog.admin.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.famlog.admin.dto.AdminDtos.OpsSummary;
import com.famlog.candidate.service.AiCostGuard;
import com.famlog.candidate.service.AiProperties;
import com.famlog.common.stream.PendingReclaimer;
import com.famlog.media.service.MediaProperties;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 페이지 위 운영 배너 (사양서 7.2 비용 관리, 14장). 이번 달(UTC, 청구 기준) AI 비용과 상한, 실패·대기 요청 수,
 * DLQ 길이. 비용이 상한의 80% 이상이면 WARN, 상한에 닿으면 OVER(새 분석을 만들지 않는 상태)
 */
@Service
@RequiredArgsConstructor
public class AdminOpsService {

    private final AiCostGuard costGuard;
    private final AiProperties aiProperties;
    private final MediaProperties mediaProperties;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public OpsSummary summary() {
        Instant now = Instant.now(clock);
        YearMonth month = YearMonth.from(now.atZone(ZoneOffset.UTC));
        Timestamp start = Timestamp.from(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        BigDecimal spent = costGuard.monthlyCost(month).setScale(2, RoundingMode.HALF_UP);
        BigDecimal budget = aiProperties.limits().monthlyBudgetUsd();
        int percent = budget.signum() == 0 ? 0 : spent.multiply(BigDecimal.valueOf(100))
                .divide(budget, 0, RoundingMode.DOWN).intValue();
        String level = spent.compareTo(budget) >= 0 ? "OVER"
                : spent.compareTo(budget.multiply(aiProperties.limits().warnRatio())) >= 0 ? "WARN" : "OK";

        Map<String, Object> jobs = jdbcTemplate.queryForMap("""
                SELECT count(*) AS total, count(*) FILTER (WHERE status = 'FAILED') AS failed
                FROM ai_jobs WHERE created_at >= ?
                """, start);
        long queued = jdbcTemplate.queryForObject("SELECT count(*) FROM ai_jobs WHERE status = 'QUEUED'", Long.class);

        Map<String, Long> deadLetters = new LinkedHashMap<>();
        for (String dlq : List.of(mediaProperties.streams().jobs() + ":dlq", aiProperties.streams().jobs() + ":dlq",
                PendingReclaimer.dlqOf(mediaProperties.streams().results()),
                PendingReclaimer.dlqOf(aiProperties.streams().results()))) {
            Long size = redis.opsForStream().size(dlq);
            deadLetters.put(dlq, size == null ? 0 : size);
        }
        return new OpsSummary(month.toString(), spent, budget, percent, level, ((Number) jobs.get("total")).longValue(),
                ((Number) jobs.get("failed")).longValue(), queued, deadLetters);
    }
}
