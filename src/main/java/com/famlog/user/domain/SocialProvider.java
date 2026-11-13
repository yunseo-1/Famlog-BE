package com.famlog.user.domain;

import java.util.Locale;
import java.util.Optional;

/** 소셜 로그인 제공자 (사양서 4.1 AUTH-01). APPLE은 iOS 대비로 값만 열어 둔다. */
public enum SocialProvider {
    KAKAO,
    GOOGLE,
    APPLE;

    /** URL 경로 값(kakao, google)을 읽는다. 대소문자는 가리지 않는다 */
    public static Optional<SocialProvider> fromPath(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
