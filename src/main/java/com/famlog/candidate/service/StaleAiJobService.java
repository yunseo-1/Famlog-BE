package com.famlog.candidate.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.famlog.candidate.domain.AiJob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 멈춘 AI 분석 요청 정리. 워커로 보내지 못했거나 30분이 지나도 결과가 없는 QUEUED 요청을 FAILED(STALE)로 바꾼다.
 * 그 구간은 다시 보내지 않는다 (ExtractJobPublisher). 결과가 늦게 오면 비용 계산을 위해 토큰 수만 남긴다 (ExtractResultHandler)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaleAiJobService {

    static final Duration STALE_AFTER = Duration.ofMinutes(30);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /** @return 정리한 요청 수 */
    public int failStale() {
        Instant now = Instant.now(clock);
        int failed = jdbcTemplate.update("""
                UPDATE ai_jobs SET status = 'FAILED', error_code = ?, finished_at = ?, updated_at = now()
                WHERE status = 'QUEUED' AND created_at < ?
                """, AiJob.STALE, Timestamp.from(now), Timestamp.from(now.minus(STALE_AFTER)));
        if (failed > 0) {
            log.warn("멈춘 AI 분석 요청을 실패로 정리했습니다: count={}", failed);
        }
        return failed;
    }
}
