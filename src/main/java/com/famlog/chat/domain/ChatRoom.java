package com.famlog.chat.domain;

import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 가족 그룹의 단체 채팅방 (그룹당 1개, 1:1 채팅 없음). 그룹 생성 때 함께 만든다.
 * lastSeq 는 방별 메시지 순번 발급기다. 이 행을 잠근 채로 {@link #allocateSeq()} 를 불러야 한다.
 */
@Getter
@Entity
@Table(name = "chat_rooms")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    public ChatRoom(Long groupId) {
        this.groupId = groupId;
    }

    /** 다음 메시지 순번. 방 행을 잠근 트랜잭션 안에서만 부른다 (ChatRoomRepository.findByGroupIdForUpdate) */
    public long allocateSeq() {
        return ++lastSeq;
    }
}
