package com.famlog.user.controller;

import static com.famlog.support.AuthApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.famlog.media.service.MediaProperties;
import com.famlog.media.storage.MediaStorage;
import com.famlog.support.AuthApi;
import com.famlog.support.ChatIntegrationTest;
import com.famlog.support.FakeSocialLoginVerifier;
import com.famlog.support.GroupApi;
import com.famlog.user.service.AccountDeletionService;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 계정 삭제·복구·파기 (사양서 4.1 ACC-01, 8.9, 18장): 즉시 로그아웃, 그룹장 규칙, 30일 유예 중 로그인 복구,
 * 파기 후 "탈퇴한 구성원"·메시지는 남음·선택한 미디어는 S3 까지 삭제, 웹 안내 페이지
 */
class AccountDeletionApiTest extends ChatIntegrationTest {

    @Autowired
    AccountDeletionService deletionService;

    @Autowired
    S3Client s3;

    @Autowired
    MediaStorage storage;

    @Autowired
    MediaProperties mediaProperties;

    /** 실제 가입(소셜 계정 포함)한 사용자 */
    private record Member(long userId, String providerUserId, String authBody) {

        String accessToken() {
            return read(authBody, "$.tokens.accessToken");
        }

        String refreshToken() {
            return read(authBody, "$.tokens.refreshToken");
        }
    }

