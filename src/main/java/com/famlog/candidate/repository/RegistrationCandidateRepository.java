package com.famlog.candidate.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.candidate.domain.RegistrationCandidate;

import jakarta.persistence.LockModeType;

public interface RegistrationCandidateRepository extends JpaRepository<RegistrationCandidate, Long> {

    /** 같은 내용의 가장 최근 후보 (중복 제거 기준) */
    Optional<RegistrationCandidate> findFirstByGroupIdAndDedupKeyOrderByIdDesc(long groupId, String dedupKey);

    /** 승인·무시·되돌리기: 동시에 눌러도 한 사람씩 (나중 사람은 바뀐 상태를 본다) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RegistrationCandidate c where c.id = :id")
    Optional<RegistrationCandidate> findByIdForUpdate(@Param("id") Long id);
}
