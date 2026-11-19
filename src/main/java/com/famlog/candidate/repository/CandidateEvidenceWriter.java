package com.famlog.candidate.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 후보의 근거 메시지 (candidate_evidence) 저장·조회와 근거 확인용 메시지 읽기.
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

    /** 카드를 붙일 메시지: 근거 중 가장 나중 메시지 */
    public record Anchor(long messageId, long seq) {
    }

    /** @param body 지운 메시지면 null */
    public record EvidenceRow(long messageId, long seq, Long senderId, Instant sentAt, String spansJson,
            String body) {
    }

    public Map<Long, Anchor> anchors(Collection<Long> candidateIds) {
        Map<Long, Anchor> anchors = new HashMap<>();
        if (candidateIds.isEmpty()) {
            return anchors;
        }
        jdbcTemplate.query("""
                SELECT DISTINCT ON (e.candidate_id) e.candidate_id, m.id, m.seq
                FROM candidate_evidence e JOIN messages m ON m.id = e.message_id
                WHERE e.candidate_id = ANY (?)
                ORDER BY e.candidate_id, m.seq DESC
                """, rs -> {
            anchors.put(rs.getLong(1), new Anchor(rs.getLong(2), rs.getLong(3)));
        }, (Object) candidateIds.toArray(Long[]::new));
        return anchors;
    }

    /** 근거 메시지 (오래된 순) */
    public List<EvidenceRow> evidence(long candidateId) {
        return jdbcTemplate.query("""
                SELECT m.id, m.seq, m.sender_id, m.created_at, e.spans::text,
                       CASE WHEN m.deleted_at IS NULL THEN m.body END
                FROM candidate_evidence e JOIN messages m ON m.id = e.message_id
                WHERE e.candidate_id = ?
                ORDER BY m.seq
                """, (rs, i) -> new EvidenceRow(rs.getLong(1), rs.getLong(2), rs.getObject(3, Long.class),
                rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getString(6)), candidateId);
    }
}
