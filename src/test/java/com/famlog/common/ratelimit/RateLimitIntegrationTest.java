package com.famlog.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.famlog.support.IntegrationTest;

/**
 * 요청 제한 (사양서 9.6, 9.9 SEC-T5 일부). 실제 Redis 컨테이너 + Bucket4j로 확인한다.
 * 한도 안의 요청은 본문이 없어 400으로 끝난다. 429만 아니면 된다.
 */
class RateLimitIntegrationTest extends IntegrationTest {

    @Autowired
    RateLimiter rateLimiter;

    @Test
    void 로그인은_IP당_분당_10회_11번째는_429_RATE_LIMITED와_Retry_After() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(login("kakao", ip)).andExpect(notLimited());
        }

        MvcResult limited = mockMvc.perform(login("kakao", ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andReturn();
        long retryAfter = Long.parseLong(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(1L, 61L);
    }

    @Test
    void 로그인_한도는_제공자와_관계없이_같은_IP로_센다() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(login("kakao", ip));
        }

        mockMvc.perform(login("google", ip)).andExpect(status().isTooManyRequests());
    }

    @Test
    void 다른_IP는_따로_센다() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(login("kakao", ip));
        }

        mockMvc.perform(login("kakao", randomIp())).andExpect(notLimited());
    }

    @Test
    void 가입은_로그인과_같은_IP_한도를_나눠_쓴다() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(login("kakao", ip));
        }

        mockMvc.perform(withIp(post("/api/v1/auth/{provider}/signup", "kakao"), ip))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void 토큰_재발급은_IP당_분당_30회() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(withIp(post("/api/v1/auth/refresh"), ip)).andExpect(notLimited());
        }

        mockMvc.perform(withIp(post("/api/v1/auth/refresh"), ip)).andExpect(status().isTooManyRequests());
    }

    @Test
    void 초대_코드_입력은_사용자당_분당_5회_6번째는_429_다른_사용자는_영향_없음() throws Exception {
        long userId = randomId();
        String sharedIp = randomIp();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(join(userId, sharedIp)).andExpect(notLimited());
        }

        mockMvc.perform(join(userId, sharedIp)).andExpect(status().isTooManyRequests());
        // 같은 IP(같은 집 Wi-Fi)라도 다른 가족은 막히지 않는다
        mockMvc.perform(join(randomId(), sharedIp)).andExpect(notLimited());
    }

    @Test
    void 기간이_지나면_다시_허용된다() throws InterruptedException {
        String key = "rate-limit:test:" + randomId();

        assertThat(rateLimiter.tryConsume(key, 2, Duration.ofSeconds(1)).allowed()).isTrue();
        assertThat(rateLimiter.tryConsume(key, 2, Duration.ofSeconds(1)).allowed()).isTrue();
        RateLimiter.Result denied = rateLimiter.tryConsume(key, 2, Duration.ofSeconds(1));
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1);

        Thread.sleep(1_100);

        assertThat(rateLimiter.tryConsume(key, 2, Duration.ofSeconds(1)).allowed()).isTrue();
    }

    private static MockHttpServletRequestBuilder login(String provider, String ip) {
        return withIp(post("/api/v1/auth/{provider}/login", provider), ip);
    }

    private static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private static ResultMatcher notLimited() {
        return result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(429);
    }

    private MockHttpServletRequestBuilder join(long userId, String ip) {
        return post("/api/v1/groups/join").with(asUser(userId)).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private static String randomIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }
}
