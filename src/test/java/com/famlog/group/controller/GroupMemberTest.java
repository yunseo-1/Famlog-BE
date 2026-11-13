package com.famlog.group.controller;

import static com.famlog.support.GroupApi.addMember;
import static com.famlog.support.GroupApi.changeRole;
import static com.famlog.support.GroupApi.createGroup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

import com.famlog.group.event.GroupMemberEvents.MemberLeft;
import com.famlog.support.IntegrationTest;

/**
 * 가족 역할·권한 변경·위임·내보내기·나가기 (사양서 2.2 권한 매트릭스, 4.1 GRP-04·05, 9.8 감사 로그).
 * 그룹: 그룹장(owner), 관리자(admin), 일반 구성원 두 명(mom, son).
 */
@RecordApplicationEvents
class GroupMemberTest extends IntegrationTest {

    @Autowired
    ApplicationEvents events;

    long owner;
    long admin;
    long mom;
    long son;
    long groupId;

    @BeforeEach
    void setUpFamily() throws Exception {
        owner = createUser();
        admin = createUser();
        mom = createUser();
        son = createUser();
        groupId = createGroup(mockMvc, owner, "우리 가족");
        addMember(mockMvc, groupId, owner, admin);
        addMember(mockMvc, groupId, owner, mom);
        addMember(mockMvc, groupId, owner, son);
        changeRole(mockMvc, groupId, owner, admin, "ADMIN").andExpect(status().isOk());
        jdbcTemplate.update("DELETE FROM audit_logs WHERE group_id = ?", groupId);
    }

    // --- GRP-04 ---

