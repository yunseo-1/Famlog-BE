package com.famlog.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.famlog.support.GroupApi;
import com.famlog.support.IntegrationTest;

/** 내 동의 (사양서 8.3, 11.3): 위치기반서비스 동의·철회와 이력. */
class ConsentApiTest extends IntegrationTest {

    private static final String CONSENTS = "/api/v1/users/me/consents";

    @Test
    void 처음에는_모두_동의하지_않았고_종류마다_현재_버전을_알려_준다() throws Exception {
        mockMvc.perform(get(CONSENTS).with(asUser(createUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].type",
                        contains("TERMS", "PRIVACY", "AI_ANALYSIS", "OVERSEAS_TRANSFER", "LOCATION")))
                .andExpect(jsonPath("$.items[*].agreed", contains(false, false, false, false, false)))
                .andExpect(jsonPath("$.items[4].currentVersion").value("1.0"))
                .andExpect(jsonPath("$.items[4].docVersion").doesNotExist())
                .andExpect(jsonPath("$.items[2].currentVersion").doesNotExist());
    }

    @Test
    void 위치에_동의하고_다시_동의해도_한_번이고_철회하면_이력이_남고_다시_동의하면_새로_남는다() throws Exception {
        long user = createUser();

        location(user, "{\"agreed\":true,\"docVersion\":\"1.0\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("LOCATION"))
                .andExpect(jsonPath("$.agreed").value(true))
                .andExpect(jsonPath("$.docVersion").value("1.0"))
                .andExpect(jsonPath("$.agreedAt").exists())
                .andExpect(jsonPath("$.reconsentRequired").value(false));
        location(user, "{\"agreed\":true,\"docVersion\":\"1.0\"}").andExpect(status().isOk());
        assertThat(rows(user)).isEqualTo(1);
        mockMvc.perform(get(CONSENTS).with(asUser(user))).andExpect(jsonPath("$.items[4].agreed").value(true));

        location(user, "{\"agreed\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agreed").value(false));
        location(user, "{\"agreed\":false}").andExpect(status().isOk());
        assertThat(rows(user)).isEqualTo(1);
        assertThat(activeRows(user)).isZero();

        location(user, "{\"agreed\":true,\"docVersion\":\"1.0\"}").andExpect(status().isOk());
        assertThat(rows(user)).isEqualTo(2);
        assertThat(activeRows(user)).isEqualTo(1);
    }

    @Test
    void 예전_버전에_동의했으면_다시_받아야_하고_새_버전에_동의하면_예전_동의는_철회된다() throws Exception {
        long user = createUser();
        jdbcTemplate.update("INSERT INTO consents (user_id, type, doc_version, agreed_at) "
                + "VALUES (?, 'LOCATION', '0.9', now() - interval '1 day')", user);

        mockMvc.perform(get(CONSENTS).with(asUser(user)))
                .andExpect(jsonPath("$.items[4].agreed").value(true))
                .andExpect(jsonPath("$.items[4].docVersion").value("0.9"))
                .andExpect(jsonPath("$.items[4].reconsentRequired").value(true));
        location(user, "{\"agreed\":true,\"docVersion\":\"1.0\"}")
                .andExpect(jsonPath("$.docVersion").value("1.0"))
                .andExpect(jsonPath("$.reconsentRequired").value(false));
        assertThat(jdbcTemplate.queryForObject("SELECT doc_version FROM consents WHERE user_id = ? "
                + "AND withdrawn_at IS NULL", String.class, user)).isEqualTo("1.0");
    }

    @Test
    void 버전이_다르거나_없거나_agreed가_없으면_400() throws Exception {
        long user = createUser();
        for (String body : new String[] { "{\"agreed\":true,\"docVersion\":\"0.9\"}", "{\"agreed\":true}", "{}",
                "{\"docVersion\":\"1.0\"}" }) {
            location(user, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(rows(user)).isZero();
    }

    @Test
    void 위치_외의_동의는_여기서_바꿀_수_없다() throws Exception {
        long user = createUser();
        for (String type : new String[] { "TERMS", "PRIVACY", "AI_ANALYSIS", "OVERSEAS_TRANSFER", "MARKETING" }) {
            mockMvc.perform(put(CONSENTS + "/{t}", type).with(asUser(user)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"agreed\":false}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void 위치_동의를_철회하면_내가_올린_사진의_위치만_지운다() throws Exception {
        long user = createUser();
        long other = createUser();
        long groupId = GroupApi.createGroup(mockMvc, user, "우리 가족");
        long mine = insertPhotoWithLocation(groupId, user);
        long theirs = insertPhotoWithLocation(groupId, other);
        location(user, "{\"agreed\":true,\"docVersion\":\"1.0\"}").andExpect(status().isOk());

        location(user, "{\"agreed\":false}").andExpect(status().isOk());

        assertThat(hasLocation(mine)).isFalse();
        assertThat(hasLocation(theirs)).isTrue();
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(get(CONSENTS)).andExpect(status().isUnauthorized());
    }

    private ResultActions location(long userId, String body) throws Exception {
        return mockMvc.perform(put(CONSENTS + "/LOCATION").with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long rows(long userId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM consents WHERE user_id = ? AND type = 'LOCATION'",
                Long.class, userId);
    }

    private long activeRows(long userId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM consents WHERE user_id = ? AND type = 'LOCATION' "
                + "AND withdrawn_at IS NULL", Long.class, userId);
    }

    private long insertPhotoWithLocation(long groupId, long uploaderId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO media_assets (group_id, uploader_id, media_type, mime, size, storage_key, status,
                    upload_expires_at, location)
                VALUES (?, ?, 'IMAGE', 'image/jpeg', 10, ?, 'READY', now(),
                    ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography)
                RETURNING id""", Long.class, groupId, uploaderId, "test/" + UUID.randomUUID());
    }

    private boolean hasLocation(long mediaId) {
        return jdbcTemplate.queryForObject("SELECT location IS NOT NULL FROM media_assets WHERE id = ?",
                Boolean.class, mediaId);
    }
}
