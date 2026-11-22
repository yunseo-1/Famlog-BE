package com.famlog.common.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.famlog.group.service.GroupPurgeService;
import com.famlog.media.service.MediaCleanupService;
import com.famlog.media.service.MediaProperties;
import com.famlog.media.storage.MediaStorage;
import com.famlog.support.ChatIntegrationTest;
import com.famlog.support.GroupApi;
import com.famlog.support.PlaceApi;
import com.famlog.support.ScheduleApi;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 운영 정리 배치 (사양서 2.2, 8.2, 9장): 삭제 예정 그룹 파기(다른 그룹은 그대로, 기록용 행만 남음), 미디어 파일 정리
 * (신고된 것은 남김), 보관 기간이 지난 행 정리(경계 앞뒤)
 */
class CleanupBatchTest extends ChatIntegrationTest {

    @Autowired
    GroupPurgeService purgeService;

    @Autowired
    MediaCleanupService mediaCleanupService;

    @Autowired
    RetentionCleanupService retentionService;

    @Autowired
    S3Client s3;

    @Autowired
    MediaStorage storage;

    @Autowired
    MediaProperties mediaProperties;

    @Test
    void 유예가_지난_삭제_예정_그룹은_데이터를_모두_지우고_기록용_행만_남긴다() throws Exception {
        long owner = createUser();
        long dad = createUser();
        long group = GroupApi.createGroup(mockMvc, owner, "지울 가족");
        GroupApi.addMember(mockMvc, group, owner, dad);
        Fixture purged = fill(group, owner, dad);
        long keepGroup = GroupApi.createGroup(mockMvc, dad, "남을 가족");
        Fixture kept = fill(keepGroup, dad, dad);
        GroupApi.issueInvite(mockMvc, keepGroup, dad);
        GroupApi.setAiAnalysis(mockMvc, group, owner, false);
        GroupApi.setAiAnalysis(mockMvc, keepGroup, dad, false);
        jdbcTemplate.update("UPDATE users SET last_group_id = ? WHERE id = ?", group, owner);
        long report = jdbcTemplate.queryForObject("INSERT INTO reports (reporter_id, group_id, target_type, target_id, "
                + "reason) VALUES (?, ?, 'MESSAGE', ?, 'SPAM') RETURNING id", Long.class, dad, group, purged.messageId);

        mockMvc.perform(delete("/api/v1/groups/{g}", group).with(asUser(owner))).andExpect(status().isNoContent());
        assertThat(purgeService.purge(group)).isFalse(); // 유예 중
        assertThat(purgeService.dueGroupIds(Instant.now())).doesNotContain(group);
        jdbcTemplate.update("UPDATE family_groups SET delete_scheduled_at = now() - interval '1 minute' WHERE id = ?",
                group);

        assertThat(purgeService.dueGroupIds(Instant.now())).contains(group).doesNotContain(keepGroup);
        assertThat(purgeService.purge(group)).isTrue();
        assertThat(purgeService.purge(group)).isFalse();

        assertThat(jdbcTemplate.queryForMap("SELECT status, name, image_key, purged_at IS NOT NULL AS purged "
                + "FROM family_groups WHERE id = ?", group))
                .containsEntry("status", "DELETED").containsEntry("name", "삭제된 그룹")
                .containsEntry("image_key", null).containsEntry("purged", true);
        for (String table : new String[] { "chat_rooms", "media_assets", "schedules", "todos", "places",
                "place_aliases", "group_invites", "group_settings", "group_members", "registration_candidates",
                "ai_jobs" }) {
            assertThat(count(table + " WHERE group_id = " + group)).as(table).isZero();
            assertThat(count(table + " WHERE group_id = " + keepGroup)).as(table).isPositive();
        }
        assertThat(count("messages WHERE id = " + purged.messageId)).isZero();
        assertThat(count("messages WHERE id = " + kept.messageId)).isOne();
        assertThat(count("item_sources WHERE message_id = " + kept.messageId)).isOne();
        assertThat(count("message_reactions WHERE message_id = " + kept.messageId)).isOne();
        assertThat(storage.size(purged.photoKey)).isEmpty();
        assertThat(storage.size(purged.photoKey + "-thumb")).isEmpty();
        assertThat(storage.size(kept.photoKey)).isPresent();
        assertThat(jdbcTemplate.queryForObject("SELECT last_group_id FROM users WHERE id = ?", Long.class, owner))
                .isNull();
        // 신고·감사 로그는 남고, 감사 로그에는 건수만
        assertThat(count("reports WHERE id = " + report)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT meta::text FROM audit_logs WHERE action = 'GROUP_PURGED' "
                + "AND target_id = ?", String.class, group)).contains("\"members\": 2", "\"media\": 1")
                .doesNotContain("안녕");
        // 남은 가족은 그대로 쓴다
        mockMvc.perform(get("/api/v1/groups/{g}", keepGroup).with(asUser(dad))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/groups/{g}", group).with(asUser(dad))).andExpect(status().isForbidden());
    }

