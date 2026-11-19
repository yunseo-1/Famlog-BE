package com.famlog.candidate.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 대화 분석 설정 (application.yml famlog.ai, 사양서 4.5 ARC-01, 6.2 ①).
 *
 * @param enabled        전체 끄기 스위치. 끄면 어떤 메시지도 분석 대상으로 넘기지 않는다
 * @param tickerEnabled  분석 시각이 된 방을 5초마다 꺼내는 스케줄러. 테스트는 끄고 직접 부른다
 * @param pause          규칙 필터에 걸린 뒤 대화가 이만큼 멈추면 분석 (30초)
 * @param batchIdle      규칙 필터에 안 걸린 메시지만 있을 때 대화가 이만큼 멈추면 보조 분석 (10분)
 * @param batchSize      분석 안 된 메시지가 이만큼 쌓이면 바로 보조 분석 (30개)
 * @param contextSize    분석 구간 앞 맥락 최대 개수 (20개)
 * @param maxMessages    한 요청의 분석 구간 최대 개수. 넘으면 최근 것만 보낸다
 * @param streams        Redis Streams 이름 (워커 worker/app/config.py 와 같아야 한다)
 */
@ConfigurationProperties("famlog.ai")
public record AiProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean tickerEnabled,
        @DefaultValue("30s") Duration pause,
        @DefaultValue("10m") Duration batchIdle,
        @DefaultValue("30") int batchSize,
        @DefaultValue("20") int contextSize,
        @DefaultValue("50") int maxMessages,
        @DefaultValue Streams streams) {

    /**
     * @param jobs         API → 워커 분석 요청
     * @param results      워커 → API 분석 결과
     * @param resultsGroup API 서버들의 소비 그룹
     */
    public record Streams(@DefaultValue("famlog:ai.extract") String jobs,
            @DefaultValue("famlog:ai.extract-results") String results,
            @DefaultValue("famlog-api") String resultsGroup) {
    }
}
