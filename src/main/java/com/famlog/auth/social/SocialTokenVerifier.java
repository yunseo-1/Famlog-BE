package com.famlog.auth.social;

import com.famlog.user.domain.SocialProvider;

/**
 * 제공자 토큰 검증 (사양서 9.1: 가짜 토큰 로그인 방지). 제공자마다 하나씩 구현한다.
 * 실패 시: 토큰이 틀림 → BusinessException(AUTH_INVALID), 제공자 장애·서버 설정 누락 → BusinessException(INTERNAL_ERROR).
 */
public interface SocialTokenVerifier {

    SocialProvider provider();

    SocialIdentity verify(String providerToken);
}
