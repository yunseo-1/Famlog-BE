package com.famlog.chat.relay;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * 서버 사이에 Redis Pub/Sub으로 주고받는 전달 지시. 받은 서버는 자기에게 붙은 WebSocket 연결에만 전달한다.
 *
 * @param destination TOPIC: /sub/groups/{groupId}, USER: /queue/events
 * @param userId      USER·DISCONNECT_*: 대상 사용자
 * @param sessionIds  DISCONNECT_SESSIONS: 끊을 로그인 세션(user_sessions.id)
 * @param payload     이미 JSON으로 바꾼 이벤트 (시각은 +09:00)
 */
public record RelayEnvelope(Kind kind, String destination, Long userId, List<Long> sessionIds, JsonNode payload) {

    public enum Kind {
        /** 그룹 구독자 모두에게 */
        TOPIC,
        /** 한 사용자의 모든 연결에 */
        USER,
        /** 한 사용자의 연결을 모두 끊는다 (그룹에서 나감·내보내짐) */
        DISCONNECT_USER,
        /** 폐기된 세션의 연결을 끊는다 */
        DISCONNECT_SESSIONS
    }
}
