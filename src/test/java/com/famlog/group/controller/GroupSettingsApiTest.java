package com.famlog.group.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.famlog.support.Family;
import com.famlog.support.GroupApi;
import com.famlog.support.IntegrationTest;

/**
 * 그룹 설정 (사양서 4.1 SET-01): 조회는 구성원, AI 분석 켜기·끄기는 ADMIN 이상. 기본은 꺼짐.
 * 켤 때 AI 분석 안내 확인, 구성원 안내(aiNoticePending), 관리자의 분석 제외 보기 (8.3, 8.8, S20-1)
 */
class GroupSettingsApiTest extends IntegrationTest {

    private static final String SETTINGS = "/api/v1/groups/{groupId}/settings";
    private static final String ON = "{\"aiAnalysisEnabled\":true,\"aiNoticeVersion\":\"1.0\"}";

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
        patchSettings(family.mom(), ON)
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
        patchSettings(family.dad(), ON)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_REQUIRED"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM group_settings WHERE group_id = ?",
                Integer.class, family.groupId())).isZero();

        GroupApi.changeRole(mockMvc, family.groupId(), family.mom(), family.dad(), "ADMIN")
                .andExpect(status().isOk());
        patchSettings(family.dad(), ON).andExpect(status().isOk());
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
        patchSettings(stranger, ON)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));
    }

    @Test
    void 켜면_분석_기준점이_지금_메시지로_옮겨진다() throws Exception {
        long lastSeq = jdbcTemplate.queryForObject("SELECT last_seq FROM chat_rooms WHERE group_id = ?", Long.class,
                family.groupId());
        assertThat(lastSeq).isPositive(); // 입장 시스템 메시지

        patchSettings(family.mom(), ON).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT ai_analyzed_seq FROM chat_rooms WHERE group_id = ?",
                Long.class, family.groupId())).isEqualTo(lastSeq);
    }

    // ---- AI 분석 안내 (S20-1) ----

    @Test
    void 안내_내용과_버전을_내려준다() throws Exception {
        mockMvc.perform(get("/api/v1/ai-notice").with(asUser(family.son())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(GroupApi.AI_NOTICE_VERSION))
                .andExpect(jsonPath("$.recipient").isNotEmpty())
                .andExpect(jsonPath("$.country").value("미국"))
                .andExpect(jsonPath("$.sentItems.length()").value(2))
                .andExpect(jsonPath("$.notSentItems.length()").value(3))
                .andExpect(jsonPath("$.refusal").isNotEmpty());
    }

    @Test
    void 켤_때는_현재_안내_버전이_필요하고_끌_때는_필요_없다() throws Exception {
        patchSettings(family.mom(), "{\"aiAnalysisEnabled\":true}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("aiNoticeVersion"));
        patchSettings(family.mom(), "{\"aiAnalysisEnabled\":true,\"aiNoticeVersion\":\"0.9\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("aiNoticeVersion"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM group_settings WHERE group_id = ? "
                + "AND ai_analysis_enabled", Integer.class, family.groupId())).isZero();

        patchSettings(family.mom(), ON).andExpect(status().isOk())
                .andExpect(jsonPath("$.aiNoticeVersion").value("1.0"))
                .andExpect(jsonPath("$.aiNoticePending").value(false));
        assertThat(jdbcTemplate.queryForList("SELECT type || ':' || doc_version FROM consents WHERE user_id = ? "
                + "AND withdrawn_at IS NULL ORDER BY type", String.class, family.mom()))
                .containsExactly("AI_ANALYSIS:1.0", "OVERSEAS_TRANSFER:1.0");
        patchSettings(family.mom(), "{\"aiAnalysisEnabled\":true}").andExpect(status().isOk());
        patchSettings(family.mom(), "{\"aiAnalysisEnabled\":false}").andExpect(status().isOk());
    }

    @Test
    void 켜진_그룹의_구성원은_안내를_확인할_때까지_pending이고_안내가_바뀌면_다시_pending이다() throws Exception {
        mockMvc.perform(get(SETTINGS, family.groupId()).with(asUser(family.dad())))
                .andExpect(jsonPath("$.aiNoticePending").value(false));
        patchSettings(family.mom(), ON).andExpect(status().isOk());

        mockMvc.perform(get(SETTINGS, family.groupId()).with(asUser(family.dad())))
                .andExpect(jsonPath("$.aiNoticePending").value(true));
        mockMvc.perform(put("/api/v1/users/me/consents/AI_ANALYSIS").with(asUser(family.dad()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agreed\":true,\"docVersion\":\"1.0\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(SETTINGS, family.groupId()).with(asUser(family.dad())))
                .andExpect(jsonPath("$.aiNoticePending").value(false));

        // 안내 버전이 1.0 으로 오른 것과 같다: 예전 버전 확인만 있으면 다시 안내
        jdbcTemplate.update("UPDATE consents SET doc_version = '0.9' WHERE user_id = ?", family.dad());
        mockMvc.perform(get(SETTINGS, family.groupId()).with(asUser(family.dad())))
                .andExpect(jsonPath("$.aiNoticePending").value(true));
    }

    @Test
    void 이_그룹에서_분석_제외했으면_안내하지_않는다() throws Exception {
        patchSettings(family.mom(), ON).andExpect(status().isOk());
        GroupApi.setAiOptOut(mockMvc, family.groupId(), family.son(), true);

        mockMvc.perform(get(SETTINGS, family.groupId()).with(asUser(family.son())))
                .andExpect(jsonPath("$.aiNoticePending").value(false));
        GroupApi.setAiOptOut(mockMvc, family.groupId(), family.son(), false);
        mockMvc.perform(get(SETTINGS, family.groupId()).with(asUser(family.son())))
                .andExpect(jsonPath("$.aiNoticePending").value(true));
    }

    @Test
    void 구성원의_분석_제외는_관리자만_볼_수_있다() throws Exception {
        GroupApi.setAiOptOut(mockMvc, family.groupId(), family.son(), true);

        mockMvc.perform(get("/api/v1/groups/{g}", family.groupId()).with(asUser(family.mom())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.userId == %d)].aiOptOut".formatted(family.son()))
                        .value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.members[?(@.userId == %d)].aiOptOut".formatted(family.dad()))
                        .value(org.hamcrest.Matchers.contains(false)));
        mockMvc.perform(get("/api/v1/groups/{g}", family.groupId()).with(asUser(family.dad())))
                .andExpect(jsonPath("$.members[0].aiOptOut").doesNotExist())
                .andExpect(jsonPath("$.members[2].aiOptOut").doesNotExist());
    }

    private ResultActions patchSettings(long userId, String body) throws Exception {
        return mockMvc.perform(patch(SETTINGS, family.groupId()).with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
