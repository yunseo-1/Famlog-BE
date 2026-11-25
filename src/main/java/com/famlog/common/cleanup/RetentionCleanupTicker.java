package com.famlog.common.cleanup;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.famlog.common.metrics.FamlogMetrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 보관 기간 정리 배치: 매일 04:45(KST). 테스트는 famlog.cleanup.enabled=false 로 끄고 직접 부른다 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "famlog.cleanup.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
class RetentionCleanupTicker {

    private final FamlogMetrics metrics;
    private final RetentionCleanupService cleanupService;

    @Scheduled(cron = "0 45 4 * * *", zone = "Asia/Seoul")
    void clean() {
        try {
            Map<String, Integer> counts = cleanupService.clean();
            log.info("보관 기간 정리: {}", counts);
        } catch (RuntimeException e) {
            metrics.batchFailure("retention");
            log.warn("보관 기간 정리 실패: error={}", e.getClass().getSimpleName());
        }
    }
}
