package com.famlog.candidate.normalize;

import java.time.LocalDate;

/** 날짜 표현을 해석한 결과 */
public sealed interface DateResult {

    /** @param inferred 표현만으로 정해지지 않아 서버가 추정했다 (이번 주말 → 토요일, 이번 주에 → 일요일) */
    record Resolved(LocalDate date, boolean inferred) implements DateResult {
    }

    /** 지난 일 (어제, 지난주). 후보를 만들지 않는다 (사양서 6.4) */
    record Past() implements DateResult {
    }

    /** 해석하지 못했다. 날짜는 사용자가 채운다 (missingFields) */
    record Unknown() implements DateResult {
    }
}
