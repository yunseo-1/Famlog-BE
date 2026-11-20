package com.famlog.admin.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 관리자 도구 (사양서 14장 최소 관리자 페이지). token 이 비어 있으면 /admin/api 전체가 꺼진다(404).
 * 운영에서는 Nginx 로 IP 를 막는다 (S22)
 *
 * @param token X-Admin-Token 으로 받는 값 (환경변수 FAMLOG_ADMIN_TOKEN). 로그에 남기지 않는다
 */
@ConfigurationProperties("famlog.admin")
public record AdminProperties(String token) {

    public boolean enabled() {
        return token != null && !token.isBlank();
    }
}
