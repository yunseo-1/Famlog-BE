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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.famlog.support.IntegrationTest;

/**
 * 요청 제한 (사양서 9.6, 9.9 SEC-T5 일부). 실제 Redis 컨테이너 + Bucket4j로 확인한다.
 * 로그인·초대 API는 아직 없어서, 한도 안의 요청은 404로 끝난다.
 */
class RateLimitIntegrationTest extends IntegrationTest {

    @Autowired
    RateLimiter rateLimiter;

    @Test
    void 로그인은_IP당_분당_10회_11번째는_429_RATE_LIMITED와_Retry_After() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(login("kakao", ip)).andExpect(status().isNotFound());
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

        mockMvc.perform(login("kakao", randomIp())).andExpect(status().isNotFound());
    }

    @Test
    void 초대_코드_입력은_사용자당_분당_5회_6번째는_429_다른_사용자는_영향_없음() throws Exception {
        long userId = randomId();
        String sharedIp = randomIp();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(join(userId, sharedIp)).andExpect(status().isNotFound());
        }

        mockMvc.perform(join(userId, sharedIp)).andExpect(status().isTooManyRequests());
        // 같은 IP(같은 집 Wi-Fi)라도 다른 가족은 막히지 않는다
        mockMvc.perform(join(randomId(), sharedIp)).andExpect(status().isNotFound());
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
        return post("/api/v1/auth/{provider}/login", provider).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
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
