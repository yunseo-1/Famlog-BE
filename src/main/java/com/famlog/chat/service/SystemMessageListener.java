package com.famlog.chat.service;

import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.famlog.chat.domain.ChatRoom;
import com.famlog.chat.domain.Message;
import com.famlog.chat.dto.ChatDtos.ChatEvent;
import com.famlog.chat.relay.ChatRelay;
import com.famlog.chat.repository.ChatRoomRepository;
import com.famlog.chat.repository.MessageRepository;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.event.GroupMemberEvents.MemberJoined;
import com.famlog.group.event.GroupMemberEvents.MemberLeft;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.user.domain.User;
import com.famlog.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 구성원 변화를 채팅방 시스템 메시지로 남긴다 (사양서 18.1 GRP-02 "채팅방에 입장 메시지가 보인다").
 * 그룹 가입·나가기 트랜잭션 안에서 함께 실행된다 (메시지만 빠지거나 남지 않게).
 * <ul>
 * <li>입장: 들어온 사람은 입장 메시지부터 본다 (joinSeq = 입장 직전 seq, 입장 메시지까지 읽음)</li>
 * <li>나감·내보내짐: 그 사람의 실시간 연결을 끊는다 (이미 구독한 그룹 토픽을 더 받지 못하게)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class SystemMessageListener {

    static final String MEMBER_JOINED = "MEMBER_JOINED";
    static final String MEMBER_LEFT = "MEMBER_LEFT";
    static final String MEMBER_KICKED = "MEMBER_KICKED";

    private final ChatRoomRepository roomRepository;
    private final MessageRepository messageRepository;
    private final GroupMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final MessageAssembler assembler;
    private final ChatRelay relay;
    private final JsonMapper jsonMapper;

    @EventListener
    void onJoined(MemberJoined event) {
        ChatRoom room = lockRoom(event.groupId());
        long joinSeq = room.getLastSeq();
        Message message = post(room, event.groupId(), event.userId(), MEMBER_JOINED, "님이 들어왔어요.");
        GroupMember member = memberRepository.findByGroupIdAndUserId(event.groupId(), event.userId()).orElseThrow();
        member.startChatFrom(joinSeq, message.getSeq());
        broadcast(event.groupId(), message);
    }

    @EventListener
    void onLeft(MemberLeft event) {
        ChatRoom room = lockRoom(event.groupId());
        Message message = post(room, event.groupId(), event.userId(), event.kicked() ? MEMBER_KICKED : MEMBER_LEFT,
                "님이 나갔어요.");
        broadcast(event.groupId(), message);
        relay.disconnectUser(event.userId());
    }

    private ChatRoom lockRoom(long groupId) {
        return roomRepository.findByGroupIdForUpdate(groupId).orElseThrow();
    }

    private Message post(ChatRoom room, long groupId, long userId, String kind, String text) {
        String nickname = userRepository.findById(userId).map(User::getNickname).orElse("가족");
        String payload = jsonMapper.writeValueAsString(Map.of("kind", kind, "userId", userId));
        return messageRepository.save(Message.system(room.getId(), room.allocateSeq(), nickname + text, payload));
    }

    private void broadcast(long groupId, Message message) {
        messageRepository.flush();
        relay.toGroup(groupId, ChatEvent.MESSAGE_CREATED, assembler.forGroup(message, groupId));
    }
}
