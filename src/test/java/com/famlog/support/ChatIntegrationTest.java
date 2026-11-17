package com.famlog.support;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.famlog.auth.jwt.AccessTokenIssuer;
import com.famlog.chat.domain.MessageType;
import com.famlog.chat.dto.ChatDtos.MessageResponse;
import com.famlog.chat.dto.ChatDtos.SendMessageRequest;
import com.famlog.chat.service.ChatService;

/** 채팅 테스트 베이스: 메시지 보내기, 실제 Access 토큰 발급 도우미. */
public abstract class ChatIntegrationTest extends IntegrationTest {

    @Autowired
    protected ChatService chatService;

    @Autowired
    protected AccessTokenIssuer accessTokenIssuer;

    @Autowired
    protected StringRedisTemplate redis;

    /** 메시지를 보낸다. 전송 제한(초당 5개)에 걸리지 않게 그 사용자의 버킷을 비운다 */
    protected MessageResponse send(long groupId, long userId, String body) {
        return send(groupId, userId, body, null);
    }

    protected MessageResponse send(long groupId, long userId, String body, Long replyToId) {
        resetSendRate(userId);
        return chatService.send(groupId, userId, text(body, replyToId)).message();
    }

    /** 사진 묶음·동영상 메시지 */
    protected MessageResponse sendMedia(long groupId, long userId, MessageType type, List<Long> mediaIds) {
        resetSendRate(userId);
        return chatService.send(groupId, userId,
                new SendMessageRequest(UUID.randomUUID().toString(), type, null, mediaIds, null)).message();
    }

    protected static SendMessageRequest text(String body, Long replyToId) {
        return new SendMessageRequest(UUID.randomUUID().toString(), null, body, null, replyToId);
    }

    protected void resetSendRate(long userId) {
        redis.delete("rate-limit:chat-send:" + userId);
    }

    /** 실제 서명된 Access 토큰 (세션 id는 무작위) */
    protected String token(long userId) {
        return accessTokenIssuer.issue(userId, randomId()).value();
    }

    protected long roomLastSeq(long groupId) {
        return jdbcTemplate.queryForObject("SELECT last_seq FROM chat_rooms WHERE group_id = ?", Long.class, groupId);
    }
}
