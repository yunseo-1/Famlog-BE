package com.famlog.schedule.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.famlog.common.metrics.FamlogMetrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 1분마다 리마인드를 보낸다 (사양서 4.3 SCH-03). 테스트는 famlog.schedule.reminder-enabled=false 로 끄고
 * {@link ReminderService#sendDue} 를 시각을 정해 직접 부른다.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "famlog.schedule.reminder-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
class ReminderTicker {

    private final FamlogMetrics metrics;
    private final ReminderService reminderService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    void tick() {
        try {
            reminderService.sendDue(Instant.now(clock));
        } catch (RuntimeException e) {
            metrics.batchFailure("reminder");
            log.warn("일정 리마인드를 처리하지 못했습니다: error={}", e.getClass().getSimpleName());
        }
    }
}
