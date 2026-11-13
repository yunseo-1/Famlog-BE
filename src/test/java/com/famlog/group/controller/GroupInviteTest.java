package com.famlog.group.controller;

import static com.famlog.support.GroupApi.addMember;
import static com.famlog.support.GroupApi.createGroup;
import static com.famlog.support.GroupApi.issueInvite;
import static com.famlog.support.GroupApi.join;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.group.event.GroupMemberEvents.MemberJoined;
import com.famlog.group.service.GroupService;
import com.famlog.support.AuthApi;
import com.famlog.support.IntegrationTest;

/** 초대 코드 발급·가입 (사양서 4.1 GRP-02, 9.1, 9.9 SEC-T5, 18.1 GRP-02). */
@RecordApplicationEvents
class GroupInviteTest extends IntegrationTest {

    @Autowired
    GroupService groupService;

    @Autowired
    ApplicationEvents events;

    @Test
    void 초대_코드는_혼동_문자를_뺀_8자리이고_7일_유효하며_링크도_준다() throws Exception {
        long owner = createUser();
        long groupId = createGroup(mockMvc, owner, "우리 가족");

        String body = mockMvc.perform(post("/api/v1/groups/{id}/invites", groupId).with(asUser(owner)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String code = AuthApi.read(body, "$.code");
        assertThat(code).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}");
        assertThat(AuthApi.read(body, "$.link")).isEqualTo("https://famlog.app/invite/" + code);
        String expiresAt = AuthApi.read(body, "$.expiresAt");
        assertThat(expiresAt).endsWith("+09:00");
        Duration validFor = Duration.between(Instant.now(), OffsetDateTime.parse(expiresAt).toInstant());
        assertThat(validFor).isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7));
    }

    @Test
    void 일반_구성원은_초대_코드를_발급할_수_없다() throws Exception {
        long owner = createUser();
        long member = createUser();
        long groupId = createGroup(mockMvc, owner, "우리 가족");
        addMember(mockMvc, groupId, owner, member);

        mockMvc.perform(post("/api/v1/groups/{id}/invites", groupId).with(asUser(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_REQUIRED"));
    }

    @Test
    void 코드로_가입하면_MEMBER가_되고_현재_그룹이_되며_입장_이벤트가_나간다() throws Exception {
        long owner = createUser();
        long son = createUser();
        long groupId = createGroup(mockMvc, owner, "우리 가족");
        String code = issueInvite(mockMvc, groupId, owner);

        join(mockMvc, son, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(groupId))
                .andExpect(jsonPath("$.myRole").value("MEMBER"))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[1].calendarColor").value("#E5568D"));

        assertThat(jdbcTemplate.queryForObject("SELECT last_group_id FROM users WHERE id = ?", Long.class, son))
                .isEqualTo(groupId);
        assertThat(jdbcTemplate.queryForObject("SELECT use_count FROM group_invites WHERE code = ?", Integer.class,
                code)).isEqualTo(1);
        assertThat(events.stream(MemberJoined.class)).containsExactly(new MemberJoined(groupId, son, false));
    }

    @Test
    void 받아_적은_코드의_소문자_공백_하이픈은_무시한다() throws Exception {
        long owner = createUser();
        long groupId = createGroup(mockMvc, owner, "우리 가족");
        String code = issueInvite(mockMvc, groupId, owner);
        String typed = " " + code.substring(0, 4).toLowerCase() + "-" + code.substring(4) + " ";

        join(mockMvc, createUser(), typed).andExpect(status().isOk());
    }

    @Test
    void 이미_구성원이면_다시_가입해도_그대로_200() throws Exception {
        long owner = createUser();
        long groupId = createGroup(mockMvc, owner, "우리 가족");
        String code = issueInvite(mockMvc, groupId, owner);

        join(mockMvc, owner, code).andExpect(status().isOk()).andExpect(jsonPath("$.members.length()").value(1));
    }

    @Test
    void 재발급하면_이전_코드는_410_INVITE_EXPIRED() throws Exception {
        long owner = createUser();
        long groupId = createGroup(mockMvc, owner, "우리 가족");
        String oldCode = issueInvite(mockMvc, groupId, owner);
        String newCode = issueInvite(mockMvc, groupId, owner);

        join(mockMvc, createUser(), oldCode)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITE_EXPIRED"));
        join(mockMvc, createUser(), newCode).andExpect(status().isOk());
    }

    @Test
    void 만료된_코드는_410() throws Exception {
        long owner = createUser();
        long groupId = createGroup(mockMvc, owner, "우리 가족");
        String code = issueInvite(mockMvc, groupId, owner);
        jdbcTemplate.update("UPDATE group_invites SET expires_at = now() - interval '1 second' WHERE code = ?", code);

        join(mockMvc, createUser(), code).andExpect(status().isGone());
    }

    @Test
    void 없는_코드는_404() throws Exception {
        join(mockMvc, createUser(), "ZZZZ2222")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("초대 코드를 다시 확인해 주세요."));
    }

    @Test
    void 연속_5번_틀리면_잠기고_6번째는_맞는_코드여도_429_SEC_T5() throws Exception {
        long owner = createUser();
        long groupId = createGroup(mockMvc, owner, "우리 가족");
        String validCode = issueInvite(mockMvc, groupId, owner);
        long attacker = createUser();
        // 분당 5회 요청 제한(필터)과 구분하기 위해 실패 5번은 서비스로 직접 시도한다
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> groupService.join(attacker, "WRONG222")).isInstanceOf(BusinessException.class);
        }

        join(mockMvc, attacker, validCode)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("초대 코드를 여러 번 틀렸어요. 잠시 후 다시 시도해 주세요."))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER,
                        org.hamcrest.Matchers.matchesPattern("([5-9][0-9][0-9]|600)")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM group_members WHERE group_id = ? AND user_id = ?", Long.class, groupId,
                attacker)).isZero();
    }

    @Test
    void 성공하면_실패_횟수를_초기화한다() {
        long owner = createUser();
        long groupId = groupService.create(owner, "우리 가족").id();
        String code = groupService.issueInvite(groupId, owner).code();
        long user = createUser();

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> groupService.join(user, "WRONG222")).isInstanceOf(BusinessException.class);
        }
        groupService.join(user, code);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> groupService.join(user, "WRONG222"))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    @Test
    void 그룹이_20명이면_21번째는_409_GROUP_LIMIT_EXCEEDED() throws Exception {
        long owner = createUser();
        long groupId = createGroup(mockMvc, owner, "대가족");
        for (int i = 0; i < 19; i++) {
            jdbcTemplate.update("""
                    INSERT INTO group_members (group_id, user_id, role, calendar_color, joined_at)
                    VALUES (?, ?, 'MEMBER', '#14A3B8', now())
                    """, groupId, createUser());
        }
        String code = issueInvite(mockMvc, groupId, owner);

        join(mockMvc, createUser(), code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("이 가족 그룹은 20명까지 함께할 수 있어요."));
    }

    @Test
    void 이미_10개_그룹에_속했으면_참여할_수_없다() throws Exception {
        long user = createUser();
        for (int i = 0; i < 10; i++) {
            createGroup(mockMvc, user, "그룹" + i);
        }
        long owner = createUser();
        String code = issueInvite(mockMvc, createGroup(mockMvc, owner, "새 그룹"), owner);

        join(mockMvc, user, code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_LIMIT_EXCEEDED"));
    }

    @Test
    void 나갔다가_다시_들어오면_같은_구성원으로_되살아나고_MEMBER부터_시작한다() throws Exception {
        long owner = createUser();
        long son = createUser();
        long groupId = createGroup(mockMvc, owner, "우리 가족");
        addMember(mockMvc, groupId, owner, son);
        com.famlog.support.GroupApi.changeRole(mockMvc, groupId, owner, son, "ADMIN");
        mockMvc.perform(post("/api/v1/groups/{id}/leave", groupId).with(asUser(son)))
                .andExpect(status().isNoContent());

        join(mockMvc, son, issueInvite(mockMvc, groupId, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("MEMBER"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM group_members WHERE group_id = ? AND user_id = ?", Long.class, groupId, son))
                .isEqualTo(1);
        assertThat(events.stream(MemberJoined.class)).contains(new MemberJoined(groupId, son, true));
    }
}
