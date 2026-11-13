package com.famlog.common.ratelimit;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.famlog.common.error.ErrorCode;
import com.famlog.common.error.ErrorResponseWriter;
import com.famlog.common.security.AuthUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 요청 제한 (사양서 9.6). 한도를 넘으면 429 RATE_LIMITED + Retry-After.
 * Redis 장애 시에는 제한을 건너뛴다 (fail-open): 요청 제한 장애가 로그인 장애로 번지지 않게 한다 (사양서 12장 가용성).
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final List<CompiledRule> rules;
    private final RateLimiter rateLimiter;
    private final ErrorResponseWriter errorResponseWriter;

    public RateLimitFilter(RateLimitProperties properties, RateLimiter rateLimiter,
            ErrorResponseWriter errorResponseWriter) {
        this.rules = properties.enabled()
                ? properties.rules().stream().map(CompiledRule::new).toList()
                : List.of();
        this.rateLimiter = rateLimiter;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        PathContainer path = PathContainer.parsePath(request.getRequestURI());
        for (CompiledRule rule : rules) {
            if (!rule.matches(request.getMethod(), path)) {
                continue;
            }
            RateLimiter.Result result;
            try {
                result = rateLimiter.tryConsume(rule.key(request), rule.rule.capacity(), rule.rule.period());
            } catch (RuntimeException e) {
                log.warn("요청 제한 확인 실패로 제한 없이 통과시킵니다: rule={}, error={}",
                        rule.rule.name(), e.getClass().getSimpleName());
                continue;
            }
            if (!result.allowed()) {
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(result.retryAfterSeconds()));
                errorResponseWriter.write(request, response, ErrorCode.RATE_LIMITED);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static final class CompiledRule {

        private final RateLimitProperties.Rule rule;
        private final PathPattern pattern;

        CompiledRule(RateLimitProperties.Rule rule) {
            this.rule = rule;
            this.pattern = PathPatternParser.defaultInstance.parse(rule.path());
        }

        boolean matches(String method, PathContainer path) {
            return rule.method().equalsIgnoreCase(method) && pattern.matches(path);
        }

        /** USER 규칙인데 로그인 사용자가 없으면 IP로 센다 */
        String key(HttpServletRequest request) {
            String subject = rule.key() == RateLimitProperties.KeyType.USER
                    ? AuthUser.from(SecurityContextHolder.getContext().getAuthentication())
                            .map(user -> "user:" + user.userId())
                            .orElse(null)
                    : null;
            if (subject == null) {
                subject = "ip:" + request.getRemoteAddr();
            }
            return "rate-limit:" + rule.name() + ":" + subject;
        }
    }
}
