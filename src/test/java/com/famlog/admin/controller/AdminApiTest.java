package com.famlog.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.famlog.support.ChatIntegrationTest;
import com.famlog.support.Family;
import com.famlog.support.GroupApi;
import com.famlog.support.StompTestClient;
import com.jayway.jsonpath.JsonPath;

/**
 * 최소 관리자 도구 (사양서 14장, 4.2 MOD-01, 9장 운영자 접근): 토큰, 신고 열람(감사 로그)·조치, 공휴일, 앱 버전·426, 기능 플래그.
 * feature_flags·app_versions 는 다른 테스트와 함께 쓰는 표라 끝나면 되돌린다
 */
class AdminApiTest extends ChatIntegrationTest {

    private static final String TOKEN = "test-admin-token";

    private Family family;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
    }

    @AfterEach
    void restoreShared() {
        jdbcTemplate.update("DELETE FROM feature_flags");
        jdbcTemplate.update("UPDATE app_versions SET min_supported_version = '0.0.0', latest_version = NULL, "
                + "notice = NULL, notice_updated_at = NULL WHERE platform = 'ANDROID'");
    }

    @Test
    void 토큰이_없거나_틀리거나_앱_사용자_토큰이면_401() throws Exception {
        mockMvc.perform(get("/admin/api/reports")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/api/reports").header("X-Admin-Token", "wrong"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/api/reports").with(asUser(family.mom()))).andExpect(status().isUnauthorized());
        admin(get("/admin/api/reports")).andExpect(status().isOk());
    }

    @Test
    void 관리자_페이지는_로그인_없이_열린다() throws Exception {
        mockMvc.perform(get("/admin/")).andExpect(status().isOk()).andExpect(forwardedUrl("/admin/index.html"));
    }

    @Test
    void 신고는_목록에_콘텐츠가_없고_상세를_열면_감사_로그가_남는다() throws Exception {
        long messageId = send(family.groupId(), family.dad(), "신고될 메시지").id();
        long other = send(family.groupId(), family.dad(), "신고 안 된 메시지").id();
        long reportId = report(family.son(), "MESSAGE", messageId, "HARASSMENT");

        String list = admin(get("/admin/api/reports").param("status", "OPEN")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).contains("\"id\":" + reportId).doesNotContain("신고될 메시지");

        admin(get("/admin/api/reports/{id}", reportId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.report.targetType").value("MESSAGE"))
                .andExpect(jsonPath("$.reporterId").value(family.son()))
                .andExpect(jsonPath("$.content.messageId").value(messageId))
                .andExpect(jsonPath("$.content.body").value("신고될 메시지"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'ADMIN_REPORT_VIEWED' "
                + "AND target_id = ? AND actor_id IS NULL", Integer.class, reportId)).isEqualTo(1);
        // 관리자 API 에는 신고되지 않은 메시지를 읽는 길이 없다
        admin(get("/admin/api/messages/{id}", other)).andExpect(status().isNotFound());
    }

    @Test
    void 아동_안전_신고가_목록_맨_위에_온다() throws Exception {
        long first = send(family.groupId(), family.dad(), "하나").id();
        long second = send(family.groupId(), family.dad(), "둘").id();
        long child = report(family.son(), "MESSAGE", first, "CHILD_SAFETY");
        report(family.son(), "MESSAGE", second, "SPAM");

        admin(get("/admin/api/reports").param("status", "OPEN"))
                .andExpect(jsonPath("$.items[0].id").value(child));
    }

    @Test
    void 메시지_조치는_모두에게서_숨기고_같은_대상의_신고를_함께_처리한다() throws Exception {
        long messageId = send(family.groupId(), family.dad(), "숨길 메시지").id();
        long reportId = report(family.son(), "MESSAGE", messageId, "HARASSMENT");
        report(family.mom(), "MESSAGE", messageId, "HARASSMENT");

        try (StompTestClient client = StompTestClient.connect(port, token(family.mom()))) {
            BlockingQueue<String> events = client.subscribeGroup(family.groupId());
            admin(post("/admin/api/reports/{id}/resolve", reportId).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"ACTIONED\",\"action\":\"HIDE_MESSAGE\",\"note\":\"괴롭힘 확인\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resolvedCount").value(2))
                    .andExpect(jsonPath("$.report.status").value("ACTIONED"));
            assertThat(awaitEvent(events, "message.deleted")).contains("\"id\":" + messageId);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM messages WHERE id = ?",
                Boolean.class, messageId)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM reports WHERE target_id = ? AND status = "
                + "'ACTIONED'", Integer.class, messageId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT meta->>'count' FROM audit_logs WHERE action = "
                + "'ADMIN_REPORT_RESOLVED' AND target_id = ?", String.class, reportId)).isEqualTo("2");
    }

    @Test
    void AI_후보_조치는_대기_중_후보를_무시로_바꾸고_맞지_않는_조치는_400() throws Exception {
        long messageId = send(family.groupId(), family.dad(), "세제 사 와").id();
        long candidateId = candidate(messageId);
        long reportId = report(family.son(), "AI_OUTPUT", candidateId, "AI_INACCURATE");

        resolve(reportId, "{\"status\":\"ACTIONED\",\"action\":\"HIDE_MESSAGE\"}").andExpect(status().isBadRequest());
        resolve(reportId, "{\"status\":\"REJECTED\",\"action\":\"DISMISS_CANDIDATE\"}")
                .andExpect(status().isBadRequest());
        resolve(reportId, "{\"status\":\"OPEN\"}").andExpect(status().isBadRequest());
        resolve(reportId, "{\"status\":\"ACTIONED\",\"action\":\"DISMISS_CANDIDATE\"}").andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM registration_candidates WHERE id = ?",
                String.class, candidateId)).isEqualTo("DISMISSED");
    }

    @Test
    void 공휴일을_추가하고_지우면_공휴일_조회에_반영된다() throws Exception {
        String body = admin(post("/admin/api/holidays").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2028-01-01\",\"name\":\"신정\",\"substitute\":false}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        mockMvc.perform(get("/api/v1/holidays").param("year", "2028").with(asUser(family.son())))
                .andExpect(jsonPath("$.items[0].name").value("신정"));
        admin(delete("/admin/api/holidays/{id}", id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/holidays").param("year", "2028").with(asUser(family.son())))
                .andExpect(jsonPath("$.items.length()").value(0));
        admin(delete("/admin/api/holidays/{id}", id)).andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action LIKE 'ADMIN_HOLIDAY_%' "
                + "AND target_id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    void 최소_버전보다_낮은_앱은_426이고_헤더가_없거나_설정_조회는_통과한다() throws Exception {
        admin(put("/admin/api/app-versions/ANDROID").contentType(MediaType.APPLICATION_JSON)
                .content("{\"minSupportedVersion\":\"1.2.0\",\"latestVersion\":\"1.3.0\",\"notice\":\"점검 안내\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me/consents").with(asUser(family.son())).header("X-App-Version", "1.1.9")
                        .header("X-Platform", "ANDROID"))
                .andExpect(status().isUpgradeRequired())
                .andExpect(jsonPath("$.code").value("UPGRADE_REQUIRED"));
        mockMvc.perform(get("/api/v1/users/me/consents").with(asUser(family.son())).header("X-App-Version", "1.2.0"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/me/consents").with(asUser(family.son()))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/app/config").header("X-App-Version", "1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("ANDROID"))
                .andExpect(jsonPath("$.minSupportedVersion").value("1.2.0"))
                .andExpect(jsonPath("$.latestVersion").value("1.3.0"))
                .andExpect(jsonPath("$.notice").value("점검 안내"))
                .andExpect(jsonPath("$.noticeUpdatedAt").exists())
                .andExpect(jsonPath("$.features.aiAnalysis").value(true));

        // 공지가 같으면 시각을 그대로 두고, 바뀌면 새 시각, 지우면 null
        java.sql.Timestamp first = noticeUpdatedAt();
        admin(put("/admin/api/app-versions/ANDROID").contentType(MediaType.APPLICATION_JSON)
                .content("{\"minSupportedVersion\":\"1.2.1\",\"notice\":\"점검 안내\"}")).andExpect(status().isOk());
        assertThat(noticeUpdatedAt()).isEqualTo(first);
        admin(put("/admin/api/app-versions/ANDROID").contentType(MediaType.APPLICATION_JSON)
                .content("{\"minSupportedVersion\":\"1.2.1\",\"notice\":\"새 공지\"}")).andExpect(status().isOk());
        assertThat(noticeUpdatedAt()).isAfter(first);
        admin(put("/admin/api/app-versions/ANDROID").contentType(MediaType.APPLICATION_JSON)
                .content("{\"minSupportedVersion\":\"1.2.1\"}")).andExpect(status().isOk());
        assertThat(noticeUpdatedAt()).isNull();

        admin(put("/admin/api/app-versions/ANDROID").contentType(MediaType.APPLICATION_JSON)
                .content("{\"minSupportedVersion\":\"1.x\"}")).andExpect(status().isBadRequest());
        admin(put("/admin/api/app-versions/WEB").contentType(MediaType.APPLICATION_JSON)
                .content("{\"minSupportedVersion\":\"1.0.0\"}")).andExpect(status().isNotFound());
    }

    @Test
    void 기능_플래그로_내_주변과_AI_분석을_끈다() throws Exception {
        admin(get("/admin/api/feature-flags")).andExpect(jsonPath("$[0].key").value("place.nearby"))
                .andExpect(jsonPath("$[0].source").value("DEFAULT"));
        admin(put("/admin/api/feature-flags/{k}", "place.nearby").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(jsonPath("$.source").value("DB"))
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(get("/api/v1/groups/{g}/places/nearby", family.groupId()).param("lat", "37.5")
                .param("lng", "127.0").with(asUser(family.son()))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/app/config")).andExpect(jsonPath("$.features.nearby").value(false));

        GroupApi.setAiAnalysis(mockMvc, family.groupId(), family.mom(), true);
        admin(put("/admin/api/feature-flags/{k}", "ai.analysis").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isOk());
        send(family.groupId(), family.dad(), "토요일 11시에 할머니 댁 가자");
        long roomId = jdbcTemplate.queryForObject("SELECT id FROM chat_rooms WHERE group_id = ?", Long.class,
                family.groupId());
        assertThat(redis.hasKey("ai:room:" + roomId)).isFalse(); // AiTriggerService.roomKey

        admin(put("/admin/api/feature-flags/{k}", "nope").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isNotFound());
        admin(get("/admin/api/audit-logs").param("limit", "5"))
                .andExpect(jsonPath("$[0].action").value("ADMIN_FEATURE_FLAG_CHANGED"));
    }

    private java.sql.Timestamp noticeUpdatedAt() {
        return jdbcTemplate.queryForObject("SELECT notice_updated_at FROM app_versions WHERE platform = 'ANDROID'",
                java.sql.Timestamp.class);
    }

    private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("X-Admin-Token", TOKEN));
    }

    private ResultActions resolve(long reportId, String body) throws Exception {
        return admin(post("/admin/api/reports/{id}/resolve", reportId).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long report(long userId, String type, long targetId, String reason) throws Exception {
        String body = mockMvc.perform(post("/api/v1/reports").with(asUser(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"%s\",\"targetId\":%d,\"reason\":\"%s\"}".formatted(type, targetId,
                                reason)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long candidate(long messageId) {
        long roomId = jdbcTemplate.queryForObject("SELECT id FROM chat_rooms WHERE group_id = ?", Long.class,
                family.groupId());
        long jobId = jdbcTemplate.queryForObject("""
                INSERT INTO ai_jobs (group_id, room_id, from_seq, to_seq, trigger, message_count, member_refs)
                VALUES (?, ?, 0, 1, 'RULE', 1, '{}') RETURNING id
                """, Long.class, family.groupId(), roomId);
        return jdbcTemplate.queryForObject("""
                INSERT INTO registration_candidates (group_id, room_id, job_id, type, fields, confidence, dedup_key,
                    expires_at)
                VALUES (?, ?, ?, 'TODO', '{"title":"세제 사 오기"}', 0.9, ?, now() + interval '1 day') RETURNING id
                """, Long.class, family.groupId(), roomId, jobId, "TODO|" + UUID.randomUUID());
    }

    private static String awaitEvent(BlockingQueue<String> events, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + StompTestClient.WAIT_SECONDS * 1000;
        while (System.currentTimeMillis() < deadline) {
            String event = events.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (event != null && event.contains("\"type\":\"" + type + "\"")) {
                return event;
            }
        }
        throw new AssertionError(type + " 이벤트가 오지 않았습니다");
    }
}
