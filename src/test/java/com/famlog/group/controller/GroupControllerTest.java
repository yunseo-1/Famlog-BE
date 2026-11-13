package com.famlog.group.controller;

import static com.famlog.support.GroupApi.addMember;
import static com.famlog.support.GroupApi.createGroup;
import static com.famlog.support.GroupApi.issueInvite;
import static com.famlog.support.GroupApi.join;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.famlog.support.AuthApi;
import com.famlog.support.FakeSocialLoginVerifier;
import com.famlog.support.IntegrationTest;

/** 그룹 생성·조회·삭제·현재 그룹 (사양서 4.1 GRP-01·03, 2.2, 18.1 GRP-03). */
class GroupControllerTest extends IntegrationTest {

    @Test
    void 그룹을_만들면_만든_사람이_그룹장이고_채팅방과_현재_그룹이_생긴다() throws Exception {
        long owner = createUser();

        String body = mockMvc.perform(post("/api/v1/groups").with(asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" 우리 가족 \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("우리 가족"))
                .andExpect(jsonPath("$.myRole").value("OWNER"))
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].userId").value(owner))
                .andExpect(jsonPath("$.members[0].role").value("OWNER"))
                .andExpect(jsonPath("$.members[0].calendarColor").value("#14A3B8"))
                .andExpect(jsonPath("$.members[0].familyRole").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long groupId = Long.parseLong(AuthApi.read(body, "$.id"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_rooms WHERE group_id = ?", Long.class,
                groupId)).isEqualTo(1);
        assertThat(lastGroupId(owner)).isEqualTo(groupId);
    }

    @Test
    void 그룹_이름이_비었거나_30자를_넘으면_400() throws Exception {
        for (String name : new String[] { " ", "가".repeat(31) }) {
            mockMvc.perform(post("/api/v1/groups").with(asUser(createUser()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"%s\"}".formatted(name)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
        }
    }

    @Test
    void 그룹은_사용자당_10개까지_11번째는_409_GROUP_LIMIT_EXCEEDED() throws Exception {
        long user = createUser();
        for (int i = 0; i < 10; i++) {
            createGroup(mockMvc, user, "그룹" + i);
        }

        mockMvc.perform(post("/api/v1/groups").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"열한번째\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("가족 그룹은 10개까지 참여할 수 있어요."));
    }

    @Test
    void 내_그룹_목록은_인원_수와_내_역할을_보여준다() throws Exception {
        long mom = createUser();
        long son = createUser();
        long family = createGroup(mockMvc, mom, "우리 가족");
        long grandparents = createGroup(mockMvc, son, "친가");
        addMember(mockMvc, family, mom, son);

        mockMvc.perform(get("/api/v1/groups/me").with(asUser(son)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(grandparents))
                .andExpect(jsonPath("$[0].myRole").value("OWNER"))
                .andExpect(jsonPath("$[0].memberCount").value(1))
                .andExpect(jsonPath("$[1].id").value(family))
                .andExpect(jsonPath("$[1].myRole").value("MEMBER"))
                .andExpect(jsonPath("$[1].memberCount").value(2));
    }

    @Test
    void 구성원이_아니면_그룹을_볼_수_없다() throws Exception {
        long groupId = createGroup(mockMvc, createUser(), "우리 가족");

        mockMvc.perform(get("/api/v1/groups/{id}", groupId).with(asUser(createUser())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));
    }

    @Test
    void 그룹장이_삭제하면_모두_즉시_접근할_수_없고_30일_뒤_삭제_예정이_된다() throws Exception {
        long owner = createUser();
        long member = createUser();
        long groupId = createGroup(mockMvc, owner, "우리 가족");
        addMember(mockMvc, groupId, owner, member);
        String invite = issueInvite(mockMvc, groupId, owner);

        mockMvc.perform(delete("/api/v1/groups/{id}", groupId).with(asUser(owner)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/groups/{id}", groupId).with(asUser(member))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/groups/me").with(asUser(member))).andExpect(jsonPath("$.length()").value(0));
        join(mockMvc, createUser(), invite).andExpect(status().isGone());
        assertThat(lastGroupId(owner)).isNull();
        assertThat(lastGroupId(member)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delete_scheduled_at - now() > interval '29 days' FROM family_groups WHERE id = ? AND status = 'PENDING_DELETION'",
                Boolean.class, groupId)).isTrue();
        assertThat(auditActions(groupId)).containsExactly("GROUP_DELETION_SCHEDULED");
    }

    @Test
    void 관리자는_그룹을_삭제할_수_없다() throws Exception {
        long owner = createUser();
        long admin = createUser();
        long groupId = createGroup(mockMvc, owner, "우리 가족");
        addMember(mockMvc, groupId, owner, admin);
        com.famlog.support.GroupApi.changeRole(mockMvc, groupId, owner, admin, "ADMIN");

        mockMvc.perform(delete("/api/v1/groups/{id}", groupId).with(asUser(admin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_REQUIRED"));
    }

    @Test
    void 현재_그룹을_바꾸면_저장되고_다시_로그인해도_유지된다() throws Exception {
        String providerUserId = "kakao-" + randomId();
        long user = Long.parseLong(AuthApi.read(AuthApi.signupAndGetBody(mockMvc, providerUserId, "d"), "$.user.id"));
        long first = createGroup(mockMvc, user, "우리 가족");
        createGroup(mockMvc, user, "친가");

        mockMvc.perform(put("/api/v1/users/me/current-group").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"groupId\":%d}".formatted(first)))
                .andExpect(status().isNoContent());

        mockMvc.perform(AuthApi.login("kakao", FakeSocialLoginVerifier.token(providerUserId), "d"))
                .andExpect(jsonPath("$.user.lastGroupId").value(first));
    }

    @Test
    void 구성원이_아닌_그룹은_현재_그룹으로_정할_수_없다() throws Exception {
        long other = createGroup(mockMvc, createUser(), "남의 가족");

        mockMvc.perform(put("/api/v1/users/me/current-group").with(asUser(createUser()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"groupId\":%d}".formatted(other)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));
    }

    private Long lastGroupId(long userId) {
        return jdbcTemplate.queryForObject("SELECT last_group_id FROM users WHERE id = ?", Long.class, userId);
    }

    private java.util.List<String> auditActions(long groupId) {
        return jdbcTemplate.queryForList("SELECT action FROM audit_logs WHERE group_id = ? ORDER BY id", String.class,
                groupId);
    }
}
