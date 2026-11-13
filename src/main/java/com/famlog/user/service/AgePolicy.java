package com.famlog.user.service;

import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.famlog.common.time.FamlogTime;

import lombok.RequiredArgsConstructor;

/**
 * 만 14세 이상 확인 (사양서 4.1 AUTH-02, 8.5). 생일 날짜를 받지 않으므로 보수적으로 판정한다:
 * 서울 기준 현재 연월이 "출생연월 + 14년"보다 뒤일 때만 허용 (14번째 생일이 있는 달에는 가입 불가).
 */
@Component
@RequiredArgsConstructor
public class AgePolicy {

    static final int MINIMUM_AGE = 14;
    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("uuuuMM");

    private final Clock clock;

    /** YYYYMM을 읽는다. 형식이 틀리거나 미래·1900년 이전이면 empty */
    public Optional<YearMonth> parse(String birthYm) {
        try {
            YearMonth value = YearMonth.parse(birthYm, YYYYMM);
            if (value.getYear() < 1900 || value.isAfter(now())) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (DateTimeParseException | NullPointerException e) {
            return Optional.empty();
        }
    }

    public boolean isOldEnough(YearMonth birth) {
        return now().isAfter(birth.plusYears(MINIMUM_AGE));
    }

    private YearMonth now() {
        return YearMonth.now(clock.withZone(FamlogTime.DISPLAY_ZONE));
    }
}
