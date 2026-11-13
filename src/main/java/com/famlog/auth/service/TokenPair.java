package com.famlog.auth.service;

import java.time.Instant;

/** 로그인·재발급 결과. refreshToken 원문은 이 응답에서만 앱에 전달된다. */
public record TokenPair(
        long sessionId,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt) {

    @Override
    public String toString() {
        // 실수로 로그에 찍혀도 토큰이 남지 않게 한다 (사양서 9.8)
        return "TokenPair[sessionId=" + sessionId + ", accessTokenExpiresAt=" + accessTokenExpiresAt
                + ", refreshTokenExpiresAt=" + refreshTokenExpiresAt + "]";
    }
}
