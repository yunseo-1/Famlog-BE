package com.famlog.chat.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.famlog.chat.domain.Message;
import com.famlog.chat.domain.MessageReaction;
import com.famlog.chat.domain.MessageType;
import com.famlog.chat.domain.ReactionEmoji;
import com.famlog.chat.dto.ChatDtos.MessageResponse;
import com.famlog.chat.dto.ChatDtos.ReactionResponse;
import com.famlog.chat.dto.ChatDtos.ReplyPreview;
import com.famlog.chat.repository.MessageReactionRepository;
import com.famlog.chat.repository.MessageRepository;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.media.domain.MediaAsset;
import com.famlog.media.dto.MediaDtos.MediaResponse;
import com.famlog.media.repository.MediaAssetRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 메시지 응답 조립: 답장 원문 미리보기, 공감, 안 읽은 수 (CHAT-04 카카오톡 방식).
 * 안 읽은 수 = 지금 그룹에 있는 구성원 중 보낸 사람이 아니고, 그 메시지 뒤에 들어오지 않았고, 아직 그 seq까지 읽지 않은 사람 수.
 * 답장 원문이 보는 사람의 입장 전 메시지면 원문 본문을 비운다 (입장 이후 대화만 공개).
 */
@Component
@RequiredArgsConstructor
class MessageAssembler {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final MessageRepository messageRepository;
    private final MessageReactionRepository reactionRepository;
    private final GroupMemberRepository memberRepository;
    private final MediaAssetRepository mediaRepository;
    private final JsonMapper jsonMapper;

    /** 그룹 전체에 보내는 응답. 답장 원문은 지금 구성원 모두가 볼 수 있을 때만 본문을 넣는다 */
    MessageResponse forGroup(Message message, long groupId) {
        List<GroupMember> members = memberRepository.findByGroupIdAndLeftAtIsNullOrderByJoinedAtAsc(groupId);
        long everyoneVisibleAfter = members.stream().mapToLong(GroupMember::getJoinSeq).max().orElse(0);
        return toResponses(List.of(message), groupId, everyoneVisibleAfter, members).get(0);
    }

    /** 한 사람에게 보내는 응답 (viewerJoinSeq: 그 사람이 들어온 시점) */
    List<MessageResponse> forViewer(List<Message> messages, long groupId, long viewerJoinSeq) {
        if (messages.isEmpty()) {
            return List.of();
        }
        return toResponses(messages, groupId, viewerJoinSeq,
                memberRepository.findByGroupIdAndLeftAtIsNullOrderByJoinedAtAsc(groupId));
    }

    private List<MessageResponse> toResponses(List<Message> messages, long groupId, long visibleAfterSeq,
            List<GroupMember> members) {
        List<Long> replyIds = messages.stream().map(Message::getReplyToId).filter(Objects::nonNull).distinct()
                .toList();
        Map<Long, Message> replies = replyIds.isEmpty() ? Map.of()
                : messageRepository.findByIdIn(replyIds).stream()
                        .collect(Collectors.toMap(Message::getId, Function.identity()));
        Map<Long, List<ReactionResponse>> reactions = reactionsOf(messages.stream().map(Message::getId).toList());
        Map<Long, List<MediaResponse>> media = mediaOf(messages);

        return messages.stream().map(message -> new MessageResponse(
                message.getId(), groupId, message.getSeq(), message.getSenderId(),
                message.getClientMessageId() == null ? null : message.getClientMessageId().toString(),
                message.getType(), message.getBody(), payloadOf(message),
                replyPreview(message.getReplyToId() == null ? null : replies.get(message.getReplyToId()),
                        visibleAfterSeq),
                reactions.getOrDefault(message.getId(), List.of()),
                media.getOrDefault(message.getId(), List.of()),
                unreadCount(message, members), message.isDeleted(), message.getCreatedAt()))
                .toList();
    }

    /** 메시지별 공감: 종류별로 묶고, 종류 순서는 처음 달린 순 */
    Map<Long, List<ReactionResponse>> reactionsOf(Collection<Long> messageIds) {
        Map<Long, Map<ReactionEmoji, List<Long>>> grouped = reactionRepository
                .findByMessageIdInOrderByCreatedAtAsc(messageIds).stream()
                .collect(Collectors.groupingBy(MessageReaction::getMessageId,
                        Collectors.groupingBy(MessageReaction::getEmoji, LinkedHashMap::new,
                                Collectors.mapping(MessageReaction::getUserId, Collectors.toList()))));
        return grouped.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> entry.getValue().entrySet().stream()
                        .map(byEmoji -> new ReactionResponse(byEmoji.getKey(), byEmoji.getValue()))
                        .toList()));
    }

    /** 사진·동영상 메시지의 미디어를 보낸 순서(payload.mediaIds)대로. 삭제된 메시지는 넣지 않는다 */
    private Map<Long, List<MediaResponse>> mediaOf(List<Message> messages) {
        Map<Long, Message> withMedia = messages.stream()
                .filter(message -> !message.isDeleted()
                        && (message.getType() == MessageType.IMAGE || message.getType() == MessageType.VIDEO))
                .collect(Collectors.toMap(Message::getId, Function.identity()));
        if (withMedia.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<MediaAsset>> byMessage = mediaRepository.findByMessageIdIn(withMedia.keySet()).stream()
                .collect(Collectors.groupingBy(MediaAsset::getMessageId));
        return byMessage.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> {
            List<Long> order = mediaOrder(withMedia.get(entry.getKey()));
            return entry.getValue().stream()
                    .sorted(Comparator.comparingInt(asset -> {
                        int index = order.indexOf(asset.getId());
                        return index < 0 ? Integer.MAX_VALUE : index;
                    }))
                    .map(MediaResponse::of)
                    .toList();
        }));
    }

    private List<Long> mediaOrder(Message message) {
        Map<String, Object> payload = payloadOf(message);
        if (payload == null || !(payload.get("mediaIds") instanceof List<?> ids)) {
            return List.of();
        }
        return ids.stream().map(id -> ((Number) id).longValue()).toList();
    }

    private static int unreadCount(Message message, List<GroupMember> members) {
        if (message.getType() == MessageType.SYSTEM) {
            return 0;
        }
        return (int) members.stream()
                .filter(member -> !member.getUserId().equals(message.getSenderId()))
                .filter(member -> member.getJoinSeq() < message.getSeq())
                .filter(member -> member.getLastReadSeq() < message.getSeq())
                .count();
    }

    private static ReplyPreview replyPreview(Message reply, long visibleAfterSeq) {
        if (reply == null) {
            return null;
        }
        String body = reply.getSeq() > visibleAfterSeq ? reply.getBody() : null;
        return new ReplyPreview(reply.getId(), reply.getSeq(), reply.getSenderId(), reply.getType(), body,
                reply.isDeleted());
    }

    private Map<String, Object> payloadOf(Message message) {
        return message.getPayload() == null ? null : jsonMapper.readValue(message.getPayload(), PAYLOAD_TYPE);
    }
}
