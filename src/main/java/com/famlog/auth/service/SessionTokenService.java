package com.famlog.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.auth.domain.Platform;
import com.famlog.auth.domain.RefreshToken;
import com.famlog.auth.domain.UserSession;
import com.famlog.auth.jwt.AccessTokenIssuer;
import com.famlog.auth.jwt.AuthProperties;
import com.famlog.auth.repository.RefreshTokenRepository;
import com.famlog.auth.repository.UserSessionRepository;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 세션과 토큰 수명 관리 (사양서 9.2).
 * <ul>
 * <li>로그인: 세션 생성 + Access(30분) + Refresh(14일)</li>
 * <li>재발급: Refresh 교체. 이전 토큰은 ROTATED로 남긴다</li>
 * <li>재사용 감지: ROTATED 토큰이 다시 오면 탈취로 보고 그 사용자의 모든 세션을 폐기</li>
 * <li>폐기: 로그아웃·원격 로그아웃·계정 삭제. 해당 세션의 Access 토큰도 즉시 무효</li>
 * </ul>
 * 앱은 Refresh 재발급을 동시에 두 번 보내지 않아야 한다 (두 번째 요청이 재사용으로 판정된다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionTokenService {

    private final UserSessionRepository sessionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionRevocationStore revocationStore;
    private final AccessTokenIssuer accessTokenIssuer;
    private final AuthProperties properties;
    private final Clock clock;

    @Transactional
    public TokenPair startSession(long userId, String deviceId, String deviceName, Platform platform) {
        Instant now = Instant.now(clock);
        Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
        UserSession session = sessionRepository.save(
                new UserSession(userId, deviceId, deviceName, platform, now, refreshExpiresAt));
        return issueTokens(session, refreshExpiresAt);
    }

    /** 실패는 모두 401 AUTH_INVALID. 재사용 감지 시 폐기는 커밋되어야 하므로 BusinessException으로 롤백하지 않는다. */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenPair refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw invalid();
        }
        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(RefreshTokens.hash(rawRefreshToken))
                .orElseThrow(SessionTokenService::invalid);
        Instant now = Instant.now(clock);

        switch (token.getStatus()) {
            case ROTATED -> {
                UserSession session = sessionRepository.findById(token.getSessionId()).orElseThrow();
                log.warn("Refresh 토큰 재사용 감지 → 전 세션 폐기: userId={}, sessionId={}",
                        session.getUserId(), session.getId());
                revokeAllSessions(session.getUserId());
                throw invalid();
            }
            // 로그아웃된 기기의 토큰. 탈취 신호가 아니므로 다른 기기는 건드리지 않는다
            case REVOKED -> throw invalid();
            case ACTIVE -> {
            }
        }
        if (token.isExpired(now)) {
            throw invalid();
        }
        UserSession session = sessionRepository.findById(token.getSessionId()).orElseThrow();
        if (!session.isActive(now)) {
            throw invalid();
        }

        token.markRotated();
        // 세션당 ACTIVE 토큰 하나(부분 유니크 인덱스)라서 새 토큰보다 먼저 반영한다
        refreshTokenRepository.saveAndFlush(token);
        Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
        session.extend(now, refreshExpiresAt);
        return issueTokens(session, refreshExpiresAt);
    }

    /** 로그아웃·원격 로그아웃 (AUTH-03). 이미 폐기된 세션이면 아무것도 하지 않는다. */
    @Transactional
    public void revokeSession(long sessionId) {
        sessionRepository.findById(sessionId)
                .filter(session -> session.getRevokedAt() == null)
                .ifPresent(session -> revoke(List.of(session)));
    }

    /** 재사용 감지, 계정 삭제 등 사용자의 모든 기기를 로그아웃시킨다. */
    @Transactional
    public void revokeAllSessions(long userId) {
        revoke(sessionRepository.findByUserIdAndRevokedAtIsNull(userId));
    }

    private void revoke(List<UserSession> sessions) {
        if (sessions.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        sessions.forEach(session -> session.revoke(now));
        refreshTokenRepository.revokeActiveBySessionIds(sessions.stream().map(UserSession::getId).toList());
        sessions.forEach(session -> revocationStore.markRevoked(session.getId()));
    }

    private TokenPair issueTokens(UserSession session, Instant refreshExpiresAt) {
        String rawRefreshToken = RefreshTokens.generate();
        refreshTokenRepository.save(
                new RefreshToken(session.getId(), RefreshTokens.hash(rawRefreshToken), refreshExpiresAt));
        AccessTokenIssuer.AccessToken accessToken = accessTokenIssuer.issue(session.getUserId(), session.getId());
        return new TokenPair(session.getId(), accessToken.value(), accessToken.expiresAt(), rawRefreshToken,
                refreshExpiresAt);
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.AUTH_INVALID);
    }
}
