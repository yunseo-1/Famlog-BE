package com.famlog.chat.relay;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/** 모든 서버가 중계 채널을 구독하고, 받은 이벤트를 자기 연결에 전달한다. */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RelayConfig {

    @Bean
    RedisMessageListenerContainer chatRelayListenerContainer(RedisConnectionFactory connectionFactory,
            JsonMapper jsonMapper, LocalDelivery localDelivery) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                RelayEnvelope envelope = jsonMapper.readValue(
                        new String(message.getBody(), StandardCharsets.UTF_8), RelayEnvelope.class);
                localDelivery.deliver(envelope);
            } catch (RuntimeException e) {
                log.warn("중계 메시지를 처리하지 못했습니다: error={}", e.getClass().getSimpleName());
            }
        }, new ChannelTopic(ChatRelay.CHANNEL));
        return container;
    }
}
