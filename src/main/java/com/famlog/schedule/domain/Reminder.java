package com.famlog.schedule.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

import com.famlog.common.time.FamlogTime;

/**
 * 일정 리마인드 시점 (사양서 4.3 SCH-03). 기본은 전날 20:00 + 1시간 전, 종일 일정은 전날 20:00만 고를 수 있다.
 */
public enum Reminder {
    /** 일정 날(서울) 전날 20:00 */
    PREV_DAY_20(null),
    MIN_10(Duration.ofMinutes(10)),
    MIN_30(Duration.ofMinutes(30)),
    HOUR_1(Duration.ofHours(1)),
    DAY_1(Duration.ofDays(1));

    public static final int MAX_PER_SCHEDULE = 3;
    public static final List<Reminder> TIMED_DEFAULT = List.of(PREV_DAY_20, HOUR_1);
    public static final List<Reminder> ALL_DAY_DEFAULT = List.of(PREV_DAY_20);
    /** 가장 이른 알림이 시작보다 얼마나 앞설 수 있는지 (전날 20:00 → 다음 날 23:59 시작이면 약 28시간) */
    public static final Duration MAX_LEAD = Duration.ofHours(28);

    private static final LocalTime EVENING = LocalTime.of(20, 0);

    private final Duration before;

    Reminder(Duration before) {
        this.before = before;
    }

    public Instant remindAt(Instant occurrenceStart) {
        if (before != null) {
            return occurrenceStart.minus(before);
        }
        ZonedDateTime start = occurrenceStart.atZone(FamlogTime.DISPLAY_ZONE);
        return start.toLocalDate().minusDays(1).atTime(EVENING).atZone(FamlogTime.DISPLAY_ZONE).toInstant();
    }

    public boolean allowedForAllDay() {
        return this == PREV_DAY_20;
    }
}