    @Test
    void 삭제를_요청하면_바로_모든_기기에서_로그아웃되고_30일_뒤_파기_예정이다() throws Exception {
        Member me = signUp();
        mockMvc.perform(get("/api/v1/users/me/consents").header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me/consents").header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(AuthApi.refresh(me.refreshToken())).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM users WHERE id = ?", String.class, me.userId()))
                .isEqualTo("PENDING_DELETION");
        Timestamp purgeAfter = jdbcTemplate.queryForObject("SELECT purge_after FROM account_deletion_requests "
                + "WHERE user_id = ? AND status = 'REQUESTED' AND source = 'APP'", Timestamp.class, me.userId());
        assertThat(Duration.between(Instant.now(), purgeAfter.toInstant())).isBetween(Duration.ofDays(29),
                Duration.ofDays(30));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = "
                + "'ACCOUNT_DELETION_REQUESTED' AND target_id = ?", Integer.class, me.userId())).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/users/me").with(asUser(me.userId()))).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM account_deletion_requests WHERE user_id = ?",
                Integer.class, me.userId())).isEqualTo(1);
    }

    @Test
    void 다른_가족이_있는_그룹의_그룹장이면_409이고_혼자인_그룹은_함께_삭제_예정이다() throws Exception {
        Member owner = signUp();
        long other = createUser();
        long family = GroupApi.createGroup(mockMvc, owner.userId(), "우리 가족");
        GroupApi.addMember(mockMvc, family, owner.userId(), other);

        mockMvc.perform(delete("/api/v1/users/me").with(asUser(owner.userId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_TRANSFER_REQUIRED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("'우리 가족'")));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM users WHERE id = ?", String.class,
                owner.userId())).isEqualTo("ACTIVE");

        Member solo = signUp();
        long alone = GroupApi.createGroup(mockMvc, solo.userId(), "혼자");
        mockMvc.perform(delete("/api/v1/users/me").with(asUser(solo.userId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deleteMedia\":true}"))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM family_groups WHERE id = ?", String.class, alone))
                .isEqualTo("PENDING_DELETION");
        assertThat(jdbcTemplate.queryForObject("SELECT delete_media FROM account_deletion_requests WHERE user_id = ?",
                Boolean.class, solo.userId())).isTrue();
    }

    @Test
    void 유예_중_로그인하면_복구할지_묻고_restore로_다시_로그인하면_그룹과_함께_돌아온다() throws Exception {
        Member me = signUp();
        long alone = GroupApi.createGroup(mockMvc, me.userId(), "혼자");
        mockMvc.perform(delete("/api/v1/users/me").with(asUser(me.userId()))).andExpect(status().isNoContent());

        mockMvc.perform(AuthApi.login("kakao", FakeSocialLoginVerifier.token(me.providerUserId()), "device-2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING_DELETION"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("일까지 복구할 수 있어요")));

        String body = restoreLogin(me).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(get("/api/v1/users/me/consents").header(HttpHeaders.AUTHORIZATION,
                "Bearer " + read(body, "$.tokens.accessToken"))).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM users WHERE id = ?", String.class, me.userId()))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM account_deletion_requests WHERE user_id = ?",
                String.class, me.userId())).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM family_groups WHERE id = ?", String.class, alone))
                .isEqualTo("ACTIVE");
        mockMvc.perform(get("/api/v1/groups/{g}", alone).with(asUser(me.userId()))).andExpect(status().isOk());
    }

    @Test
    void 유예가_지나지_않으면_파기하지_않는다() throws Exception {
        Member me = signUp();
        mockMvc.perform(delete("/api/v1/users/me").with(asUser(me.userId()))).andExpect(status().isNoContent());

        assertThat(deletionService.dueRequestIds(Instant.now())).doesNotContain(requestId(me));
        assertThat(jdbcTemplate.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, me.userId()))
                .isEqualTo("엄마");
    }

    @Test
    void 파기하면_탈퇴한_구성원이_되고_메시지는_남고_사진_위치는_지운다() throws Exception {
        Member me = signUp();
        long mom = createUser();
        long group = GroupApi.createGroup(mockMvc, mom, "우리 가족");
        GroupApi.addMember(mockMvc, group, mom, me.userId());
        long messageId = send(group, me.userId(), "잘 지내?").id();
        long photo = insertMedia(group, me.userId(), null, "test/" + UUID.randomUUID(), true);
        mockMvc.perform(delete("/api/v1/users/me").with(asUser(me.userId()))).andExpect(status().isNoContent());
        expire(me);

        assertThat(deletionService.dueRequestIds(Instant.now())).contains(requestId(me));
        assertThat(deletionService.purge(requestId(me))).isTrue();
        assertThat(deletionService.purge(requestId(me))).isFalse();

        assertThat(jdbcTemplate.queryForMap("SELECT nickname, birth_ym, status, deleted_at IS NOT NULL AS deleted "
                + "FROM users WHERE id = ?", me.userId()))
                .containsEntry("nickname", "탈퇴한 구성원").containsEntry("birth_ym", null)
                .containsEntry("status", "DELETED").containsEntry("deleted", true);
        assertThat(count("social_accounts WHERE user_id = " + me.userId())).isZero();
        assertThat(count("user_sessions WHERE user_id = " + me.userId())).isZero();
        assertThat(jdbcTemplate.queryForMap("SELECT left_at IS NOT NULL AS left_group, family_title FROM "
                + "group_members WHERE group_id = ? AND user_id = ?", group, me.userId()))
                .containsEntry("left_group", true).containsEntry("family_title", null);
        assertThat(jdbcTemplate.queryForObject("SELECT body FROM messages WHERE id = ? AND deleted_at IS NULL",
                String.class, messageId)).isEqualTo("잘 지내?");
        assertThat(jdbcTemplate.queryForObject("SELECT location IS NULL FROM media_assets WHERE id = ?",
                Boolean.class, photo)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT meta::text FROM audit_logs WHERE action = 'ACCOUNT_PURGED' "
                + "AND target_id = ?", String.class, me.userId())).contains("\"groups\": 1").doesNotContain("잘 지내");

        // 같은 소셜 계정으로 새로 가입할 수 있다
        mockMvc.perform(AuthApi.signup(me.providerUserId(), "device-3")).andExpect(status().isCreated());
    }

    @Test
    void 사진_동영상_삭제를_골랐으면_S3_객체와_함께_지우고_메시지를_삭제_처리한다() throws Exception {
        Member me = signUp();
        long mom = createUser();
        long group = GroupApi.createGroup(mockMvc, mom, "우리 가족");
        GroupApi.addMember(mockMvc, group, mom, me.userId());
        long messageId = send(group, me.userId(), "사진 보낼게").id();
        String key = "test/" + UUID.randomUUID();
        s3.putObject(builder -> builder.bucket(mediaProperties.s3().bucket()).key(key), RequestBody.fromString("x"));
        insertMedia(group, me.userId(), messageId, key, false);
        assertThat(storage.size(key)).isPresent();
        mockMvc.perform(delete("/api/v1/users/me").with(asUser(me.userId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deleteMedia\":true}"))
                .andExpect(status().isNoContent());
        expire(me);

        deletionService.purge(requestId(me));

        assertThat(storage.size(key)).isEmpty();
        assertThat(count("media_assets WHERE uploader_id = " + me.userId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM messages WHERE id = ?",
                Boolean.class, messageId)).isTrue();
    }

    @Test
    void 삭제_예정_계정에는_그룹장을_넘길_수_없고_파기되는_그룹장은_다른_가족에게_넘긴다() throws Exception {
        Member me = signUp();
        long owner = createUser();
        long dad = createUser();
        long group = GroupApi.createGroup(mockMvc, owner, "우리 가족");
        GroupApi.addMember(mockMvc, group, owner, me.userId());
        GroupApi.addMember(mockMvc, group, owner, dad);
        mockMvc.perform(delete("/api/v1/users/me").with(asUser(me.userId()))).andExpect(status().isNoContent());

        GroupApi.changeRole(mockMvc, group, owner, me.userId(), "OWNER").andExpect(status().isBadRequest());

        // 유예 중 위임받은 경우와 같다: 파기할 때 먼저 들어온 다른 가족에게 넘긴다
        jdbcTemplate.update("UPDATE group_members SET role = 'MEMBER' WHERE group_id = ? AND user_id = ?", group,
                owner);
        jdbcTemplate.update("UPDATE group_members SET role = 'OWNER' WHERE group_id = ? AND user_id = ?", group,
                me.userId());
        expire(me);
        deletionService.purge(requestId(me));

        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM group_members WHERE group_id = ? AND role = "
                + "'OWNER' AND left_at IS NULL", Long.class, group)).isEqualTo(owner);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM family_groups WHERE id = ?", String.class, group))
                .isEqualTo("ACTIVE");
    }

    @Test
    void 혼자인_그룹의_그룹장이_파기되면_그룹은_삭제_예정이다() throws Exception {
        Member me = signUp();
        long owner = createUser();
        long group = GroupApi.createGroup(mockMvc, owner, "우리 가족");
        GroupApi.addMember(mockMvc, group, owner, me.userId());
        mockMvc.perform(delete("/api/v1/users/me").with(asUser(me.userId()))).andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE group_members SET left_at = now() WHERE group_id = ? AND user_id = ?", group,
                owner);
        jdbcTemplate.update("UPDATE group_members SET role = 'OWNER' WHERE group_id = ? AND user_id = ?", group,
                me.userId());
        expire(me);

        deletionService.purge(requestId(me));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM family_groups WHERE id = ?", String.class, group))
                .isEqualTo("PENDING_DELETION");
        assertThat(jdbcTemplate.queryForObject("SELECT left_at IS NOT NULL FROM group_members WHERE group_id = ? "
                + "AND user_id = ?", Boolean.class, group, me.userId())).isTrue();
    }

    @Test
    void 관리자가_웹_요청을_대신_처리한다() throws Exception {
        Member me = signUp();

        mockMvc.perform(post("/admin/api/users/{id}/deletion", me.userId())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/api/users/{id}/deletion", me.userId()).header("X-Admin-Token",
                "test-admin-token")).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT source FROM account_deletion_requests WHERE user_id = ?",
                String.class, me.userId())).isEqualTo("ADMIN");
        mockMvc.perform(get("/api/v1/users/me/consents").header(HttpHeaders.AUTHORIZATION, bearer(me)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 웹_삭제_안내_페이지는_로그인_없이_열리고_연락처가_있다() throws Exception {
        mockMvc.perform(get("/account-deletion")).andExpect(status().isOk())
                .andExpect(forwardedUrl("/account-deletion/index.html"));
        String html = mockMvc.perform(get("/account-deletion/index.html")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("kimyunseo112@gmail.com", "30일");
    }

    private Member signUp() throws Exception {
        String providerUserId = "del-" + UUID.randomUUID();
        String body = AuthApi.signupAndGetBody(mockMvc, providerUserId, "device-1");
        return new Member(Long.parseLong(read(body, "$.user.id")), providerUserId, body);
    }

    private static String bearer(Member member) {
        return "Bearer " + member.accessToken();
    }

    private ResultActions restoreLogin(Member member) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/{p}/login", "kakao").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"providerToken":"%s","device":{"deviceId":"device-2","deviceName":"새 폰","platform":"ANDROID"},
                         "restore":true}""".formatted(FakeSocialLoginVerifier.token(member.providerUserId()))));
    }

    private long requestId(Member member) {
        return jdbcTemplate.queryForObject("SELECT id FROM account_deletion_requests WHERE user_id = ? "
                + "ORDER BY id DESC LIMIT 1", Long.class, member.userId());
    }

    private void expire(Member member) {
        jdbcTemplate.update("UPDATE account_deletion_requests SET purge_after = now() - interval '1 minute' "
                + "WHERE user_id = ?", member.userId());
    }

    private long insertMedia(long groupId, long uploaderId, Long messageId, String key, boolean withLocation) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO media_assets (group_id, uploader_id, message_id, media_type, mime, size, storage_key,
                    status, upload_expires_at, location)
                VALUES (?, ?, ?, 'IMAGE', 'image/jpeg', 10, ?, 'READY', now(),
                    CASE WHEN ? THEN ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography END)
                RETURNING id""", Long.class, groupId, uploaderId, messageId, key, withLocation);
    }

    private int count(String fromWhere) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class);
    }
}
