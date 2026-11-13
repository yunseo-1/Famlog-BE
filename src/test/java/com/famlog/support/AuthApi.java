package com.famlog.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

/**
 * 인증 API 테스트 도우미. 요청마다 다른 IP를 써서 로그인 요청 제한(IP당 분당 10회)에 걸리지 않게 한다.
 */
public final class AuthApi {

    public static final String TERMS_VERSION = "1.0";
    public static final String PRIVACY_VERSION = "1.0";

    private AuthApi() {
    }

    public static MockHttpServletRequestBuilder login(String provider, String providerToken, String deviceId) {
        return json(post("/api/v1/auth/{provider}/login", provider), """
                {"providerToken":"%s","device":{"deviceId":"%s","deviceName":"테스트폰","platform":"ANDROID"}}
                """.formatted(providerToken, deviceId));
    }

    public static MockHttpServletRequestBuilder signup(String provider, String providerToken, String deviceId,
            String nickname, String birthYm, String termsVersion, String privacyVersion) {
        return json(post("/api/v1/auth/{provider}/signup", provider), """
                {"providerToken":"%s",
                 "device":{"deviceId":"%s","deviceName":"테스트폰","platform":"ANDROID"},
                 "nickname":"%s","birthYm":"%s",
                 "agreements":{"termsVersion":"%s","privacyVersion":"%s"}}
                """.formatted(providerToken, deviceId, nickname, birthYm, termsVersion, privacyVersion));
    }

    /** 만 14세를 넘긴 정상 가입 */
    public static MockHttpServletRequestBuilder signup(String providerUserId, String deviceId) {
        return signup("kakao", FakeSocialLoginVerifier.token(providerUserId), deviceId, "엄마", "198005",
                TERMS_VERSION, PRIVACY_VERSION);
    }

    public static MockHttpServletRequestBuilder refresh(String refreshToken) {
        return json(post("/api/v1/auth/refresh"), "{\"refreshToken\":\"%s\"}".formatted(refreshToken));
    }

    public static MockHttpServletRequestBuilder logout(String refreshToken) {
        return json(post("/api/v1/auth/logout"), "{\"refreshToken\":\"%s\"}".formatted(refreshToken));
    }

    /** 가입하고 응답 JSON을 돌려준다 */
    public static String signupAndGetBody(MockMvc mockMvc, String providerUserId, String deviceId) throws Exception {
        return mockMvc.perform(signup(providerUserId, deviceId)).andReturn().getResponse().getContentAsString();
    }

    public static String read(String json, String path) {
        Object value = JsonPath.read(json, path);
        return value == null ? null : value.toString();
    }

    /** 서울 기준 현재 연월에서 years년·months개월 전 (YYYYMM) */
    public static String birthYmAgo(int years, int months) {
        return YearMonth.now(ZoneId.of("Asia/Seoul")).minusYears(years).minusMonths(months)
                .format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String ip = "172.16." + random.nextInt(256) + "." + random.nextInt(1, 255);
        return builder.contentType(MediaType.APPLICATION_JSON).content(body).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }
}
