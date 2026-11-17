package com.famlog.media.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.famlog.media.domain.MediaAsset;
import com.famlog.media.domain.MediaStatus;
import com.famlog.media.domain.MediaType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 미디어 업로드·조회 (사양서 11.3 CHAT-05). */
public final class MediaDtos {

    private MediaDtos() {
    }

    public record UploadUrlsRequest(
            @NotNull Long groupId,
            @Schema(description = "한 번에 30개까지") @NotEmpty List<@Valid @NotNull UploadItem> items) {
    }

    /**
     * @param mimeType image/jpeg|png|webp|heic|heif, video/mp4|quicktime
     * @param size     바이트. 사진 30MB, 동영상 500MB 이하
     */
    public record UploadItem(@NotBlank String mimeType, @NotNull @Positive Long size) {
    }

    public record UploadUrlsResponse(List<UploadTarget> items) {
    }

    /**
     * 이 URL로 파일을 PUT 한 뒤 POST /media/{mediaId}/complete 를 부른다.
     *
     * @param headers PUT 요청에 그대로 넣어야 하는 헤더 (서명에 포함됨)
     */
    public record UploadTarget(long mediaId, String uploadUrl, String method, Map<String, String> headers,
            Instant expiresAt) {
    }

    /**
     * 파일 주소는 주지 않는다. 앱은 GET /media/{id}/content?variant= 로 받는다 (status 가 READY 일 때).
     *
     * @param takenAt 촬영 시각 (EXIF). 없으면 null
     */
    public record MediaResponse(long id, MediaType mediaType, String mime, long size, MediaStatus status,
            Integer width, Integer height, Integer durationSec, Instant takenAt) {

        public static MediaResponse of(MediaAsset media) {
            return new MediaResponse(media.getId(), media.getMediaType(), media.getMime(), media.getSize(),
                    media.getStatus(), media.getWidth(), media.getHeight(), media.getDurationSec(),
                    media.getTakenAt());
        }
    }

    /** 그룹 이벤트 media.updated: 처리 끝(READY)·실패. 앱은 messageId 메시지의 사진을 바꾼다 */
    public record MediaUpdated(long groupId, long messageId, MediaResponse media) {
    }
}
