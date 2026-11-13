package com.famlog.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.user.domain.User;

import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {

    /** 사용자당 그룹 수 제한(10개)이 동시 요청에도 지켜지도록 사용자 행을 잠근다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /** 삭제 예정 그룹을 현재 그룹으로 둔 사용자들의 현재 그룹을 비운다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.lastGroupId = null where u.lastGroupId = :groupId")
    int clearCurrentGroup(@Param("groupId") Long groupId);
}
