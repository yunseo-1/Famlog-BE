package com.famlog.chat.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 채팅 설정 (application.yml famlog.chat).
 *
 * @param sendRateCapacity 사용자당 sendRatePeriod 동안 보낼 수 있는 메시지 수 (사양서 9.6: 초당 5개)
 * @param defaultPageSize  메시지 목록 기본 개수
 * @param maxPageSize      메시지 목록 최대 개수
 */
@ConfigurationProperties("famlog.chat")
public record ChatProperties(long sendRateCapacity, Duration sendRatePeriod, int defaultPageSize, int maxPageSize) {
}
