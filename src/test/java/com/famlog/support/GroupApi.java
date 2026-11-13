package com.famlog.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.famlog.auth.jwt.FamlogJwtClaims;
import com.jayway.jsonpath.JsonPath;

/** 그룹 API 테스트 도우미. 실제 API를 불러 그룹·초대·구성원을 만든다. */
public final class GroupApi {

    private GroupApi() {
    }

    public static long createGroup(MockMvc mockMvc, long ownerId, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups").with(as(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"%s\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    public static String issueInvite(MockMvc mockMvc, long groupId, long userId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups/{id}/invites", groupId).with(as(userId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }

    public static ResultActions join(MockMvc mockMvc, long userId, String code) throws Exception {
        return mockMvc.perform(post("/api/v1/groups/join").with(as(userId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"%s\"}".formatted(code)));
    }

    /** 초대 코드를 발급받아 가입시킨다 */
    public static void addMember(MockMvc mockMvc, long groupId, long inviterId, long newMemberId) throws Exception {
        join(mockMvc, newMemberId, issueInvite(mockMvc, groupId, inviterId)).andExpect(status().isOk());
    }

    public static ResultActions changeRole(MockMvc mockMvc, long groupId, long actorId, long targetId, String role)
            throws Exception {
        return mockMvc.perform(patch("/api/v1/groups/{g}/members/{u}", groupId, targetId).with(as(actorId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"%s\"}".formatted(role)));
    }

    static RequestPostProcessor as(long userId) {
        return jwt().jwt(token -> token.subject(Long.toString(userId)).claim(FamlogJwtClaims.SESSION_ID, "1"));
    }
}
