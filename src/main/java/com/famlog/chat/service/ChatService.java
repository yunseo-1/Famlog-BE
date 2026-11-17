package com.famlog.chat.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.chat.domain.ChatRoom;
import com.famlog.chat.domain.Message;
import com.famlog.chat.domain.MessageReaction;
import com.famlog.chat.domain.MessageType;
import com.famlog.chat.domain.ReactionEmoji;
import com.famlog.chat.dto.ChatDtos.ChatEvent;
import com.famlog.chat.dto.ChatDtos.MessageDeleted;
import com.famlog.chat.dto.ChatDtos.MessageResponse;
import com.famlog.chat.dto.ChatDtos.ReactionsResponse;
import com.famlog.chat.dto.ChatDtos.ReadResponse;
import com.famlog.chat.dto.ChatDtos.ReadUpdated;
import com.famlog.chat.dto.ChatDtos.SendMessageRequest;
import com.famlog.chat.relay.ChatRelay;
import com.famlog.chat.repository.ChatRoomRepository;
import com.famlog.chat.repository.MessageReactionRepository;
import com.famlog.chat.repository.MessageRepository;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.error.RateLimitedException;
import com.famlog.common.ratelimit.RateLimiter;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.group.service.GroupAccessChecker;
import com.famlog.media.domain.MediaAsset;
import com.famlog.media.domain.MediaType;
import com.famlog.media.repository.MediaAssetRepository;
import com.famlog.media.service.MediaProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 채팅 쓰기 (사양서 4.2 CHAT-02·04·06).
 * <ul>
 * <li>전송: TEXT·IMAGE(사진 묶음)·VIDEO. 방 행을 잠그고 seq를 매긴다 → 같은 방 안에서 겹침·빠짐 없이 순서가 정해진다.
 * 같은 사람이 같은 clientMessageId로 다시 보내면 저장된 메시지를 돌려준다 (중복 저장 없음)</li>
 * <li>실시간 이벤트는 커밋 뒤 ChatRelay로 모든 서버에 보낸다</li>
 * <li>메시지 id만 있는 API(/messages/{id})는 다른 그룹이거나 입장 전 메시지면 404 (존재 여부 숨김, 사양서 9.3)</li>
 * </ul>
 * 메시지 본문은 로그에 남기지 않는다 (사양서 9.8).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRoomRepository roomRepository;
    private final MessageRepository messageRepository;
    private final MessageReactionRepository reactionRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupAccessChecker accessChecker;
    private final MessageAssembler assembler;
    private final ChatRelay relay;
    private final RateLimiter rateLimiter;
    private final ChatProperties properties;
    private final MediaAssetRepository mediaRepository;
    private final MediaProperties mediaProperties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /** @param duplicate 이미 저장된 메시지를 다시 보낸 경우 (그룹에 다시 알리지 않는다) */
    public record SendResult(MessageResponse message, boolean duplicate) {
    }

    // --- CHAT-02 전송 ---

    @Transactional
    public SendResult send(long groupId, long userId, SendMessageRequest request) {
        accessChecker.requireMember(groupId, userId);
        UUID clientId = parseClientMessageId(request.clientMessageId());
        MessageType type = request.typeOrText();
        List<Long> mediaIds = request.mediaIds() == null ? List.of() : request.mediaIds();
        validateShape(type, request.body(), mediaIds);
        Optional<SendResult> resent = findResent(groupId, userId, clientId);
        if (resent.isPresent()) {
            return resent.get();
        }
        checkSendRate(userId);

        ChatRoom room = roomRepository.findByGroupIdForUpdate(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // 잠금을 기다리는 동안 같은 메시지가 먼저 저장됐을 수 있다
        resent = findResent(groupId, userId, clientId);
        if (resent.isPresent()) {
            return resent.get();
        }
        GroupMember me = memberRepository.findActiveForUpdate(groupId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_MEMBER));
        requireReplyTarget(room, me, request.replyToId());
        List<MediaAsset> media = type == MessageType.TEXT ? List.of() : attachableMedia(groupId, userId, type, mediaIds);

        long seq = room.allocateSeq();
        Message message = messageRepository.save(type == MessageType.TEXT
                ? Message.text(room.getId(), seq, userId, clientId, request.body(), request.replyToId())
                : Message.media(room.getId(), seq, userId, clientId, type,
                        jsonMapper.writeValueAsString(Map.of("mediaIds", mediaIds)), request.replyToId()));
        media.forEach(asset -> asset.attachTo(message.getId()));
        long readBefore = me.markReadUpTo(seq);
        messageRepository.flush();

        MessageResponse response = assembler.forGroup(message, groupId);
        relay.toGroup(groupId, ChatEvent.MESSAGE_CREATED, response);
        if (readBefore >= 0) {
            // 보낸 사람은 그때까지의 메시지를 읽은 것이다
            relay.toGroup(groupId, ChatEvent.READ_UPDATED, new ReadUpdated(groupId, userId, readBefore, seq));
        }
        return new SendResult(response, false);
    }

    /** TEXT는 본문, IMAGE는 사진 1~30개, VIDEO는 동영상 1개 */
    private void validateShape(MessageType type, String body, List<Long> mediaIds) {
        switch (type) {
            case TEXT -> {
                if (body == null || body.isBlank()) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "메시지를 입력해 주세요.");
                }
                if (!mediaIds.isEmpty()) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "글 메시지에는 사진을 붙일 수 없어요.");
                }
            }
            case IMAGE, VIDEO -> {
                int max = type == MessageType.IMAGE ? mediaProperties.maxItems() : 1;
                if (mediaIds.isEmpty() || mediaIds.size() > max || mediaIds.stream().anyMatch(Objects::isNull)
                        || mediaIds.stream().distinct().count() != mediaIds.size()) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, type == MessageType.IMAGE
                            ? "사진은 한 번에 " + max + "장까지 보낼 수 있어요." : "동영상은 하나씩 보내 주세요.");
                }
                if (body != null && !body.isBlank()) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "사진·동영상에는 글을 함께 보낼 수 없어요.");
                }
            }
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "보낼 수 없는 메시지 종류예요.");
        }
    }

    /**
     * 메시지에 붙일 미디어를 잠근다: 내가 이 그룹에 올렸고, 업로드 확인이 끝났고(UPLOADED·READY), 아직 아무 메시지에도 안 붙은 것.
     * 워커 결과 반영도 같은 행을 잠그므로 둘 중 나중 쪽이 앞의 변경을 본다 (media.updated 누락 없음)
     */
    private List<MediaAsset> attachableMedia(long groupId, long userId, MessageType type, List<Long> mediaIds) {
        MediaType expected = type == MessageType.IMAGE ? MediaType.IMAGE : MediaType.VIDEO;
        List<MediaAsset> media = mediaRepository.findAllByIdForUpdate(mediaIds);
        boolean valid = media.size() == mediaIds.size() && media.stream().allMatch(asset ->
                asset.getGroupId() == groupId && asset.getUploaderId() == userId && asset.getMessageId() == null
                        && asset.getMediaType() == expected && asset.getStatus().isSendable());
        if (!valid) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "보낼 수 없는 사진·동영상이 있어요. 다시 올려 주세요.");
        }
        return media;
    }

    private Optional<SendResult> findResent(long groupId, long userId, UUID clientId) {
        return messageRepository.findBySenderIdAndClientMessageId(userId, clientId).map(existing -> {
            ChatRoom room = roomRepository.findById(existing.getRoomId()).orElseThrow();
            if (room.getGroupId() != groupId) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "다른 메시지에 이미 쓴 clientMessageId예요.");
            }
            long joinSeq = memberRepository.findByGroupIdAndUserId(groupId, userId).orElseThrow().getJoinSeq();
            return new SendResult(assembler.forViewer(List.of(existing), groupId, joinSeq).get(0), true);
        });
    }

    private void requireReplyTarget(ChatRoom room, GroupMember me, Long replyToId) {
        if (replyToId == null) {
            return;
        }
        boolean visible = messageRepository.findById(replyToId)
                .filter(target -> target.getRoomId().equals(room.getId()) && target.getSeq() > me.getJoinSeq())
                .isPresent();
        if (!visible) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "답장할 메시지를 찾을 수 없어요.");
        }
    }

    /** 사용자당 초당 5개 (사양서 9.6). Redis 장애 시에는 제한 없이 통과 (REST 요청 제한과 같은 정책) */
    private void checkSendRate(long userId) {
        RateLimiter.Result result;
        try {
            result = rateLimiter.tryConsume("rate-limit:chat-send:" + userId, properties.sendRateCapacity(),
                    properties.sendRatePeriod());
        } catch (RuntimeException e) {
            log.warn("메시지 전송 제한 확인 실패로 제한 없이 통과시킵니다: error={}", e.getClass().getSimpleName());
            return;
        }
        if (!result.allowed()) {
            throw new RateLimitedException("메시지를 너무 빨리 보내고 있어요. 잠시 후 다시 보내 주세요.",
                    result.retryAfterSeconds());
        }
    }

    private static UUID parseClientMessageId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "clientMessageId는 UUID여야 해요.");
        }
    }

    // --- CHAT-04 읽음 ---

    /** 읽음 위치는 앞으로만 움직이고, 방의 마지막 seq를 넘지 않는다. 바뀌었을 때만 그룹에 알린다 */
    @Transactional
    public ReadResponse markRead(long groupId, long userId, long lastReadSeq) {
        accessChecker.requireMember(groupId, userId);
        if (lastReadSeq < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "lastReadSeq는 0 이상이어야 해요.");
        }
        ChatRoom room = roomRepository.findByGroupId(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        GroupMember me = memberRepository.findActiveForUpdate(groupId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_MEMBER));
        long target = Math.min(lastReadSeq, room.getLastSeq());
        long before = me.markReadUpTo(target);
        if (before >= 0) {
            relay.toGroup(groupId, ChatEvent.READ_UPDATED, new ReadUpdated(groupId, userId, before, target));
        }
        return new ReadResponse(me.getLastReadSeq());
    }

    // --- CHAT-06 삭제·공감 ---

    /** 보낸 사람만, 보낸 지 5분 안에 모두에게서 지운다. 이미 지운 메시지는 그대로 성공 */
    @Transactional
    public void delete(long messageId, long userId) {
        VisibleMessage visible = visibleMessage(messageId, userId);
        Message message = visible.message();
        if (message.isDeleted()) {
            return;
        }
        Instant now = Instant.now(clock);
        if (!message.isDeletableBy(userId, now)) {
            throw new BusinessException(ErrorCode.MESSAGE_NOT_DELETABLE);
        }
        message.delete(now);
        reactionRepository.deleteByMessageId(message.getId());
        relay.toGroup(visible.groupId(), ChatEvent.MESSAGE_DELETED,
                new MessageDeleted(visible.groupId(), message.getId(), message.getSeq()));
    }

    /** 공감을 달거나 바꾼다 (한 사람당 하나) */
    @Transactional
    public ReactionsResponse react(long messageId, long userId, ReactionEmoji emoji) {
        VisibleMessage visible = visibleMessage(messageId, userId);
        Message message = visible.message();
        if (message.isDeleted() || message.getType() == MessageType.SYSTEM) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "이 메시지에는 공감할 수 없어요.");
        }
        reactionRepository.findById(new MessageReaction.Key(messageId, userId)).ifPresentOrElse(
                reaction -> reaction.change(emoji),
                () -> reactionRepository.save(new MessageReaction(messageId, userId, emoji)));
        return publishReactions(visible);
    }

    @Transactional
    public ReactionsResponse unreact(long messageId, long userId) {
        VisibleMessage visible = visibleMessage(messageId, userId);
        reactionRepository.findById(new MessageReaction.Key(messageId, userId)).ifPresent(reactionRepository::delete);
        return publishReactions(visible);
    }

    private ReactionsResponse publishReactions(VisibleMessage visible) {
        reactionRepository.flush();
        Message message = visible.message();
        ReactionsResponse response = new ReactionsResponse(visible.groupId(), message.getId(), message.getSeq(),
                assembler.reactionsOf(List.of(message.getId())).getOrDefault(message.getId(), List.of()));
        relay.toGroup(visible.groupId(), ChatEvent.REACTION_UPDATED, response);
        return response;
    }

    private record VisibleMessage(Message message, long groupId) {
    }

    /** 내 그룹의, 내가 들어온 뒤의 메시지. 아니면 404 */
    private VisibleMessage visibleMessage(long messageId, long userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        long groupId = roomRepository.findById(message.getRoomId()).orElseThrow().getGroupId();
        accessChecker.requireMemberOrNotFound(groupId, userId);
        long joinSeq = memberRepository.findByGroupIdAndUserId(groupId, userId).orElseThrow().getJoinSeq();
        if (message.getSeq() <= joinSeq) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return new VisibleMessage(message, groupId);
    }
}
