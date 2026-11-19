package com.famlog.candidate.normalize;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 이 뽑은 날짜 표현을 절대 날짜로 바꾼다 (사양서 6.2 ②, 6.5 AR-9). 기준은 근거 메시지 발송 시각(Asia/Seoul)이고
 * 한 주는 월~일이다. 날짜 계산은 서버 코드만 한다 (LLM 은 원문 표현만 넘긴다).
 * <ul>
 * <li>오늘·내일·모레·글피, N일/N주 뒤</li>
 * <li>요일: "이번 토요일"·"토요일" = 이번 주의 그 요일, 지났으면 다음 주. 오늘과 같은 요일은 오늘(말한 시각이 지났으면 다음 주).
 * "다음 주 토요일" = 다음 주의 토요일 (일요일에 "다음 주 월요일"은 내일)</li>
 * <li>"15일" = 이번 달 15일, 지났으면 다음 달 (그 날이 없는 달은 건너뛴다). "다음 달 15일", "12월 25일"·"12/25"(지났으면 내년)</li>
 * <li>주말(일정) = 이번 주 토요일, 일요일에 말하면 오늘 (추정)</li>
 * <li>기한: "이번 주에" → 이번 주 일요일(추정), "주말까지"·"이번 주까지" → 일요일, "이번 달 안에" → 말일, "다음 주까지" → 다음 주 일요일</li>
 * <li>어제·지난주·지난달 → 지난 일. 그 밖은 해석 못 함 (사용자가 채운다)</li>
 * </ul>
 */
public final class DateNormalizer {

    private static final Pattern PAST = Pattern.compile("어제|그저께|그제|지난\\s*주|지난\\s*달|작년|저번\\s*주");
    private static final Pattern DAY_AFTER_TOMORROW = Pattern.compile("내일\\s*모레");
    private static final Pattern RELATIVE_DAY = Pattern.compile("오늘|내일|모레|글피");
    private static final Pattern AFTER = Pattern.compile("(\\d{1,3})\\s*(일|주)\\s*(?:뒤|후)|(일주일)\\s*(?:뒤|후)");
    private static final Pattern MONTH_DAY = Pattern.compile(
            "(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일|(?<![\\d/])(\\d{1,2})/(\\d{1,2})(?![\\d/])");
    private static final Pattern MONTH_OFFSET_DAY = Pattern.compile("(이번|다음)\\s*달\\s*(\\d{1,2})\\s*일");
    private static final Pattern DAY = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*일(?!\\s*(?:뒤|후))");
    private static final Pattern WEEKDAY = Pattern.compile("(이번|다음|다다음)?\\s*(?:주\\s*)?([월화수목금토일])(?:요일|욜)");
    private static final Pattern WEEKEND = Pattern.compile("(다음)?\\s*(?:주\\s*)?주말");

    private static final Pattern WEEK_DEADLINE = Pattern.compile(
            "(이번|다음)\\s*주\\s*(까지|안에|안|내로|내|에|중)?(?!\\s*(?:[월화수목금토일](?:요일|욜)|말))|주말\\s*까지");
    private static final Pattern MONTH_DEADLINE = Pattern.compile("(이번|다음)\\s*달\\s*(까지|안에|안|내로|내|에|중)?(?!\\s*\\d)");

    private static final String WEEKDAYS = "월화수목금토일";

    private DateNormalizer() {
    }

