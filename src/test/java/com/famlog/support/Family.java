package com.famlog.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.function.LongSupplier;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 일정·할 일 테스트용 가족: 엄마(그룹장, MOTHER) · 아빠(MEMBER, FATHER) · 아들(MEMBER, SON).
 * 실제 API로 그룹을 만들고 초대해 가입시킨 뒤 가족 역할을 정한다.
 */
public record Family(long groupId, long mom, long dad, long son) {

    public static Family create(MockMvc mockMvc, LongSupplier newUser) throws Exception {
        long mom = newUser.getAsLong();
        long dad = newUser.getAsLong();
        long son = newUser.getAsLong();
        long groupId = GroupApi.createGroup(mockMvc, mom, "우리 가족");
        GroupApi.addMember(mockMvc, groupId, mom, dad);
        GroupApi.addMember(mockMvc, groupId, mom, son);
        setRole(mockMvc, groupId, mom, "MOTHER");
        setRole(mockMvc, groupId, dad, "FATHER");
        setRole(mockMvc, groupId, son, "SON");
        return new Family(groupId, mom, dad, son);
    }

    private static void setRole(MockMvc mockMvc, long groupId, long userId, String role) throws Exception {
        mockMvc.perform(patch("/api/v1/groups/{g}/members/me", groupId).with(GroupApi.as(userId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"familyRole\":\"%s\"}".formatted(role)))
                .andExpect(status().isOk());
    }
}
