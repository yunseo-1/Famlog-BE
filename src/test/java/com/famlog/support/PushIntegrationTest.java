package com.famlog.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.famlog.auth.jwt.FamlogJwtClaims;

/** 푸시 테스트 베이스: 로그인 세션 만들기, 토큰 등록, 가짜 발송기. */
public abstract class PushIntegrationTest extends ChatIntegrationTest {

    @Autowired
    protected FakePushSender pushSender;

    /** 로그인한 기기(세션) 행을 바로 만든다 */
    protected long createSession(long userId) {
        return jdbcTemplate.queryForObject("INSERT INTO user_sessions (user_id, device_id, device_name, platform, "
                + "expires_at) VALUES (?, ?, '테스트폰', 'ANDROID', now() + interval '14 days') RETURNING id",
                Long.class, userId, "device-" + randomId());
    }

    /** 그 세션으로 인증된 요청 */
    protected static RequestPostProcessor asSession(long userId, long sessionId) {
        return jwt().jwt(token -> token.subject(Long.toString(userId))
                .claim(FamlogJwtClaims.SESSION_ID, Long.toString(sessionId)));
    }

    protected void registerToken(long userId, long sessionId, String token) throws Exception {
        mockMvc.perform(put("/api/v1/devices/token").with(asSession(userId, sessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\",\"appVersion\":\"1.0.0\"}".formatted(token)))
                .andExpect(status().isNoContent());
    }

    /** 새 세션을 만들고 토큰을 등록한다 */
    protected String deviceOf(long userId) throws Exception {
        String token = newToken();
        registerToken(userId, createSession(userId), token);
        return token;
    }

    protected static String newToken() {
        return "fcm-" + UUID.randomUUID();
    }

    protected long tokenCount(String token) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM device_tokens WHERE fcm_token = ?", Long.class,
                token);
    }
}
