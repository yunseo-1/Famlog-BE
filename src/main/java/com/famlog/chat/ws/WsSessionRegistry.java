package com.famlog.chat.ws;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import com.famlog.chat.dto.ChatDtos.ChatEvent;
import com.famlog.chat.dto.ChatDtos.SessionRevoked;
import com.famlog.chat.relay.LocalDelivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이 서버에 붙은 WebSocket 연결 목록. 그룹에서 나가거나 세션이 폐기된 사용자의 연결을 서버가 끊는다
 * (이미 구독한 그룹 토픽을 계속 받지 못하게). 앱은 다시 연결하면서 검사를 다시 받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsSessionRegistry {

    /** 정책 위반으로 끊음 (앱: 재연결, 그룹 목록 새로고침) */
    static final CloseStatus ACCESS_CHANGED = CloseStatus.POLICY_VIOLATION.withReason("ACCESS_CHANGED");

    private final ObjectProvider<SimpMessagingTemplate> messagingTemplate;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                connections.put(session.getId(), new Connection(session, null));
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                connections.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /** STOMP CONNECT가 인증되면 연결에 사용자를 붙인다 (WebSocket 세션 id = STOMP simpSessionId) */
    void bind(String wsSessionId, StompPrincipal principal) {
        connections.computeIfPresent(wsSessionId, (id, connection) -> new Connection(connection.session, principal));
    }

    public void closeUser(long userId) {
        close(principal -> principal.userId() == userId, false);
    }

    public void closeSessions(long userId, Collection<Long> sessionIds) {
        close(principal -> principal.userId() == userId && sessionIds.contains(principal.sessionId()), true);
    }

    public int countOf(long userId) {
        return (int) connections.values().stream()
                .filter(connection -> connection.principal != null && connection.principal.userId() == userId)
                .count();
    }

    private void close(Predicate<StompPrincipal> target, boolean revoked) {
        List<Connection> matched = connections.values().stream()
                .filter(connection -> connection.principal != null && target.test(connection.principal))
                .toList();
        for (Connection connection : matched) {
            if (revoked) {
                notifyRevoked(connection);
            }
            try {
                connection.session.close(ACCESS_CHANGED);
            } catch (IOException | RuntimeException e) {
                log.debug("WebSocket 연결을 닫지 못했습니다: error={}", e.getClass().getSimpleName());
            }
        }
    }

    /** 끊기 전에 그 연결에만 session.revoked 를 보낸다 (앱: 로그인 화면). 전달은 보장하지 않는다 */
    private void notifyRevoked(Connection connection) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(connection.session.getId());
        headers.setLeaveMutable(true);
        try {
            messagingTemplate.getObject().convertAndSendToUser(connection.principal.getName(),
                    LocalDelivery.USER_EVENTS,
                    new ChatEvent(ChatEvent.SESSION_REVOKED, new SessionRevoked(connection.principal.sessionId())),
                    headers.getMessageHeaders());
        } catch (RuntimeException e) {
            log.debug("session.revoked 를 보내지 못했습니다: error={}", e.getClass().getSimpleName());
        }
    }

    private record Connection(WebSocketSession session, StompPrincipal principal) {
    }
}
