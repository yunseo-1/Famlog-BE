package com.famlog.candidate.normalize;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 날짜·시각 표현을 합쳐 일정 시점과 기념일 날짜를 만든다 (사양서 6.2 ②, 6.3).
 * <ul>
 * <li>날짜만 있으면 종일. 시각만 있으면 그 시각이 아직 안 지났으면 오늘, 지났으면 내일 (날짜 추정)</li>
 * <li>오늘을 가리키는 요일·날짜인데 시각이 이미 지났으면 다음 회차 (DateNormalizer)</li>
 * <li>자정·밤 12시는 말한 날의 다음 날 00:00</li>
 * <li>해석 못 하는 시각은 버리고 종일로 둔다 (사용자가 폼에서 고친다)</li>
 * </ul>
 */
public final class DateTimeNormalizer {

    public static final String INFERRED_DATE = "date";
    public static final String INFERRED_AMPM = "startTime.ampm";

    private DateTimeNormalizer() {
    }

    /**
     * @param dateText 날짜 표현 (없으면 null)
     * @param timeText 시각 표현 (없으면 null)
     * @param clue     근거 메시지 원문 (오전·오후 단서)
     * @param sentAt   근거 메시지 발송 시각 (Asia/Seoul)
     */
    public static TimingResult schedule(String dateText, String timeText, String clue, ZonedDateTime sentAt) {
        Optional<TimeResult> time = TimeNormalizer.resolve(timeText, clue);
        Set<String> inferred = new LinkedHashSet<>();
        time.filter(TimeResult::ampmInferred).ifPresent(t -> inferred.add(INFERRED_AMPM));
        LocalTime startTime = time.map(TimeResult::time).orElse(null);
        int dayShift = time.filter(TimeResult::nextDay).isPresent() ? 1 : 0;

        if (dateText == null || dateText.isBlank()) {
            if (time.isEmpty()) {
                return new TimingResult.Unknown();
            }
            LocalDate today = sentAt.toLocalDate();
            LocalDateTime candidate = LocalDateTime.of(today.plusDays(dayShift), startTime);
            LocalDate date = candidate.isBefore(sentAt.toLocalDateTime()) ? today.plusDays(1) : today;
            inferred.add(INFERRED_DATE);
            return new TimingResult.Resolved(date.plusDays(dayShift), startTime, Set.copyOf(inferred));
        }
        DateResult date = DateNormalizer.scheduleDate(dateText, sentAt, dayShift == 0 ? startTime : null);
        return switch (date) {
            case DateResult.Past past -> new TimingResult.Past();
            case DateResult.Unknown unknown -> new TimingResult.Unknown();
            case DateResult.Resolved resolved -> {
                if (resolved.inferred()) {
                    inferred.add(INFERRED_DATE);
                }
                yield new TimingResult.Resolved(resolved.date().plusDays(dayShift), startTime, Set.copyOf(inferred));
            }
        };
    }

    /** 기념일(ANNIVERSARY)의 월·일. "3월 5일"처럼 지난 날짜여도 매년 반복이므로 그 월·일. 지난 일 표현("어제")은 없음 */
    public static Optional<MonthDay> anniversary(String dateText, ZonedDateTime sentAt) {
        return switch (DateNormalizer.scheduleDate(dateText, sentAt, null)) {
            case DateResult.Resolved resolved -> Optional.of(MonthDay.from(resolved.date()));
            case DateResult.Past past -> Optional.empty();
            case DateResult.Unknown unknown -> Optional.empty();
        };
    }
}
