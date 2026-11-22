package com.famlog.common.stream;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 워커 결과 스트림의 멈춘 대기 메시지(PEL) 회수.
 *
 * @param enabled       1분마다 회수한다. 테스트는 끄고 직접 부른다
 * @param minIdle       이보다 오래 확인되지 않은 메시지를 가져온다 (처리 중 서버가 죽었거나 반영이 실패한 결과)
 * @param maxDeliveries 이만큼 전달돼도 처리되지 않으면 DLQ 로 옮기고 확인한다
 */
@ConfigurationProperties("famlog.stream-reclaim")
public record StreamReclaimProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("5m") Duration minIdle,
        @DefaultValue("3") int maxDeliveries) {
}
