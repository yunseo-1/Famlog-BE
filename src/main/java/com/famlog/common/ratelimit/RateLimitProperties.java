package com.famlog.common.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 요청 제한 규칙 (사양서 9.6). 수치는 application.yml 의 famlog.rate-limit.rules 에 둔다.
 */
@ConfigurationProperties("famlog.rate-limit")
public record RateLimitProperties(@DefaultValue("true") boolean enabled, @DefaultValue List<Rule> rules) {

    /**
     * @param name     Redis 키에 쓰는 규칙 이름
     * @param method   HTTP 메서드
     * @param path     경로 패턴 (예: /api/v1/auth/{provider}/login)
     * @param key      무엇 기준으로 셀지 (IP / 로그인 사용자)
     * @param capacity 기간 안에 허용하는 횟수
     * @param period   기간
     */
    public record Rule(String name, String method, String path, KeyType key, long capacity, Duration period) {
    }

    public enum KeyType {
        IP,
        USER
    }
}
