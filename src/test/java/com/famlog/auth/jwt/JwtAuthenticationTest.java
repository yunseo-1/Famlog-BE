package com.famlog.auth.jwt;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.ResultActions;

import com.famlog.auth.domain.Platform;
import com.famlog.auth.service.SessionTokenService;
import com.famlog.auth.service.TokenPair;
import com.famlog.support.IntegrationTest;
import com.famlog.support.TestJwtKeys;
import com.famlog.support.web.AuthTestController;
import com.nimbusds.jwt.JWTClaimsSet;

/** Access JWT 검증 (사양서 9.2, 9.9 SEC-T3, 18.1 AUTH-03). 실제 토큰으로 보안 필터 체인을 통과시킨다. */
class JwtAuthenticationTest extends IntegrationTest {

    private static final String ME = AuthTestController.ME;

    @Autowired
    SessionTokenService sessionTokenService;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    AuthProperties authProperties;

    @Autowired
    JwtKeys jwtKeys;

    @Test
    void 정상_토큰이면_인증되고_사용자와_세션을_알_수_있다() throws Exception {
        long userId = createUser();
        TokenPair tokens = sessionTokenService.startSession(userId, "device-1", "Pixel 9", Platform.ANDROID);

        call(tokens.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.sessionId").value(tokens.sessionId()));
    }

    @Test
    void 만료된_토큰은_401_AUTH_TOKEN_EXPIRED() throws Exception {
        long userId = createUser();
        long sessionId = sessionTokenService.startSession(userId, "d", null, Platform.ANDROID).sessionId();
        AccessTokenIssuer pastIssuer = new AccessTokenIssuer(jwtEncoder, authProperties, jwtKeys,
                Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-40)));

        call(pastIssuer.issue(userId, sessionId).value())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_EXPIRED"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""));
    }

    @Test
    void 서버가_모르는_키로_서명한_위조_토큰은_401_AUTH_INVALID() throws Exception {
        String forged = TestJwtKeys.sign(TestJwtKeys.UNREGISTERED, TestJwtKeys.ACTIVE_KID,
                TestJwtKeys.validClaims(1, 1).build());

        call(forged).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void 서명_후_내용을_바꾼_토큰은_401() throws Exception {
        long userId = createUser();
        String token = sessionTokenService.startSession(userId, "d", null, Platform.ANDROID).accessToken();
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"sub\":\"" + userId + "\"", "\"sub\":\"1\"");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        call(tampered).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void 서명_없는_alg_none_토큰은_401() throws Exception {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url(TestJwtKeys.validClaims(1, 1).build().toString());

        call(header + "." + payload + ".").andExpect(status().isUnauthorized());
    }

    @Test
    void 형식이_틀린_토큰은_401() throws Exception {
        call("not-a-jwt").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void 토큰이_없으면_401_AUTH_INVALID() throws Exception {
        mockMvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @Test
    void 교체_전_키로_서명한_토큰도_검증된다() throws Exception {
        long userId = createUser();
        long sessionId = sessionTokenService.startSession(userId, "d", null, Platform.ANDROID).sessionId();
        String token = TestJwtKeys.sign(TestJwtKeys.PREVIOUS, TestJwtKeys.PREVIOUS_KID,
                TestJwtKeys.validClaims(userId, sessionId).build());

        call(token).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(userId));
    }

    @Test
    void 발급자가_다른_토큰은_401() throws Exception {
        String token = TestJwtKeys.sign(TestJwtKeys.ACTIVE, TestJwtKeys.ACTIVE_KID,
                TestJwtKeys.validClaims(1, 1).issuer("someone-else").build());

        call(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void 세션_ID_클레임이_없는_토큰은_401() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(TestJwtKeys.validClaims(1, 1).build()).claim("sid", null)
                .build();

        call(TestJwtKeys.sign(TestJwtKeys.ACTIVE, TestJwtKeys.ACTIVE_KID, claims))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 폐기된_세션의_Access_토큰은_만료_전이라도_즉시_401_AUTH03() throws Exception {
        long userId = createUser();
        TokenPair deviceA = sessionTokenService.startSession(userId, "device-a", null, Platform.ANDROID);
        TokenPair deviceB = sessionTokenService.startSession(userId, "device-b", null, Platform.ANDROID);
        call(deviceA.accessToken()).andExpect(status().isOk());

        // 기기 B에서 기기 A를 원격 로그아웃
        sessionTokenService.revokeSession(deviceA.sessionId());

        call(deviceA.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        call(deviceB.accessToken()).andExpect(status().isOk());
    }

    @Test
    void 만료됐고_폐기된_세션이면_재발급해도_소용없으므로_AUTH_INVALID() throws Exception {
        long userId = createUser();
        long sessionId = sessionTokenService.startSession(userId, "d", null, Platform.ANDROID).sessionId();
        sessionTokenService.revokeSession(sessionId);
        String expiredToken = TestJwtKeys.sign(TestJwtKeys.ACTIVE, TestJwtKeys.ACTIVE_KID,
                TestJwtKeys.validClaims(userId, sessionId)
                        .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                        .expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                        .build());

        call(expiredToken).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    private ResultActions call(String accessToken) throws Exception {
        return mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
