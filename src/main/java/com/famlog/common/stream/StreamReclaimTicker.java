package com.famlog.common.stream;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 1분마다 결과 스트림의 멈춘 대기 메시지를 회수한다. 테스트는 famlog.stream-reclaim.enabled=false 로 끄고 직접 부른다 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "famlog.stream-reclaim.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
class StreamReclaimTicker {

    private final List<ReclaimableStream> streams;

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void reclaim() {
        for (ReclaimableStream stream : streams) {
            try {
                stream.reclaimPending();
            } catch (RuntimeException e) {
                log.warn("대기 메시지 회수 실패: error={}", e.getClass().getSimpleName());
            }
        }
    }
}
