package com.famlog.media.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.famlog.media.storage.MediaStorage;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 미디어 파일 정리 (사양서 8.2 "본인 삭제 시 즉시", 9.1). S3 원본·썸네일을 지운 뒤 행을 지운다.
 * <ul>
 * <li>메시지에 붙지 않은 채 하루가 지난 것: 올리다 만 것, 올렸지만 보내지 않은 것, 처리 실패·거부된 것</li>
 * <li>삭제된 메시지에 붙은 것</li>
 * </ul>
 * 신고가 걸린 메시지·미디어는 신고 보관 기간(처리 후 1년, 신고 행이 지워질 때까지) 동안 남긴다
 */
@Service
@RequiredArgsConstructor
public class MediaCleanupService {

    static final int BATCH = 200;
    private static final TypeReference<Map<String, String>> VARIANTS = new TypeReference<>() {
    };
    private static final String NOT_REPORTED = """
            NOT EXISTS (SELECT 1 FROM reports r WHERE (r.target_type = 'MEDIA' AND r.target_id = a.id)
                        OR (r.target_type = 'MESSAGE' AND r.target_id = a.message_id))""";

    private final JdbcTemplate jdbcTemplate;
    private final MediaStorage storage;
    private final JsonMapper jsonMapper;

    private record Row(long id, String key, String variants) {
    }

    /** 원본과 썸네일 객체를 지운다 (없어도 성공) */
    public void deleteFiles(String storageKey, String variantsJson) {
        storage.delete(storageKey);
        if (variantsJson != null) {
            jsonMapper.readValue(variantsJson, VARIANTS).values().forEach(storage::delete);
        }
    }

    /** 메시지에 붙지 않은 채 before 보다 먼저 만든 미디어. 지운 개수 */
    public int cleanUnattached(Instant before) {
        return clean("""
                SELECT a.id, a.storage_key, a.variants::text FROM media_assets a
                WHERE a.message_id IS NULL AND a.created_at < ? AND %s ORDER BY a.id LIMIT ?
                """.formatted(NOT_REPORTED), Timestamp.from(before), BATCH);
    }

    /** 삭제된 메시지에 붙은 미디어. 지운 개수 */
    public int cleanDeletedMessages() {
        return clean("""
                SELECT a.id, a.storage_key, a.variants::text FROM media_assets a JOIN messages m ON m.id = a.message_id
                WHERE m.deleted_at IS NOT NULL AND %s ORDER BY a.id LIMIT ?
                """.formatted(NOT_REPORTED), BATCH);
    }

    private int clean(String sql, Object... args) {
        int total = 0;
        while (true) {
            List<Row> rows = jdbcTemplate.query(sql, (rs, i) -> new Row(rs.getLong(1), rs.getString(2),
                    rs.getString(3)), args);
            rows.forEach(row -> deleteFiles(row.key(), row.variants()));
            if (!rows.isEmpty()) {
                jdbcTemplate.update("DELETE FROM media_assets WHERE id = ANY(?)",
                        (Object) rows.stream().map(Row::id).toArray(Long[]::new));
            }
            total += rows.size();
            if (rows.size() < BATCH) {
                return total;
            }
        }
    }
}
