package com.famlog.auth.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.auth.domain.UserSession;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    List<UserSession> findByUserIdAndRevokedAtIsNull(Long userId);

    /** 이 기기로 로그인한 적이 있는가 (폐기된 세션 포함). 새 기기 로그인 알림 기준 */
    boolean existsByUserIdAndDeviceId(Long userId, String deviceId);

    List<UserSession> findByUserIdAndDeviceIdAndRevokedAtIsNull(Long userId, String deviceId);

    /** 기기 관리 화면: 쓸 수 있는 세션만, 최근 사용 순 */
    List<UserSession> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastUsedAtDesc(Long userId, Instant now);
}
