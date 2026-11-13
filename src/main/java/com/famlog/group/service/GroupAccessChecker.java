package com.famlog.group.service;

import java.util.Optional;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.group.domain.GroupRole;

/**
 * 그룹 데이터 접근 검사 (사양서 9.3). 모든 그룹 데이터 접근은 이것을 거친다.
 * <ul>
 * <li>경로에 groupId가 있는 API: {@link #requireMember} → 403 GROUP_NOT_MEMBER (필터가 자동 적용)</li>
 * <li>리소스 ID만 있는 API(/schedules/{id}): 리소스의 group_id로 {@link #requireMemberOrNotFound} → 404 (존재 여부 숨김)</li>
 * <li>관리자 동작: {@link #requireRole} → 403 GROUP_ROLE_REQUIRED</li>
 * </ul>
 * 구현은 활성 구성원의 역할 조회만 제공한다. 권한은 토큰이 아니라 매번 여기서 확인한다 (사양서 9.2).
 */
public interface GroupAccessChecker {

    /** 활성 구성원(나가지 않음)이면 역할, 아니면 empty */
    Optional<GroupRole> findActiveRole(long groupId, long userId);

    default GroupRole requireMember(long groupId, long userId) {
        return findActiveRole(groupId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_MEMBER));
    }

    default GroupRole requireRole(long groupId, long userId, GroupRole required) {
        GroupRole role = requireMember(groupId, userId);
        if (!role.atLeast(required)) {
            throw new BusinessException(ErrorCode.GROUP_ROLE_REQUIRED);
        }
        return role;
    }

    default GroupRole requireMemberOrNotFound(long groupId, long userId) {
        return findActiveRole(groupId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
