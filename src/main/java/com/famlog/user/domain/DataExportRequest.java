package com.famlog.user.domain;

import java.time.Instant;

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

/**
 * 내 데이터 내보내기 요청 (사양서 4.1 ACC-02). REQUESTED → (배치가 잡으면) PROCESSING → READY → 7일 뒤 EXPIRED.
 * 만들다 실패하면 REQUESTED 로 돌려 다시 시도하고, 3번째도 실패하면 FAILED
 */
@Getter
@Entity
@Table(name = "data_export_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataExportRequest extends BaseTimeEntity {

    public static final String REQUESTED = "REQUESTED";
    public static final String PROCESSING = "PROCESSING";
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";
    public static final String EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** 항목별 건수 JSON {"messages": 3, ...} */
    @JdbcTypeCode(SqlTypes.JSON)
    private String counts;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "downloaded_at")
    private Instant downloadedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public DataExportRequest(long userId, Instant now) {
        this.userId = userId;
        this.status = REQUESTED;
        this.requestedAt = now;
    }

    public boolean isOpen() {
        return REQUESTED.equals(status) || PROCESSING.equals(status);
    }

    public boolean isReady() {
        return READY.equals(status);
    }

    public void ready(String storageKey, long sizeBytes, String counts, Instant now, Instant expiresAt) {
        this.status = READY;
        this.storageKey = storageKey;
        this.sizeBytes = sizeBytes;
        this.counts = counts;
        this.readyAt = now;
        this.expiresAt = expiresAt;
    }

    /** 실패: 시도 횟수가 남았으면 다시 대기열로, 아니면 FAILED */
    public void failed(int maxAttempts, Instant now) {
        if (attempts < maxAttempts) {
            this.status = REQUESTED;
        } else {
            fail(now);
        }
    }

    /** 다시 시도하지 않는 실패 (삭제 예정 계정 등) */
    public void fail(Instant now) {
        this.status = FAILED;
        this.finishedAt = now;
    }

    public void expire(Instant now) {
        this.status = EXPIRED;
        this.storageKey = null;
        this.finishedAt = now;
    }

    /** 처음 다운로드 링크를 받았으면 true */
    public boolean markDownloaded(Instant now) {
        if (downloadedAt != null) {
            return false;
        }
        this.downloadedAt = now;
        return true;
    }
}
