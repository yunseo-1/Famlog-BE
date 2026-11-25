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
import org.springframework.transaction.annotation.Transactional;

import com.famlog.admin.dto.AdminDtos.OpsSummary;
import com.famlog.auth.service.SessionTokenService;
import com.famlog.candidate.service.AiCostGuard;
import com.famlog.candidate.service.AiProperties;
import com.famlog.common.audit.AuditAction;
import com.famlog.common.audit.AuditLogger;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.stream.PendingReclaimer;
import com.famlog.media.service.MediaProperties;
import com.famlog.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 운영 기능 (사양서 7.2 비용 관리, 9.8 사고 대응, 14장). 운영 배너와 사고 때 세션 폐기. 이번 달(UTC, 청구 기준) AI 비용과 상한, 실패·대기 요청 수,
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
    private final SessionTokenService sessionTokenService;
    private final UserRepository userRepository;
    private final AuditLogger auditLogger;
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

    /**
     * 사고 대응(docs/runbooks/incident.md): 그 사용자의 모든 기기를 바로 로그아웃시킨다. Access 토큰도 즉시 거부되고 WebSocket 이
     * 끊긴다. 없는 사용자면 404
     */
    @Transactional
    public void revokeSessions(long userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        sessionTokenService.revokeAllSessions(userId);
        auditLogger.recordAdmin(AuditAction.ADMIN_SESSIONS_REVOKED, "USER", userId, null, null);
    }
}
