package com.famlog.group.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.famlog.support.Family;
import com.famlog.support.GroupApi;
import com.famlog.support.IntegrationTest;

/** 그룹 설정 (사양서 4.1 SET-01): 조회는 구성원, AI 분석 켜기·끄기는 ADMIN 이상. 기본은 꺼짐. */
class GroupSettingsApiTest extends IntegrationTest {

    private static final String SETTINGS = "/api/v1/groups/{groupId}/settings";

    private Family family;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
    }

    @Test
    void 설정한_적이_없으면_AI_분석은_꺼져_있다() throws Exception {
        mockMvc.perform(get(SETTINGS, family.groupId()).with(asUser(family.son())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiAnalysisEnabled").value(false))
                .andExpect(jsonPath("$.aiEnabledAt").doesNotExist());
    }

    @Test
    void 그룹장이_AI_분석을_켜고_끌_수_있다() throws Exception {
        patchSettings(family.mom(), "{\"aiAnalysisEnabled\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiAnalysisEnabled").value(true))
                .andExpect(jsonPath("$.aiEnabledAt").value(org.hamcrest.Matchers.endsWith("+09:00")));
        mockMvc.perform(get(SETTINGS, family.groupId()).with(asUser(family.dad())))
                .andExpect(jsonPath("$.aiAnalysisEnabled").value(true));

        patchSettings(family.mom(), "{\"aiAnalysisEnabled\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiAnalysisEnabled").value(false));
        assertThat(jdbcTemplate.queryForObject("SELECT ai_enabled_by FROM group_settings WHERE group_id = ?",
                Long.class, family.groupId())).isEqualTo(family.mom());
    }

    @Test
    void 일반_구성원은_바꿀_수_없고_관리자로_올리면_바꿀_수_있다() throws Exception {
        patchSettings(family.dad(), "{\"aiAnalysisEnabled\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_REQUIRED"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM group_settings WHERE group_id = ?",
                Integer.class, family.groupId())).isZero();

        GroupApi.changeRole(mockMvc, family.groupId(), family.mom(), family.dad(), "ADMIN")
                .andExpect(status().isOk());
        patchSettings(family.dad(), "{\"aiAnalysisEnabled\":true}").andExpect(status().isOk());
    }

    @Test
    void 값이_없으면_400() throws Exception {
        patchSettings(family.mom(), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 구성원이_아니면_보거나_바꿀_수_없다() throws Exception {
        long stranger = createUser();

        mockMvc.perform(get(SETTINGS, family.groupId()).with(asUser(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));
        patchSettings(stranger, "{\"aiAnalysisEnabled\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));
    }

    @Test
    void 켜면_분석_기준점이_지금_메시지로_옮겨진다() throws Exception {
        long lastSeq = jdbcTemplate.queryForObject("SELECT last_seq FROM chat_rooms WHERE group_id = ?", Long.class,
                family.groupId());
        assertThat(lastSeq).isPositive(); // 입장 시스템 메시지

        patchSettings(family.mom(), "{\"aiAnalysisEnabled\":true}").andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT ai_analyzed_seq FROM chat_rooms WHERE group_id = ?",
                Long.class, family.groupId())).isEqualTo(lastSeq);
    }

    private ResultActions patchSettings(long userId, String body) throws Exception {
        return mockMvc.perform(patch(SETTINGS, family.groupId()).with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
