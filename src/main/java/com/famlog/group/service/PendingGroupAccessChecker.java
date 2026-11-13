package com.famlog.group.service;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.famlog.group.domain.GroupRole;

/**
 * 임시 구현: 그룹 테이블(S5) 전이라 모든 접근을 거부한다 (안전한 기본값).
 * TODO(S5 GRP-01): group_members 조회 구현으로 교체하고 이 클래스를 삭제한다.
 */
@Component
public class PendingGroupAccessChecker implements GroupAccessChecker {

    @Override
    public Optional<GroupRole> findActiveRole(long groupId, long userId) {
        return Optional.empty();
    }
}
