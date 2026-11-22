package com.famlog.group.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.common.audit.AuditAction;
import com.famlog.common.audit.AuditLogger;
import com.famlog.group.domain.FamilyGroup;
import com.famlog.group.repository.FamilyGroupRepository;
import com.famlog.media.service.MediaCleanupService;
import com.famlog.media.storage.MediaStorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 삭제 예정 그룹 파기 (사양서 2.2, 8.2 "그룹 삭제 시"). 유예(30일)가 지난 그룹의 데이터를 모두 지운다.
 * <ul>
 * <li>S3: 그룹 사진, 미디어 원본·썸네일</li>
 * <li>DB: 메시지·반응·원문 연결·후보(근거·피드백)·AI 요청·미디어·일정·할 일·장소(별칭·표시)·초대·설정·구성원·채팅방</li>
 * <li>그룹 행은 신고·감사 로그가 가리키므로 DELETED("삭제된 그룹", 사진 없음)로 남긴다. 감사 로그에는 건수만</li>
 * </ul>
 * 그룹 하나를 한 트랜잭션으로 지운다. S3 를 지운 뒤 DB 가 실패하면 다음 배치가 다시 지운다 (S3 삭제는 없어도 성공)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupPurgeService {

    static final int BATCH = 20;

    private final FamilyGroupRepository groupRepository;
    private final MediaCleanupService mediaCleanupService;
    private final MediaStorage storage;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogger auditLogger;
    private final Clock clock;

    private record MediaFile(String key, String variants) {
    }

    /** 유예가 지난 그룹 id (배치가 하나씩 purge 를 부른다) */
    @Transactional(readOnly = true)
    public List<Long> dueGroupIds(Instant now) {
        return groupRepository.findPurgeDueIds(now, Limit.of(BATCH));
    }

    /** 그룹 하나를 파기한다. 다른 서버가 처리 중이거나 이미 처리했거나 유예 중이면 false */
    @Transactional
    public boolean purge(long groupId) {
        Instant now = Instant.now(clock);
        List<Long> locked = jdbcTemplate.queryForList("""
                SELECT id FROM family_groups WHERE id = ? AND status = 'PENDING_DELETION' AND delete_scheduled_at <= ?
                FOR UPDATE SKIP LOCKED
                """, Long.class, groupId, Timestamp.from(now));
        if (locked.isEmpty()) {
            return false;
        }
        List<MediaFile> files = jdbcTemplate.query("SELECT storage_key, variants::text FROM media_assets "
                + "WHERE group_id = ?", (rs, i) -> new MediaFile(rs.getString(1), rs.getString(2)), groupId);
        files.forEach(file -> mediaCleanupService.deleteFiles(file.key(), file.variants()));
        FamilyGroup group = groupRepository.findById(groupId).orElseThrow();
        if (group.getImageKey() != null) {
            storage.delete(group.getImageKey());
        }

        String groupMessages = "SELECT m.id FROM messages m JOIN chat_rooms r ON r.id = m.room_id WHERE r.group_id = ?";
        Map<String, Object> counts = new LinkedHashMap<>();
        jdbcTemplate.update("DELETE FROM item_sources WHERE message_id IN (" + groupMessages + ")", groupId);
        jdbcTemplate.update("DELETE FROM message_reactions WHERE message_id IN (" + groupMessages + ")", groupId);
        counts.put("candidates", jdbcTemplate.update("DELETE FROM registration_candidates WHERE group_id = ?",
                groupId));
        counts.put("media", jdbcTemplate.update("DELETE FROM media_assets WHERE group_id = ?", groupId));
        jdbcTemplate.update("DELETE FROM ai_jobs WHERE group_id = ?", groupId);
        counts.put("messages", jdbcTemplate.update("DELETE FROM messages WHERE room_id IN "
                + "(SELECT id FROM chat_rooms WHERE group_id = ?)", groupId));
        jdbcTemplate.update("DELETE FROM chat_rooms WHERE group_id = ?", groupId);
        counts.put("schedules", jdbcTemplate.update("DELETE FROM schedules WHERE group_id = ?", groupId));
        counts.put("todos", jdbcTemplate.update("DELETE FROM todos WHERE group_id = ?", groupId));
        jdbcTemplate.update("DELETE FROM place_aliases WHERE group_id = ?", groupId);
        counts.put("places", jdbcTemplate.update("DELETE FROM places WHERE group_id = ?", groupId));
        jdbcTemplate.update("DELETE FROM group_invites WHERE group_id = ?", groupId);
        jdbcTemplate.update("DELETE FROM group_settings WHERE group_id = ?", groupId);
        counts.put("members", jdbcTemplate.update("DELETE FROM group_members WHERE group_id = ?", groupId));
        jdbcTemplate.update("UPDATE users SET last_group_id = NULL WHERE last_group_id = ?", groupId);

        group.purge(now);
        groupRepository.saveAndFlush(group);
        auditLogger.recordSystem(AuditAction.GROUP_PURGED, "GROUP", groupId, groupId, counts);
        log.info("그룹 파기: groupId={}, counts={}", groupId, counts);
        return true;
    }
}
