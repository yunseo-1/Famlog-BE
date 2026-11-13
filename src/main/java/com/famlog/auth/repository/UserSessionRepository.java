package com.famlog.auth.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.auth.domain.UserSession;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    List<UserSession> findByUserIdAndRevokedAtIsNull(Long userId);

    List<UserSession> findByUserIdAndDeviceIdAndRevokedAtIsNull(Long userId, String deviceId);

    /** 기기 관리 화면: 쓸 수 있는 세션만, 최근 사용 순 */
    List<UserSession> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastUsedAtDesc(Long userId, Instant now);
}
