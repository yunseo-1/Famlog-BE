package com.famlog.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.group.domain.GroupRole;
import com.famlog.support.FakeGroupAccessChecker;

/** 그룹 인가 규칙 (사양서 2.2 권한, 9.3 인가). */
class GroupAccessCheckerTest {

    private final FakeGroupAccessChecker checker = new FakeGroupAccessChecker();

    @Test
    void 구성원이_아니면_403_GROUP_NOT_MEMBER() {
        assertError(() -> checker.requireMember(1, 100), ErrorCode.GROUP_NOT_MEMBER);
    }

    @Test
    void 구성원이면_역할을_돌려준다() {
        checker.addMember(1, 100, GroupRole.MEMBER);

        assertThat(checker.requireMember(1, 100)).isEqualTo(GroupRole.MEMBER);
    }

    @Test
    void 다른_그룹의_구성원은_이_그룹_구성원이_아니다() {
        checker.addMember(2, 100, GroupRole.OWNER);

        assertError(() -> checker.requireMember(1, 100), ErrorCode.GROUP_NOT_MEMBER);
    }

    @Test
    void MEMBER가_관리자_동작을_하면_403_GROUP_ROLE_REQUIRED() {
        checker.addMember(1, 100, GroupRole.MEMBER);

        assertError(() -> checker.requireRole(1, 100, GroupRole.ADMIN), ErrorCode.GROUP_ROLE_REQUIRED);
    }

    @Test
    void OWNER와_ADMIN은_관리자_동작을_할_수_있고_OWNER_전용은_OWNER만() {
        checker.addMember(1, 100, GroupRole.OWNER);
        checker.addMember(1, 200, GroupRole.ADMIN);

        assertThat(checker.requireRole(1, 100, GroupRole.ADMIN)).isEqualTo(GroupRole.OWNER);
        assertThat(checker.requireRole(1, 200, GroupRole.ADMIN)).isEqualTo(GroupRole.ADMIN);
        assertError(() -> checker.requireRole(1, 200, GroupRole.OWNER), ErrorCode.GROUP_ROLE_REQUIRED);
    }

    @Test
    void 비구성원의_역할_검사는_역할_부족이_아니라_구성원_아님으로_답한다() {
        assertError(() -> checker.requireRole(1, 100, GroupRole.ADMIN), ErrorCode.GROUP_NOT_MEMBER);
    }

    @Test
    void 리소스_ID로_접근할_때_다른_그룹이면_404로_존재를_숨긴다() {
        checker.addMember(2, 100, GroupRole.MEMBER);

        assertError(() -> checker.requireMemberOrNotFound(1, 100), ErrorCode.NOT_FOUND);
        assertThat(checker.requireMemberOrNotFound(2, 100)).isEqualTo(GroupRole.MEMBER);
    }

    @Test
    void S5_전_임시_구현은_모든_접근을_거부한다() {
        PendingGroupAccessChecker pending = new PendingGroupAccessChecker();

        assertError(() -> pending.requireMember(1, 100), ErrorCode.GROUP_NOT_MEMBER);
    }

    private static void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}
