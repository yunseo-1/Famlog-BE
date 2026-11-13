package com.famlog.auth.repository;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.auth.domain.RefreshToken;

import jakarta.persistence.LockModeType;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** 같은 토큰으로 동시에 재발급을 요청해도 하나만 처리되도록 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.status = com.famlog.auth.domain.RefreshTokenStatus.REVOKED
            where t.sessionId in :sessionIds and t.status = com.famlog.auth.domain.RefreshTokenStatus.ACTIVE
            """)
    int revokeActiveBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);
}
