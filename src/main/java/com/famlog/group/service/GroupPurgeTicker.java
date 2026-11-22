package com.famlog.group.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 그룹 파기 배치 (사양서 2.2, 8.2): 매일 04:30(KST) 유예가 지난 삭제 예정 그룹을 하나씩 파기한다. 여러 서버가 함께 돌아도
 * 그룹 행 잠금으로 한 번만 처리한다. 하나가 실패해도 나머지는 계속하고, 실패한 그룹은 다음 날 다시 처리한다.
 * 테스트는 famlog.cleanup.enabled=false 로 끄고 직접 부른다
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "famlog.cleanup.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
class GroupPurgeTicker {

    private final GroupPurgeService purgeService;
    private final Clock clock;

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    void purgeDue() {
        int purged = 0;
        for (long groupId : purgeService.dueGroupIds(Instant.now(clock))) {
            try {
                if (purgeService.purge(groupId)) {
                    purged++;
                }
            } catch (RuntimeException e) {
                log.warn("그룹 파기 실패: groupId={}, error={}", groupId, e.getClass().getSimpleName());
            }
        }
        if (purged > 0) {
            log.info("그룹 파기 배치: purged={}", purged);
        }
    }
}
