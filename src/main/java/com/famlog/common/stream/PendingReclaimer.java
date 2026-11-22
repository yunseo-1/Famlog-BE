package com.famlog.common.stream;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결과 스트림의 멈춘 대기 메시지(PEL)를 이 서버로 가져와 다시 처리한다.
 * <ul>
 * <li>minIdle(5분) 넘게 확인되지 않은 메시지만. 처리에 성공하면 확인(필요하면 스트림에서도 삭제)</li>
 * <li>이미 maxDeliveries(3번) 전달됐으면 다시 처리하지 않고 DLQ 로 옮긴다. DLQ 에는 원래 id·대상 id 만 남기고 내용은 넣지 않는다
 * (분석 결과가 Redis 에 남지 않게, 사양서 8.7)</li>
 * <li>다시 처리하다 실패하면 대기 목록에 그대로 둔다 (다음 회수에서 전달 횟수가 늘어난다)</li>
 * </ul>
 */
@Slf4j
@Component
@EnableConfigurationProperties(StreamReclaimProperties.class)
@RequiredArgsConstructor
public class PendingReclaimer {

    static final int BATCH = 20;

    private final StringRedisTemplate redis;
    private final StreamReclaimProperties properties;

    /**
     * @param stream          결과 스트림
     * @param idField         DLQ 에 남길 대상 id 필드 (jobId, mediaId)
     * @param deleteAfterAck  확인한 뒤 스트림에서도 지운다
     * @param handler         결과 반영 (예외를 던지면 대기 목록에 남는다)
     */
    public int reclaim(String stream, String group, String consumer, String idField, boolean deleteAfterAck,
            Consumer<Map<String, String>> handler) {
        PendingMessages pending = redis.opsForStream().pending(stream, group, Range.unbounded(), BATCH);
        Duration minIdle = properties.minIdle();
        int handled = 0;
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) < 0) {
                continue;
            }
            RecordId id = message.getId();
            List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(stream, group, consumer,
                    minIdle, id);
            if (claimed.isEmpty()) { // 다른 서버가 먼저 가져갔거나 스트림에서 지워졌다
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            claimed.getFirst().getValue().forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
            if (message.getTotalDeliveryCount() >= properties.maxDeliveries()) {
                Map<String, String> dead = new LinkedHashMap<>();
                dead.put("recordId", id.getValue());
                String target = fields.getOrDefault(idField, "");
                dead.put(idField, target.matches("\\d{1,19}") ? target : "");
                dead.put("error", "MAX_DELIVERIES");
                redis.opsForStream().add(StreamRecords.mapBacked(dead).withStreamKey(dlqOf(stream)));
                log.error("결과가 {}번 전달돼도 처리되지 않아 DLQ로 옮깁니다: stream={}, recordId={}",
                        message.getTotalDeliveryCount(), stream, id.getValue());
            } else {
                try {
                    handler.accept(fields);
                    log.warn("멈춘 결과를 다시 처리했습니다: stream={}, recordId={}", stream, id.getValue());
                } catch (RuntimeException e) {
                    log.warn("멈춘 결과를 다시 처리하지 못했습니다: stream={}, recordId={}, error={}", stream,
                            id.getValue(), e.getClass().getSimpleName());
                    continue;
                }
            }
            redis.opsForStream().acknowledge(stream, group, id);
            if (deleteAfterAck) {
                redis.opsForStream().delete(stream, id);
            }
            handled++;
        }
        return handled;
    }

    /** 결과 스트림의 DLQ 이름 (famlog:media-results → famlog:media-results:dlq) */
    public static String dlqOf(String stream) {
        return stream + ":dlq";
    }
}
