package com.famlog.auth.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.user.domain.SocialProvider;

/** 카카오 토큰 검증 (사양서 9.1 소셜 토큰 위조 대응). 카카오 서버 대신 MockRestServiceServer로 응답을 흉내 낸다. */
class KakaoTokenVerifierTest {

    private static final long OUR_APP_ID = 1234567L;
    private static final String TOKEN_INFO = "https://kapi.kakao.com/v1/user/access_token_info";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://kapi.kakao.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final KakaoTokenVerifier verifier = new KakaoTokenVerifier(builder.build(), OUR_APP_ID);

    @Test
    void 우리_앱에서_발급된_토큰이면_회원번호를_돌려준다() {
        server.expect(requestTo(TOKEN_INFO))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer kakao-access-token"))
                .andRespond(withSuccess("{\"id\":3001,\"expires_in\":7199,\"app_id\":1234567}",
                        MediaType.APPLICATION_JSON));

        SocialIdentity identity = verifier.verify("kakao-access-token");

        assertThat(identity).isEqualTo(new SocialIdentity(SocialProvider.KAKAO, "3001"));
        server.verify();
    }

    @Test
    void 다른_앱에서_발급된_토큰은_거부한다() {
        server.expect(requestTo(TOKEN_INFO))
                .andRespond(withSuccess("{\"id\":3001,\"expires_in\":7199,\"app_id\":9999}",
                        MediaType.APPLICATION_JSON));

        assertError(() -> verifier.verify("other-app-token"), ErrorCode.AUTH_INVALID);
    }

    @Test
    void 카카오가_401로_거부한_토큰은_AUTH_INVALID() {
        server.expect(requestTo(TOKEN_INFO))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"msg\":\"this access token does not exist\",\"code\":-401}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertError(() -> verifier.verify("expired"), ErrorCode.AUTH_INVALID);
    }

    @Test
    void 카카오_서버_오류는_재로그인이_아니라_일시적_오류() {
        server.expect(requestTo(TOKEN_INFO)).andRespond(withServerError());

        assertError(() -> verifier.verify("token"), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void 카카오에_연결하지_못해도_일시적_오류() {
        server.expect(requestTo(TOKEN_INFO)).andRespond(withException(new IOException("timeout")));

        assertError(() -> verifier.verify("token"), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void 앱_ID가_설정되지_않았으면_카카오를_부르지_않고_일시적_오류() {
        KakaoTokenVerifier unconfigured = new KakaoTokenVerifier(builder.build(), null);

        assertError(() -> unconfigured.verify("token"), ErrorCode.INTERNAL_ERROR);
        server.verify();
    }

    private static void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}
