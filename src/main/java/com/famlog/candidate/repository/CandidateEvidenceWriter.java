package com.famlog.candidate.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 후보의 근거 메시지 (candidate_evidence) 저장과 근거 확인용 메시지 읽기.
 * 메시지 본문은 정규화 단서로만 쓰고 로그에 남기지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class CandidateEvidenceWriter {

    /** pg_advisory_xact_lock 의 첫 번째 키 (후보 저장) */
    private static final int LOCK_NAMESPACE = 1810;

    private final JdbcTemplate jdbcTemplate;

    /** @param body 지운 메시지면 null */
    public record EvidenceMessage(long id, long roomId, long seq, Long senderId, String body, Instant sentAt) {
    }

    public List<EvidenceMessage> messages(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT id, room_id, seq, sender_id, CASE WHEN deleted_at IS NULL THEN body END, created_at
                FROM messages WHERE id = ANY (?)
                """, (rs, i) -> new EvidenceMessage(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getObject(4, Long.class), rs.getString(5), rs.getTimestamp(6).toInstant()),
                (Object) ids.toArray(Long[]::new));
    }

    /** 이미 있는 근거면 그대로 둔다 */
    public void add(long candidateId, long messageId, String spansJson) {
        jdbcTemplate.update("""
                INSERT INTO candidate_evidence (candidate_id, message_id, spans) VALUES (?, ?, ?::jsonb)
                ON CONFLICT DO NOTHING
                """, candidateId, messageId, spansJson);
    }

    /** 같은 그룹의 후보 저장을 한 번에 하나씩 (중복 제거 경쟁 방지). 트랜잭션이 끝나면 풀린다 */
    public void lockGroup(long groupId) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> null, LOCK_NAMESPACE,
                Math.toIntExact(groupId));
    }
}
