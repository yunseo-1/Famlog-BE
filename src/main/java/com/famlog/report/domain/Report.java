package com.famlog.report.domain;

import java.time.Instant;

import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 신고 (사양서 4.2 MOD-01, 10.2). 그룹은 대상에서 서버가 정한다. 처리 후 1년 보관 (8.2, 정리는 S20-5) */
@Getter
@Entity
@Table(name = "reports")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private Long reporterId;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ReportReason reason;

    @Column(updatable = false)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportStatus status;

    @Enumerated(EnumType.STRING)
    private ReportAction action;

    @Column(name = "handler_note")
    private String handlerNote;

    @Column(name = "handled_at")
    private Instant handledAt;

    public Report(long reporterId, long groupId, ReportTargetType targetType, long targetId, ReportReason reason,
            String detail) {
        this.reporterId = reporterId;
        this.groupId = groupId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reason = reason;
        this.detail = detail;
        this.status = ReportStatus.OPEN;
    }

    public void resolve(ReportStatus status, ReportAction action, String note, Instant now) {
        this.status = status;
        this.action = action;
        this.handlerNote = note;
        this.handledAt = now;
    }
}
