package com.famlog.chat.relay;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.famlog.chat.ws.WsSessionRegistry;

import lombok.RequiredArgsConstructor;

/** Redis로 받은 전달 지시를 이 서버에 붙은 WebSocket 연결에 실행한다. */
@Component
@RequiredArgsConstructor
public class LocalDelivery {

    public static final String USER_EVENTS = "/queue/events";

    private final SimpMessagingTemplate messagingTemplate;
    private final WsSessionRegistry sessionRegistry;

    public void deliver(RelayEnvelope envelope) {
        switch (envelope.kind()) {
            case TOPIC -> messagingTemplate.convertAndSend(envelope.destination(), envelope.payload());
            case USER -> messagingTemplate.convertAndSendToUser(Long.toString(envelope.userId()),
                    envelope.destination(), envelope.payload());
            case DISCONNECT_USER -> sessionRegistry.closeUser(envelope.userId());
            case DISCONNECT_SESSIONS -> sessionRegistry.closeSessions(envelope.userId(), envelope.sessionIds());
        }
    }
}