    @Test
    void 붙지_않은_미디어는_하루가_지나면_삭제된_메시지의_미디어는_바로_지우고_신고된_것은_남긴다() throws Exception {
        long me = createUser();
        long group = GroupApi.createGroup(mockMvc, me, "우리 가족");
        String fresh = putObject();
        String stale = putObject();
        long freshId = insertMedia(group, me, null, fresh, "now()");
        long staleId = insertMedia(group, me, null, stale, "now() - interval '25 hours'");
        long deletedMessage = send(group, me, "사진").id();
        long reportedMessage = send(group, me, "신고된 사진").id();
        long liveMessage = send(group, me, "남는 사진").id();
        String deletedKey = putObject();
        String reportedKey = putObject();
        String liveKey = putObject();
        long deletedMedia = insertMedia(group, me, deletedMessage, deletedKey, "now()");
        insertMedia(group, me, reportedMessage, reportedKey, "now()");
        insertMedia(group, me, liveMessage, liveKey, "now()");
        jdbcTemplate.update("UPDATE messages SET deleted_at = now() WHERE id IN (?, ?)", deletedMessage,
                reportedMessage);
        jdbcTemplate.update("INSERT INTO reports (reporter_id, group_id, target_type, target_id, reason) "
                + "VALUES (?, ?, 'MESSAGE', ?, 'SPAM')", me, group, reportedMessage);

        assertThat(mediaCleanupService.cleanUnattached(Instant.now().minusSeconds(24 * 3600)))
                .isGreaterThanOrEqualTo(1);
        assertThat(mediaCleanupService.cleanDeletedMessages()).isGreaterThanOrEqualTo(1);

        assertThat(count("media_assets WHERE id = " + staleId)).isZero();
        assertThat(storage.size(stale)).isEmpty();
        assertThat(storage.size(stale + "-thumb")).isEmpty();
        assertThat(count("media_assets WHERE id = " + freshId)).isOne();
        assertThat(count("media_assets WHERE id = " + deletedMedia)).isZero();
        assertThat(storage.size(deletedKey)).isEmpty();
        assertThat(storage.size(reportedKey)).isPresent();
        assertThat(storage.size(liveKey)).isPresent();
        assertThat(storage.size(fresh)).isPresent();
    }

