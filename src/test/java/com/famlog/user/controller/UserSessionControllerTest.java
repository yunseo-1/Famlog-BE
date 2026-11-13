package com.famlog.user.controller;

import static com.famlog.support.AuthApi.login;
import static com.famlog.support.AuthApi.read;
import static com.famlog.support.AuthApi.signupAndGetBody;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.famlog.support.FakeSocialLoginVerifier;
import com.famlog.support.IntegrationTest;

/** 로그인 기기 관리 (사양서 4.1 AUTH-03, 18.1 AUTH-03). 실제 발급 토큰으로 보안 체인을 통과한다. */
class UserSessionControllerTest extends IntegrationTest {

    private static final String SESSIONS = "/api/v1/users/me/sessions";

    @Test
    void 로그인한_기기_목록을_최근_사용_순으로_보여주고_지금_기기를_표시한다() throws Exception {
        String providerUserId = "kakao-" + randomId();
        String phone = signupAndGetBody(mockMvc, providerUserId, "phone");
        String tablet = loginBody(providerUserId, "tablet");

        mockMvc.perform(authorized(get(SESSIONS), tablet))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.current == true)].sessionId")
                        .value(org.hamcrest.Matchers.contains((int) sessionId(tablet))))
                .andExpect(jsonPath("$[?(@.current == false)].sessionId")
                        .value(org.hamcrest.Matchers.contains((int) sessionId(phone))))
                .andExpect(jsonPath("$[0].deviceName").value("테스트폰"))
                .andExpect(jsonPath("$[0].platform").value("ANDROID"))
                .andExpect(jsonPath("$[0].lastUsedAt").value(org.hamcrest.Matchers.endsWith("+09:00")));
    }

    @Test
    void 기기_B에서_기기_A를_로그아웃시키면_A의_다음_API_호출이_401() throws Exception {
        String providerUserId = "kakao-" + randomId();
        String deviceA = signupAndGetBody(mockMvc, providerUserId, "device-a");
        String deviceB = loginBody(providerUserId, "device-b");
        mockMvc.perform(authorized(get(SESSIONS), deviceA)).andExpect(status().isOk());

        mockMvc.perform(authorized(delete(SESSIONS + "/{id}", sessionId(deviceA)), deviceB))
                .andExpect(status().isNoContent());

        mockMvc.perform(authorized(get(SESSIONS), deviceA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        mockMvc.perform(authorized(get(SESSIONS), deviceB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void 다른_사람의_세션은_로그아웃시킬_수_없고_404로_존재를_숨긴다() throws Exception {
        String mine = signupAndGetBody(mockMvc, "kakao-" + randomId(), "d");
        String others = signupAndGetBody(mockMvc, "kakao-" + randomId(), "d");

        mockMvc.perform(authorized(delete(SESSIONS + "/{id}", sessionId(others)), mine))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(authorized(get(SESSIONS), others)).andExpect(status().isOk());
    }

    @Test
    void 없는_세션도_404() throws Exception {
        String mine = signupAndGetBody(mockMvc, "kakao-" + randomId(), "d");

        mockMvc.perform(authorized(delete(SESSIONS + "/{id}", Long.MAX_VALUE), mine))
                .andExpect(status().isNotFound());
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get(SESSIONS)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(SESSIONS + "/{id}", 1)).andExpect(status().isUnauthorized());
    }

    private String loginBody(String providerUserId, String deviceId) throws Exception {
        return mockMvc.perform(login("kakao", FakeSocialLoginVerifier.token(providerUserId), deviceId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder, String authBody) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + read(authBody, "$.tokens.accessToken"));
    }

    private long sessionId(String authBody) {
        String accessToken = read(authBody, "$.tokens.accessToken");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        return Long.parseLong(read(payload, "$.sid"));
    }
}
