package com.famlog.support;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.famlog.group.domain.GroupRole;
import com.famlog.group.service.GroupAccessChecker;

/**
 * 테스트 더블: 그룹 테이블(S5) 전까지 구성원 관계를 메모리에 둔다.
 * 테스트끼리 섞이지 않게 테스트마다 다른 groupId를 쓴다.
 */
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
