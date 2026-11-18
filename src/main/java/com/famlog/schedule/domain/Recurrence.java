package com.famlog.schedule.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.famlog.common.time.FamlogTime;

/**
 * 반복 규칙 (사양서 4.3 SCH-01 "매주/매월/매년"). RFC 5545 RRULE 중 앱이 쓰는 부분만 받는다:
 * {@code FREQ=WEEKLY|MONTHLY|YEARLY}, {@code INTERVAL}(1~99),
 * {@code UNTIL}(YYYYMMDD 또는 YYYYMMDDTHHMMSSZ) 또는 {@code COUNT}(1~999).
 * <p>
 * 회차는 서울 시각 기준으로 첫 회차의 요일·날짜·시각을 그대로 옮긴다. 그 날짜가 없는 달·해(31일, 2월 29일)는 건너뛰고
 * 회차 수(COUNT)에도 세지 않는다 (RFC 5545). UNTIL 이 날짜면 그날(서울) 안에 시작하는 회차까지다.
 */
public record Recurrence(Frequency frequency, int interval, String until, Integer count) {

    public enum Frequency { WEEKLY, MONTHLY, YEARLY }

    public static final int MAX_LENGTH = 200;
    private static final int MAX_INTERVAL = 99;
    private static final int MAX_COUNT = 999;
    /** 회차 계산을 끝없이 돌지 않게 (매주 반복 약 190년) */
    private static final int MAX_STEPS = 10_000;
    private static final Set<String> KEYS = Set.of("FREQ", "INTERVAL", "UNTIL", "COUNT");
    private static final DateTimeFormatter UNTIL_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter UNTIL_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    /** @throws IllegalArgumentException 형식이 틀리거나 지원하지 않는 규칙 */
    public static Recurrence parse(String rrule) {
        if (rrule == null || rrule.isBlank() || rrule.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("반복 규칙이 비었거나 너무 길어요.");
        }
        Map<String, String> parts = new HashMap<>();
        for (String part : rrule.trim().split(";", -1)) {
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            if (eq < 0 || !KEYS.contains(key) || parts.put(key, part.substring(eq + 1)) != null) {
                throw new IllegalArgumentException("지원하지 않는 반복 규칙이에요.");
            }
        }
        Frequency frequency = parseFrequency(parts.get("FREQ"));
        int interval = parts.containsKey("INTERVAL") ? parseNumber(parts.get("INTERVAL"), MAX_INTERVAL) : 1;
        String until = parts.get("UNTIL");
        Integer count = parts.containsKey("COUNT") ? parseNumber(parts.get("COUNT"), MAX_COUNT) : null;
        if (until != null && count != null) {
            throw new IllegalArgumentException("UNTIL 과 COUNT 는 함께 쓸 수 없어요.");
        }
        Recurrence recurrence = new Recurrence(frequency, interval, until, count);
        if (until != null) {
            recurrence.untilInclusive();
        }
        return recurrence;
    }

    /** 저장 형식 (항상 같은 순서) */
    public String toRrule() {
        StringBuilder rrule = new StringBuilder("FREQ=").append(frequency);
        if (interval != 1) {
            rrule.append(";INTERVAL=").append(interval);
        }
        if (until != null) {
            rrule.append(";UNTIL=").append(until);
        }
        if (count != null) {
            rrule.append(";COUNT=").append(count);
        }
        return rrule.toString();
    }

    /**
     * [from, to) 와 겹치는 회차의 시작 시각. 길이가 0인 일정은 시작이 구간 안이면 겹친다.
     *
     * @param firstStart 첫 회차 시작 (일정의 starts_at)
     * @param duration   회차 길이 (ends_at - starts_at)
     */
    public List<Instant> startsOverlapping(Instant firstStart, Duration duration, Instant from, Instant to) {
        LocalDateTime base = LocalDateTime.ofInstant(firstStart, FamlogTime.DISPLAY_ZONE);
        Instant last = until == null ? null : untilInclusive();
        List<Instant> starts = new ArrayList<>();
        int produced = 0;
        for (int n = 0; n < MAX_STEPS; n++) {
            LocalDateTime local = nth(base, (long) n * interval);
            if (local == null) {
                continue;
            }
            Instant start = local.atZone(FamlogTime.DISPLAY_ZONE).toInstant();
            if ((last != null && start.isAfter(last)) || (count != null && produced >= count) || !start.isBefore(to)) {
                break;
            }
            produced++;
            if (overlaps(start, start.plus(duration), from, to)) {
                starts.add(start);
            }
        }
        return starts;
    }

    public static boolean overlaps(Instant start, Instant end, Instant from, Instant to) {
        return start.isBefore(to) && (end.isAfter(from) || !start.isBefore(from));
    }

    private LocalDateTime nth(LocalDateTime base, long steps) {
        return switch (frequency) {
            case WEEKLY -> base.plusWeeks(steps);
            case MONTHLY -> sameDay(base, YearMonth.from(base).plusMonths(steps));
            case YEARLY -> sameDay(base, YearMonth.from(base).plusYears(steps));
        };
    }

    /** 그 달에 같은 날짜가 없으면 null (건너뛴다) */
    private static LocalDateTime sameDay(LocalDateTime base, YearMonth month) {
        if (base.getDayOfMonth() > month.lengthOfMonth()) {
            return null;
        }
        return base.with(month.atDay(base.getDayOfMonth()));
    }

    private Instant untilInclusive() {
        try {
            if (until.length() == 8) {
                LocalDate date = LocalDate.parse(until, UNTIL_DATE);
                return date.plusDays(1).atStartOfDay(FamlogTime.DISPLAY_ZONE).toInstant().minusNanos(1);
            }
            return LocalDateTime.parse(until, UNTIL_DATE_TIME).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("UNTIL 은 YYYYMMDD 또는 YYYYMMDDTHHMMSSZ 형식이어야 해요.", e);
        }
    }

    private static Frequency parseFrequency(String value) {
        if (value == null) {
            throw new IllegalArgumentException("FREQ 가 필요해요.");
        }
        try {
            return Frequency.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("반복은 매주·매월·매년만 돼요.", e);
        }
    }

    private static int parseNumber(String value, int max) {
        if (value == null || value.isEmpty() || value.length() > 3 || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("반복 숫자 형식이 틀렸어요.");
        }
        int number = Integer.parseInt(value);
        if (number < 1 || number > max) {
            throw new IllegalArgumentException("반복 숫자는 1~" + max + " 이어야 해요.");
        }
        return number;
    }
}
