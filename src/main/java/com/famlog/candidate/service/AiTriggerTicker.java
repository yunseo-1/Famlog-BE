package com.famlog.candidate.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 5초마다 분석 시각이 된 방을 꺼낸다 (사양서 4.5 ARC-01, 12장 "대화 멈춘 뒤 1분 이내"). 5분마다 멈춘 요청을 정리한다.
 * 테스트는 famlog.ai.ticker-enabled=false 로 끄고 {@link AiTriggerService#runDue} 를 시각을 정해 직접 부른다.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "famlog.ai.ticker-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
class AiTriggerTicker {

    private final AiTriggerService triggerService;
    private final StaleAiJobService staleJobService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "PT5S", initialDelayString = "PT10S")
    void tick() {
        try {
            triggerService.runDue(Instant.now(clock));
        } catch (RuntimeException e) {
            log.warn("AI 분석 트리거를 처리하지 못했습니다: error={}", e.getClass().getSimpleName());
        }
    }

    /** 5분마다 30분 넘게 결과가 없는 요청을 실패로 정리한다 */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    void failStale() {
        try {
            staleJobService.failStale();
        } catch (RuntimeException e) {
            log.warn("멈춘 AI 분석 요청을 정리하지 못했습니다: error={}", e.getClass().getSimpleName());
        }
    }
}
