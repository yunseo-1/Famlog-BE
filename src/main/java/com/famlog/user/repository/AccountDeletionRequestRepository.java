package com.famlog.user.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.user.domain.AccountDeletionRequest;

public interface AccountDeletionRequestRepository extends JpaRepository<AccountDeletionRequest, Long> {

    Optional<AccountDeletionRequest> findByUserIdAndStatus(Long userId, String status);

    /** 유예가 지난 요청 (파기 배치) */
    @Query("""
            select r.id from AccountDeletionRequest r
            where r.status = 'REQUESTED' and r.purgeAfter <= :now order by r.id
            """)
    List<Long> findDueIds(@Param("now") Instant now, Limit limit);
}
