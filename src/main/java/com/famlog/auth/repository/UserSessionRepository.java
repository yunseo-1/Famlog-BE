package com.famlog.auth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.auth.domain.UserSession;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    List<UserSession> findByUserIdAndRevokedAtIsNull(Long userId);
}
