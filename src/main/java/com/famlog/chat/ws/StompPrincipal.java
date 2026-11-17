package com.famlog.chat.ws;

import java.security.Principal;

/**
 * STOMP 연결의 사용자. CONNECT 때 Access 토큰에서 만든다. 이름(userId)으로 /user/queue/events 가 전달된다.
 *
 * @param sessionId user_sessions.id (JWT sid). 세션이 폐기되면 이 연결을 끊는다
 */
public record StompPrincipal(long userId, long sessionId) implements Principal {

    @Override
    public String getName() {
        return Long.toString(userId);
    }
}
