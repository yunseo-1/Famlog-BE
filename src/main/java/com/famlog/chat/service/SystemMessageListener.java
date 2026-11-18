package com.famlog.chat.service;

import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.famlog.chat.domain.ChatRoom;
import com.famlog.chat.domain.Message;
import com.famlog.chat.dto.ChatDtos.ChatEvent;
import com.famlog.chat.event.MessagePosted;
import com.famlog.chat.relay.ChatRelay;
import com.famlog.chat.repository.ChatRoomRepository;
import com.famlog.chat.repository.MessageRepository;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.event.GroupMemberEvents.MemberJoined;
import com.famlog.group.event.GroupMemberEvents.MemberLeft;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.todo.event.TodoCompleted;
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
 * <li>할 일 완료(알리기로 한 경우, 사양서 4.3 TODO-01): "엄마님이 '우유 사 오기' 할 일을 완료했어요."</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class SystemMessageListener {

    static final String MEMBER_JOINED = "MEMBER_JOINED";
    static final String MEMBER_LEFT = "MEMBER_LEFT";
    static final String MEMBER_KICKED = "MEMBER_KICKED";
    static final String TODO_DONE = "TODO_DONE";

    private final ChatRoomRepository roomRepository;
    private final MessageRepository messageRepository;
    private final GroupMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final MessageAssembler assembler;
    private final ChatRelay relay;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher events;

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

    @EventListener
    void onTodoCompleted(TodoCompleted event) {
        ChatRoom room = lockRoom(event.groupId());
        Message message = post(room, event.userId(), "님이 '" + event.title() + "' 할 일을 완료했어요.",
                Map.of("kind", TODO_DONE, "userId", event.userId(), "todoId", event.todoId()));
        broadcast(event.groupId(), message);
    }

    private ChatRoom lockRoom(long groupId) {
        return roomRepository.findByGroupIdForUpdate(groupId).orElseThrow();
    }

    private Message post(ChatRoom room, long groupId, long userId, String kind, String text) {
        return post(room, userId, text, Map.of("kind", kind, "userId", userId));
    }

    /** payload 의 userId 는 이 일의 당사자 (푸시에서 뺀다) */
    private Message post(ChatRoom room, long userId, String text, Map<String, Object> payload) {
        String nickname = userRepository.findById(userId).map(User::getNickname).orElse("가족");
        return messageRepository.save(Message.system(room.getId(), room.allocateSeq(), nickname + text,
                jsonMapper.writeValueAsString(payload)));
    }

    private void broadcast(long groupId, Message message) {
        messageRepository.flush();
        relay.toGroup(groupId, ChatEvent.MESSAGE_CREATED, assembler.forGroup(message, groupId));
        events.publishEvent(new MessagePosted(groupId, message.getId()));
    }
}
