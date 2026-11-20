package com.famlog.user.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 계정 삭제 (사양서 4.1 ACC-01, 8.9).
 *
 * @param deletionGrace 요청 뒤 파기까지 (30일, 이 동안 로그인하면 복구할 수 있다)
 * @param purgeEnabled  매일 04:00(KST) 파기 배치. 테스트는 끄고 직접 부른다
 */
@ConfigurationProperties("famlog.account")
public record AccountProperties(@DefaultValue("30d") Duration deletionGrace,
        @DefaultValue("true") boolean purgeEnabled) {
}
