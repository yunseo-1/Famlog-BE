package com.famlog.admin.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.famlog.report.domain.ReportAction;
import com.famlog.report.domain.ReportReason;
import com.famlog.report.domain.ReportStatus;
import com.famlog.report.domain.ReportTargetType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 관리자 도구 API (사양서 14장, 9장 운영자 접근: 신고된 콘텐츠만 열람) */
public final class AdminDtos {

    private AdminDtos() {
    }

    // --- 신고 ---

    /** 목록에는 신고 내용·콘텐츠를 넣지 않는다 (상세에서 열람 → 감사 로그) */
    public record ReportSummary(long id, long groupId, ReportTargetType targetType, long targetId, ReportReason reason,
            ReportStatus status, ReportAction action, Instant createdAt, Instant handledAt) {
    }

    public record ReportPage(List<ReportSummary> items, String nextCursor) {
    }

    /**
     * @param detail  신고한 사람이 적은 내용
     * @param content 신고된 콘텐츠 (대상 종류별): MESSAGE {messageId, senderId, type, body, deleted, sentAt, media[]},
     *                MEDIA {mediaId, messageId, uploaderId, type, url}, AI_OUTPUT {candidateId, type, status, fields},
     *                USER {userId, nickname, status}. 지워졌으면 null
     */
    public record ReportDetail(ReportSummary report, long reporterId, String detail, String handlerNote,
            Map<String, Object> content) {
    }

    /**
     * @param action ACTIONED 일 때: 메시지·사진은 HIDE_MESSAGE, AI 후보는 DISMISS_CANDIDATE (사용자 신고는 없음)
     */
    public record ResolveReportRequest(@NotNull ReportStatus status, ReportAction action,
            @Size(max = 500) String note) {
    }

    /** @param resolvedCount 같은 대상의 처리 전 신고를 함께 처리한 수 */
    public record ResolveReportResponse(ReportSummary report, int resolvedCount) {
    }

    // --- 공휴일·앱 버전·기능 플래그·감사 로그 ---

    public record AdminHoliday(long id, LocalDate date, String name, boolean substitute) {
    }

    public record AddHolidayRequest(@NotNull LocalDate date, @NotBlank @Size(max = 30) String name,
            boolean substitute) {
    }

    public record UpdateAppVersionRequest(
            @Schema(example = "1.0.0") @NotBlank @Size(max = 20) String minSupportedVersion,
            @Size(max = 20) String latestVersion, @Size(max = 500) String notice) {
    }

    /** @param source DB(관리자가 정함) 또는 DEFAULT(설정값) */
    public record FeatureFlagItem(String key, boolean enabled, String source) {
    }

    public record UpdateFeatureFlagRequest(@NotNull Boolean enabled) {
    }

    public record AuditLogItem(long id, Long actorId, String action, String targetType, Long targetId, Long groupId,
            String meta, String ip, Instant createdAt) {
    }
}
