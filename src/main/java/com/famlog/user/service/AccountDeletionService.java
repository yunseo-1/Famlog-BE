package com.famlog.user.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.auth.service.SessionTokenService;
import com.famlog.chat.service.ChatService;
import com.famlog.common.audit.AuditAction;
import com.famlog.common.audit.AuditLogger;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.time.FamlogTime;
import com.famlog.group.service.GroupService;
import com.famlog.media.repository.MediaAssetRepository;
import com.famlog.media.storage.MediaStorage;
import com.famlog.user.domain.AccountDeletionRequest;
import com.famlog.user.domain.User;
import com.famlog.user.repository.AccountDeletionRequestRepository;
import com.famlog.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 계정 삭제·복구·파기 (사양서 4.1 ACC-01, 8.2, 8.9, 18장).
 * <ul>
 * <li>요청: 다른 구성원이 있는 그룹의 그룹장이면 409. 혼자인 그룹은 삭제 예정. 계정은 삭제 예정이 되고 모든 기기가 로그아웃되며
 * (푸시 토큰도 지운다) 30일 뒤 파기 예정. 구성원 자격은 유예 동안 그대로 둔다. 다시 요청하면 그대로 성공</li>
 * <li>복구: 유예 중 로그인하며 restore=true 로 다시 로그인하면 계정과 함께 삭제 예정으로 돌린 그룹을 되살린다</li>
 * <li>파기(배치): 닉네임 "탈퇴한 구성원", 프로필 사진·출생연도월·소셜 계정·세션·알림 설정·장소 표시를 지우고 모든 그룹에서 나간다.
 * 사진 위치는 언제나 지우고, 사진·동영상 삭제를 골랐으면 S3 객체와 함께 지우고 그 메시지를 삭제 처리한다.
 * 메시지 내용·일정·장소·동의 이력·신고·감사 기록은 남긴다. 감사 로그에는 건수만</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    static final int PURGE_BATCH = 50;
    private static final TypeReference<Map<String, String>> VARIANTS = new TypeReference<>() {
    };

    private final UserRepository userRepository;
    private final AccountDeletionRequestRepository requestRepository;
    private final GroupService groupService;
    private final SessionTokenService sessionTokenService;
    private final ChatService chatService;
    private final MediaAssetRepository mediaRepository;
    private final MediaStorage storage;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogger auditLogger;
    private final AccountProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /** @param source APP(본인) 또는 ADMIN(웹 요청을 관리자 도구로 대신 처리) */
    @Transactional
    public void request(long userId, boolean deleteMedia, String source) {
        User current = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (current.isPendingDeletion()) {
            return;
        }
        if (!current.isActive()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        // 그룹 정리는 영속성 컨텍스트를 비우는 갱신을 부를 수 있어 사용자는 그 뒤에 다시 읽는다
        List<Long> scheduled = groupService.prepareAccountDeletion(userId);
        Instant now = Instant.now(clock);
        User user = userRepository.findById(userId).orElseThrow();
        user.requestDeletion();
        userRepository.saveAndFlush(user);
        requestRepository.saveAndFlush(new AccountDeletionRequest(userId, source, deleteMedia, scheduled, now,
                now.plus(properties.deletionGrace())));
        sessionTokenService.revokeAllSessions(userId);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", source);
        meta.put("deleteMedia", deleteMedia);
        meta.put("scheduledGroups", scheduled.size());
        auditLogger.record(userId, AuditAction.ACCOUNT_DELETION_REQUESTED, "USER", userId, null, meta);
    }

    /** 로그인할 때: 삭제 예정 계정이면 복구하거나(restore) 403 ACCOUNT_PENDING_DELETION(복구 가능한 날짜) */
    @Transactional
    public void restoreOrReject(User user, boolean restore) {
        AccountDeletionRequest request = requestRepository
                .findByUserIdAndStatus(user.getId(), AccountDeletionRequest.REQUESTED).orElse(null);
        Instant now = Instant.now(clock);
        if (request == null || !request.getPurgeAfter().isAfter(now)) {
            throw new BusinessException(ErrorCode.AUTH_INVALID);
        }
        if (!restore) {
            ZonedDateTime until = request.getPurgeAfter().atZone(FamlogTime.DISPLAY_ZONE);
            throw new BusinessException(ErrorCode.ACCOUNT_PENDING_DELETION, "삭제 예정인 계정이에요. %d월 %d일까지 복구할 수 있어요."
                    .formatted(until.getMonthValue(), until.getDayOfMonth()));
        }
        user.restore();
        userRepository.saveAndFlush(user);
        request.cancel(now);
        requestRepository.saveAndFlush(request);
        groupService.restoreAfterAccountRestore(Arrays.asList(request.getScheduledGroupIds()));
        auditLogger.record(user.getId(), AuditAction.ACCOUNT_RESTORED, "USER", user.getId(), null, null);
    }

    /** 유예가 지난 요청 id (배치가 하나씩 purge 를 부른다) */
    @Transactional(readOnly = true)
    public List<Long> dueRequestIds(Instant now) {
        return requestRepository.findDueIds(now, Limit.of(PURGE_BATCH));
    }

    /** 요청 하나를 파기한다. 다른 서버가 처리 중이거나 이미 처리했으면 넘어간다 (행 잠금, SKIP LOCKED) */
    @Transactional
    public boolean purge(long requestId) {
        List<Long> locked = jdbcTemplate.queryForList("""
                SELECT id FROM account_deletion_requests WHERE id = ? AND status = 'REQUESTED'
                FOR UPDATE SKIP LOCKED
                """, Long.class, requestId);
        if (locked.isEmpty()) {
            return false;
        }
        AccountDeletionRequest request = requestRepository.findById(requestId).orElseThrow();
        long userId = request.getUserId();
        Instant now = Instant.now(clock);

        int media = request.isDeleteMedia() ? deleteMedia(userId) : 0;
        mediaRepository.clearLocations(userId);
        int groups = groupService.leaveAllForPurge(userId);
        jdbcTemplate.update("DELETE FROM social_accounts WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM device_tokens WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM refresh_tokens WHERE session_id IN (SELECT id FROM user_sessions "
                + "WHERE user_id = ?)", userId);
        jdbcTemplate.update("DELETE FROM user_sessions WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_notification_settings WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM place_marks WHERE user_id = ?", userId);

        User user = userRepository.findById(userId).orElseThrow();
        user.purge(now);
        userRepository.saveAndFlush(user);
        request.complete(now);
        requestRepository.saveAndFlush(request);
        auditLogger.record(userId, AuditAction.ACCOUNT_PURGED, "USER", userId, null,
                Map.of("groups", groups, "media", media));
        log.info("계정 파기: requestId={}, groups={}, media={}", requestId, groups, media);
        return true;
    }

    /** 내가 올린 사진·동영상: S3 원본·썸네일을 지우고 붙은 메시지를 삭제 처리한 뒤 행을 지운다 */
    private int deleteMedia(long userId) {
        record Row(long id, Long messageId, String key, String variants) {
        }
        List<Row> rows = jdbcTemplate.query("""
                SELECT id, message_id, storage_key, variants::text FROM media_assets WHERE uploader_id = ?
                """, (rs, i) -> new Row(rs.getLong(1), rs.getObject(2, Long.class), rs.getString(3), rs.getString(4)),
                userId);
        List<Long> messageIds = new ArrayList<>();
        for (Row row : rows) {
            storage.delete(row.key());
            if (row.variants() != null) {
                jsonMapper.readValue(row.variants(), VARIANTS).values().forEach(storage::delete);
            }
            if (row.messageId() != null && !messageIds.contains(row.messageId())) {
                messageIds.add(row.messageId());
            }
        }
        messageIds.forEach(chatService::hideByAdmin);
        jdbcTemplate.update("DELETE FROM media_assets WHERE uploader_id = ?", userId);
        return rows.size();
    }
}
