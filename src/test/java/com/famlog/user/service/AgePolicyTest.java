package com.famlog.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** 만 14세 판정 (사양서 4.1 AUTH-02, 8.5). 생일 날짜를 모르므로 보수적으로 판정한다. */
class AgePolicyTest {

    /** 서울 2026-11-13 10:00 */
    private final AgePolicy policy = policyAt("2026-11-13T01:00:00Z");

    @ParameterizedTest
    @CsvSource({
            "201211, false",   // 14번째 생일이 이번 달 → 날짜를 모르니 거절
            "201212, false",   // 아직 13세
            "201210, true",    // 지난달에 14세가 됨
            "201001, true",
            "198005, true",
            "202601, false",
    })
    void 서울_현재_연월이_출생연월_더하기_14년보다_뒤일_때만_허용(String birthYm, boolean allowed) {
        assertThat(policy.isOldEnough(policy.parse(birthYm).orElseThrow())).isEqualTo(allowed);
    }

    @Test
    void 날짜는_서울_기준으로_센다() {
        // UTC 2026-11-30 16:00 = 서울 2026-12-01 01:00 → 서울 기준 12월이라 2012년 11월생은 허용
        AgePolicy endOfMonthUtc = policyAt("2026-11-30T16:00:00Z");

        assertThat(endOfMonthUtc.isOldEnough(YearMonth.of(2012, 11))).isTrue();
        assertThat(policy.isOldEnough(YearMonth.of(2012, 11))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = { "199013", "199000", "1990", "abcdef", "189912", "202612", "" })
    void 형식이_틀리거나_미래거나_1900년_이전이면_읽지_않는다(String birthYm) {
        assertThat(policy.parse(birthYm)).isEmpty();
    }

    @Test
    void null도_읽지_않는다() {
        assertThat(policy.parse(null)).isEmpty();
    }

    private static AgePolicy policyAt(String instant) {
        return new AgePolicy(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
}
