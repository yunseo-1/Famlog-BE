package com.famlog.chat.relay;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.famlog.chat.dto.ChatDtos.ChatEvent;
import com.famlog.chat.relay.RelayEnvelope.Kind;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실시간 이벤트를 모든 서버로 보낸다 (사양서 11.4: 서버가 여러 대여도 같은 구조).
 * 트랜잭션 안에서 부르면 커밋된 뒤에 보낸다 (롤백된 메시지가 나가지 않게).
 * Redis에 보내지 못하면 이 서버에 붙은 연결에만 전달한다. 놓친 메시지는 앱이 재접속 때 afterSeq로 채운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRelay {

    public static final String CHANNEL = "famlog:ws:relay";

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final LocalDelivery localDelivery;

    public void toGroup(long groupId, String type, Object data) {
        publish(new RelayEnvelope(Kind.TOPIC, groupTopic(groupId), null, null,
                jsonMapper.valueToTree(new ChatEvent(type, data))));
    }

    public void toUser(long userId, String type, Object data) {
        publish(new RelayEnvelope(Kind.USER, LocalDelivery.USER_EVENTS, userId, null,
                jsonMapper.valueToTree(new ChatEvent(type, data))));
    }

    public void disconnectUser(long userId) {
        publish(new RelayEnvelope(Kind.DISCONNECT_USER, null, userId, null, null));
    }

    public void disconnectSessions(long userId, List<Long> sessionIds) {
        publish(new RelayEnvelope(Kind.DISCONNECT_SESSIONS, null, userId, List.copyOf(sessionIds), null));
    }

    public static String groupTopic(long groupId) {
        return "/sub/groups/" + groupId;
    }

    private void publish(RelayEnvelope envelope) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(envelope);
                }
            });
        } else {
            send(envelope);
        }
    }

    private void send(RelayEnvelope envelope) {
        try {
            redis.convertAndSend(CHANNEL, jsonMapper.writeValueAsString(envelope));
        } catch (RuntimeException e) {
            log.warn("Redis 중계 실패, 이 서버의 연결에만 전달합니다: kind={}, error={}", envelope.kind(),
                    e.getClass().getSimpleName());
            localDelivery.deliver(envelope);
        }
    }
}
