package com.famlog.report.dto;

import java.time.Instant;

import com.famlog.report.domain.Report;
import com.famlog.report.domain.ReportReason;
import com.famlog.report.domain.ReportStatus;
import com.famlog.report.domain.ReportTargetType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 신고 API (사양서 4.2 MOD-01, 11.3 POST /reports, SCR-82) */
public final class ReportDtos {

    public static final int MAX_DETAIL = 500;

    private ReportDtos() {
    }

    /**
     * @param targetId 메시지·미디어·후보 id, USER 면 사용자 id
     * @param groupId  USER 신고에만 필요: 어느 가족 그룹에서 신고하는지 (나와 상대가 모두 구성원이어야 한다)
     */
    public record CreateReportRequest(@NotNull ReportTargetType targetType, @NotNull @Positive Long targetId,
            @Schema(description = "USER 신고에만: 신고하는 그룹") Long groupId, @NotNull ReportReason reason,
            @Schema(description = "자세한 내용 (OTHER 면 필수)") @Size(max = MAX_DETAIL) String detail) {
    }

    public record ReportResponse(long id, ReportTargetType targetType, long targetId, ReportReason reason,
            ReportStatus status, Instant createdAt) {

        public static ReportResponse from(Report report) {
            return new ReportResponse(report.getId(), report.getTargetType(), report.getTargetId(), report.getReason(),
                    report.getStatus(), report.getCreatedAt());
        }
    }
}
