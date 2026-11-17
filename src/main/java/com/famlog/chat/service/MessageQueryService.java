package com.famlog.chat.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.chat.domain.ChatRoom;
import com.famlog.chat.domain.Message;
import com.famlog.chat.dto.ChatDtos.ChatSummary;
import com.famlog.chat.dto.ChatDtos.LastMessage;
import com.famlog.chat.dto.ChatDtos.MessagePage;
import com.famlog.chat.repository.ChatRoomRepository;
import com.famlog.chat.repository.MessageRepository;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.group.service.GroupAccessChecker;

import lombok.RequiredArgsConstructor;

/**
 * 채팅 읽기 (사양서 4.2 CHAT-03 내역, SCR-10 채팅 탭). 구성원은 들어온 뒤의 메시지(seq > joinSeq)만 본다.
 * <ul>
 * <li>?afterSeq=N: N 다음부터 오래된 순 (재접속 동기화. hasMore면 마지막 seq로 다시 요청)</li>
 * <li>?beforeSeq=N: N 이전의 최신 size개 (위로 스크롤). 둘 다 없으면 최신 size개</li>
 * </ul>
 * 응답 items는 언제나 seq 오름차순이다.
 */
@Service
@RequiredArgsConstructor
public class MessageQueryService {

    private final ChatRoomRepository roomRepository;
    private final MessageRepository messageRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupAccessChecker accessChecker;
    private final MessageAssembler assembler;
    private final ChatProperties properties;

    @Transactional(readOnly = true)
    public MessagePage page(long groupId, long userId, Long afterSeq, Long beforeSeq, Integer size) {
        accessChecker.requireMember(groupId, userId);
        if (afterSeq != null && beforeSeq != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "afterSeq와 beforeSeq는 함께 쓸 수 없어요.");
        }
        if ((afterSeq != null && afterSeq < 0) || (beforeSeq != null && beforeSeq < 1)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "seq 범위를 다시 확인해 주세요.");
        }
        int pageSize = size == null ? properties.defaultPageSize() : size;
        if (pageSize < 1 || pageSize > properties.maxPageSize()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "size는 1~" + properties.maxPageSize() + " 사이여야 해요.");
        }
        ChatRoom room = roomRepository.findByGroupId(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        long joinSeq = memberRepository.findByGroupIdAndUserId(groupId, userId).orElseThrow().getJoinSeq();
        Limit limit = Limit.of(pageSize + 1);

        List<Message> found;
        if (afterSeq != null) {
            found = messageRepository.findByRoomIdAndSeqGreaterThanOrderBySeqAsc(room.getId(),
                    Math.max(afterSeq, joinSeq), limit);
        } else {
            long before = beforeSeq == null ? Long.MAX_VALUE : beforeSeq;
            found = messageRepository.findByRoomIdAndSeqGreaterThanAndSeqLessThanOrderBySeqDesc(room.getId(),
                    joinSeq, before, limit);
        }
        boolean hasMore = found.size() > pageSize;
        List<Message> items = new ArrayList<>(hasMore ? found.subList(0, pageSize) : found);
        if (afterSeq == null) {
            Collections.reverse(items);
        }
        return new MessagePage(assembler.forViewer(items, groupId, joinSeq), hasMore);
    }

    /** 채팅 탭: 내 그룹별 마지막 메시지와 안 읽은 수 */
    @Transactional(readOnly = true)
    public Map<Long, ChatSummary> summaries(long userId, Collection<GroupMember> memberships) {
        Map<Long, GroupMember> byGroup = memberships.stream()
                .collect(Collectors.toMap(GroupMember::getGroupId, Function.identity()));
        return roomRepository.findByGroupIdIn(byGroup.keySet()).stream().collect(Collectors.toMap(
                ChatRoom::getGroupId,
                room -> summary(room, byGroup.get(room.getGroupId()), userId)));
    }

    private ChatSummary summary(ChatRoom room, GroupMember me, long userId) {
        LastMessage last = messageRepository.findFirstByRoomIdAndSeqGreaterThanOrderBySeqDesc(room.getId(),
                        me.getJoinSeq())
                .map(message -> new LastMessage(message.getSeq(), message.getSenderId(), message.getType(),
                        message.getBody(), message.isDeleted(), message.getCreatedAt()))
                .orElse(null);
        long unread = messageRepository.countUnread(room.getId(), Math.max(me.getLastReadSeq(), me.getJoinSeq()),
                userId);
        return new ChatSummary(last, unread);
    }
}
