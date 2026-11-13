package com.famlog.common.security;

import static com.famlog.support.GroupApi.createGroup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.famlog.support.IntegrationTest;

/**
 * /api/v1/groups/{groupId}/** 는 컨트롤러 전에 구성원 검사 (사양서 9.3, 9.9 SEC-T1 일부). 실제 그룹·구성원 DB로 확인한다.
 * 아직 없는 하위 API(일정 등)는 구성원이면 404, 아니면 403 으로 "필터를 지났는지"를 본다.
 */
class GroupPathAccessFilterTest extends IntegrationTest {

    @Test
    void 구성원이면_그룹_경로를_통과한다() throws Exception {
        long owner = createUser();
        long groupId = createGroup(mockMvc, owner, "우리 가족");

        mockMvc.perform(get("/api/v1/groups/{groupId}", groupId).with(asUser(owner))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/groups/{groupId}/schedules", groupId).with(asUser(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 다른_그룹_사용자는_컨트롤러_전에_403_GROUP_NOT_MEMBER() throws Exception {
        long groupId = createGroup(mockMvc, createUser(), "우리 가족");
        long outsider = createUser();
        createGroup(mockMvc, outsider, "다른 가족");

        mockMvc.perform(get("/api/v1/groups/{groupId}/schedules", groupId).with(asUser(outsider)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        mockMvc.perform(delete("/api/v1/groups/{groupId}", groupId).with(asUser(outsider)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/groups/{groupId}/invites", groupId).with(asUser(outsider)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/groups/{groupId}/members/me/preferences", groupId).with(asUser(outsider)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 없는_그룹도_403으로_같게_응답한다() throws Exception {
        mockMvc.perform(get("/api/v1/groups/{groupId}", Long.MAX_VALUE).with(asUser(createUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 그룹_ID가_아닌_경로는_그룹_범위가_아니므로_검사하지_않는다() throws Exception {
        long userId = createUser();

        mockMvc.perform(get("/api/v1/groups/me").with(asUser(userId))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/groups/join").with(asUser(userId))).andExpect(status().isBadRequest());
    }

    @Test
    void 토큰이_없으면_구성원_검사_전에_401() throws Exception {
        mockMvc.perform(get("/api/v1/groups/{groupId}/schedules", 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }
}
