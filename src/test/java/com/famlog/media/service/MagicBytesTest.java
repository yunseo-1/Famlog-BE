package com.famlog.media.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** 파일 앞부분으로 형식 판별 (사양서 9.1, SEC-T6). */
class MagicBytesTest {

    @Test
    void 허용한_형식을_알아본다() {
        assertThat(MagicBytes.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0))).hasValue("image/jpeg");
        assertThat(MagicBytes.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))).hasValue("image/png");
        assertThat(MagicBytes.detect(ascii("RIFF\0\0\0\0WEBPVP8 "))).hasValue("image/webp");
        assertThat(MagicBytes.detect(ascii("\0\0\0\u0018ftypheic\0\0\0\0"))).hasValue("image/heic");
        assertThat(MagicBytes.detect(ascii("\0\0\0\u0018ftypmif1\0\0\0\0"))).hasValue("image/heif");
        assertThat(MagicBytes.detect(ascii("\0\0\0\u0018ftypmp42\0\0\0\0"))).hasValue("video/mp4");
        assertThat(MagicBytes.detect(ascii("\0\0\0\u0014ftypqt  \0\0\0\0"))).hasValue("video/quicktime");
        assertThat(MagicBytes.detect(ascii("\0\0\0\u0008wide\0\0\0\0mdat"))).hasValue("video/quicktime");
    }

    @Test
    void 실행_파일이나_문서는_알아보지_못한다() {
        assertThat(MagicBytes.detect(ascii("MZ\u0090\0\u0003\0\0\0"))).isEmpty();
        assertThat(MagicBytes.detect(ascii("%PDF-1.7\n"))).isEmpty();
        assertThat(MagicBytes.detect(ascii("<html><script>"))).isEmpty();
        assertThat(MagicBytes.detect(new byte[0])).isEmpty();
    }

    @Test
    void HEIC_HEIF_와_MP4_QuickTime_은_같은_형식으로_본다() {
        assertThat(MagicBytes.matches("image/heic", "image/heif")).isTrue();
        assertThat(MagicBytes.matches("video/mp4", "video/quicktime")).isTrue();
        assertThat(MagicBytes.matches("image/jpeg", "image/png")).isFalse();
        assertThat(MagicBytes.matches("image/jpeg", "video/mp4")).isFalse();
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }
}
