package com.famlog.chat.ws;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * STOMP over WebSocket (사양서 11.4). 앱은 ws(s)://{서버}/ws 에 연결하고 CONNECT 헤더에 Authorization: Bearer {accessToken}.
 * <ul>
 * <li>앱 → 서버: /pub/groups/{groupId}/messages, /pub/groups/{groupId}/read</li>
 * <li>서버 → 앱: /sub/groups/{groupId} (그룹 이벤트), /user/queue/events (개인 이벤트)</li>
 * </ul>
 * 브로커는 서버 안 메모리 브로커이고, 서버 사이 전달은 Redis Pub/Sub(ChatRelay)으로 한다.
 * 인증은 쿠키가 아니라 CONNECT 헤더 토큰이라 출처(Origin) 제한이 필요 없다 (Android 앱은 Origin을 보내지 않는다).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    static final long HEARTBEAT_MILLIS = 10_000;
    /** 본문 2,000자(한글 3바이트) + 헤더 여유 */
    static final int MAX_MESSAGE_BYTES = 16 * 1024;

    private final StompAuthInterceptor authInterceptor;
    private final WsSessionRegistry sessionRegistry;
    private final JsonMapper jsonMapper;
    private TaskScheduler heartbeatScheduler;

    /** 하트비트는 STOMP 설정이 만드는 스케줄러를 쓴다 (순환 참조를 피하려고 지연 주입) */
    @Autowired
    void setHeartbeatScheduler(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler scheduler) {
        this.heartbeatScheduler = scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
        registry.setErrorHandler(new StompErrorHandler(jsonMapper));
        // 한 연결에서 보낸 프레임은 보낸 순서대로 처리한다 (구독 → 전송, 연달아 보낸 메시지의 순서)
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/sub", "/queue")
                .setHeartbeatValue(new long[] { HEARTBEAT_MILLIS, HEARTBEAT_MILLIS })
                .setTaskScheduler(heartbeatScheduler);
        registry.setApplicationDestinationPrefixes("/pub");
        registry.setUserDestinationPrefix("/user");
        // 한 연결로 나가는 이벤트도 발행 순서대로 보낸다
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(MAX_MESSAGE_BYTES);
        registration.addDecoratorFactory(sessionRegistry::decorate);
    }
}
