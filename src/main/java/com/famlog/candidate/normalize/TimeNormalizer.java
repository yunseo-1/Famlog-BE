package com.famlog.candidate.normalize;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 이 뽑은 시각 표현을 시각으로 바꾼다 (사양서 6.2 ② 오전/오후 생략).
 * <ul>
 * <li>숫자("11시", "3시 20분", "11시 반", "7:30")와 한글 숫자("다섯 시", "열한 시 반")</li>
 * <li>오전·오후 단서: 오전·아침·새벽 / 오후·저녁 / 밤 / 점심·낮. 시간 표현에 없으면 같은 메시지(clue)에서 찾는다 → 추정 아님</li>
 * <li>단서가 없으면 1~6시 오후, 7~11시 오전, 12시 낮 12시 (추정). 13시 이상은 24시간제로 확정</li>
 * <li>정오 = 12:00, 자정·밤 12시 = 다음 날 00:00, 밤 1~4시 = 다음 날 새벽</li>
 * </ul>
 */
public final class TimeNormalizer {

    private enum Period {
        AM, PM, NIGHT, NOON
    }

    private static final Pattern COLON = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*:\\s*(\\d{2})(?!\\d)");
    private static final Pattern DIGIT_HOUR = Pattern.compile(
            "(?<!\\d)(\\d{1,2})\\s*시(?:\\s*(\\d{1,2})\\s*분|\\s*(반))?");
    private static final List<String> KOREAN_HOURS = List.of("한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉",
            "열", "열한", "열두");
    private static final Pattern KOREAN_HOUR = Pattern.compile(
            "(열한|열두|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*시(?![간작])(?:\\s*(\\d{1,2})\\s*분|\\s*(반))?");
    private static final Pattern AM = Pattern.compile("오전|아침|새벽");
    private static final Pattern PM = Pattern.compile("오후|저녁");
    private static final Pattern NIGHT = Pattern.compile("밤");
    private static final Pattern NOON = Pattern.compile("점심|낮");

    private TimeNormalizer() {
    }

    /**
     * @param text 시각 표현 ("11시", "저녁 6시")
     * @param clue 같은 메시지 원문 (오전·오후 단서를 찾는다). 없으면 null
     */
    public static Optional<TimeResult> resolve(String text, String clue) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (text.contains("정오")) {
            return Optional.of(new TimeResult(LocalTime.NOON, false, false));
        }
        if (text.contains("자정")) {
            return Optional.of(new TimeResult(LocalTime.MIDNIGHT, false, true));
        }
        int hour;
        int minute;
        Matcher m = COLON.matcher(text);
        Matcher digit = DIGIT_HOUR.matcher(text);
        Matcher korean = KOREAN_HOUR.matcher(text);
        if (m.find()) {
            hour = Integer.parseInt(m.group(1));
            minute = Integer.parseInt(m.group(2));
        } else if (digit.find()) {
            hour = Integer.parseInt(digit.group(1));
            minute = minuteOf(digit.group(2), digit.group(3));
        } else if (korean.find()) {
            hour = KOREAN_HOURS.indexOf(korean.group(1)) + 1;
            minute = minuteOf(korean.group(2), korean.group(3));
        } else {
            return Optional.empty();
        }
        if (hour > 24 || minute > 59) {
            return Optional.empty();
        }
        if (hour == 24) {
            return Optional.of(new TimeResult(LocalTime.MIDNIGHT, false, true));
        }
        if (hour == 0 || hour >= 13) {
            return Optional.of(new TimeResult(LocalTime.of(hour, minute), false, false));
        }
        Period period = periodOf(text);
        if (period == null) {
            period = periodOf(clue);
        }
        if (period == null) {
            // 1~6시 오후, 7~11시 오전, 12시 낮 (추정)
            int inferred = hour <= 6 ? hour + 12 : hour;
            return Optional.of(new TimeResult(LocalTime.of(inferred, minute), true, false));
        }
        return Optional.of(apply(period, hour, minute));
    }

    private static TimeResult apply(Period period, int hour, int minute) {
        return switch (period) {
            // 오전·새벽 12시 = 00시
            case AM -> new TimeResult(LocalTime.of(hour == 12 ? 0 : hour, minute), false, false);
            // 오후 12시 = 낮 12시
            case PM -> new TimeResult(LocalTime.of(hour == 12 ? 12 : hour + 12, minute), false, false);
            // 밤 12시 = 다음 날 00시, 밤 1~4시 = 다음 날 새벽
            case NIGHT -> hour == 12 || hour <= 4 ? new TimeResult(LocalTime.of(hour % 12, minute), false, true)
                    : new TimeResult(LocalTime.of(hour + 12, minute), false, false);
            // 점심·낮: 12시는 그대로, 1~5시는 오후
            case NOON -> new TimeResult(LocalTime.of(hour == 12 || hour >= 6 ? hour : hour + 12, minute), false,
                    false);
        };
    }

    /** 단서가 하나로 정해질 때만 쓴다 (아침·저녁이 함께 있으면 모른다) */
    private static Period periodOf(String text) {
        if (text == null) {
            return null;
        }
        List<Period> found = new ArrayList<>();
        if (AM.matcher(text).find()) {
            found.add(Period.AM);
        }
        if (PM.matcher(text).find()) {
            found.add(Period.PM);
        }
        if (NIGHT.matcher(text).find()) {
            found.add(Period.NIGHT);
        }
        if (NOON.matcher(text).find()) {
            found.add(Period.NOON);
        }
        return found.size() == 1 ? found.get(0) : null;
    }

    private static int minuteOf(String minutes, String half) {
        if (half != null) {
            return 30;
        }
        return minutes == null ? 0 : Integer.parseInt(minutes);
    }
}