    @Test
    void 내_가족_역할_호칭_색을_정한다() throws Exception {
        updateMe(mom, "{\"familyRole\":\"MOTHER\",\"familyTitle\":\"엄마\",\"calendarColor\":\"#4c8df0\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.familyRole").value("MOTHER"))
                .andExpect(jsonPath("$.familyTitle").value("엄마"))
                .andExpect(jsonPath("$.calendarColor").value("#4C8DF0"));
    }

    @Test
    void 보낸_값만_바꾸고_빈_호칭은_지운다() throws Exception {
        updateMe(son, "{\"familyRole\":\"SON\",\"familyTitle\":\"막내\"}").andExpect(status().isOk());

        updateMe(son, "{\"familyTitle\":\"\"}")
                .andExpect(jsonPath("$.familyRole").value("SON"))
                .andExpect(jsonPath("$.familyTitle").doesNotExist());
    }

    @Test
    void 팔레트에_없는_색이나_잘못된_형식은_400() throws Exception {
        updateMe(mom, "{\"calendarColor\":\"#000000\"}").andExpect(status().isBadRequest());
        updateMe(mom, "{\"calendarColor\":\"red\"}").andExpect(status().isBadRequest());
        updateMe(mom, "{\"familyRole\":\"UNCLE\"}").andExpect(status().isBadRequest());
        updateMe(mom, "{\"familyTitle\":\"%s\"}".formatted("가".repeat(21))).andExpect(status().isBadRequest());
    }

    // --- 권한 매트릭스 (2.2) ---

    @Test
    void 일반_구성원은_권한을_바꾸거나_내보낼_수_없다() throws Exception {
        changeRole(mockMvc, groupId, mom, son, "ADMIN")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_REQUIRED"));
        kick(mom, son).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GROUP_ROLE_REQUIRED"));
    }

    @Test
    void 관리자는_구성원의_권한을_바꾸고_감사_로그가_남는다() throws Exception {
        changeRole(mockMvc, groupId, admin, mom, "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        assertThat(jdbcTemplate.queryForList(
                "SELECT action || ' ' || (meta->>'before') || '->' || (meta->>'after') || ' by ' || actor_id "
                        + "FROM audit_logs WHERE group_id = ?", String.class, groupId))
                .containsExactly("GROUP_ROLE_CHANGED MEMBER->ADMIN by " + admin);
    }

    @Test
    void 관리자는_그룹장의_권한을_바꾸거나_내보내거나_위임할_수_없다() throws Exception {
        changeRole(mockMvc, groupId, admin, owner, "MEMBER").andExpect(status().isForbidden());
        kick(admin, owner).andExpect(status().isForbidden());
        changeRole(mockMvc, groupId, admin, mom, "OWNER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("그룹장만 그룹장을 넘길 수 있어요."));
        assertThat(roleOf(owner)).isEqualTo("OWNER");
    }

    @Test
    void 자기_자신의_권한_변경과_내보내기는_400() throws Exception {
        changeRole(mockMvc, groupId, admin, admin, "MEMBER").andExpect(status().isBadRequest());
        kick(admin, admin).andExpect(status().isBadRequest());
    }

    @Test
    void 그룹에_없는_사람은_404() throws Exception {
        changeRole(mockMvc, groupId, owner, createUser(), "ADMIN").andExpect(status().isNotFound());
        kick(owner, createUser()).andExpect(status().isNotFound());
    }

    // --- 내보내기 ---

    @Test
    void 내보내진_사람은_다음_요청부터_그룹에_접근할_수_없고_현재_그룹이_비워진다() throws Exception {
        mockMvc.perform(get("/api/v1/groups/{id}", groupId).with(asUser(son))).andExpect(status().isOk());

        kick(admin, son).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/groups/{id}", groupId).with(asUser(son)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));
        assertThat(jdbcTemplate.queryForObject("SELECT last_group_id FROM users WHERE id = ?", Long.class, son))
                .isNull();
        assertThat(auditActions()).containsExactly("GROUP_MEMBER_KICKED");
        assertThat(events.stream(MemberLeft.class)).containsExactly(new MemberLeft(groupId, son, true));
        mockMvc.perform(get("/api/v1/groups/{id}", groupId).with(asUser(owner)))
                .andExpect(jsonPath("$.members.length()").value(3));
    }

    // --- 위임 ---

    @Test
    void 그룹장은_위임할_수_있고_이전_그룹장은_관리자가_된다() throws Exception {
        changeRole(mockMvc, groupId, owner, mom, "OWNER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));

        assertThat(roleOf(mom)).isEqualTo("OWNER");
        assertThat(roleOf(owner)).isEqualTo("ADMIN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM group_members WHERE group_id = ? AND role = 'OWNER' AND left_at IS NULL",
                Long.class, groupId)).isEqualTo(1);
        assertThat(auditActions()).containsExactly("GROUP_OWNER_TRANSFERRED");
    }

    // --- 나가기 ---

    @Test
    void 구성원은_나갈_수_있다() throws Exception {
        leave(son).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/groups/{id}", groupId).with(asUser(son))).andExpect(status().isForbidden());
        assertThat(events.stream(MemberLeft.class)).containsExactly(new MemberLeft(groupId, son, false));
    }

    @Test
    void 다른_구성원이_있는_그룹장은_위임하지_않고_나갈_수_없다() throws Exception {
        leave(owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_TRANSFER_REQUIRED"));
    }

    @Test
    void 혼자_남은_그룹장이_나가면_그룹은_삭제_예정이_된다() throws Exception {
        for (long member : List.of(admin, mom, son)) {
            leave(member).andExpect(status().isNoContent());
        }

        leave(owner).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM family_groups WHERE id = ?", String.class,
                groupId)).isEqualTo("PENDING_DELETION");
        assertThat(auditActions()).containsExactly("GROUP_DELETION_SCHEDULED");
    }

    private ResultActions updateMe(long userId, String json) throws Exception {
        return mockMvc.perform(patch("/api/v1/groups/{id}/members/me", groupId).with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions kick(long actor, long target) throws Exception {
        return mockMvc.perform(delete("/api/v1/groups/{g}/members/{u}", groupId, target).with(asUser(actor)));
    }

    private ResultActions leave(long userId) throws Exception {
        return mockMvc.perform(post("/api/v1/groups/{id}/leave", groupId).with(asUser(userId)));
    }

    private String roleOf(long userId) {
        return jdbcTemplate.queryForObject("SELECT role FROM group_members WHERE group_id = ? AND user_id = ?",
                String.class, groupId, userId);
    }

    private List<String> auditActions() {
        return jdbcTemplate.queryForList("SELECT action FROM audit_logs WHERE group_id = ? ORDER BY id",
                String.class, groupId);
    }
}
