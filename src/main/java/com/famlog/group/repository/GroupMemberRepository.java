package com.famlog.group.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.group.domain.GroupMember;
import com.famlog.group.domain.GroupRole;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);

    List<GroupMember> findByGroupIdAndLeftAtIsNullOrderByJoinedAtAsc(Long groupId);

    long countByGroupIdAndLeftAtIsNull(Long groupId);

    /** 활성 그룹의 활성 구성원일 때만 역할을 돌려준다 (사양서 9.3 인가의 기준) */
    @Query("""
            select m.role from GroupMember m, FamilyGroup g
            where g.id = m.groupId and m.groupId = :groupId and m.userId = :userId
              and m.leftAt is null and g.status = com.famlog.group.domain.GroupStatus.ACTIVE
            """)
    Optional<GroupRole> findActiveRole(@Param("groupId") Long groupId, @Param("userId") Long userId);

    /** 사용자가 속한 활성 그룹 수 (사용자당 10개 제한) */
    @Query("""
            select count(m) from GroupMember m, FamilyGroup g
            where g.id = m.groupId and m.userId = :userId
              and m.leftAt is null and g.status = com.famlog.group.domain.GroupStatus.ACTIVE
            """)
    long countActiveGroupsOf(@Param("userId") Long userId);

    /** 내 그룹 목록: [그룹, 내 구성원 행, 활성 인원 수] */
    @Query("""
            select g, m, (select count(m2) from GroupMember m2 where m2.groupId = g.id and m2.leftAt is null)
            from GroupMember m, FamilyGroup g
            where g.id = m.groupId and m.userId = :userId
              and m.leftAt is null and g.status = com.famlog.group.domain.GroupStatus.ACTIVE
            order by m.joinedAt asc
            """)
    List<Object[]> findMyGroups(@Param("userId") Long userId);
}
