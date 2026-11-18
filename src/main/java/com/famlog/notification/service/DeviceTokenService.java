package com.famlog.notification.service;

import java.util.Collection;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.auth.domain.UserSession;
import com.famlog.auth.repository.UserSessionRepository;
import com.famlog.auth.service.SessionsRevoked;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.notification.domain.DeviceToken;
import com.famlog.notification.repository.DeviceTokenRepository;

import lombok.RequiredArgsConstructor;

/**
 * 푸시 토큰 등록·삭제 (사양서 11.3 NOTI-01, 8.2 "로그아웃·탈퇴 시 파기").
 * <ul>
 * <li>세션(로그인한 기기) 하나에 토큰 하나. 새 토큰을 등록하면 그 세션의 이전 토큰을 바꾼다</li>
 * <li>같은 토큰이 다른 세션에 있으면 지금 세션으로 옮긴다 (한 기기에서 계정을 바꾼 경우)</li>
 * <li>세션이 폐기되면 그 세션의 토큰을 같은 트랜잭션에서 지운다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository tokenRepository;
    private final UserSessionRepository sessionRepository;

    @Transactional
    public void register(long userId, long sessionId, String token, String appVersion) {
        UserSession session = sessionRepository.findById(sessionId)
                .filter(found -> found.getUserId() == userId && found.getRevokedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID));
        DeviceToken existing = tokenRepository.findByFcmToken(token).orElse(null);
        if (existing != null && existing.getSessionId() == sessionId) {
            existing.updateAppVersion(appVersion);
            return;
        }
        if (existing != null) {
            tokenRepository.delete(existing);
        }
        tokenRepository.deleteBySessionIds(List.of(sessionId));
        tokenRepository.save(new DeviceToken(userId, sessionId, session.getPlatform(), token, appVersion));
    }

    /** 이 기기의 토큰을 지운다 (알림 권한을 끈 경우 등). 없으면 아무것도 하지 않는다 */
    @Transactional
    public void unregister(long sessionId) {
        tokenRepository.deleteBySessionIds(List.of(sessionId));
    }

    /** FCM이 더 쓸 수 없다고 알려준 토큰 */
    @Transactional
    public void deleteTokens(Collection<String> tokens) {
        if (!tokens.isEmpty()) {
            tokenRepository.deleteByTokens(tokens);
        }
    }

    /** 로그아웃·원격 로그아웃·전 세션 폐기: 폐기와 같은 트랜잭션에서 토큰도 지운다 */
    @EventListener
    void onSessionsRevoked(SessionsRevoked event) {
        tokenRepository.deleteBySessionIds(event.sessionIds());
    }
}
