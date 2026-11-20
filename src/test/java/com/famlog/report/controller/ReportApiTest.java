package com.famlog.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.famlog.support.ChatIntegrationTest;
import com.famlog.support.Family;
import com.famlog.support.GroupApi;
import com.jayway.jsonpath.JsonPath;

/** 신고 (사양서 4.2 MOD-01, 9.3): 내가 볼 수 있는 메시지·사진·AI 후보·구성원만, 같은 대상 재신고는 한 건 */
class ReportApiTest extends ChatIntegrationTest {

    private Family family;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
    }

    @Test
    void 메시지를_신고하면_접수되고_그룹은_서버가_정한다() throws Exception {
        long messageId = send(family.groupId(), family.dad(), "광고 메시지").id();

        report(family.son(), "{\"targetType\":\"MESSAGE\",\"targetId\":%d,\"reason\":\"SPAM\",\"detail\":\"광고\"}"
                .formatted(messageId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.targetType").value("MESSAGE"))
                .andExpect(jsonPath("$.reason").value("SPAM"));

        assertThat(jdbcTemplate.queryForObject("SELECT group_id FROM reports WHERE target_id = ? AND reporter_id = ?",
                Long.class, messageId, family.son())).isEqualTo(family.groupId());
    }

    @Test
    void 같은_대상을_처리_전에_다시_신고하면_한_건이다() throws Exception {
        long messageId = send(family.groupId(), family.dad(), "광고 메시지").id();
        String body = "{\"targetType\":\"MESSAGE\",\"targetId\":%d,\"reason\":\"SPAM\"}".formatted(messageId);

        long first = id(report(family.son(), body).andExpect(status().isCreated()));
        long second = id(report(family.son(), body).andExpect(status().isCreated()));
        report(family.mom(), body).andExpect(status().isCreated());

        assertThat(second).isEqualTo(first);
        assertThat(count("reports WHERE target_type = 'MESSAGE' AND target_id = " + messageId)).isEqualTo(2);
    }

    @Test
    void 사진과_AI_후보와_구성원을_신고할_수_있다() throws Exception {
        long messageId = send(family.groupId(), family.dad(), "사진").id();
        long mediaId = jdbcTemplate.queryForObject("""
                INSERT INTO media_assets (group_id, uploader_id, message_id, media_type, mime, size, storage_key,
                    status, upload_expires_at)
                VALUES (?, ?, ?, 'IMAGE', 'image/jpeg', 10, ?, 'READY', now()) RETURNING id
                """, Long.class, family.groupId(), family.dad(), messageId, "test/" + UUID.randomUUID());
        long candidateId = candidate(family.groupId(), messageId);

        report(family.son(), "{\"targetType\":\"MEDIA\",\"targetId\":%d,\"reason\":\"SEXUAL\"}".formatted(mediaId))
                .andExpect(status().isCreated());
        report(family.son(), "{\"targetType\":\"AI_OUTPUT\",\"targetId\":%d,\"reason\":\"AI_INACCURATE\"}"
                .formatted(candidateId)).andExpect(status().isCreated());
        report(family.son(), "{\"targetType\":\"USER\",\"targetId\":%d,\"groupId\":%d,\"reason\":\"HARASSMENT\"}"
                .formatted(family.dad(), family.groupId())).andExpect(status().isCreated());
    }

    @Test
    void 사유가_없거나_기타인데_내용이_없거나_시스템_메시지나_나를_신고하면_400() throws Exception {
        long messageId = send(family.groupId(), family.dad(), "안녕").id();
        long systemId = jdbcTemplate.queryForObject("""
                SELECT m.id FROM messages m JOIN chat_rooms r ON r.id = m.room_id
                WHERE r.group_id = ? AND m.type = 'SYSTEM' ORDER BY m.seq DESC LIMIT 1
                """, Long.class, family.groupId());

        report(family.son(), "{\"targetType\":\"MESSAGE\",\"targetId\":%d}".formatted(messageId))
                .andExpect(status().isBadRequest());
        report(family.son(), "{\"targetType\":\"MESSAGE\",\"targetId\":%d,\"reason\":\"OTHER\",\"detail\":\" \"}"
                .formatted(messageId)).andExpect(status().isBadRequest());
        report(family.son(), "{\"targetType\":\"MESSAGE\",\"targetId\":%d,\"reason\":\"SPAM\"}".formatted(systemId))
                .andExpect(status().isBadRequest());
        report(family.son(), "{\"targetType\":\"USER\",\"targetId\":%d,\"groupId\":%d,\"reason\":\"SPAM\"}"
                .formatted(family.son(), family.groupId())).andExpect(status().isBadRequest());
        report(family.son(), "{\"targetType\":\"USER\",\"targetId\":%d,\"reason\":\"SPAM\"}".formatted(family.dad()))
                .andExpect(status().isBadRequest());
        report(family.son(), "{\"targetType\":\"MESSAGE\",\"targetId\":%d,\"reason\":\"SPAM\",\"detail\":\"%s\"}"
                .formatted(messageId, "가".repeat(501))).andExpect(status().isBadRequest());
    }

    @Test
    void 다른_그룹의_대상은_404이고_구성원이_아닌_그룹의_구성원_신고는_403() throws Exception {
        Family other = Family.create(mockMvc, this::createUser);
        long theirMessage = send(other.groupId(), other.dad(), "남의 집 대화").id();
        long theirCandidate = candidate(other.groupId(), theirMessage);

        report(family.son(), "{\"targetType\":\"MESSAGE\",\"targetId\":%d,\"reason\":\"SPAM\"}".formatted(theirMessage))
                .andExpect(status().isNotFound());
        report(family.son(), "{\"targetType\":\"AI_OUTPUT\",\"targetId\":%d,\"reason\":\"SPAM\"}"
                .formatted(theirCandidate)).andExpect(status().isNotFound());
        report(family.son(), "{\"targetType\":\"MEDIA\",\"targetId\":999999999,\"reason\":\"SPAM\"}")
                .andExpect(status().isNotFound());
        report(family.son(), "{\"targetType\":\"USER\",\"targetId\":%d,\"groupId\":%d,\"reason\":\"SPAM\"}"
                .formatted(other.dad(), other.groupId())).andExpect(status().isForbidden());
        report(family.son(), "{\"targetType\":\"USER\",\"targetId\":%d,\"groupId\":%d,\"reason\":\"SPAM\"}"
                .formatted(other.dad(), family.groupId())).andExpect(status().isNotFound());
        assertThat(count("reports WHERE reporter_id = " + family.son())).isZero();
    }

    @Test
    void 들어오기_전_메시지는_신고할_수_없다() throws Exception {
        long before = send(family.groupId(), family.dad(), "예전 대화").id();
        long newcomer = createUser();
        GroupApi.addMember(mockMvc, family.groupId(), family.mom(), newcomer);

        report(newcomer, "{\"targetType\":\"MESSAGE\",\"targetId\":%d,\"reason\":\"SPAM\"}".formatted(before))
                .andExpect(status().isNotFound());
    }

    private ResultActions report(long userId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/reports").with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** 그 메시지를 근거로 한 대기 중 후보 (S18 경로 대신 직접 넣는다) */
    private long candidate(long groupId, long messageId) {
        long roomId = jdbcTemplate.queryForObject("SELECT id FROM chat_rooms WHERE group_id = ?", Long.class, groupId);
        long jobId = jdbcTemplate.queryForObject("""
                INSERT INTO ai_jobs (group_id, room_id, from_seq, to_seq, trigger, message_count, member_refs)
                VALUES (?, ?, 0, 1, 'RULE', 1, '{}') RETURNING id
                """, Long.class, groupId, roomId);
        return jdbcTemplate.queryForObject("""
                INSERT INTO registration_candidates (group_id, room_id, job_id, type, fields, confidence, dedup_key,
                    expires_at)
                VALUES (?, ?, ?, 'TODO', '{"title":"세제 사 오기"}', 0.9, ?, now() + interval '1 day') RETURNING id
                """, Long.class, groupId, roomId, jobId, "TODO|" + UUID.randomUUID());
    }

    private int count(String fromWhere) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class);
    }
}
