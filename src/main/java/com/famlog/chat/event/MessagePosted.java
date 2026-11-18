package com.famlog.chat.event;

/**
 * 채팅방에 새 메시지가 저장됐다 (사람이 보낸 메시지·입장/퇴장 시스템 메시지). 같은 메시지를 다시 보낸 경우에는 발행하지 않는다.
 * NOTI(S11)가 커밋 뒤 이것으로 새 메시지 푸시를 보낸다.
 */
public record MessagePosted(long groupId, long messageId) {
}
