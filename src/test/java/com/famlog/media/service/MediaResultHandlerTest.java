package com.famlog.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.famlog.chat.relay.ChatRelay;
import com.famlog.media.domain.MediaAsset;
import com.famlog.media.domain.MediaStatus;
import com.famlog.media.domain.MediaType;
import com.famlog.media.dto.MediaDtos.MediaUpdated;
import com.famlog.media.repository.MediaAssetRepository;

/**
 * 워커 결과 반영 규칙 (사양서 8.6 사진 위치).
 * 위치 저장은 기능 플래그·동의에 달려 있어 통합 테스트(플래그 꺼짐)로는 켜진 쪽을 볼 수 없으므로 단위 테스트로 확인한다.
 */
class MediaResultHandlerTest {

    MediaAssetRepository repository = mock(MediaAssetRepository.class);
    MediaService mediaService = mock(MediaService.class);
    ChatRelay relay = mock(ChatRelay.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    MediaResultHandler handler = new MediaResultHandler(repository, mediaService, relay, jdbcTemplate);
    MediaAsset media;

    @BeforeEach
    void setUp() {
        media = new MediaAsset(10L, 20L, MediaType.IMAGE, "image/heic", 1000, "media/abc/original",
                Instant.now());
        ReflectionTestUtils.setField(media, "id", 1L);
        media.markUploaded("image/heic", 1000);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(media));
    }

    @Test
    void READY_결과로_형식_크기_해상도_촬영_시각_썸네일을_바꾼다() {
        handler.apply(ready());

        assertThat(media.getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(media.getMime()).isEqualTo("image/jpeg");
        assertThat(media.getSize()).isEqualTo(900);
        assertThat(media.getWidth()).isEqualTo(4032);
        assertThat(media.getTakenAt()).isEqualTo(Instant.parse("2026-11-14T02:30:00Z"));
        assertThat(media.getVariants()).contains("THUMB_M");
    }

    @Test
    void 동의와_기능_플래그가_있을_때만_위치를_저장하고_확인자료를_남긴다() {
        when(mediaService.extractLocation(20L)).thenReturn(true);

        handler.apply(withLocation(ready()));

        verify(jdbcTemplate).update(contains("SET location"), eq(126.978), eq(37.5665), eq(1L));
        verify(jdbcTemplate).update(contains("location_access_logs"), eq(20L), eq("PHOTO_LOCATION"), eq(1L));
    }

    @Test
    void 동의가_없으면_결과에_좌표가_있어도_저장하지_않는다() {
        when(mediaService.extractLocation(20L)).thenReturn(false);

        handler.apply(withLocation(ready()));

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void 메시지로_보낸_미디어면_그룹에_media_updated를_보낸다() {
        media.attachTo(99L);

        handler.apply(ready());

        verify(relay).toGroup(eq(10L), eq(MediaResultHandler.MEDIA_UPDATED), any(MediaUpdated.class));
    }

    @Test
    void 아직_메시지로_보내지_않았으면_알리지_않는다() {
        handler.apply(ready());

        verify(relay, never()).toGroup(anyLong(), anyString(), any());
    }

    @Test
    void 형식이_틀린_결과는_무시한다() {
        Map<String, String> broken = ready();
        broken.put("size", "abc");

        handler.apply(broken);

        assertThat(media.getStatus()).isEqualTo(MediaStatus.UPLOADED);
    }

    @Test
    void REJECTED_결과는_이유와_함께_남긴다() {
        handler.apply(new HashMap<>(Map.of("mediaId", "1", "status", "REJECTED", "reason", "TOO_LONG")));

        assertThat(media.getStatus()).isEqualTo(MediaStatus.REJECTED);
        assertThat(media.getRejectReason()).isEqualTo("TOO_LONG");
    }

    private static Map<String, String> ready() {
        Map<String, String> result = new HashMap<>();
        result.put("mediaId", "1");
        result.put("status", "READY");
        result.put("mime", "image/jpeg");
        result.put("size", "900");
        result.put("width", "4032");
        result.put("height", "3024");
        result.put("takenAt", "2026-11-14T11:30:00+09:00");
        result.put("variants", "{\"THUMB_S\":\"a\",\"THUMB_M\":\"b\",\"THUMB_L\":\"c\"}");
        return result;
    }

    private static Map<String, String> withLocation(Map<String, String> result) {
        result.put("lat", "37.5665");
        result.put("lng", "126.978");
        return result;
    }
}
