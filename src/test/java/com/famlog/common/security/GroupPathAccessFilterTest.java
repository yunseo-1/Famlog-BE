package com.famlog.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.famlog.group.domain.GroupRole;
import com.famlog.support.IntegrationTest;

/**
 * /api/v1/groups/{groupId}/** 는 컨트롤러 전에 구성원 검사 (사양서 9.3).
 * 아직 그룹 API가 없어서, 통과한 요청은 404(NOT_FOUND)로 끝나는 것으로 "필터를 지났다"를 확인한다.
 */
class GroupPathAccessFilterTest extends IntegrationTest {

    @Test
    void 구성원이면_그룹_경로를_통과한다() throws Exception {
        long groupId = randomId();
        long userId = randomId();
        groupAccessChecker.addMember(groupId, userId, GroupRole.MEMBER);

        mockMvc.perform(get("/api/v1/groups/{groupId}/schedules", groupId).with(asUser(userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 구성원이_아니면_컨트롤러_전에_403_GROUP_NOT_MEMBER() throws Exception {
        long groupId = randomId();
        groupAccessChecker.addMember(groupId, randomId(), GroupRole.OWNER);

        mockMvc.perform(get("/api/v1/groups/{groupId}/schedules", groupId).with(asUser(randomId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 그룹_자체_경로와_깊은_하위_경로도_검사한다() throws Exception {
        long groupId = randomId();

        mockMvc.perform(delete("/api/v1/groups/{groupId}", groupId).with(asUser(randomId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/groups/{groupId}/members/me/preferences", groupId).with(asUser(randomId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 그룹_ID가_아닌_경로는_그룹_범위가_아니므로_검사하지_않는다() throws Exception {
        long userId = randomId();

        mockMvc.perform(post("/api/v1/groups/join").with(asUser(userId))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/groups/me").with(asUser(userId))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/groups").with(asUser(userId))).andExpect(status().isNotFound());
    }

    @Test
    void 토큰이_없으면_구성원_검사_전에_401() throws Exception {
        mockMvc.perform(get("/api/v1/groups/{groupId}/schedules", randomId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }
}
