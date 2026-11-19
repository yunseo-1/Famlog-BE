package com.famlog.candidate.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.famlog.common.time.FamlogTime;

/**
 * 날짜 정규화 (사양서 6.2 ② 상대 날짜·기간 표현, 6.5 AR-9). 기준은 근거 메시지 발송 시각(Asia/Seoul), 한 주는 월~일.
 * 기준 2026-11-12(목) 10:00: 이번 주 11/9~11/15, 다음 주 11/16~11/22.
 * 기대값: 날짜, 추정이면 끝에 "~", 지난 일은 PAST, 해석 못 하면 UNKNOWN.
 */
class DateNormalizerTest {

    private static final String THU = "2026-11-12T10:00";

    @ParameterizedTest(name = "[{index}] {0} \"{1}\" → {2}")
    @CsvSource(delimiter = '|', value = {
            // 상대 날짜
            THU + "|오늘|2026-11-12",
            THU + "|내일|2026-11-13",
            THU + "|모레|2026-11-14",
            THU + "|글피|2026-11-15",
            THU + "|내일모레|2026-11-14",
            // 요일: 이번 주(월~일)의 그 요일, 지났으면 다음 주
            THU + "|이번 토요일|2026-11-14",
            THU + "|토요일에|2026-11-14",
            THU + "|이번 주 화요일|2026-11-17",
            THU + "|목요일|2026-11-12",
            THU + "|다음 주 토요일|2026-11-21",
            THU + "|다음주 월요일|2026-11-16",
            THU + "|다다음 주 수요일|2026-11-25",
            // N일: 이번 달, 지났으면 다음 달. 없는 날은 그 날이 있는 다음 달
            THU + "|15일|2026-11-15",
            THU + "|10일|2026-12-10",
            THU + "|12일|2026-11-12",
            THU + "|31일|2026-12-31",
            THU + "|다음 달 15일|2026-12-15",
            THU + "|이번 달 20일|2026-11-20",
            // 월·일: 올해, 지났으면 내년
            THU + "|12월 25일|2026-12-25",
            THU + "|3월 1일|2027-03-01",
            THU + "|11/20|2026-11-20",
            // 며칠·몇 주 뒤
            THU + "|3일 뒤|2026-11-15",
            THU + "|일주일 뒤|2026-11-19",
            THU + "|2주 후|2026-11-26",
            // 주말(일정): 토요일 추정, 일요일에 말하면 오늘
            THU + "|이번 주말|2026-11-14~",
            // 월말·연말 경계 (AR-9)
            "2026-12-31T10:00|다음 주 월요일|2027-01-04",
            "2026-12-31T10:00|내일|2027-01-01",
            "2026-12-31T10:00|다음 달 15일|2027-01-15",
            "2026-12-31T10:00|15일|2027-01-15",
            "2027-01-30T10:00|다음 달 30일|UNKNOWN",
            "2028-02-28T10:00|내일|2028-02-29",
            // 일요일에 말한 경우
            "2026-11-15T10:00|다음 주 월요일|2026-11-16",
            "2026-11-15T10:00|이번 토요일|2026-11-21",
            "2026-11-15T10:00|이번 주말|2026-11-15~",
            // 지난 일 (6.4: 후보를 만들지 않는다), 해석 못 함
            THU + "|어제|PAST",
            THU + "|지난주 토요일|PAST",
            THU + "|언젠가|UNKNOWN"
    })
    void 일정_날짜(String sentAt, String text, String expected) {
        assertThat(describe(DateNormalizer.scheduleDate(text, at(sentAt), null))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] {0} \"{1}\" → {2}")
    @CsvSource(delimiter = '|', value = {
            THU + "|이번 주에|2026-11-15~",
            THU + "|주말까지|2026-11-15",
            THU + "|이번 달 안에|2026-11-30",
            THU + "|다음 주까지|2026-11-22",
            THU + "|내일까지|2026-11-13",
            "2026-12-31T10:00|이번 달 안에|2026-12-31",
            THU + "|어제까지|PAST"
    })
    void 할_일_기한(String sentAt, String text, String expected) {
        assertThat(describe(DateNormalizer.deadline(text, at(sentAt)))).isEqualTo(expected);
    }

    @Test
    void 오늘과_같은_요일이라도_말한_시각이_이미_지났으면_다음_주다() {
        DateResult result = DateNormalizer.scheduleDate("목요일", at(THU), LocalTime.of(9, 0));

        assertThat(describe(result)).isEqualTo("2026-11-19");
        assertThat(describe(DateNormalizer.scheduleDate("목요일", at(THU), LocalTime.of(15, 0))))
                .isEqualTo("2026-11-12");
    }

    @Test
    void 오늘_날짜를_말해도_시각이_지났으면_다음_달이다() {
        assertThat(describe(DateNormalizer.scheduleDate("12일", at(THU), LocalTime.of(9, 0))))
                .isEqualTo("2026-12-12");
    }

    static ZonedDateTime at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(FamlogTime.DISPLAY_ZONE);
    }

    static String describe(DateResult result) {
        return switch (result) {
            case DateResult.Resolved(LocalDate date, boolean inferred) -> date + (inferred ? "~" : "");
            case DateResult.Past past -> "PAST";
            case DateResult.Unknown unknown -> "UNKNOWN";
        };
    }
}
