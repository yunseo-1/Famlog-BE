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
 * 승인·무시·되돌리기는 S19 에서 붙인다.
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
}
