package com.famlog.auth.controller;

import static com.famlog.support.AuthApi.PRIVACY_VERSION;
import static com.famlog.support.AuthApi.TERMS_VERSION;
import static com.famlog.support.AuthApi.birthYmAgo;
import static com.famlog.support.AuthApi.login;
import static com.famlog.support.AuthApi.logout;
import static com.famlog.support.AuthApi.read;
import static com.famlog.support.AuthApi.refresh;
import static com.famlog.support.AuthApi.signup;
import static com.famlog.support.AuthApi.signupAndGetBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.famlog.support.FakeSocialLoginVerifier;
import com.famlog.support.IntegrationTest;

/** 소셜 로그인·가입·토큰 API (사양서 4.1 AUTH-01·02, 8.2, 8.3, 8.5, 18.1). */
class AuthControllerTest extends IntegrationTest {

    // --- 가입 ---

    @Test
    void 가입하면_201과_토큰_사용자를_주고_계정_소셜연결_필수동의가_저장된다() throws Exception {
        String providerUserId = "kakao-" + randomId();

        String body = mockMvc.perform(signup(providerUserId, "device-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokens.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokens.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokens.accessTokenExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.user.nickname").value("엄마"))
                .andExpect(jsonPath("$.user.lastGroupId").doesNotExist())
                .andExpect(jsonPath("$.user.birthYm").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long userId = Long.parseLong(read(body, "$.user.id"));

        Map<String, Object> user = jdbcTemplate.queryForMap(
                "SELECT nickname, birth_ym, age_verified_at, status FROM users WHERE id = ?", userId);
        assertThat(user.get("nickname")).isEqualTo("엄마");
        assertThat(user.get("birth_ym")).isEqualTo("198005");
        assertThat(user.get("age_verified_at")).isNotNull();
        assertThat(user.get("status")).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider FROM social_accounts WHERE user_id = ? AND provider_user_id = ?", String.class,
                userId, providerUserId)).isEqualTo("KAKAO");
        assertThat(jdbcTemplate.queryForList(
                "SELECT type || ':' || doc_version FROM consents WHERE user_id = ? ORDER BY type", String.class,
                userId)).containsExactly("PRIVACY:" + PRIVACY_VERSION, "TERMS:" + TERMS_VERSION);
    }

    @Test
    void 만_14세_미만이면_403_AGE_RESTRICTED이고_아무것도_저장하지_않는다() throws Exception {
        String providerUserId = "kakao-" + randomId();
        long usersBefore = count("users");

        mockMvc.perform(signup("kakao", FakeSocialLoginVerifier.token(providerUserId), "d", "딸", birthYmAgo(10, 0),
                        TERMS_VERSION, PRIVACY_VERSION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGE_RESTRICTED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("보호자")));

        assertThat(count("users")).isEqualTo(usersBefore);
        assertThat(socialAccountExists(providerUserId)).isFalse();
    }

    @Test
    void 열네_번째_생일이_있는_달은_거절하고_그다음_달부터_허용한다() throws Exception {
        mockMvc.perform(signup("kakao", FakeSocialLoginVerifier.token("kakao-" + randomId()), "d", "아들",
                        birthYmAgo(14, 0), TERMS_VERSION, PRIVACY_VERSION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGE_RESTRICTED"));

        mockMvc.perform(signup("kakao", FakeSocialLoginVerifier.token("kakao-" + randomId()), "d", "아들",
                        birthYmAgo(14, 1), TERMS_VERSION, PRIVACY_VERSION))
                .andExpect(status().isCreated());
    }

    @Test
    void 앱이_보여준_약관_버전이_현재와_다르면_400() throws Exception {
        mockMvc.perform(signup("kakao", FakeSocialLoginVerifier.token("kakao-" + randomId()), "d", "엄마", "198005",
                        "0.9", PRIVACY_VERSION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 동의_정보가_없으면_400_fieldErrors() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/kakao/signup")
                        .contentType("application/json")
                        .content("""
                                {"providerToken":"ok:x","device":{"deviceId":"d","platform":"ANDROID"},
                                 "nickname":"엄마","birthYm":"198005"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("agreements"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "199013", "1990", "abcdef", "209901" })
    void 출생연도월_형식이_틀리거나_미래면_400(String birthYm) throws Exception {
        mockMvc.perform(signup("kakao", FakeSocialLoginVerifier.token("kakao-" + randomId()), "d", "엄마", birthYm,
                        TERMS_VERSION, PRIVACY_VERSION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 닉네임이_비었거나_30자를_넘으면_400() throws Exception {
        for (String nickname : List.of("  ", "가".repeat(31))) {
            mockMvc.perform(signup("kakao", FakeSocialLoginVerifier.token("kakao-" + randomId()), "d", nickname,
                            "198005", TERMS_VERSION, PRIVACY_VERSION))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("nickname"));
        }
    }

    @Test
    void 이미_가입한_계정으로_다시_가입하면_409() throws Exception {
        String providerUserId = "kakao-" + randomId();
        mockMvc.perform(signup(providerUserId, "d")).andExpect(status().isCreated());

        mockMvc.perform(signup(providerUserId, "d"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 가입된 계정이에요. 로그인해 주세요."));
    }

    // --- 로그인 ---

    @Test
    void 가입된_사용자는_로그인하면_토큰과_마지막_그룹을_받는다() throws Exception {
        String providerUserId = "kakao-" + randomId();
        String signupBody = signupAndGetBody(mockMvc, providerUserId, "device-1");

        mockMvc.perform(login("kakao", FakeSocialLoginVerifier.token(providerUserId), "device-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokens.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.id").value(Long.parseLong(read(signupBody, "$.user.id"))))
                .andExpect(jsonPath("$.user.lastGroupId").doesNotExist());
    }

    @Test
    void 처음_보는_사용자는_404_USER_NOT_REGISTERED이고_아무것도_저장하지_않는다() throws Exception {
        String providerUserId = "kakao-" + randomId();
        long usersBefore = count("users");
        long sessionsBefore = count("user_sessions");

        mockMvc.perform(login("kakao", FakeSocialLoginVerifier.token(providerUserId), "d"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_REGISTERED"));

        assertThat(count("users")).isEqualTo(usersBefore);
        assertThat(count("user_sessions")).isEqualTo(sessionsBefore);
        assertThat(socialAccountExists(providerUserId)).isFalse();
    }

    @Test
    void 같은_회원번호라도_제공자가_다르면_다른_계정이다() throws Exception {
        String providerUserId = "same-" + randomId();
        mockMvc.perform(signup(providerUserId, "d")).andExpect(status().isCreated());

        mockMvc.perform(login("google", FakeSocialLoginVerifier.token(providerUserId), "d"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_REGISTERED"));
    }

    @Test
    void 제공자가_토큰을_거부하면_401_AUTH_INVALID() throws Exception {
        mockMvc.perform(login("kakao", "forged-token", "d"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void 제공자_장애면_재로그인이_아니라_일시적_오류로_응답한다() throws Exception {
        mockMvc.perform(login("kakao", "down", "d"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "apple", "naver" })
    void 지원하지_않는_제공자는_400(String provider) throws Exception {
        mockMvc.perform(login(provider, "ok:1", "d"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("지원하지 않는 로그인 방식이에요."));
    }

    @Test
    void 삭제_유예_중인_계정은_로그인할_수_없다() throws Exception {
        String providerUserId = "kakao-" + randomId();
        long userId = Long.parseLong(read(signupAndGetBody(mockMvc, providerUserId, "d"), "$.user.id"));
        jdbcTemplate.update("UPDATE users SET status = 'PENDING_DELETION' WHERE id = ?", userId);

        mockMvc.perform(login("kakao", FakeSocialLoginVerifier.token(providerUserId), "d"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 같은_기기로_다시_로그인하면_그_기기의_이전_세션은_폐기된다() throws Exception {
        String providerUserId = "kakao-" + randomId();
        String first = signupAndGetBody(mockMvc, providerUserId, "same-device");
        long userId = Long.parseLong(read(first, "$.user.id"));

        mockMvc.perform(login("kakao", FakeSocialLoginVerifier.token(providerUserId), "same-device"))
                .andExpect(status().isOk());

        mockMvc.perform(refresh(read(first, "$.tokens.refreshToken"))).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_sessions WHERE user_id = ? AND revoked_at IS NULL", Long.class, userId))
                .isEqualTo(1);
    }

    // --- 재발급·로그아웃 ---

    @Test
    void 재발급하면_새_토큰을_주고_이전_Refresh를_다시_쓰면_401() throws Exception {
        String body = signupAndGetBody(mockMvc, "kakao-" + randomId(), "d");
        String oldRefresh = read(body, "$.tokens.refreshToken");

        String refreshed = mockMvc.perform(refresh(oldRefresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(read(refreshed, "$.refreshToken")).isNotEqualTo(oldRefresh);

        mockMvc.perform(refresh(oldRefresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void 로그아웃하면_204이고_그_Refresh로_재발급할_수_없다() throws Exception {
        String refreshToken = read(signupAndGetBody(mockMvc, "kakao-" + randomId(), "d"), "$.tokens.refreshToken");

        mockMvc.perform(logout(refreshToken)).andExpect(status().isNoContent());

        mockMvc.perform(refresh(refreshToken)).andExpect(status().isUnauthorized());
    }

    @Test
    void 모르는_토큰으로_로그아웃해도_204() throws Exception {
        mockMvc.perform(logout("unknown-token")).andExpect(status().isNoContent());
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private boolean socialAccountExists(String providerUserId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM social_accounts WHERE provider_user_id = ?",
                Long.class, providerUserId) > 0;
    }
}
