package com.famlog.candidate.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.candidate.domain.AiJob;

import jakarta.persistence.LockModeType;

public interface AiJobRepository extends JpaRepository<AiJob, Long> {

    /** 결과 반영용. 같은 결과가 두 번 와도 한 번만 반영한다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AiJob j where j.id = :id")
    Optional<AiJob> findByIdForUpdate(@Param("id") Long id);
}
