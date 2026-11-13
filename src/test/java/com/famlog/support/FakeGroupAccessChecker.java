package com.famlog.support;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.famlog.group.domain.GroupRole;
import com.famlog.group.service.GroupAccessChecker;

/** 테스트 더블: GroupAccessChecker 기본 메서드(권한 규칙) 단위 테스트용. 통합 테스트는 실제 DB 구현을 쓴다. */
public class FakeGroupAccessChecker implements GroupAccessChecker {

    private final Map<String, GroupRole> memberships = new ConcurrentHashMap<>();

    public void addMember(long groupId, long userId, GroupRole role) {
        memberships.put(groupId + ":" + userId, role);
    }

    @Override
    public Optional<GroupRole> findActiveRole(long groupId, long userId) {
        return Optional.ofNullable(memberships.get(groupId + ":" + userId));
    }
}
