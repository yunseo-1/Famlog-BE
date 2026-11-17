package com.famlog.media.domain;

import java.time.Instant;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사진·동영상 (사양서 10.2 media_assets). 파일은 비공개 버킷에 UUID 키로 두고, 앱은 10분짜리 Presigned URL로만 올리고 받는다.
 * 위치(location)는 개인위치정보라 엔티티에 두지 않고 MediaResultHandler 가 동의를 확인한 뒤에만 SQL로 쓴다 (사양서 8.6).
 */
@Getter
@Entity
@Table(name = "media_assets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaAsset extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Column(name = "uploader_id", nullable = false, updatable = false)
    private Long uploaderId;

    @Column(name = "message_id")
    private Long messageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, updatable = false)
    private MediaType mediaType;

    @Column(nullable = false)
    private String mime;

    @Column(nullable = false)
    private long size;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    /** 썸네일 키 JSON {"THUMB_S": "...", ...} */
    @JdbcTypeCode(SqlTypes.JSON)
    private String variants;

    private Integer width;

    private Integer height;

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "taken_at")
    private Instant takenAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaStatus status;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "upload_expires_at", nullable = false, updatable = false)
    private Instant uploadExpiresAt;

    public MediaAsset(long groupId, long uploaderId, MediaType mediaType, String mime, long size, String storageKey,
            Instant uploadExpiresAt) {
        this.groupId = groupId;
        this.uploaderId = uploaderId;
        this.mediaType = mediaType;
        this.mime = mime;
        this.size = size;
        this.storageKey = storageKey;
        this.uploadExpiresAt = uploadExpiresAt;
        this.status = MediaStatus.PENDING_UPLOAD;
    }

    /** 업로드 확인 끝: 파일에서 확인한 형식·크기로 바꾸고 워커 처리를 기다린다 */
    public void markUploaded(String detectedMime, long actualSize) {
        this.mime = detectedMime;
        this.size = actualSize;
        this.status = MediaStatus.UPLOADED;
    }

    public void reject(String reason) {
        this.status = MediaStatus.REJECTED;
        this.rejectReason = reason;
    }

    public void markFailed(String reason) {
        this.status = MediaStatus.FAILED;
        this.rejectReason = reason;
    }

    /** 워커 처리 결과. 원본은 GPS를 지운 파일로 바뀌어 형식·크기가 달라질 수 있다 (HEIC → JPEG) */
    public void markReady(String mime, long size, Integer width, Integer height, Integer durationSec,
            Instant takenAt, String variants) {
        this.mime = mime;
        this.size = size;
        this.width = width;
        this.height = height;
        this.durationSec = durationSec;
        this.takenAt = takenAt;
        this.variants = variants;
        this.status = MediaStatus.READY;
        this.rejectReason = null;
    }

    public void attachTo(long messageId) {
        this.messageId = messageId;
    }
}
