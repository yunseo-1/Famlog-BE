package com.famlog.candidate.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.famlog.candidate.domain.CandidateType;
import com.famlog.candidate.normalize.DateTimeNormalizer;
import com.famlog.candidate.normalize.TimeResult;
import com.famlog.candidate.service.CandidateBuilder.Built;
import com.famlog.common.source.ItemSources;
import com.famlog.common.time.FamlogTime;

/**
 * 기존 항목 변경·완료 후보의 대상 찾기 (사양서 6.4 변경 요청, ARC-04 완료 표현, S19-2). LLM 에는 항목 목록을 보내지 않고 여기서 찾는다.
 * <ul>
 * <li>일정 변경: 앞으로 {@value #SCHEDULE_DAYS}일 안의 반복 아닌 일정 중 말한 것(핵심어·바꾸기 전 날짜·시각)이 모두 맞는 것.
 * 아무것도 말하지 않았으면 최근 대화에서 승인한 후보의 일정. 여럿이면 그중 최근 대화의 일정을 먼저 본다</li>
 * <li>할 일 완료: 끝나지 않은 할 일 중 핵심어가 제목에 모두 들어 있는 것</li>
 * <li>못 찾으면 후보를 만들지 않는다 (AR-8). 여럿이면 {@value #MAX_CHOICES}개까지 고르게 하고(missingFields targetId), 더 많으면 만들지 않는다</li>
 * <li>일정은 날짜·시각만 바꾼다. 시간 일정은 길이를 그대로 두고, 종일 일정에 시각을 정하면 그 시각의 시간 일정이 된다.
 * 오전·오후를 추정한 시각은 바꾸기 전 시각에 가까운 쪽으로 고른다</li>
 * </ul>
 * target_ref 형태는 HANDOFF "후보 API 규칙 (S19-2)" 와 같다.
 */
public final class CandidateTargets {

    public static final int SCHEDULE_DAYS = 60;
    public static final int MAX_CHOICES = 5;
    public static final String CHANGE = "CHANGE";
    public static final String COMPLETE = "COMPLETE";
    public static final String MISSING_TARGET = "targetId";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    /** 핵심어에서 빼고 보는 동작 명사 */
    private static final Set<String> ACTION_WORDS = Set.of("예약", "납부", "구매", "구입", "주문", "완료", "처리", "신청",
            "하기", "가기", "사기", "보내기", "맡기기");

    private CandidateTargets() {
    }

    /**
     * LLM 이 말한 대상과 바꿀 값 (정규화한 것)
     *
     * @param action    CHANGE(일정) 또는 COMPLETE(할 일)
     * @param keyword   어떤 항목인지 핵심어 (없으면 null)
     * @param fromDate  바꾸기 전 날짜, fromTime 바꾸기 전 시각
     * @param date      바꿀 날짜, startTime 바꿀 시각 (안 바꾸면 null)
     * @param inferred  추정 표시 (바꿀 날짜·시각)
     * @param weekday   바꿀 날짜를 요일로만 말했으면("목요일로") 그 요일. 대상 일정이 있는 주(월~일)의 그 요일로 본다
     */
    public record Query(String action, String keyword, LocalDate fromDate, TimeResult fromTime, LocalDate date,
            TimeResult startTime, List<String> inferred, DayOfWeek weekday) {
    }

    /** 대상이 될 수 있는 그룹 항목 */
    public record Items(List<ScheduleItem> schedules, List<TodoItem> todos, Set<Long> recentScheduleIds) {

        public static final Items EMPTY = new Items(List.of(), List.of(), Set.of());
    }

    /** @param createdBy 등록한 사람 (변경 승인은 이 사람이나 ADMIN 이상만) */
    public record ScheduleItem(long id, String title, Instant startsAt, Instant endsAt, boolean allDay,
            long createdBy) {
    }

    public record TodoItem(long id, String title, Long assigneeId, LocalDate dueDate) {
    }

