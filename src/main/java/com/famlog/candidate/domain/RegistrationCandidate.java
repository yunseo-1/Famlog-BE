package com.famlog.candidate.domain;

import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 가 만든 등록 후보 (사양서 6장). 승인 전에는 캘린더·할 일·장소 목록에 나타나지 않는다.
 * 상태: PENDING → APPROVED(→ 5분 안에 UNDONE) / DISMISSED. 먼저 처리한 사람이 반영된다 (version).
 */
@Getter
@Entity
@Table(name = "registration_candidates")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegistrationCandidate extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Column(name = "room_id", nullable = false, updatable = false)
    private Long roomId;

    @Column(name = "job_id", nullable = false, updatable = false)
    private Long jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private CandidateType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CandidateStatus status;

    /** 정규화한 값 JSON (유형별 형태는 HANDOFF "후보 생성 규칙") */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String fields;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "inferred_fields", nullable = false, columnDefinition = "varchar(30)[]")
    private String[] inferredFields;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "missing_fields", nullable = false, columnDefinition = "varchar(30)[]")
    private String[] missingFields;

    @Column(nullable = false, updatable = false)
    private double confidence;

    @Column(name = "dedup_key", nullable = false, updatable = false)
    private String dedupKey;

    @Column(name = "model_version", updatable = false)
    private String modelVersion;

    @Column(name = "prompt_version", updatable = false)
    private String promptVersion;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "dismiss_reason")
    private DismissReason dismissReason;

    /** SCHEDULE, TODO, PLACE, 이미 공유된 장소에 연결했으면 PLACE_LINKED */
    @Column(name = "created_entity_type")
    private String createdEntityType;

    @Column(name = "created_entity_id")
    private Long createdEntityId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    @Column(nullable = false)
    private long version;

    public RegistrationCandidate(AiJob job, AiJob.Usage usage, CandidateType type, String fields,
            List<String> inferredFields, List<String> missingFields, double confidence, String dedupKey,
            Instant expiresAt) {
        this.groupId = job.getGroupId();
        this.roomId = job.getRoomId();
        this.jobId = job.getId();
        this.type = type;
        this.status = CandidateStatus.PENDING;
        this.fields = fields;
        this.inferredFields = inferredFields.toArray(String[]::new);
        this.missingFields = missingFields.toArray(String[]::new);
        this.confidence = confidence;
        this.dedupKey = dedupKey;
        this.modelVersion = usage.modelVersion();
        this.promptVersion = usage.promptVersion();
        this.expiresAt = expiresAt;
    }

    /** 아직 처리할 수 있는 후보 (PENDING 이고 만료 전) */
    public boolean isOpen(Instant now) {
        return status == CandidateStatus.PENDING && expiresAt.isAfter(now);
    }

    public boolean isExpired(Instant now) {
        return status == CandidateStatus.PENDING && !expiresAt.isAfter(now);
    }

    public void approve(long userId, Instant now, String entityType, long entityId) {
        decide(CandidateStatus.APPROVED, userId, now);
        this.createdEntityType = entityType;
        this.createdEntityId = entityId;
    }

    public void dismiss(long userId, Instant now, DismissReason reason) {
        decide(CandidateStatus.DISMISSED, userId, now);
        this.dismissReason = reason;
    }

    /** 승인을 되돌린다. 처리한 사람·시각은 승인 때 그대로 둔다 */
    public void undo() {
        this.status = CandidateStatus.UNDONE;
    }

    private void decide(CandidateStatus next, long userId, Instant now) {
        this.status = next;
        this.decidedBy = userId;
        this.decidedAt = now;
    }
}
