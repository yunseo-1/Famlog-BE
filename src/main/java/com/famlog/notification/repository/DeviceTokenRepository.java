package com.famlog.notification.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.notification.domain.DeviceToken;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    Optional<DeviceToken> findByFcmToken(String fcmToken);

    /** 받을 사람들의 토큰. 쓸 수 있는 세션(폐기·만료 전)의 토큰만 */
    @Query("""
            select t from DeviceToken t, UserSession s
            where s.id = t.sessionId and t.userId in :userIds
              and s.revokedAt is null and s.expiresAt > :now
            """)
    List<DeviceToken> findUsable(@Param("userIds") Collection<Long> userIds, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DeviceToken t where t.sessionId in :sessionIds")
    int deleteBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DeviceToken t where t.fcmToken in :tokens")
    int deleteByTokens(@Param("tokens") Collection<String> tokens);
}
