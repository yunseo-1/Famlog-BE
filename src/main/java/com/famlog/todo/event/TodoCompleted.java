package com.famlog.todo.event;

/**
 * 할 일을 완료하면서 채팅방에 알리기로 했다 (사양서 4.3 TODO-01 "완료 시 채팅방에 한 줄 알림(선택)").
 * CHAT 이 같은 트랜잭션에서 시스템 메시지를 남긴다.
 */
public record TodoCompleted(long groupId, long todoId, long userId, String title) {

    @Override
    public String toString() {
        // 제목이 로그에 찍히지 않게 (사양서 9.8)
        return "TodoCompleted[groupId=" + groupId + ", todoId=" + todoId + "]";
    }
}
