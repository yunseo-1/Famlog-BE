package com.famlog.user.service;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 내보내기 배치 (사양서 4.1 ACC-02): 1분마다 만료된 파일을 지우고, 대기 요청을 한 번에 최대 20개까지 하나씩 만든다.
 * 여러 서버가 함께 돌아도 요청 행 잠금으로 한 번만 만든다. 테스트는 famlog.data-export.ticker-enabled=false 로 끄고 직접 부른다
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "famlog.data-export.ticker-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
class DataExportTicker {

    private final DataExportService exportService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    void tick() {
        try {
            int expired = exportService.expireDue();
            if (expired > 0) {
                log.info("내보내기 만료: expired={}", expired);
            }
            for (int i = 0; i < DataExportService.BATCH; i++) {
                Optional<Long> next = exportService.claimNext();
                if (next.isEmpty()) {
                    break;
                }
                exportService.process(next.get());
            }
        } catch (RuntimeException e) {
            log.warn("내보내기 배치 실패: error={}", e.getClass().getSimpleName());
        }
    }
}
