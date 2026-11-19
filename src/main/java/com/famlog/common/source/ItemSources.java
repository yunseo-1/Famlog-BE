package com.famlog.common.source;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 등록 항목의 원문 연결 item_sources (사양서 4.5 ARC-06 "원문 보기", 10.2).
 * AI 후보를 승인해 만든 일정·할 일·장소가 어느 채팅 메시지에서 왔는지 남긴다. 항목을 지우면 함께 지운다.
 */
@Repository
@RequiredArgsConstructor
public class ItemSources {

    public static final String SCHEDULE = "SCHEDULE";
    public static final String TODO = "TODO";
    public static final String PLACE = "PLACE";

    private final JdbcTemplate jdbcTemplate;

    public void add(String entityType, long entityId, Collection<Long> messageIds) {
        for (long messageId : messageIds) {
            jdbcTemplate.update("""
                    INSERT INTO item_sources (entity_type, entity_id, message_id) VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, entityType, entityId, messageId);
        }
    }

    public void remove(String entityType, long entityId, Collection<Long> messageIds) {
        for (long messageId : messageIds) {
            jdbcTemplate.update("DELETE FROM item_sources WHERE entity_type = ? AND entity_id = ? AND message_id = ?",
                    entityType, entityId, messageId);
        }
    }

    public void removeAll(String entityType, long entityId) {
        jdbcTemplate.update("DELETE FROM item_sources WHERE entity_type = ? AND entity_id = ?", entityType, entityId);
    }

    /** 항목별 원문 (가장 먼저 말한 메시지). 원문이 없는 항목은 빠진다 */
    public Map<Long, SourceRef> find(String entityType, Collection<Long> entityIds) {
        Map<Long, SourceRef> sources = new HashMap<>();
        if (entityIds.isEmpty()) {
            return sources;
        }
        jdbcTemplate.query("""
                SELECT DISTINCT ON (s.entity_id) s.entity_id, m.id, m.seq
                FROM item_sources s JOIN messages m ON m.id = s.message_id
                WHERE s.entity_type = ? AND s.entity_id = ANY (?)
                ORDER BY s.entity_id, m.seq
                """, rs -> {
            sources.put(rs.getLong(1), new SourceRef(rs.getLong(2), rs.getLong(3)));
        }, entityType, entityIds.toArray(Long[]::new));
        return sources;
    }

    public SourceRef find(String entityType, long entityId) {
        return find(entityType, List.of(entityId)).get(entityId);
    }
}
