package com.famlog.media.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.chat.relay.ChatRelay;
import com.famlog.media.domain.MediaAsset;
import com.famlog.media.dto.MediaDtos.MediaResponse;
import com.famlog.media.dto.MediaDtos.MediaUpdated;
import com.famlog.media.repository.MediaAssetRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 워커 처리 결과를 반영한다 (Redis Streams famlog:media-results). 필드는 워커 worker/app/schemas.py MediaResult 와 같다.
 * <ul>
 * <li>READY: 형식·크기·해상도·길이·촬영 시각·썸네일 키</li>
 * <li>위치: 결과에 좌표가 있어도 지금 기능 플래그와 동의를 다시 확인한 뒤에만 저장하고, 확인자료를 남긴다 (사양서 8.6).
 * 좌표는 로그에 남기지 않는다</li>
 * <li>이미 메시지로 보낸 미디어면 그룹에 media.updated (썸네일을 채우라고)</li>
 * </ul>
 * 같은 결과가 두 번 와도 결과는 같다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaResultHandler {

    public static final String MEDIA_UPDATED = "media.updated";

    private final MediaAssetRepository mediaRepository;
    private final MediaService mediaService;
    private final ChatRelay relay;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void apply(Map<String, String> result) {
        long mediaId;
        try {
            mediaId = Long.parseLong(result.get("mediaId"));
        } catch (NumberFormatException e) {
            log.warn("미디어 처리 결과 형식이 잘못됐습니다: 필드 mediaId");
            return;
        }
        MediaAsset media = mediaRepository.findByIdForUpdate(mediaId).orElse(null);
        if (media == null) {
            log.warn("처리 결과의 미디어가 없습니다: mediaId={}", mediaId);
            return;
        }
        String status = result.getOrDefault("status", "");
        try {
            switch (status) {
                case "READY" -> {
                    media.markReady(result.get("mime"), Long.parseLong(result.get("size")), intOrNull(result, "width"),
                            intOrNull(result, "height"), intOrNull(result, "durationSec"), instantOrNull(result),
                            blankToNull(result.get("variants")));
                    saveLocation(media, result);
                }
                case "REJECTED" -> media.reject(result.getOrDefault("reason", "UNSUPPORTED"));
                case "FAILED" -> media.markFailed(result.getOrDefault("reason", "PROCESSING_FAILED"));
                default -> {
                    log.warn("알 수 없는 처리 결과 상태입니다: mediaId={}, status={}", mediaId, status);
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.warn("미디어 처리 결과를 읽지 못했습니다: mediaId={}, error={}", mediaId, e.getClass().getSimpleName());
            return;
        }
        if (media.getMessageId() != null) {
            relay.toGroup(media.getGroupId(), MEDIA_UPDATED,
                    new MediaUpdated(media.getGroupId(), media.getMessageId(), MediaResponse.of(media)));
        }
    }

    private void saveLocation(MediaAsset media, Map<String, String> result) {
        String lat = blankToNull(result.get("lat"));
        String lng = blankToNull(result.get("lng"));
        if (lat == null || lng == null || !mediaService.extractLocation(media.getUploaderId())) {
            return;
        }
        double latitude = Double.parseDouble(lat);
        double longitude = Double.parseDouble(lng);
        if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            return;
        }
        mediaRepository.flush();
        jdbcTemplate.update("UPDATE media_assets SET location = ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography "
                + "WHERE id = ?", longitude, latitude, media.getId());
        jdbcTemplate.update("INSERT INTO location_access_logs (user_id, purpose, target_id) VALUES (?, ?, ?)",
                media.getUploaderId(), "PHOTO_LOCATION", media.getId());
    }

    private static Integer intOrNull(Map<String, String> result, String field) {
        String value = blankToNull(result.get(field));
        return value == null ? null : (int) Math.round(Double.parseDouble(value));
    }

    private static Instant instantOrNull(Map<String, String> result) {
        String value = blankToNull(result.get("takenAt"));
        return value == null ? null : OffsetDateTime.parse(value).toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
