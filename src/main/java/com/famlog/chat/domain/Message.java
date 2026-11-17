package com.famlog.chat.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채팅 메시지 (사양서 10.2 messages). seq 는 방 안에서 서버가 매기는 순번이고, 앱은 seq 순서로 보여준다.
 * 같은 사람이 같은 clientMessageId로 다시 보내면 새로 저장하지 않는다 (CHAT-02 멱등).
 * 본문은 로그에 남기지 않는다 (사양서 9.8).
 */
@Getter
@Entity
@Table(name = "messages")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message extends BaseTimeEntity {

    /** 보낸 사람이 모두에게서 지울 수 있는 기간 (CHAT-06) */
    public static final Duration DELETE_WINDOW = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false, updatable = false)
    private Long roomId;

    @Column(nullable = false, updatable = false)
    private long seq;

    /** null = 시스템 메시지 */
    @Column(name = "sender_id", updatable = false)
    private Long senderId;

    @Column(name = "client_message_id", updatable = false)
    private UUID clientMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MessageType type;

    private String body;

    /** 종류별 추가 정보 JSON (시스템: {"kind":"MEMBER_JOINED","userId":1}, 사진·동영상: {"mediaIds":[1,2]}) */
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(name = "reply_to_id", updatable = false)
    private Long replyToId;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public static Message text(long roomId, long seq, long senderId, UUID clientMessageId, String body,
            Long replyToId) {
        Message message = new Message();
        message.roomId = roomId;
        message.seq = seq;
        message.senderId = senderId;
        message.clientMessageId = clientMessageId;
        message.type = MessageType.TEXT;
        message.body = body;
        message.replyToId = replyToId;
        return message;
    }

    /** 사진 묶음·동영상. payload = {"mediaIds": [...]} (보낸 순서) */
    public static Message media(long roomId, long seq, long senderId, UUID clientMessageId, MessageType type,
            String payload, Long replyToId) {
        Message message = new Message();
        message.roomId = roomId;
        message.seq = seq;
        message.senderId = senderId;
        message.clientMessageId = clientMessageId;
        message.type = type;
        message.payload = payload;
        message.replyToId = replyToId;
        return message;
    }

    public static Message system(long roomId, long seq, String body, String payload) {
        Message message = new Message();
        message.roomId = roomId;
        message.seq = seq;
        message.type = MessageType.SYSTEM;
        message.body = body;
        message.payload = payload;
        return message;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** 보낸 사람 본인이고 보낸 지 5분이 안 됐으면 지울 수 있다. 시스템 메시지는 지울 수 없다 */
    public boolean isDeletableBy(long userId, Instant now) {
        return senderId != null && senderId == userId && now.isBefore(getCreatedAt().plus(DELETE_WINDOW));
    }

    /** 모두에게서 삭제. 행은 남기고(순서·답장 유지) 내용을 지운다 */
    public void delete(Instant now) {
        if (deletedAt == null) {
            deletedAt = now;
            body = null;
            payload = null;
        }
    }
}