    @Test
    void 보관_기간이_지난_기록만_지운다() throws Exception {
        long me = createUser();
        long group = GroupApi.createGroup(mockMvc, me, "우리 가족");
        long room = jdbcTemplate.queryForObject("SELECT id FROM chat_rooms WHERE group_id = ?", Long.class, group);
        long oldSession = session(me, "now() - interval '91 days'", null);
        long liveSession = session(me, "now() + interval '1 day'", null);
        long revokedSession = session(me, "now() + interval '1 day'", "now() - interval '91 days'");
        jdbcTemplate.update("INSERT INTO device_tokens (user_id, session_id, platform, fcm_token) "
                + "VALUES (?, ?, 'ANDROID', ?)", me, oldSession, "fcm-" + UUID.randomUUID());
        jdbcTemplate.update("INSERT INTO refresh_tokens (session_id, token_hash, expires_at) VALUES (?, ?, "
                + "now() - interval '91 days')", oldSession, UUID.randomUUID().toString());
        long oldLog = locationLog(me, "now() - interval '181 days'");
        long newLog = locationLog(me, "now() - interval '179 days'");
        long oldReport = report(me, group, "'ACTIONED'", "now() - interval '366 days'");
        long openReport = report(me, group, "'OPEN'", null);
        long recentReport = report(me, group, "'REJECTED'", "now() - interval '364 days'");
        long oldJob = aiJob(group, room, "now() - interval '366 days'");
        long oldCandidate = candidate(group, room, oldJob, "now() - interval '366 days'");
        long newJob = aiJob(group, room, "now() - interval '1 day'");
        long oldAudit = jdbcTemplate.queryForObject("INSERT INTO audit_logs (action, target_type, created_at) VALUES "
                + "('GROUP_PURGED', 'GROUP', now() - interval '366 days') RETURNING id", Long.class);
        long expiredInvite = jdbcTemplate.queryForObject("INSERT INTO group_invites (group_id, code, created_by, "
                + "expires_at) VALUES (?, ?, ?, now() - interval '31 days') RETURNING id", Long.class, group,
                UUID.randomUUID().toString().substring(0, 8), me);

        Map<String, Integer> counts = retentionService.clean();

        assertThat(counts).containsKeys("sessions", "locationLogs", "reports", "candidates", "aiJobs", "auditLogs");
        assertThat(count("user_sessions WHERE id = " + oldSession)).isZero();
        assertThat(count("user_sessions WHERE id = " + revokedSession)).isZero();
        assertThat(count("user_sessions WHERE id = " + liveSession)).isOne();
        assertThat(count("device_tokens WHERE session_id = " + oldSession)).isZero();
        assertThat(count("location_access_logs WHERE id = " + oldLog)).isZero();
        assertThat(count("location_access_logs WHERE id = " + newLog)).isOne();
        assertThat(count("reports WHERE id = " + oldReport)).isZero();
        assertThat(count("reports WHERE id IN (" + openReport + ", " + recentReport + ")")).isEqualTo(2);
        assertThat(count("registration_candidates WHERE id = " + oldCandidate)).isZero();
        assertThat(count("ai_jobs WHERE id = " + oldJob)).isZero();
        assertThat(count("ai_jobs WHERE id = " + newJob)).isOne();
        assertThat(count("audit_logs WHERE id = " + oldAudit)).isZero();
        assertThat(count("group_invites WHERE id = " + expiredInvite)).isZero();
    }

    /** 그룹 하나를 채운 데이터 */
    private record Fixture(long messageId, String photoKey) {
    }