    /**
     * 일정 날짜 (SCHEDULE).
     *
     * @param time 함께 말한 시각 (없으면 null). 오늘을 가리키는 요일·날짜인데 이 시각이 이미 지났으면 다음 회차로 넘긴다
     */
    public static DateResult scheduleDate(String text, ZonedDateTime sentAt, LocalTime time) {
        if (text == null || text.isBlank()) {
            return new DateResult.Unknown();
        }
        LocalDate today = sentAt.toLocalDate();
        boolean timePassed = time != null && time.isBefore(sentAt.toLocalTime());
        if (PAST.matcher(text).find()) {
            return new DateResult.Past();
        }
        if (DAY_AFTER_TOMORROW.matcher(text).find()) {
            return resolved(today.plusDays(2));
        }
        Matcher m = RELATIVE_DAY.matcher(text);
        if (m.find()) {
            int offset = switch (m.group()) {
                case "오늘" -> 0;
                case "내일" -> 1;
                case "모레" -> 2;
                default -> 3;
            };
            return resolved(today.plusDays(offset));
        }
        m = AFTER.matcher(text);
        if (m.find()) {
            if (m.group(3) != null) {
                return resolved(today.plusWeeks(1));
            }
            long amount = Long.parseLong(m.group(1));
            return resolved("주".equals(m.group(2)) ? today.plusWeeks(amount) : today.plusDays(amount));
        }
        m = MONTH_OFFSET_DAY.matcher(text);
        if (m.find()) {
            YearMonth month = YearMonth.from(today).plusMonths("다음".equals(m.group(1)) ? 1 : 0);
            int day = Integer.parseInt(m.group(2));
            if (!month.isValidDay(day)) {
                return new DateResult.Unknown();
            }
            LocalDate date = month.atDay(day);
            return date.isBefore(today) ? new DateResult.Past() : resolved(date);
        }
        m = MONTH_DAY.matcher(text);
        if (m.find()) {
            int month = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(3));
            int day = Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(4));
            return nextMonthDay(today, month, day, timePassed);
        }
        m = DAY.matcher(text);
        if (m.find()) {
            return nextDayOfMonth(today, Integer.parseInt(m.group(1)), timePassed);
        }
        m = WEEKDAY.matcher(text);
        if (m.find()) {
            DayOfWeek dayOfWeek = DayOfWeek.of(WEEKDAYS.indexOf(m.group(2)) + 1);
            LocalDate thisWeek = weekStart(today).with(TemporalAdjusters.nextOrSame(dayOfWeek));
            if (m.group(1) == null || "이번".equals(m.group(1))) {
                boolean passed = thisWeek.isBefore(today) || thisWeek.equals(today) && timePassed;
                return resolved(passed ? thisWeek.plusWeeks(1) : thisWeek);
            }
            return resolved(thisWeek.plusWeeks("다음".equals(m.group(1)) ? 1 : 2));
        }
        m = WEEKEND.matcher(text);
        if (m.find()) {
            LocalDate saturday = weekStart(today).plusDays(5);
            if (m.group(1) != null) {
                return new DateResult.Resolved(saturday.plusWeeks(1), true);
            }
            return new DateResult.Resolved(today.getDayOfWeek() == DayOfWeek.SUNDAY ? today : saturday, true);
        }
        return new DateResult.Unknown();
    }

    /** 할 일 기한 (TODO). 기간 표현은 그 기간의 마지막 날. "~에/중"은 추정, "~까지/안에"는 확정 */
    public static DateResult deadline(String text, ZonedDateTime sentAt) {
        if (text == null || text.isBlank()) {
            return new DateResult.Unknown();
        }
        if (PAST.matcher(text).find()) {
            return new DateResult.Past();
        }
        LocalDate today = sentAt.toLocalDate();
        Matcher m = WEEK_DEADLINE.matcher(text);
        if (m.find()) {
            LocalDate sunday = weekStart(today).plusDays(6).plusWeeks("다음".equals(m.group(1)) ? 1 : 0);
            return new DateResult.Resolved(sunday, isVague(m.group(2)) && m.group(1) != null);
        }
        m = MONTH_DEADLINE.matcher(text);
        if (m.find()) {
            YearMonth month = YearMonth.from(today).plusMonths("다음".equals(m.group(1)) ? 1 : 0);
            return new DateResult.Resolved(month.atEndOfMonth(), isVague(m.group(2)));
        }
        return scheduleDate(text, sentAt, null);
    }

    private static boolean isVague(String suffix) {
        return suffix == null || "에".equals(suffix) || "중".equals(suffix);
    }

    private static DateResult resolved(LocalDate date) {
        return new DateResult.Resolved(date, false);
    }

    private static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** 이번 달부터 그 날이 있는 첫 달 (오늘이 지났거나, 오늘인데 시각이 지났으면 다음 달부터) */
    private static DateResult nextDayOfMonth(LocalDate today, int day, boolean timePassed) {
        if (day < 1 || day > 31) {
            return new DateResult.Unknown();
        }
        YearMonth month = YearMonth.from(today);
        for (int i = 0; i < 13; i++, month = month.plusMonths(1)) {
            if (!month.isValidDay(day)) {
                continue;
            }
            LocalDate date = month.atDay(day);
            if (date.isAfter(today) || date.equals(today) && !timePassed) {
                return resolved(date);
            }
        }
        return new DateResult.Unknown();
    }

    /** 올해의 그 날, 지났으면 내년. 없는 날(2월 30일)은 해석 못 함 */
    private static DateResult nextMonthDay(LocalDate today, int month, int day, boolean timePassed) {
        try {
            LocalDate date = LocalDate.of(today.getYear(), month, day);
            boolean passed = date.isBefore(today) || date.equals(today) && timePassed;
            return resolved(passed ? LocalDate.of(today.getYear() + 1, month, day) : date);
        } catch (DateTimeException e) {
            return new DateResult.Unknown();
        }
    }
}
