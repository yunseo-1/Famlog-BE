package com.famlog.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.famlog.common.error.ErrorResponseWriter;

import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {

    private static final RateLimitProperties.Rule LOGIN = new RateLimitProperties.Rule("login", "POST",
            "/api/v1/auth/{provider}/login", RateLimitProperties.KeyType.IP, 10, Duration.ofMinutes(1));

    private final ErrorResponseWriter writer = new ErrorResponseWriter(JsonMapper.builder().build());

    @Test
    void Redis_장애로_제한을_확인할_수_없으면_요청을_통과시킨다_fail_open() throws Exception {
        RateLimitFilter filter = filter((key, capacity, period) -> {
            throw new RedisConnectionFailureException("down");
        });
        AtomicBoolean passed = new AtomicBoolean();

        MockHttpServletResponse response = run(filter, "POST", "/api/v1/auth/kakao/login", passed);

        assertThat(passed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void 한도를_넘으면_컨트롤러로_보내지_않고_429와_Retry_After() throws Exception {
        RateLimitFilter filter = filter((key, capacity, period) -> new RateLimiter.Result(false, 42));
        AtomicBoolean passed = new AtomicBoolean();

        MockHttpServletResponse response = run(filter, "POST", "/api/v1/auth/kakao/login", passed);

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
        assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
    }

    @Test
    void 규칙에_맞지_않는_요청은_세지_않는다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RateLimitFilter filter = filter((key, capacity, period) -> {
            calls.incrementAndGet();
            return new RateLimiter.Result(true, 0);
        });

        run(filter, "GET", "/api/v1/auth/kakao/login", new AtomicBoolean());
        run(filter, "POST", "/api/v1/auth/refresh", new AtomicBoolean());

        assertThat(calls).hasValue(0);
    }

    @Test
    void IP_규칙은_규칙_이름과_IP로_키를_만든다() throws Exception {
        StringBuilder usedKey = new StringBuilder();
        RateLimitFilter filter = filter((key, capacity, period) -> {
            usedKey.append(key);
            return new RateLimiter.Result(true, 0);
        });

        run(filter, "POST", "/api/v1/auth/google/login", new AtomicBoolean());

        assertThat(usedKey.toString()).isEqualTo("rate-limit:login:ip:203.0.113.7");
    }

    @Test
    void 요청_제한을_끄면_세지_않는다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RateLimitFilter filter = new RateLimitFilter(new RateLimitProperties(false, List.of(LOGIN)),
                (key, capacity, period) -> {
                    calls.incrementAndGet();
                    return new RateLimiter.Result(false, 1);
                }, writer);
        AtomicBoolean passed = new AtomicBoolean();

        run(filter, "POST", "/api/v1/auth/kakao/login", passed);

        assertThat(calls).hasValue(0);
        assertThat(passed).isTrue();
    }

    private RateLimitFilter filter(RateLimiter limiter) {
        return new RateLimitFilter(new RateLimitProperties(true, List.of(LOGIN)), limiter, writer);
    }

    private static MockHttpServletResponse run(RateLimitFilter filter, String method, String uri,
            AtomicBoolean passed) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> passed.set(true));
        return response;
    }
}
