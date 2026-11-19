package com.famlog.candidate.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.candidate.domain.RegistrationCandidate;

public interface RegistrationCandidateRepository extends JpaRepository<RegistrationCandidate, Long> {

    /** 같은 내용의 가장 최근 후보 (중복 제거 기준) */
    Optional<RegistrationCandidate> findFirstByGroupIdAndDedupKeyOrderByIdDesc(long groupId, String dedupKey);
}
