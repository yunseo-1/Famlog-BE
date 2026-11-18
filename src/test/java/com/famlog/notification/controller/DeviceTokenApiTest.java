package com.famlog.notification.controller;

import static com.famlog.support.AuthApi.read;
import static com.famlog.support.AuthApi.signupAndGetBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.famlog.support.AuthApi;
import com.famlog.support.PushIntegrationTest;

/** 푸시 토큰 등록·삭제 (사양서 11.3 NOTI-01, 8.2 로그아웃 시 파기). */
class DeviceTokenApiTest extends PushIntegrationTest {

    private static final String TOKEN = "/api/v1/devices/token";

    @Test
    void 등록하면_지금_세션의_토큰으로_저장된다() throws Exception {
        long userId = createUser();
        long sessionId = createSession(userId);
        String token = newToken();

        registerToken(userId, sessionId, token);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT user_id, session_id, platform, app_version FROM device_tokens WHERE fcm_token = ?", token);
        assertThat(row).containsEntry("user_id", userId).containsEntry("session_id", sessionId)
                .containsEntry("platform", "ANDROID").containsEntry("app_version", "1.0.0");
    }

    @Test
    void 같은_세션에서_새_토큰을_등록하면_이전_토큰을_바꾼다() throws Exception {
        long userId = createUser();
        long sessionId = createSession(userId);
        String old = newToken();
        String renewed = newToken();
        registerToken(userId, sessionId, old);

        registerToken(userId, sessionId, renewed);

        assertThat(tokenCount(old)).isZero();
        assertThat(tokenCount(renewed)).isOne();
    }

    @Test
    void 같은_토큰을_다시_등록하면_앱_버전만_바뀐다() throws Exception {
        long userId = createUser();
        long sessionId = createSession(userId);
        String token = newToken();
        registerToken(userId, sessionId, token);

        mockMvc.perform(put(TOKEN).with(asSession(userId, sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\",\"appVersion\":\"1.1.0\"}".formatted(token)))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT app_version FROM device_tokens WHERE fcm_token = ?",
                String.class, token)).isEqualTo("1.1.0");
    }

    @Test
    void 한_기기에서_계정을_바꾸면_토큰이_새_계정으로_옮겨진다() throws Exception {
        long mom = createUser();
        long dad = createUser();
        String token = newToken();
        registerToken(mom, createSession(mom), token);

        long dadSession = createSession(dad);
        registerToken(dad, dadSession, token);

        assertThat(jdbcTemplate.queryForMap("SELECT user_id, session_id FROM device_tokens WHERE fcm_token = ?",
                token)).containsEntry("user_id", dad).containsEntry("session_id", dadSession);
    }

    @Test
    void 한_사람이_여러_기기의_토큰을_가질_수_있고_삭제하면_이_기기의_토큰만_지워진다() throws Exception {
        long userId = createUser();
        long phone = createSession(userId);
        long tablet = createSession(userId);
        String phoneToken = newToken();
        String tabletToken = newToken();
        registerToken(userId, phone, phoneToken);
        registerToken(userId, tablet, tabletToken);

        mockMvc.perform(delete(TOKEN).with(asSession(userId, phone))).andExpect(status().isNoContent());
        mockMvc.perform(delete(TOKEN).with(asSession(userId, phone))).andExpect(status().isNoContent());

        assertThat(tokenCount(phoneToken)).isZero();
        assertThat(tokenCount(tabletToken)).isOne();
    }

    @Test
    void 로그아웃하면_그_기기의_토큰이_지워진다() throws Exception {
        String auth = signupAndGetBody(mockMvc, "kakao-" + randomId(), "phone");
        String token = newToken();
        mockMvc.perform(put(TOKEN).header(HttpHeaders.AUTHORIZATION, "Bearer " + read(auth, "$.tokens.accessToken"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isNoContent());
        assertThat(tokenCount(token)).isOne();

        mockMvc.perform(AuthApi.logout(read(auth, "$.tokens.refreshToken"))).andExpect(status().isNoContent());

        assertThat(tokenCount(token)).isZero();
    }

    @Test
    void 폐기된_세션으로는_등록할_수_없다() throws Exception {
        long userId = createUser();
        long sessionId = createSession(userId);
        jdbcTemplate.update("UPDATE user_sessions SET revoked_at = now() WHERE id = ?", sessionId);

        mockMvc.perform(put(TOKEN).with(asSession(userId, sessionId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(newToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void 토큰이_비었거나_너무_길면_400() throws Exception {
        long userId = createUser();
        long sessionId = createSession(userId);

        for (String token : new String[] { "", "x".repeat(513) }) {
            mockMvc.perform(put(TOKEN).with(asSession(userId, sessionId)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"%s\"}".formatted(token)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void 인증_토큰이_없으면_401() throws Exception {
        mockMvc.perform(put(TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"t\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(TOKEN)).andExpect(status().isUnauthorized());
    }
}
