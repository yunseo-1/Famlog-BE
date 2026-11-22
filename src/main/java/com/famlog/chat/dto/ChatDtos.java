package com.famlog.chat.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.famlog.chat.domain.MessageType;
import com.famlog.chat.domain.ReactionEmoji;
import com.famlog.media.dto.MediaDtos.MediaResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 채팅 요청·응답·실시간 이벤트 (사양서 11.3 CHAT-03·04·06, 11.4).
 * 실시간 이벤트는 모두 {@link ChatEvent} {type, data} 로 감싸서 보낸다.
 */
public final class ChatDtos {

    public static final int MAX_BODY_LENGTH = 2000;

    private ChatDtos() {
    }

    // --- 요청 ---

    /**
     * STOMP SEND /pub/groups/{groupId}/messages.
     * <ul>
     * <li>TEXT(기본): body 필수</li>
     * <li>IMAGE: mediaIds 사진 1~30개 (한 묶음), body 없음</li>
     * <li>VIDEO: mediaIds 동영상 1개, body 없음</li>
     * </ul>
     * 미디어는 내가 이 그룹에 올리고 complete 까지 끝낸 것, 아직 다른 메시지에 붙지 않은 것이어야 한다.
     */
    public record SendMessageRequest(
            @Schema(description = "앱이 만든 UUID. 재전송해도 같은 값을 보내면 한 번만 저장된다")
            @NotBlank String clientMessageId,
            @Schema(description = "TEXT(기본)·IMAGE·VIDEO") MessageType type,
            @Size(max = MAX_BODY_LENGTH) String body,
            @Schema(description = "IMAGE·VIDEO: 보낼 미디어 id (보이는 순서대로)") List<Long> mediaIds,
            @Schema(description = "답장할 메시지 id") Long replyToId) {

        public MessageType typeOrText() {
            return type == null ? MessageType.TEXT : type;
        }
    }

    /** PUT /groups/{groupId}/read, STOMP SEND /pub/groups/{groupId}/read */
    public record ReadRequest(
            @Schema(description = "여기까지 읽음. 앞으로만 움직이고, 방의 마지막 seq를 넘으면 마지막 seq로 맞춘다")
            @NotNull @PositiveOrZero Long lastReadSeq) {
    }

    public record ReactionRequest(@NotNull ReactionEmoji emoji) {
    }

    // --- 응답 ---

    /**
     * @param senderId    null 이면 시스템 메시지 (입장·퇴장)
     * @param body        삭제된 메시지는 null
     * @param payload     종류별 추가 정보. 시스템 메시지: {kind: MEMBER_JOINED|MEMBER_LEFT|MEMBER_KICKED, userId}
     * @param unreadCount 아직 안 읽은 구성원 수 (보낸 사람 제외, 시스템 메시지는 0)
     * @param media       IMAGE·VIDEO 메시지의 사진·동영상 (보낸 순서). 처리 중이면 status=UPLOADED,
     *                    끝나면 그룹 이벤트 media.updated 가 온다. 삭제된 메시지는 빈 목록
     */
    public record MessageResponse(long id, long groupId, long seq, Long senderId, String clientMessageId,
            MessageType type, String body, Map<String, Object> payload, ReplyPreview replyTo,
            List<ReactionResponse> reactions, List<MediaResponse> media, int unreadCount, boolean deleted,
            Instant sentAt) {
    }

    public record ReplyPreview(long id, long seq, Long senderId, MessageType type, String body, boolean deleted) {
    }

    public record ReactionResponse(ReactionEmoji emoji, List<Long> userIds) {
    }

    /** @param items seq 오름차순. hasMore: afterSeq 면 더 새 메시지가, 그 밖에는 더 오래된 메시지가 남아 있다 */
    public record MessagePage(List<MessageResponse> items, boolean hasMore) {
    }

    public record ReadResponse(long lastReadSeq) {
    }

    public record ReactionsResponse(long groupId, long messageId, long seq, List<ReactionResponse> reactions) {
    }

    /** 채팅 탭(SCR-10) 그룹별 요약 */
    public record ChatSummary(LastMessage lastMessage, long unreadCount) {
    }

    public record LastMessage(long seq, Long senderId, MessageType type, String body, boolean deleted,
            Instant sentAt) {
    }

    // --- 실시간 이벤트 ---

    /**
     * /sub/groups/{groupId}: message.created, message.deleted, read.updated, reaction.updated, media.updated,
     * candidate.created, candidate.resolved
     * /user/queue/events: message.created(재전송 확인), error, session.revoked, data-export.ready
     */
    public record ChatEvent(String type, Object data) {

        public static final String MESSAGE_CREATED = "message.created";
        public static final String MESSAGE_DELETED = "message.deleted";
        public static final String READ_UPDATED = "read.updated";
        public static final String REACTION_UPDATED = "reaction.updated";
        /** AI 등록 후보가 생겼다 (사양서 11.4). data = 후보 (CandidateResponse) */
        public static final String CANDIDATE_CREATED = "candidate.created";
        /** 후보가 승인·무시·되돌려졌다. data = CandidateResolved */
        public static final String CANDIDATE_RESOLVED = "candidate.resolved";
        public static final String ERROR = "error";
        public static final String SESSION_REVOKED = "session.revoked";
        /** 내 데이터 내보내기 ZIP 이 준비됐다. data = {id} */
        public static final String DATA_EXPORT_READY = "data-export.ready";
    }

    /** 앱은 seq가 (fromSeq, lastReadSeq] 이고 보낸 사람이 userId가 아닌 메시지의 unreadCount를 1씩 뺀다 */
    public record ReadUpdated(long groupId, long userId, long fromSeq, long lastReadSeq) {
    }

    public record MessageDeleted(long groupId, long id, long seq) {
    }

    /** STOMP로 보낸 요청이 실패했다. clientMessageId 로 어떤 메시지인지 찾는다 */
    public record ErrorEvent(String code, String message, String clientMessageId) {
    }

    public record SessionRevoked(long sessionId) {
    }
}