    private Fixture fill(long group, long writer, long other) throws Exception {
        long room = jdbcTemplate.queryForObject("SELECT id FROM chat_rooms WHERE group_id = ?", Long.class, group);
        long first = send(group, writer, "안녕").id();
        long reply = send(group, other, "안녕하세요", first).id();
        jdbcTemplate.update("INSERT INTO message_reactions (message_id, user_id, emoji) VALUES (?, ?, 'LIKE')", first,
                other);
        String photoKey = putObject();
        jdbcTemplate.update("""
                INSERT INTO media_assets (group_id, uploader_id, message_id, media_type, mime, size, storage_key,
                    variants, status, upload_expires_at)
                VALUES (?, ?, ?, 'IMAGE', 'image/jpeg', 1, ?, ?::jsonb, 'READY', now())""", group, writer, first,
                photoKey, "{\"THUMB_S\": \"%s-thumb\"}".formatted(photoKey));
        long schedule = ScheduleApi.create(mockMvc, group, writer, ScheduleApi.timed("병원", "2026-12-01T10:00",
                "2026-12-01T11:00", writer));
        jdbcTemplate.update("INSERT INTO item_sources (entity_type, entity_id, message_id) VALUES ('SCHEDULE', ?, ?)",
                schedule, first);
        jdbcTemplate.update("INSERT INTO todos (group_id, title, created_by) VALUES (?, '우유', ?)", group, writer);
        long place = jdbcTemplate.queryForObject("""
                INSERT INTO places (group_id, kakao_place_id, name, category, address, region1, lat, lng, shared_by)
                VALUES (?, ?, '식당', 'RESTAURANT', '서울', '서울', 37.5, 127.0, ?) RETURNING id""", Long.class, group,
                PlaceApi.newKakaoId(), writer);
        jdbcTemplate.update("INSERT INTO place_marks (place_id, user_id, kind) VALUES (?, ?, 'WANT')", place, writer);
        jdbcTemplate.update("INSERT INTO place_aliases (group_id, alias, place_id, created_by) VALUES (?, '단골', ?, ?)",
                group, place, writer);
        long job = aiJob(group, room, "now()");
        long candidate = candidate(group, room, job, "now()");
        jdbcTemplate.update("INSERT INTO candidate_evidence (candidate_id, message_id) VALUES (?, ?)", candidate,
                reply);
        return new Fixture(first, photoKey);
    }

    private String putObject() {
        String key = "test/" + UUID.randomUUID();
        for (String k : new String[] { key, key + "-thumb" }) {
            s3.putObject(builder -> builder.bucket(mediaProperties.s3().bucket()).key(k), RequestBody.fromString("x"));
        }
        return key;
    }

    private long insertMedia(long group, long uploader, Long messageId, String key, String createdAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO media_assets (group_id, uploader_id, message_id, media_type, mime, size, storage_key,
                    variants, status, upload_expires_at, created_at)
                VALUES (?, ?, ?, 'IMAGE', 'image/jpeg', 1, ?, ?::jsonb, 'READY', now(), %s) RETURNING id"""
                .formatted(createdAt), Long.class, group, uploader, messageId, key,
                "{\"THUMB_S\": \"%s-thumb\"}".formatted(key));
    }

    private long aiJob(long group, long room, String createdAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO ai_jobs (group_id, room_id, from_seq, to_seq, trigger, message_count, member_refs,
                    status, created_at)
                VALUES (?, ?, 0, 1, 'BATCH', 1, '[]'::jsonb, 'DONE', %s) RETURNING id""".formatted(createdAt),
                Long.class, group, room);
    }

    private long candidate(long group, long room, long job, String createdAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO registration_candidates (group_id, room_id, job_id, type, fields, confidence, dedup_key,
                    expires_at, created_at)
                VALUES (?, ?, ?, 'TODO', '{}'::jsonb, 0.9, ?, now() + interval '7 days', %s) RETURNING id"""
                .formatted(createdAt), Long.class, group, room, job, UUID.randomUUID().toString());
    }

    private long session(long user, String expiresAt, String revokedAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO user_sessions (user_id, device_id, platform, expires_at, revoked_at)
                VALUES (?, ?, 'ANDROID', %s, %s) RETURNING id""".formatted(expiresAt,
                revokedAt == null ? "NULL" : revokedAt), Long.class, user, "d-" + UUID.randomUUID());
    }

    private long locationLog(long user, String accessedAt) {
        return jdbcTemplate.queryForObject("INSERT INTO location_access_logs (user_id, purpose, accessed_at) "
                + "VALUES (?, 'NEARBY', %s) RETURNING id".formatted(accessedAt), Long.class, user);
    }

    private long report(long user, long group, String status, String handledAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO reports (reporter_id, group_id, target_type, target_id, reason, status, handled_at)
                VALUES (?, ?, 'USER', ?, 'SPAM', %s, %s) RETURNING id""".formatted(status,
                handledAt == null ? "NULL" : handledAt), Long.class, user, group, System.nanoTime());
    }

    private int count(String fromWhere) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class);
    }
}
