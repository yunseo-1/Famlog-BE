package com.famlog.candidate.normalize;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/** 일정 날짜 + 시각을 해석한 결과 */
public sealed interface TimingResult {

    /**
     * @param startTime null 이면 종일
     * @param inferred  추정한 항목 (후보 JSON inferred 와 같은 키: "date", "startTime.ampm")
     */
    record Resolved(LocalDate date, LocalTime startTime, Set<String> inferred) implements TimingResult {

        public boolean allDay() {
            return startTime == null;
        }
    }

    record Past() implements TimingResult {
    }

    record Unknown() implements TimingResult {
    }
}
