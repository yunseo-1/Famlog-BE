package com.famlog.common.cleanup;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 보관 기간이 지난 행을 지운다 (사양서 8.2, 9장). 기간은 {@link CleanupProperties}.
 * 내용이 아닌 기록만 지우고, 아직 처리하지 않은 신고와 동의 이력은 남긴다. 표마다 지운 행 수를 돌려준다
 */
@Service
@RequiredArgsConstructor
public class RetentionCleanupService {

    private final JdbcTemplate jdbcTemplate;
    private final CleanupProperties properties;
    private final Clock clock;

    @Transactional
    public Map<String, Integer> clean() {
        Instant now = Instant.now(clock);
        Map<String, Integer> counts = new LinkedHashMap<>();

        // 세션: 푸시 토큰·리프레시 토큰이 세션을 가리키므로 먼저 지운다
        String oldSessions = "SELECT id FROM user_sessions WHERE revoked_at < ? OR expires_at < ?";
        Timestamp sessions = cutoff(now, properties.sessionsAfter());
        jdbcTemplate.update("DELETE FROM device_tokens WHERE session_id IN (" + oldSessions + ")", sessions,
                sessions);
        jdbcTemplate.update("DELETE FROM refresh_tokens WHERE session_id IN (" + oldSessions + ")", sessions,
                sessions);
        counts.put("refreshTokens", jdbcTemplate.update("DELETE FROM refresh_tokens WHERE expires_at < ?", sessions));
        counts.put("sessions", jdbcTemplate.update("DELETE FROM user_sessions WHERE id IN (" + oldSessions + ")",
                sessions, sessions));

        Timestamp invites = cutoff(now, properties.invitesAfter());
        counts.put("invites", jdbcTemplate.update("DELETE FROM group_invites WHERE expires_at < ? OR revoked_at < ?",
                invites, invites));
        counts.put("reminderSends", jdbcTemplate.update("DELETE FROM schedule_reminder_sends "
                + "WHERE occurrence_start < ?", cutoff(now, properties.reminderSendsAfter())));
        counts.put("locationLogs", jdbcTemplate.update("DELETE FROM location_access_logs WHERE accessed_at < ?",
                cutoff(now, properties.locationLogsAfter())));
        counts.put("reports", jdbcTemplate.update("DELETE FROM reports WHERE status <> 'OPEN' AND handled_at < ?",
                cutoff(now, properties.reportsAfter())));

        // 후보(근거·피드백은 함께 지워진다) → 후보가 남지 않은 AI 요청
        Timestamp candidates = cutoff(now, properties.candidatesAfter());
        counts.put("candidates", jdbcTemplate.update("DELETE FROM registration_candidates WHERE created_at < ?",
                candidates));
        counts.put("aiJobs", jdbcTemplate.update("""
                DELETE FROM ai_jobs j WHERE j.created_at < ?
                    AND NOT EXISTS (SELECT 1 FROM registration_candidates c WHERE c.job_id = j.id)
                """, candidates));
        counts.put("auditLogs", jdbcTemplate.update("DELETE FROM audit_logs WHERE created_at < ?",
                cutoff(now, properties.auditLogsAfter())));
        counts.put("dataExports", jdbcTemplate.update("DELETE FROM data_export_requests "
                + "WHERE status IN ('FAILED', 'EXPIRED') AND finished_at < ?",
                cutoff(now, properties.dataExportsAfter())));
        return counts;
    }

    private static Timestamp cutoff(Instant now, Duration age) {
        return Timestamp.from(now.minus(age));
    }
}
