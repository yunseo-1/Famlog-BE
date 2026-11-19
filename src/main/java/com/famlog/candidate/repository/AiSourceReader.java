package com.famlog.candidate.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.famlog.group.domain.FamilyRole;

import lombok.RequiredArgsConstructor;

/**
 * AI 분석 구간을 읽는다 (사양서 6.2 ①, 8.7). 분석 대상 메시지는 모두 이 조건을 지난다:
 * <ul>
 * <li>사람이 보낸 TEXT, 지우지 않은 것 (사진·장소·시스템 메시지 제외)</li>
 * <li>그룹 AI 분석을 켠 뒤에 보낸 것</li>
 * <li>보낸 사람이 지금 그룹 구성원이고 분석 제외가 꺼져 있으며, 분석 제외를 마지막으로 바꾼 뒤에 보낸 것
 * (제외했던 동안의 메시지는 다시 켜도 보내지 않는다)</li>
 * </ul>
 * chat_rooms.ai_analyzed_seq 는 JPA 엔티티에 매핑하지 않고 여기서만 쓴다 (채팅 전송의 방 갱신과 섞이지 않게).
 */
@Repository
@RequiredArgsConstructor
public class AiSourceReader {

    private final JdbcTemplate jdbcTemplate;

    public record Room(long id, long groupId, long lastSeq, long analyzedSeq) {
    }

    public record SourceMessage(long id, long seq, long senderId, String body, Instant sentAt) {
    }

    public record Member(long userId, FamilyRole familyRole, String familyTitle) {
    }

    public Optional<Room> room(long roomId) {
        return jdbcTemplate.query("SELECT id, group_id, last_seq, ai_analyzed_seq FROM chat_rooms WHERE id = ?",
                (rs, i) -> new Room(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), roomId)
                .stream().findFirst();
    }

    /** seq 가 (afterSeq, uptoSeq] 인 분석 대상 메시지 중 최근 limit 개를 오래된 순으로 */
    public List<SourceMessage> eligibleMessages(Room room, long afterSeq, long uptoSeq, Instant enabledAt,
            int limit) {
        List<SourceMessage> newestFirst = jdbcTemplate.query("""
                SELECT m.id, m.seq, m.sender_id, m.body, m.created_at
                FROM messages m
                JOIN group_members gm ON gm.group_id = ? AND gm.user_id = m.sender_id
                WHERE m.room_id = ? AND m.seq > ? AND m.seq <= ?
                  AND m.type = 'TEXT' AND m.deleted_at IS NULL AND m.created_at >= ?
                  AND gm.left_at IS NULL AND NOT gm.ai_opt_out
                  AND (gm.ai_opt_out_changed_at IS NULL OR m.created_at >= gm.ai_opt_out_changed_at)
                ORDER BY m.seq DESC
                LIMIT ?
                """, (rs, i) -> new SourceMessage(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                rs.getTimestamp(5).toInstant()),
                room.groupId(), room.id(), afterSeq, uptoSeq, Timestamp.from(enabledAt), limit);
        return newestFirst.reversed();
    }

    /** 지금 구성원 (들어온 순). 임시 ID를 이 순서로 매긴다 */
    public List<Member> members(long groupId) {
        return jdbcTemplate.query("""
                SELECT user_id, family_role, family_title FROM group_members
                WHERE group_id = ? AND left_at IS NULL
                ORDER BY joined_at, id
                """, (rs, i) -> new Member(rs.getLong(1),
                rs.getString(2) == null ? null : FamilyRole.valueOf(rs.getString(2)), rs.getString(3)), groupId);
    }

    /** 기준점을 옮긴다. 다른 서버가 먼저 옮겼으면 false */
    public boolean advance(long roomId, long fromSeq, long toSeq) {
        return jdbcTemplate.update("UPDATE chat_rooms SET ai_analyzed_seq = ? WHERE id = ? AND ai_analyzed_seq = ?",
                toSeq, roomId, fromSeq) == 1;
    }

    /** AI 분석을 켰을 때: 지금까지의 메시지는 분석하지 않는다 */
    public void skipToLatest(long groupId) {
        jdbcTemplate.update("UPDATE chat_rooms SET ai_analyzed_seq = last_seq WHERE group_id = ?", groupId);
    }
}
