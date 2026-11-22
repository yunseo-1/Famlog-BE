package com.famlog.user.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 내 데이터 내보내기 (사양서 4.1 ACC-02, 8.4).
 *
 * @param linkTtl        ZIP 과 그 안의 원본 링크가 유효한 기간 (7일. S3 서명 URL 최대치와 같다)
 * @param downloadUrlTtl 상태 조회 때마다 새로 만드는 ZIP 다운로드 링크
 * @param cooldown       만든 뒤 다시 요청할 수 있을 때까지 (실패한 요청은 세지 않는다)
 * @param maxAttempts    만들다 실패하면 다시 시도하는 최대 횟수
 * @param staleAfter     PROCESSING 이 이보다 오래되면 서버가 멈춘 것으로 보고 다시 잡는다
 * @param tickerEnabled  1분마다 대기 요청을 만들고 만료된 파일을 지운다. 테스트는 끄고 직접 부른다
 */
@ConfigurationProperties("famlog.data-export")
public record DataExportProperties(@DefaultValue("7d") Duration linkTtl,
        @DefaultValue("10m") Duration downloadUrlTtl,
        @DefaultValue("24h") Duration cooldown,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("30m") Duration staleAfter,
        @DefaultValue("true") boolean tickerEnabled) {
}
