package com.famlog.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/** 방해 금지 시간 계산 (사양서 4.1 SET-02). 시작은 포함, 끝은 제외, 자정을 넘길 수 있다. */
class NotificationSettingsTest {

    @Test
    void 꺼져_있으면_언제나_방해_금지가_아니다() {
        NotificationSettings settings = NotificationSettings.defaults(1);

        assertThat(settings.isQuietAt(LocalTime.of(23, 0))).isFalse();
    }

    @Test
    void 자정을_넘기는_기본_22시부터_7시() {
        NotificationSettings settings = NotificationSettings.defaults(1);
        settings.update(null, true, null, null);

        assertThat(settings.isQuietAt(LocalTime.of(22, 0))).isTrue();
        assertThat(settings.isQuietAt(LocalTime.of(23, 59))).isTrue();
        assertThat(settings.isQuietAt(LocalTime.of(0, 0))).isTrue();
        assertThat(settings.isQuietAt(LocalTime.of(6, 59))).isTrue();
        assertThat(settings.isQuietAt(LocalTime.of(7, 0))).isFalse();
        assertThat(settings.isQuietAt(LocalTime.of(21, 59))).isFalse();
        assertThat(settings.isQuietAt(LocalTime.NOON)).isFalse();
    }

    @Test
    void 하루_안의_시간대_13시부터_14시() {
        NotificationSettings settings = NotificationSettings.defaults(1);
        settings.update(null, true, LocalTime.of(13, 0), LocalTime.of(14, 0));

        assertThat(settings.isQuietAt(LocalTime.of(12, 59))).isFalse();
        assertThat(settings.isQuietAt(LocalTime.of(13, 0))).isTrue();
        assertThat(settings.isQuietAt(LocalTime.of(13, 59))).isTrue();
        assertThat(settings.isQuietAt(LocalTime.of(14, 0))).isFalse();
    }
}
