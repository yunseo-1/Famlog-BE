package com.famlog.candidate.normalize;

import static com.famlog.candidate.normalize.DateNormalizerTest.at;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 날짜 + 시각 → 일정 시점 (사양서 6.2 ②, 6.3 예시, 기준 2026-11-12(목) 10:00).
 * inferred 키는 후보 JSON(6.3)과 같다: "date", "startTime.ampm".
 */
class DateTimeNormalizerTest {

    private static final String THU = "2026-11-12T10:00";

    @Test
    void 예시1_이번_토요일_11시는_11월_14일_오전_11시_오전_추정() {
        TimingResult result = DateTimeNormalizer.schedule("이번 토요일", "11시", "이번 토요일 11시에 할머니 댁 가자", at(THU));

        assertThat(result).isEqualTo(new TimingResult.Resolved(LocalDate.of(2026, 11, 14), LocalTime.of(11, 0),
                Set.of("startTime.ampm")));
    }

    @Test
    void 예시2_이번_주에_맡겨야_해는_기한_11월_15일_추정() {
        assertThat(DateNormalizer.deadline("이번 주에", at(THU)))
                .isEqualTo(new DateResult.Resolved(LocalDate.of(2026, 11, 15), true));
    }

    @Test
    void 예시4_다음_달_15일_생신은_매년_12월_15일() {
        assertThat(DateTimeNormalizer.anniversary("다음 달 15일", at(THU))).contains(MonthDay.of(12, 15));
        assertThat(DateTimeNormalizer.anniversary("3월 5일", at(THU))).contains(MonthDay.of(3, 5));
        assertThat(DateTimeNormalizer.anniversary("어제", at(THU))).isEmpty();
    }

    @Test
    void 예시5_다음_주_토요일_6시_저녁은_11월_21일_오후_6시() {
        TimingResult result = DateTimeNormalizer.schedule("다음 주 토요일", "6시", "다음 주 토요일 6시에 할머니댁 저녁",
                at(THU));

        assertThat(result).isEqualTo(new TimingResult.Resolved(LocalDate.of(2026, 11, 21), LocalTime.of(18, 0),
                Set.of()));
    }

    @Test
    void 시간만_있으면_아직_안_지났을_때_오늘_날짜_추정() {
        assertThat(DateTimeNormalizer.schedule(null, "6시", "6시에 보자", at(THU)))
                .isEqualTo(new TimingResult.Resolved(LocalDate.of(2026, 11, 12), LocalTime.of(18, 0),
                        Set.of("date", "startTime.ampm")));
    }

    @Test
    void 시간만_있고_그_시각이_지났으면_내일_날짜_추정() {
        assertThat(DateTimeNormalizer.schedule(null, "6시", "6시에 보자", at("2026-11-12T19:00")))
                .isEqualTo(new TimingResult.Resolved(LocalDate.of(2026, 11, 13), LocalTime.of(18, 0),
                        Set.of("date", "startTime.ampm")));
    }

    @Test
    void 시간이_없으면_종일() {
        TimingResult result = DateTimeNormalizer.schedule("다음 주 토요일", null, "다음 주 토요일 할머니 생신 잔치", at(THU));

        assertThat(result).isEqualTo(new TimingResult.Resolved(LocalDate.of(2026, 11, 21), null, Set.of()));
        assertThat(((TimingResult.Resolved) result).allDay()).isTrue();
    }

    @Test
    void 자정은_다음_날_00시() {
        assertThat(DateTimeNormalizer.schedule("토요일", "자정", "토요일 자정에 출발", at(THU)))
                .isEqualTo(new TimingResult.Resolved(LocalDate.of(2026, 11, 15), LocalTime.MIDNIGHT, Set.of()));
    }

    @Test
    void 지난_일과_해석_못_하는_날짜() {
        assertThat(DateTimeNormalizer.schedule("어제", "11시", "어제 11시에 갔었지", at(THU)))
                .isInstanceOf(TimingResult.Past.class);
        assertThat(DateTimeNormalizer.schedule("언젠가", null, "언젠가 달에 가자", at(THU)))
                .isInstanceOf(TimingResult.Unknown.class);
        assertThat(DateTimeNormalizer.schedule(null, null, "시간 돼?", at(THU)))
                .isInstanceOf(TimingResult.Unknown.class);
    }
}
