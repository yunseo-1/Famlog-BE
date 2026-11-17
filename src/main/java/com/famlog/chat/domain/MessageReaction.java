package com.famlog.chat.domain;

import java.io.Serializable;

import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 메시지 공감 (CHAT-06). 한 사람이 메시지 하나에 하나만 단다. */
@Getter
@Entity
@Table(name = "message_reactions")
@IdClass(MessageReaction.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageReaction extends BaseTimeEntity {

    @Id
    @Column(name = "message_id")
    private Long messageId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReactionEmoji emoji;

    public MessageReaction(long messageId, long userId, ReactionEmoji emoji) {
        this.messageId = messageId;
        this.userId = userId;
        this.emoji = emoji;
    }

    public void change(ReactionEmoji emoji) {
        this.emoji = emoji;
    }

    public record Key(Long messageId, Long userId) implements Serializable {
    }
}
