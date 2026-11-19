package com.famlog.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.famlog.support.GroupApi;
import com.famlog.support.IntegrationTest;

/** 알림 설정 (사양서 4.1 SET-02): 미리보기 숨기기·방해 금지(사용자), 알림 끄기(그룹). */
class NotificationSettingsApiTest extends IntegrationTest {

    private static final String SETTINGS = "/api/v1/users/me/notification-settings";
    private static final String PREFERENCES = "/api/v1/groups/{groupId}/members/me/preferences";

    @Test
    void 설정한_적이_없으면_기본값이다() throws Exception {
        mockMvc.perform(get(SETTINGS).with(asUser(createUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewHidden").value(false))
                .andExpect(jsonPath("$.quietHoursEnabled").value(false))
                .andExpect(jsonPath("$.quietStart").value("22:00"))
                .andExpect(jsonPath("$.quietEnd").value("07:00"));
    }

    @Test
    void 보낸_값만_바뀌고_다시_읽어도_같다() throws Exception {
        long userId = createUser();
        patchSettings(userId, "{\"previewHidden\":true}").andExpect(status().isOk());

        patchSettings(userId, "{\"quietHoursEnabled\":true,\"quietStart\":\"23:30\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewHidden").value(true))
                .andExpect(jsonPath("$.quietHoursEnabled").value(true))
                .andExpect(jsonPath("$.quietStart").value("23:30"))
                .andExpect(jsonPath("$.quietEnd").value("07:00"));
        mockMvc.perform(get(SETTINGS).with(asUser(userId)))
                .andExpect(jsonPath("$.previewHidden").value(true))
                .andExpect(jsonPath("$.quietStart").value("23:30"));
    }

    @Test
    void 시각_형식이_틀리면_400() throws Exception {
        long userId = createUser();
        for (String time : new String[] { "24:00", "7:00", "07:60", "오후 10시" }) {
            patchSettings(userId, "{\"quietStart\":\"%s\"}".formatted(time))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void 시작과_끝이_같으면_400이고_저장되지_않는다() throws Exception {
        long userId = createUser();

        patchSettings(userId, "{\"quietStart\":\"07:00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get(SETTINGS).with(asUser(userId))).andExpect(jsonPath("$.quietStart").value("22:00"));
    }

    @Test
    void 그룹_알림을_끄고_켤_수_있다() throws Exception {
        long userId = createUser();
        long groupId = GroupApi.createGroup(mockMvc, userId, "우리 가족");
        mockMvc.perform(get(PREFERENCES, groupId).with(asUser(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifMuted").value(false));

        patchPreferences(groupId, userId, "{\"notifMuted\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifMuted").value(true));

        mockMvc.perform(get(PREFERENCES, groupId).with(asUser(userId))).andExpect(jsonPath("$.notifMuted").value(true));
        patchPreferences(groupId, userId, "{\"notifMuted\":false}").andExpect(jsonPath("$.notifMuted").value(false));
    }

    @Test
    void 내_메시지_AI_분석_제외를_바꿀_수_있고_알림_설정은_그대로다() throws Exception {
        long userId = createUser();
        long groupId = GroupApi.createGroup(mockMvc, userId, "우리 가족");
        patchPreferences(groupId, userId, "{\"notifMuted\":true}").andExpect(status().isOk());
        mockMvc.perform(get(PREFERENCES, groupId).with(asUser(userId)))
                .andExpect(jsonPath("$.aiOptOut").value(false));

        patchPreferences(groupId, userId, "{\"aiOptOut\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiOptOut").value(true))
                .andExpect(jsonPath("$.notifMuted").value(true));

        assertThat(jdbcTemplate.queryForObject("SELECT ai_opt_out_changed_at IS NOT NULL FROM group_members "
                + "WHERE group_id = ? AND user_id = ?", Boolean.class, groupId, userId)).isTrue();
        mockMvc.perform(get(PREFERENCES, groupId).with(asUser(userId))).andExpect(jsonPath("$.aiOptOut").value(true));
    }

    @Test
    void 관리자도_다른_구성원의_AI_분석_제외를_바꿀_수_없다() throws Exception {
        long owner = createUser();
        long member = createUser();
        long groupId = GroupApi.createGroup(mockMvc, owner, "우리 가족");
        GroupApi.addMember(mockMvc, groupId, owner, member);

        // 경로에 다른 사람을 지정하는 API 가 없다: me 는 언제나 요청한 본인이다
        patchPreferences(groupId, owner, "{\"aiOptOut\":true}").andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT ai_opt_out FROM group_members WHERE group_id = ? AND user_id = ?", Boolean.class, groupId,
                member)).isFalse();
    }

    @Test
    void 그룹_알림_값이_없으면_400() throws Exception {
        long userId = createUser();
        long groupId = GroupApi.createGroup(mockMvc, userId, "우리 가족");

        patchPreferences(groupId, userId, "{}").andExpect(status().isBadRequest());
    }

    @Test
    void 구성원이_아니면_그룹_알림_설정을_보거나_바꿀_수_없다() throws Exception {
        long groupId = GroupApi.createGroup(mockMvc, createUser(), "우리 가족");
        long stranger = createUser();

        mockMvc.perform(get(PREFERENCES, groupId).with(asUser(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));
        patchPreferences(groupId, stranger, "{\"notifMuted\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));
    }

    private ResultActions patchSettings(long userId, String body) throws Exception {
        return mockMvc.perform(patch(SETTINGS).with(asUser(userId)).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions patchPreferences(long groupId, long userId, String body) throws Exception {
        return mockMvc.perform(patch(PREFERENCES, groupId).with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
