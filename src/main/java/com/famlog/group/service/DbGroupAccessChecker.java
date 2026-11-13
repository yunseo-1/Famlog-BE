package com.famlog.group.service;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.group.domain.GroupRole;
import com.famlog.group.repository.GroupMemberRepository;

import lombok.RequiredArgsConstructor;

/**
 * group_members 기준 인가 (사양서 9.3). 활성 그룹(삭제 예정 아님)의 활성 구성원(나가지 않음)만 인정한다.
 * 권한 변경·내보내기가 다음 요청부터 바로 반영되도록 캐시하지 않는다 (인덱스 조회 한 번).
 */
@Component
@RequiredArgsConstructor
public class DbGroupAccessChecker implements GroupAccessChecker {

    private final GroupMemberRepository groupMemberRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<GroupRole> findActiveRole(long groupId, long userId) {
        return groupMemberRepository.findActiveRole(groupId, userId);
    }
}
