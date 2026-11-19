package com.famlog.candidate.service;

import java.util.Map;

import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 워커에게 분석 요청을 보낸다 (Redis Streams famlog:ai.extract). 커밋된 뒤에 보낸다.
 * 필드 이름은 워커 worker/app/ai/schemas.py ExtractJob 과 같아야 한다.
 * 보내지 못하면 ai_jobs 는 QUEUED 로 남는다 (그 구간은 다시 보내지 않는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractJobPublisher {

    private final StringRedisTemplate redis;
    private final AiProperties properties;

    public void publish(long jobId, String payload) {
        Map<String, String> fields = Map.of("jobId", Long.toString(jobId), "attempt", "1", "payload", payload);
        Runnable send = () -> {
            try {
                redis.opsForStream().add(StreamRecords.newRecord().in(properties.streams().jobs()).ofMap(fields));
            } catch (RuntimeException e) {
                log.warn("AI 분석 요청을 보내지 못했습니다: jobId={}, error={}", jobId, e.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }
}
