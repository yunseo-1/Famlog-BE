package com.famlog.user.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 계정 파기 배치 (사양서 8.9): 매일 04:00(KST) 유예가 지난 삭제 요청을 하나씩 파기한다. 여러 서버가 함께 돌아도 요청 행 잠금으로
 * 한 번만 처리한다. 하나가 실패해도 나머지는 계속하고, 실패한 요청은 다음 날 다시 처리한다.
 * 테스트는 famlog.account.purge-enabled=false 로 끄고 직접 부른다
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "famlog.account.purge-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
class AccountPurgeTicker {

    private final AccountDeletionService deletionService;
    private final Clock clock;

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    void purgeDue() {
        int purged = 0;
        for (long requestId : deletionService.dueRequestIds(Instant.now(clock))) {
            try {
                if (deletionService.purge(requestId)) {
                    purged++;
                }
            } catch (RuntimeException e) {
                log.warn("계정 파기 실패: requestId={}, error={}", requestId, e.getClass().getSimpleName());
            }
        }
        if (purged > 0) {
            log.info("계정 파기 배치: purged={}", purged);
        }
    }
}
