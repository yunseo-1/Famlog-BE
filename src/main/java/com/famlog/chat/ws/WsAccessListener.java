package com.famlog.chat.ws;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.famlog.auth.service.SessionsRevoked;
import com.famlog.chat.relay.ChatRelay;

import lombok.RequiredArgsConstructor;

/** 로그아웃·원격 로그아웃된 세션의 WebSocket 연결을 끊는다 (사양서 18.1 AUTH-03). 커밋 뒤에 모든 서버에 알린다. */
@Component
@RequiredArgsConstructor
class WsAccessListener {

    private final ChatRelay relay;

    @EventListener
    void onSessionsRevoked(SessionsRevoked event) {
        relay.disconnectSessions(event.userId(), event.sessionIds());
    }
}
