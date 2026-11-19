package com.famlog.candidate.domain;

import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

/** 후보를 어떻게 처리했는지 (사양서 6.5 AR-4, 7.3). 고친 항목 이름만 남기고 값은 남기지 않는다 */
@Getter
@Entity
@Table(name = "suggestion_feedback")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SuggestionFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id", nullable = false, updatable = false)
    private Long candidateId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private FeedbackAction action;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "edited_fields", nullable = false, updatable = false, columnDefinition = "varchar(30)[]")
    private String[] editedFields;

    @Enumerated(EnumType.STRING)
    @Column(name = "dismiss_reason", updatable = false)
    private DismissReason dismissReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SuggestionFeedback(long candidateId, long userId, FeedbackAction action, List<String> editedFields,
            DismissReason dismissReason) {
        this.candidateId = candidateId;
        this.userId = userId;
        this.action = action;
        this.editedFields = editedFields.toArray(String[]::new);
        this.dismissReason = dismissReason;
    }
}
