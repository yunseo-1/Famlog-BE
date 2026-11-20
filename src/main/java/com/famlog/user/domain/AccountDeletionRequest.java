package com.famlog.user.domain;

import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 계정 삭제 요청 (사양서 4.1 ACC-01, 8.9). REQUESTED → 복구하면 CANCELLED, 유예가 지나 파기하면 COMPLETED */
@Getter
@Entity
@Table(name = "account_deletion_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountDeletionRequest extends BaseTimeEntity {

    public static final String REQUESTED = "REQUESTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String COMPLETED = "COMPLETED";
    /** 앱에서 요청 / 웹(이메일) 요청을 개발자가 관리자 도구로 대신 처리 */
    public static final String SOURCE_APP = "APP";
    public static final String SOURCE_ADMIN = "ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private String source;

    @Column(name = "delete_media", nullable = false, updatable = false)
    private boolean deleteMedia;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "scheduled_group_ids", nullable = false, updatable = false, columnDefinition = "bigint[]")
    private Long[] scheduledGroupIds;

    @Column(nullable = false)
    private String status;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "purge_after", nullable = false, updatable = false)
    private Instant purgeAfter;

    @Column(name = "completed_at")
    private Instant completedAt;

    public AccountDeletionRequest(long userId, String source, boolean deleteMedia, List<Long> scheduledGroupIds,
            Instant now, Instant purgeAfter) {
        this.userId = userId;
        this.source = source;
        this.deleteMedia = deleteMedia;
        this.scheduledGroupIds = scheduledGroupIds.toArray(Long[]::new);
        this.status = REQUESTED;
        this.requestedAt = now;
        this.purgeAfter = purgeAfter;
    }

    public void cancel(Instant now) {
        this.status = CANCELLED;
        this.completedAt = now;
    }

    public void complete(Instant now) {
        this.status = COMPLETED;
        this.completedAt = now;
    }
}
