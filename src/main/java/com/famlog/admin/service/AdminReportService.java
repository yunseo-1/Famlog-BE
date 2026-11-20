package com.famlog.admin.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.admin.dto.AdminDtos.ReportDetail;
import com.famlog.admin.dto.AdminDtos.ReportPage;
import com.famlog.admin.dto.AdminDtos.ReportSummary;
import com.famlog.admin.dto.AdminDtos.ResolveReportRequest;
import com.famlog.admin.dto.AdminDtos.ResolveReportResponse;
import com.famlog.candidate.service.CandidateDecisionService;
import com.famlog.chat.service.ChatService;
import com.famlog.common.audit.AuditAction;
import com.famlog.common.audit.AuditLogger;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.media.storage.MediaStorage;
import com.famlog.report.domain.Report;
import com.famlog.report.domain.ReportAction;
import com.famlog.report.domain.ReportStatus;
import com.famlog.report.domain.ReportTargetType;
import com.famlog.report.repository.ReportRepository;
import com.famlog.report.service.ReportService;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 신고 처리 (사양서 4.2 MOD-01, 9장 "운영자는 신고된 콘텐츠만 열람, 열람은 감사 로그").
 * <ul>
 * <li>목록에는 콘텐츠를 넣지 않는다. 상세를 열 때만 신고된 대상 하나를 읽고 ADMIN_REPORT_VIEWED 를 남긴다</li>
 * <li>처리: REVIEWED·ACTIONED·REJECTED. 조치는 메시지·사진 숨김(모두에게서 삭제 처리, message.deleted),
 * AI 후보 무시. 같은 대상의 처리 전 신고는 함께 처리한다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AdminReportService {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final Duration MEDIA_URL_TTL = Duration.ofMinutes(10);

    private final ReportRepository reportRepository;
    private final ChatService chatService;
    private final CandidateDecisionService candidateDecisionService;
    private final MediaStorage storage;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogger auditLogger;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ReportPage list(ReportStatus status, Long cursor, Integer size) {
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(size, 1), 100);
        List<Report> found = reportRepository.findForAdmin(status, cursor, Limit.of(pageSize + 1));
        boolean hasNext = found.size() > pageSize;
        List<Report> page = hasNext ? found.subList(0, pageSize) : found;
        return new ReportPage(page.stream().map(AdminReportService::summary).toList(),
                hasNext ? Long.toString(page.getLast().getId()) : null);
    }

    @Transactional
    public ReportDetail detail(long reportId) {
        Report report = find(reportId);
        auditLogger.recordAdmin(AuditAction.ADMIN_REPORT_VIEWED, "REPORT", reportId, report.getGroupId(),
                Map.of("targetType", report.getTargetType().name()));
        return new ReportDetail(summary(report), report.getReporterId(), report.getDetail(), report.getHandlerNote(),
                content(report.getTargetType(), report.getTargetId()));
    }

    @Transactional
    public ResolveReportResponse resolve(long reportId, ResolveReportRequest request) {
        Report report = find(reportId);
        ReportStatus status = request.status();
        ReportAction action = request.action();
        if (status == ReportStatus.OPEN) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "처리 상태는 REVIEWED·ACTIONED·REJECTED 중 하나예요.");
        }
        if (action != null && (status != ReportStatus.ACTIONED
                || !ReportService.actionFits(report.getTargetType(), action))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "이 신고에 맞지 않는 조치예요.");
        }
        if (action == ReportAction.HIDE_MESSAGE) {
            Long messageId = report.getTargetType() == ReportTargetType.MESSAGE ? report.getTargetId()
                    : jdbcTemplate.queryForObject("SELECT message_id FROM media_assets WHERE id = ?", Long.class,
                            report.getTargetId());
            if (messageId != null) {
                chatService.hideByAdmin(messageId);
            }
        } else if (action == ReportAction.DISMISS_CANDIDATE) {
            candidateDecisionService.dismissByAdmin(report.getTargetId());
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        Instant now = Instant.now(clock);
        List<Report> same = reportRepository.findByTargetTypeAndTargetIdAndStatus(report.getTargetType(),
                report.getTargetId(), ReportStatus.OPEN).stream()
                .filter(r -> !r.getId().equals(report.getId())).toList();
        report.resolve(status, action, note, now);
        same.forEach(r -> r.resolve(status, action, note, now));
        reportRepository.flush();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("status", status.name());
        meta.put("action", action == null ? null : action.name());
        meta.put("count", same.size() + 1);
        auditLogger.recordAdmin(AuditAction.ADMIN_REPORT_RESOLVED, "REPORT", reportId, report.getGroupId(), meta);
        return new ResolveReportResponse(summary(report), same.size() + 1);
    }

    /** 신고된 대상 하나 (지워졌으면 null) */
    private Map<String, Object> content(ReportTargetType type, long targetId) {
        return switch (type) {
            case MESSAGE -> jdbcTemplate.query("""
                    SELECT id, sender_id, type, body, deleted_at IS NOT NULL, created_at FROM messages WHERE id = ?
                    """, (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("messageId", rs.getLong(1));
                m.put("senderId", rs.getObject(2, Long.class));
                m.put("type", rs.getString(3));
                m.put("body", rs.getString(4));
                m.put("deleted", rs.getBoolean(5));
                m.put("sentAt", rs.getTimestamp(6).toInstant());
                m.put("media", mediaOf(rs.getLong(1)));
                return m;
            }, targetId).stream().findFirst().orElse(null);
            case MEDIA -> jdbcTemplate.query("""
                    SELECT id, message_id, uploader_id, media_type, storage_key FROM media_assets WHERE id = ?
                    """, (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("mediaId", rs.getLong(1));
                m.put("messageId", rs.getObject(2, Long.class));
                m.put("uploaderId", rs.getLong(3));
                m.put("type", rs.getString(4));
                m.put("url", storage.presignGet(rs.getString(5), MEDIA_URL_TTL).toString());
                return m;
            }, targetId).stream().findFirst().orElse(null);
            case AI_OUTPUT -> jdbcTemplate.query("""
                    SELECT id, type, status, fields::text FROM registration_candidates WHERE id = ?
                    """, (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("candidateId", rs.getLong(1));
                m.put("type", rs.getString(2));
                m.put("status", rs.getString(3));
                m.put("fields", rs.getString(4));
                return m;
            }, targetId).stream().findFirst().orElse(null);
            case USER -> jdbcTemplate.query("SELECT id, nickname, status FROM users WHERE id = ?", (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("userId", rs.getLong(1));
                m.put("nickname", rs.getString(2));
                m.put("status", rs.getString(3));
                return m;
            }, targetId).stream().findFirst().orElse(null);
        };
    }

    /** 메시지에 붙은 사진·동영상 (10분 링크) */
    private List<Map<String, Object>> mediaOf(long messageId) {
        return jdbcTemplate.query("""
                SELECT id, media_type, storage_key FROM media_assets WHERE message_id = ? ORDER BY id
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mediaId", rs.getLong(1));
            m.put("type", rs.getString(2));
            m.put("url", storage.presignGet(rs.getString(3), MEDIA_URL_TTL).toString());
            return m;
        }, messageId);
    }

    private Report find(long reportId) {
        return reportRepository.findById(reportId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static ReportSummary summary(Report r) {
        return new ReportSummary(r.getId(), r.getGroupId(), r.getTargetType(), r.getTargetId(), r.getReason(),
                r.getStatus(), r.getAction(), r.getCreatedAt(), r.getHandledAt());
    }
}
