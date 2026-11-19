package com.famlog.candidate.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.MonthDay;

import org.junit.jupiter.api.Test;

import com.famlog.candidate.domain.CandidateType;

/** 후보 중복 기준 (사양서 6.2 ② 중복 제거) */
class DedupKeysTest {

    @Test
    void 제목은_공백_문장부호_대소문자를_무시한다() {
        LocalDate date = LocalDate.of(2026, 11, 14);

        assertThat(DedupKeys.of(CandidateType.SCHEDULE, "할머니 댁 방문", date))
                .isEqualTo(DedupKeys.of(CandidateType.SCHEDULE, "할머니댁 방문!", date))
                .isEqualTo("SCHEDULE|할머니댁방문|2026-11-14");
        assertThat(DedupKeys.normalize("KTX 예매")).isEqualTo(DedupKeys.normalize("ktx-예매"));
    }

    @Test
    void 날짜나_유형이_다르면_다른_후보다() {
        LocalDate date = LocalDate.of(2026, 11, 14);

        assertThat(DedupKeys.of(CandidateType.SCHEDULE, "장보기", date))
                .isNotEqualTo(DedupKeys.of(CandidateType.SCHEDULE, "장보기", date.plusDays(1)))
                .isNotEqualTo(DedupKeys.of(CandidateType.TODO, "장보기", date));
        assertThat(DedupKeys.of(CandidateType.TODO, "장보기", null)).isEqualTo("TODO|장보기|");
    }

    @Test
    void 기념일은_월일로_장소는_카카오_ID로_비교한다() {
        assertThat(DedupKeys.anniversary("할머니 생신", MonthDay.of(12, 5))).isEqualTo("ANNIVERSARY|할머니생신|12-05");
        assertThat(DedupKeys.place("123", "OO칼국수")).isEqualTo("PLACE|kakao:123");
        assertThat(DedupKeys.place(null, "OO 칼국수")).isEqualTo("PLACE|name:oo칼국수");
    }

    @Test
    void 아주_긴_제목도_300자를_넘지_않는다() {
        assertThat(DedupKeys.of(CandidateType.TODO, "가".repeat(400), null)).hasSize(300);
    }
}