    /** 일정 시각 (target_ref 의 before·after 값) */
    public record Timing(LocalDate date, LocalTime startTime, LocalTime endTime, boolean allDay, Instant startsAt,
            Instant endsAt) {

        static Timing of(Instant startsAt, Instant endsAt, boolean allDay) {
            ZonedDateTime start = startsAt.atZone(FamlogTime.DISPLAY_ZONE);
            ZonedDateTime end = endsAt.atZone(FamlogTime.DISPLAY_ZONE);
            return new Timing(start.toLocalDate(), allDay ? null : start.toLocalTime(),
                    allDay ? null : end.toLocalTime(), allDay, startsAt, endsAt);
        }

        /** {date, startTime, endTime, allDay, startsAt, endsAt} (종일은 endsAt 이 마지막 날 다음 날 0시) */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("date", date.toString());
            map.put("startTime", startTime == null ? null : startTime.format(HH_MM));
            map.put("endTime", endTime == null ? null : endTime.format(HH_MM));
            map.put("allDay", allDay);
            map.put("startsAt", startsAt.toString());
            map.put("endsAt", endsAt.toString());
            return map;
        }
    }

    /** 대상을 찾아 fields·target 을 채운 후보. 못 찾으면 빈 값 */
    public static Optional<Built> resolve(Built built, Items items, Instant now) {
        Query query = built.query();
        return built.type() == CandidateType.SCHEDULE ? schedule(built, query, items, now)
                : todo(built, query, items, now);
    }

    private static Optional<Built> schedule(Built built, Query q, Items items, Instant now) {
        boolean said = q.keyword() != null || q.fromDate() != null || q.fromTime() != null;
        List<ScheduleItem> found = items.schedules().stream()
                .filter(s -> said ? keywordMatches(q.keyword(), s.title()) && fromMatches(q, s)
                        : items.recentScheduleIds().contains(s.id()))
                .toList();
        if (found.size() > 1) {
            List<ScheduleItem> recent = found.stream().filter(s -> items.recentScheduleIds().contains(s.id()))
                    .toList();
            found = recent.isEmpty() ? found : recent;
        }
        List<Map<String, Object>> choices = new ArrayList<>();
        Instant expiresAt = now.plus(CandidateBuilder.DEFAULT_TTL);
        Map<String, Object> single = null;
        for (ScheduleItem s : found) {
            Timing before = Timing.of(s.startsAt(), s.endsAt(), s.allDay());
            Optional<Timing> after = after(before, q).filter(a -> a.startsAt().isAfter(now));
            if (after.isEmpty()) {
                continue;
            }
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("entityId", s.id());
            choice.put("title", s.title());
            choice.put("createdBy", s.createdBy());
            choice.put("before", before.toMap());
            choice.put("after", after.get().toMap());
            choices.add(choice);
            if (single == null) {
                single = choice;
                expiresAt = min(expiresAt, min(s.startsAt(), after.get().startsAt()));
            }
        }
        if (choices.isEmpty() || choices.size() > MAX_CHOICES) {
            return Optional.empty();
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        if (choices.size() == 1) {
            fields.put("title", single.get("title"));
            fields.putAll(withoutInstants(single.get("after")));
        } else {
            fields.put("title", q.keyword());
            missing.add(MISSING_TARGET);
            expiresAt = now.plus(CandidateBuilder.DEFAULT_TTL);
        }
        List<Long> ids = choices.stream().map(c -> (Long) c.get("entityId")).toList();
        String key = DedupKeys.target(CandidateType.SCHEDULE, CHANGE, ids, (q.date() == null ? "" : q.date()) + "|"
                + (q.startTime() == null ? "" : q.startTime().time()));
        return Optional.of(withTarget(built, fields, q.inferred(), missing, key, expiresAt,
                target(ItemSources.SCHEDULE, CHANGE, choices)));
    }

    private static Optional<Built> todo(Built built, Query q, Items items, Instant now) {
        List<Map<String, Object>> choices = new ArrayList<>();
        for (TodoItem t : items.todos()) {
            if (!keywordMatches(q.keyword(), t.title())) {
                continue;
            }
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("assigneeId", t.assigneeId());
            before.put("dueDate", t.dueDate() == null ? null : t.dueDate().toString());
            before.put("done", false);
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("entityId", t.id());
            choice.put("title", t.title());
            choice.put("before", before);
            choices.add(choice);
        }
        if (choices.isEmpty() || choices.size() > MAX_CHOICES) {
            return Optional.empty();
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        fields.put("title", choices.size() == 1 ? choices.get(0).get("title") : q.keyword());
        if (choices.size() > 1) {
            missing.add(MISSING_TARGET);
        }
        List<Long> ids = choices.stream().map(c -> (Long) c.get("entityId")).toList();
        return Optional.of(withTarget(built, fields, List.of(), missing,
                DedupKeys.target(CandidateType.TODO, COMPLETE, ids, ""), now.plus(CandidateBuilder.DEFAULT_TTL),
                target(ItemSources.TODO, COMPLETE, choices)));
    }

    /**
     * {entityType, action, entityId, title, createdBy?, before, after?, choices}. 일정은 createdBy(등록한 사람)도 준다.
     * 하나면 entityId·title·before(·after)를 채우고,
     * 여럿이면 entityId 는 null 이고 choices 에서 고른다 (choices 는 언제나 모든 후보)
     */
    private static Map<String, Object> target(String entityType, String action, List<Map<String, Object>> choices) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("entityType", entityType);
        target.put("action", action);
        Map<String, Object> only = choices.size() == 1 ? choices.get(0) : Map.of();
        target.put("entityId", only.get("entityId"));
        target.put("title", only.get("title"));
        if (ItemSources.SCHEDULE.equals(entityType)) {
            target.put("createdBy", only.get("createdBy"));
        }
        target.put("before", only.get("before"));
        if (CHANGE.equals(action)) {
            target.put("after", only.get("after"));
        }
        target.put("choices", choices);
        return target;
    }

    private static Built withTarget(Built built, Map<String, Object> fields, List<String> inferred,
            List<String> missing, String dedupKey, Instant expiresAt, Map<String, Object> target) {
        return new Built(built.type(), fields, inferred, missing, built.confidence(), dedupKey, expiresAt, null,
                target);
    }

    /** 바꾼 뒤 시각. 바뀌는 것이 없거나 정할 수 없으면 빈 값 */
    static Optional<Timing> after(Timing before, Query q) {
        LocalDate date = q.weekday() != null ? before.date().with(ChronoField.DAY_OF_WEEK, q.weekday().getValue())
                : q.date() != null ? q.date() : before.date();
        Instant start;
        Instant end;
        boolean allDay;
        if (before.allDay()) {
            long days = ChronoUnit.DAYS.between(before.date(), LocalDate.ofInstant(before.endsAt(),
                    FamlogTime.DISPLAY_ZONE));
            if (q.startTime() != null) {
                if (days > 1) {
                    return Optional.empty();
                }
                start = date.atTime(q.startTime().time()).atZone(FamlogTime.DISPLAY_ZONE).toInstant();
                end = start;
                allDay = false;
            } else {
                start = date.atStartOfDay(FamlogTime.DISPLAY_ZONE).toInstant();
                end = date.plusDays(days).atStartOfDay(FamlogTime.DISPLAY_ZONE).toInstant();
                allDay = true;
            }
        } else {
            LocalTime time = q.startTime() == null ? before.startTime() : closest(q.startTime(), before.startTime());
            start = date.atTime(time).atZone(FamlogTime.DISPLAY_ZONE).toInstant();
            end = start.plus(Duration.between(before.startsAt(), before.endsAt()));
            allDay = false;
        }
        if (start.equals(before.startsAt()) && allDay == before.allDay()) {
            return Optional.empty();
        }
        return Optional.of(Timing.of(start, end, allDay));
    }

    /** 오전·오후를 추정했으면 바꾸기 전 시각에 가까운 쪽 ("6시 말고 7시" → 저녁 6시면 저녁 7시) */
    private static LocalTime closest(TimeResult time, LocalTime before) {
        if (!time.ampmInferred()) {
            return time.time();
        }
        LocalTime other = time.time().plusHours(12);
        return minutesApart(other, before) < minutesApart(time.time(), before) ? other : time.time();
    }

    private static long minutesApart(LocalTime a, LocalTime b) {
        long diff = Math.abs(Duration.between(a, b).toMinutes());
        return Math.min(diff, 24 * 60 - diff);
    }

    private static boolean fromMatches(Query q, ScheduleItem s) {
        ZonedDateTime start = s.startsAt().atZone(FamlogTime.DISPLAY_ZONE);
        if (q.fromDate() != null && !q.fromDate().equals(start.toLocalDate())) {
            return false;
        }
        if (q.fromTime() == null) {
            return true;
        }
        LocalTime at = start.toLocalTime();
        LocalTime said = q.fromTime().time();
        return !s.allDay() && (at.equals(said) || (q.fromTime().ampmInferred() && at.equals(said.plusHours(12))));
    }

    /**
     * 핵심어를 띄어 쓴 조각이 모두 제목에 들어 있거나, 제목이 핵심어에 들어 있다 (공백·문장부호·대소문자 무시). 핵심어가 없으면 참.
     * 동작을 뜻하는 조각("예약", "납부" 등)은 다른 조각이 있으면 빼고 본다 ("치과 예약" → 치과, "관리비 납부" → 관리비)
     */
    static boolean keywordMatches(String keyword, String title) {
        if (keyword == null) {
            return true;
        }
        String normalizedTitle = DedupKeys.normalize(title);
        List<String> parts = Arrays.stream(keyword.split("\\s+")).map(DedupKeys::normalize)
                .filter(p -> !p.isEmpty()).toList();
        List<String> objects = parts.stream().filter(p -> !ACTION_WORDS.contains(p)).toList();
        List<String> wanted = objects.isEmpty() ? parts : objects;
        return !wanted.isEmpty() && (wanted.stream().allMatch(normalizedTitle::contains)
                || (!normalizedTitle.isEmpty() && DedupKeys.normalize(keyword).contains(normalizedTitle)));
    }

    /** 응답용 after 값 (startsAt·endsAt 는 target 에만 둔다) */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> withoutInstants(Object timing) {
        Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) timing);
        copy.remove("startsAt");
        copy.remove("endsAt");
        return copy;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    static List<String> inferredOf(TimeResult startTime, boolean dateInferred) {
        List<String> inferred = new ArrayList<>();
        if (dateInferred) {
            inferred.add(DateTimeNormalizer.INFERRED_DATE);
        }
        if (startTime != null && startTime.ampmInferred()) {
            inferred.add(DateTimeNormalizer.INFERRED_AMPM);
        }
        return inferred;
    }
}
