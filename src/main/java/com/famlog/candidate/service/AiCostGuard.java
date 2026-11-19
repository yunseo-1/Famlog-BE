package com.famlog.candidate.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.famlog.common.time.FamlogTime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 호출·비용 상한 (사양서 7.2 비용 관리). 분석 요청을 만들기 전에 확인한다.
 * <ul>
 * <li>그룹별 하루(Asia/Seoul) 요청 수 famlog.ai.limits.daily-calls-per-group (100)</li>
 * <li>월(UTC) 전체 비용: ai_jobs 토큰 수 × famlog.ai.pricing 단가. 상한(20달러)에 닿으면 새 요청을 만들지 않는다</li>
 * <li>상한의 80%를 넘거나 상한에 닿으면 그달에 한 번 경고 로그 (알림 시스템은 아직 없다)</li>
 * </ul>
 * 아직 결과가 오지 않은 요청의 비용은 세지 못한다 (한 번에 1센트 미만이라 넘쳐도 작다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiCostGuard {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final String WARNED_KEY = "ai:cost-warned:";
    private static final Duration WARNED_TTL = Duration.ofDays(40);

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redis;
    private final AiProperties properties;

    /** 이 그룹의 분석 요청을 지금 만들어도 되는지 */
    public boolean allows(long groupId, Instant now) {
        Instant startOfDay = now.atZone(FamlogTime.DISPLAY_ZONE).toLocalDate().atStartOfDay(FamlogTime.DISPLAY_ZONE)
                .toInstant();
        Integer today = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_jobs WHERE group_id = ? AND created_at >= ?", Integer.class, groupId,
                Timestamp.from(startOfDay));
        if (today != null && today >= properties.limits().dailyCallsPerGroup()) {
            log.info("그룹 AI 하루 상한으로 분석을 건너뜁니다: groupId={}, today={}", groupId, today);
            return false;
        }
        YearMonth month = YearMonth.from(now.atZone(ZoneOffset.UTC));
        BigDecimal spent = monthlyCost(month);
        BigDecimal budget = properties.limits().monthlyBudgetUsd();
        if (spent.compareTo(budget) >= 0) {
            warnOnce("blocked:" + month, "AI 월 비용 상한에 닿아 분석을 멈춥니다: spent={}, budget={}", spent, budget);
            return false;
        }
        if (spent.compareTo(budget.multiply(properties.limits().warnRatio())) >= 0) {
            warnOnce("warn:" + month, "AI 월 비용이 경고 기준을 넘었습니다: spent={}, budget={}", spent, budget);
        }
        return true;
    }

    /** 이번 달(UTC) 비용 (달러) */
    public BigDecimal monthlyCost(YearMonth month) {
        Instant start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        AiProperties.Pricing price = properties.pricing();
        return jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(input_tokens), 0), coalesce(sum(output_tokens), 0),
                       coalesce(sum(cache_read_tokens), 0), coalesce(sum(cache_write_tokens), 0)
                FROM ai_jobs WHERE created_at >= ? AND created_at < ?
                """, (rs, i) -> price.input().multiply(rs.getBigDecimal(1))
                .add(price.output().multiply(rs.getBigDecimal(2)))
                .add(price.cacheRead().multiply(rs.getBigDecimal(3)))
                .add(price.cacheWrite().multiply(rs.getBigDecimal(4)))
                .divide(MILLION, 4, RoundingMode.HALF_UP),
                Timestamp.from(start), Timestamp.from(month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC)
                        .toInstant()));
    }

    /** 서버가 여러 대여도 그달에 한 번만 (Redis 장애 때는 매번 남긴다) */
    private void warnOnce(String kind, String message, Object... args) {
        boolean first;
        try {
            first = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(WARNED_KEY + kind, "1", WARNED_TTL));
        } catch (RuntimeException e) {
            first = true;
        }
        if (first) {
            log.warn(message, args);
        }
    }
}
