package com.famlog.media.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.famlog.common.cleanup.CleanupProperties;
import com.famlog.common.metrics.FamlogMetrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 미디어 정리 배치: 매시 10분. 테스트는 famlog.cleanup.enabled=false 로 끄고 직접 부른다 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "famlog.cleanup.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
class MediaCleanupTicker {

    private final FamlogMetrics metrics;
    private final MediaCleanupService cleanupService;
    private final CleanupProperties properties;
    private final Clock clock;

    @Scheduled(cron = "0 10 * * * *", zone = "Asia/Seoul")
    void clean() {
        try {
            Instant before = Instant.now(clock).minus(properties.unattachedMediaAfter());
            int unattached = cleanupService.cleanUnattached(before);
            int deleted = cleanupService.cleanDeletedMessages();
            if (unattached + deleted > 0) {
                log.info("미디어 정리: unattached={}, deletedMessages={}", unattached, deleted);
            }
        } catch (RuntimeException e) {
            metrics.batchFailure("media-cleanup");
            log.warn("미디어 정리 실패: error={}", e.getClass().getSimpleName());
        }
    }
}
