package com.famlog.group.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.group.domain.FamilyGroup;

import jakarta.persistence.LockModeType;

public interface FamilyGroupRepository extends JpaRepository<FamilyGroup, Long> {

    /** 인원 제한(20명)이 동시 가입에도 지켜지도록 그룹 행을 잠근다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from FamilyGroup g where g.id = :id")
    Optional<FamilyGroup> findByIdForUpdate(@Param("id") Long id);
}
