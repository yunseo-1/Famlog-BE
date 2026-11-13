package com.famlog.group.event;

/**
 * 구성원 변화 이벤트. CHAT(S8)이 이것으로 채팅방 시스템 메시지("엄마가 들어왔어요")를,
 * NOTI(S11)가 푸시를 만든다. 지금은 발행만 한다 (사양서 18.1 GRP-02 입장 메시지는 S8에서 완성).
 */
public final class GroupMemberEvents {

    private GroupMemberEvents() {
    }

    /** @param rejoined 나갔다가 다시 들어온 경우 */
    public record MemberJoined(long groupId, long userId, boolean rejoined) {
    }

    /** @param kicked 관리자가 내보낸 경우 (false면 스스로 나감) */
    public record MemberLeft(long groupId, long userId, boolean kicked) {
    }
}
