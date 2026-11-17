package com.famlog.media.service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.famlog.media.domain.MediaType;

/** 받는 형식 목록 (사양서 9.1 허용 형식 목록). 그 밖은 업로드 URL을 주지 않는다. */
public final class MediaFormats {

    private static final Map<String, MediaType> ALLOWED = Map.of(
            "image/jpeg", MediaType.IMAGE,
            "image/png", MediaType.IMAGE,
            "image/webp", MediaType.IMAGE,
            "image/heic", MediaType.IMAGE,
            "image/heif", MediaType.IMAGE,
            "video/mp4", MediaType.VIDEO,
            "video/quicktime", MediaType.VIDEO);

    private MediaFormats() {
    }

    public static Optional<MediaType> typeOf(String mime) {
        return mime == null ? Optional.empty() : Optional.ofNullable(ALLOWED.get(normalize(mime)));
    }

    public static String normalize(String mime) {
        return mime.strip().toLowerCase(Locale.ROOT);
    }
}
