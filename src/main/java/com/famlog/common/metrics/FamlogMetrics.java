package com.famlog.common.metrics;

import java.util.List;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 알림 규칙(infra/prometheus/alerts.yml)이 보는 사건 카운터 (사양서 9.8 보안 알림, 12장 관측).
 * 태그에는 정해진 값만 넣는다 (사용자·그룹 id, 토큰 등 개인정보를 넣지 않는다).
 * HTTP 5xx·로그인 실패(401)는 기본 메트릭 http_server_requests 로 본다
 */
@Component
public class FamlogMetrics {

    /** Refresh 토큰 재사용 감지 (탈취 의심, 전 세션 폐기) */
    public static final String REFRESH_REUSE = "famlog.auth.refresh.reuse";
    /** 배치 실패. 태그 job */
    public static final String BATCH_FAILURES = "famlog.batch.failures";
    /** 푸시 발송 실패. 태그 reason (invalid_token, rejected, gave_up) */
    public static final String PUSH_FAILURES = "famlog.push.failures";

    /** 미리 0으로 만들어 둔다: 처음 생긴 시계열의 첫 증가는 increase() 로 잡히지 않는다 */
    static final List<String> JOBS = List.of("account-purge", "group-purge", "media-cleanup", "retention",
            "data-export", "stream-reclaim", "ai-trigger", "ai-stale", "reminder");
    static final List<String> PUSH_REASONS = List.of("invalid_token", "rejected", "gave_up");

    private final MeterRegistry registry;

    public FamlogMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.counter(REFRESH_REUSE);
        JOBS.forEach(job -> registry.counter(BATCH_FAILURES, "job", job));
        PUSH_REASONS.forEach(reason -> registry.counter(PUSH_FAILURES, "reason", reason));
    }

    public void refreshReuse() {
        registry.counter(REFRESH_REUSE).increment();
    }

    /** @param job {@link #JOBS} 중 하나 */
    public void batchFailure(String job) {
        registry.counter(BATCH_FAILURES, "job", job).increment();
    }
}
