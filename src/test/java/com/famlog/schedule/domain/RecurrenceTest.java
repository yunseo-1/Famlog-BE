package com.famlog.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 반복 규칙 (사양서 4.3 SCH-01): 서울 시각 기준으로 펼치고, 없는 날짜는 건너뛴다 (RFC 5545). */
class RecurrenceTest {

    private static final Duration HOUR = Duration.ofHours(1);

    @Test
    void 매주_반복은_기간_안의_같은_요일_같은_시각이다() {
        List<Instant> starts = Recurrence.parse("FREQ=WEEKLY")
                .startsOverlapping(kst("2026-11-02T10:00"), HOUR, kst("2026-11-01T00:00"), kst("2026-12-01T00:00"));

        assertThat(starts).containsExactly(kst("2026-11-02T10:00"), kst("2026-11-09T10:00"), kst("2026-11-16T10:00"),
                kst("2026-11-23T10:00"), kst("2026-11-30T10:00"));
    }

    @Test
    void 간격이_있으면_그만큼_건너뛰고_첫_회차_전_기간은_비어_있다() {
        Recurrence everyOtherWeek = Recurrence.parse("FREQ=WEEKLY;INTERVAL=2");

        assertThat(everyOtherWeek.startsOverlapping(kst("2026-11-02T10:00"), HOUR, kst("2026-11-01T00:00"),
                kst("2026-12-01T00:00")))
                .containsExactly(kst("2026-11-02T10:00"), kst("2026-11-16T10:00"), kst("2026-11-30T10:00"));
        assertThat(everyOtherWeek.startsOverlapping(kst("2026-11-02T10:00"), HOUR, kst("2026-10-01T00:00"),
                kst("2026-11-01T00:00"))).isEmpty();
    }

    @Test
    void 매월_31일은_31일이_없는_달을_건너뛰고_횟수에도_세지_않는다() {
        Instant first = kst("2027-01-31T09:00");

        assertThat(Recurrence.parse("FREQ=MONTHLY").startsOverlapping(first, HOUR, kst("2027-01-01T00:00"),
                kst("2027-07-01T00:00")))
                .containsExactly(first, kst("2027-03-31T09:00"), kst("2027-05-31T09:00"));
        assertThat(Recurrence.parse("FREQ=MONTHLY;COUNT=3").startsOverlapping(first, HOUR, kst("2027-01-01T00:00"),
                kst("2028-01-01T00:00")))
                .containsExactly(first, kst("2027-03-31T09:00"), kst("2027-05-31T09:00"));
    }

    @Test
    void 매년_2월_29일은_윤년에만_있다() {
        assertThat(Recurrence.parse("FREQ=YEARLY").startsOverlapping(kst("2028-02-29T00:00"), Duration.ofDays(1),
                kst("2028-01-01T00:00"), kst("2034-01-01T00:00")))
                .containsExactly(kst("2028-02-29T00:00"), kst("2032-02-29T00:00"));
    }

    @Test
    void 날짜_UNTIL은_그날_서울_시각_안에_시작하는_회차까지다() {
        assertThat(Recurrence.parse("FREQ=WEEKLY;UNTIL=20261116").startsOverlapping(kst("2026-11-02T23:30"), HOUR,
                kst("2026-11-01T00:00"), kst("2027-01-01T00:00")))
                .containsExactly(kst("2026-11-02T23:30"), kst("2026-11-09T23:30"), kst("2026-11-16T23:30"));
    }

    @Test
    void 시각_UNTIL은_UTC_시각까지다() {
        // 11/16 10:00 KST = 01:00Z 이라 00:00Z 까지면 빠진다
        assertThat(Recurrence.parse("FREQ=WEEKLY;UNTIL=20261116T000000Z").startsOverlapping(kst("2026-11-02T10:00"),
                HOUR, kst("2026-11-01T00:00"), kst("2027-01-01T00:00")))
                .containsExactly(kst("2026-11-02T10:00"), kst("2026-11-09T10:00"));
    }

    @Test
    void 기간_앞에서_시작해_기간에_걸친_회차도_보인다() {
        // 매주 토요일 22:00 ~ 일요일 02:00
        assertThat(Recurrence.parse("FREQ=WEEKLY").startsOverlapping(kst("2026-11-07T22:00"), Duration.ofHours(4),
                kst("2026-11-15T00:00"), kst("2026-11-16T00:00")))
                .containsExactly(kst("2026-11-14T22:00"));
    }

    @Test
    void 저장_형식은_순서가_정해져_있다() {
        assertThat(Recurrence.parse("COUNT=3;FREQ=MONTHLY").toRrule()).isEqualTo("FREQ=MONTHLY;COUNT=3");
        assertThat(Recurrence.parse("UNTIL=20270228;INTERVAL=2;FREQ=WEEKLY").toRrule())
                .isEqualTo("FREQ=WEEKLY;INTERVAL=2;UNTIL=20270228");
        assertThat(Recurrence.parse("FREQ=YEARLY;INTERVAL=1").toRrule()).isEqualTo("FREQ=YEARLY");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "FREQ=DAILY", "FREQ=WEEKLY;BYDAY=MO,WE", "INTERVAL=2",
            "FREQ=WEEKLY;COUNT=2;UNTIL=20270101", "FREQ=WEEKLY;INTERVAL=0", "FREQ=WEEKLY;INTERVAL=100",
            "FREQ=WEEKLY;COUNT=1000", "FREQ=WEEKLY;FREQ=MONTHLY", "FREQ=WEEKLY;UNTIL=2027-01-01",
            "FREQ=WEEKLY;UNTIL=20271340", "RRULE:FREQ=WEEKLY", "FREQ=weekly", "FREQ=WEEKLY;COUNT=-1",
            "FREQ=WEEKLY;" })
    void 지원하지_않거나_틀린_규칙은_거부한다(String rrule) {
        assertThatThrownBy(() -> Recurrence.parse(rrule)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 전날_20시_알림은_일정_날의_서울_날짜_기준이다() {
        // 11/21 00:30 KST 일정 → 11/20 20:00 KST (UTC 로는 11/20 15:30 일정이지만 서울 날짜가 기준)
        assertThat(Reminder.PREV_DAY_20.remindAt(kst("2026-11-21T00:30"))).isEqualTo(kst("2026-11-20T20:00"));
        assertThat(Reminder.HOUR_1.remindAt(kst("2026-11-21T00:30"))).isEqualTo(kst("2026-11-20T23:30"));
        assertThat(Reminder.DAY_1.remindAt(kst("2026-11-21T00:30"))).isEqualTo(kst("2026-11-20T00:30"));
    }

    static Instant kst(String localDateTime) {
        return OffsetDateTime.parse(localDateTime + "+09:00").toInstant();
    }
}
