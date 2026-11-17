package com.famlog.media.service;

import java.util.Map;

import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.famlog.media.domain.MediaAsset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 워커에게 처리 요청을 보낸다 (Redis Streams, 사양서 13.1). 커밋된 뒤에 보낸다.
 * 필드 이름은 워커 worker/app/schemas.py MediaJob 과 같아야 한다.
 * 보내지 못하면 미디어는 UPLOADED 로 남고, 앱이 complete 를 다시 부르면 다시 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaJobPublisher {

    private final StringRedisTemplate redis;
    private final MediaProperties properties;

    public void publish(MediaAsset media, boolean extractLocation) {
        Map<String, String> fields = Map.of(
                "mediaId", Long.toString(media.getId()),
                "storageKey", media.getStorageKey(),
                "mediaType", media.getMediaType().name(),
                "mime", media.getMime(),
                "extractLocation", Boolean.toString(extractLocation),
                "attempt", "1");
        Runnable send = () -> {
            try {
                redis.opsForStream().add(StreamRecords.newRecord().in(properties.streams().jobs()).ofMap(fields));
            } catch (RuntimeException e) {
                log.warn("미디어 처리 요청을 보내지 못했습니다: mediaId={}, error={}", media.getId(),
                        e.getClass().getSimpleName());
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
