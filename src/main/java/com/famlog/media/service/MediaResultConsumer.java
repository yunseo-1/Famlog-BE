package com.famlog.media.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 워커 처리 결과 스트림을 소비 그룹으로 읽는다 (서버가 여러 대여도 결과마다 한 서버만 처리).
 * 반영에 성공하면 ACK. 실패한 결과는 대기 목록(PEL)에 남는다.
 */
@Slf4j
@Component
public class MediaResultConsumer implements SmartLifecycle {

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redis;
    private final MediaResultHandler handler;
    private final MediaProperties.Streams streams;
    private final String consumerName = "api-" + UUID.randomUUID();
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    public MediaResultConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redis,
            MediaResultHandler handler, MediaProperties properties) {
        this.connectionFactory = connectionFactory;
        this.redis = redis;
        this.handler = handler;
        this.streams = properties.streams();
    }

    @Override
    public void start() {
        createGroupIfMissing();
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(1))
                        .batchSize(10)
                        .errorHandler(e -> log.warn("미디어 처리 결과를 읽지 못했습니다: error={}",
                                e.getClass().getSimpleName()))
                        .build();
        container = StreamMessageListenerContainer.create(connectionFactory, options);
        container.receive(Consumer.from(streams.resultsGroup(), consumerName),
                StreamOffset.create(streams.results(), ReadOffset.lastConsumed()),
                message -> {
                    handler.apply(message.getValue());
                    redis.opsForStream().acknowledge(streams.resultsGroup(), message);
                });
        container.start();
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return container != null && container.isRunning();
    }

    private void createGroupIfMissing() {
        try {
            redis.opsForStream().createGroup(streams.results(), ReadOffset.from("0"), streams.resultsGroup());
        } catch (RuntimeException e) {
            // 이미 있으면 BUSYGROUP. Redis에 닿지 못하면 결과 반영만 멈추고 서버는 뜬다
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")
                    && !String.valueOf(e.getCause()).contains("BUSYGROUP")) {
                log.warn("미디어 결과 소비 그룹을 만들지 못했습니다: error={}", e.getClass().getSimpleName());
            }
        }
    }
}
