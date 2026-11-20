package com.famlog.candidate.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.famlog.candidate.domain.CandidateType;
import com.famlog.candidate.dto.ExtractDtos.ExtractedCandidate;
import com.famlog.candidate.dto.ExtractDtos.ExtractedFields;
import com.famlog.candidate.normalize.DateNormalizer;
import com.famlog.candidate.normalize.DateResult;
import com.famlog.candidate.normalize.DateTimeNormalizer;
import com.famlog.candidate.normalize.MemberMatcher;
import com.famlog.candidate.normalize.MemberMatcher.MatchResult;
import com.famlog.candidate.normalize.TimeNormalizer;
import com.famlog.candidate.normalize.TimeResult;
import com.famlog.candidate.normalize.TimingResult;
import com.famlog.candidate.repository.CandidateEvidenceWriter.EvidenceMessage;
import com.famlog.common.time.FamlogTime;
import com.famlog.place.domain.PlaceAlias;

import lombok.RequiredArgsConstructor;

/**
 * LLM 이 뽑은 원문 표현을 후보 값으로 바꾼다 (사양서 6.2 ②, 6.4). 날짜 계산·구성원 매칭·장소 확정은 여기서 한다.
 * <ul>
 * <li>기준은 기준 메시지(anchor)의 발송 시각(Asia/Seoul)과 보낸 사람. 오전·오후 단서도 그 메시지에서 찾는다</li>
 * <li>신뢰도 0.5 미만, 지난 날짜의 일정·기한, 지난 일 표현의 기념일은 만들지 않는다</li>
 * <li>필수 항목이 비면 missingFields 에 넣고 만든다 (사용자가 채워야 등록된다)</li>
 * <li>만료: 일정은 일정 시각(종일이면 다음 날 0시), 나머지와 날짜 없는 일정은 7일 뒤</li>
 * </ul>
 * fields 형태는 HANDOFF "후보 생성 규칙 (S18)" 과 같다.
 */
@Component
@RequiredArgsConstructor
public class CandidateBuilder {

    public static final double MIN_CONFIDENCE = 0.5;
    static final Duration DEFAULT_TTL = Duration.ofDays(7);
    static final String ALL_MEMBERS = "ALL";
    static final String INFERRED_ATTENDEES = "attendeeIds";
    static final String INFERRED_ASSIGNEES = "assigneeIds";
    static final String INFERRED_DUE = "dueDate";
    private static final int MAX_OPINION = 200;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    /** 주 없이 요일만 말한 날짜 ("목요일", "목요일로") */
    private static final Pattern BARE_WEEKDAY = Pattern.compile("\\s*([월화수목금토일])요일\\s*");
    private static final List<DayOfWeek> WEEKDAYS = List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private final CandidatePlaceResolver placeResolver;

    /**
     * 정규화한 후보 (저장 전).
     *
     * @param query  기존 항목 변경·완료 표현이면 찾을 대상 (CandidateTargets 가 target 으로 바꾼다)
     * @param target 찾은 대상 (target_ref)
     */
    public record Built(CandidateType type, Map<String, Object> fields, List<String> inferred, List<String> missing,
            double confidence, String dedupKey, Instant expiresAt, CandidateTargets.Query query,
            Map<String, Object> target) {

        Built(CandidateType type, Map<String, Object> fields, List<String> inferred, List<String> missing,
                double confidence, String dedupKey, Instant expiresAt) {
            this(type, fields, inferred, missing, confidence, dedupKey, expiresAt, null, null);
        }
    }

    /** 후보를 만들 때 쓰는 그룹 정보 */
    public record Context(List<MemberMatcher.Member> members, List<PlaceAlias> aliases, Instant now) {
    }

    public Optional<Built> build(ExtractedCandidate candidate, EvidenceMessage anchor, Context context) {
        if (candidate.type() == null || candidate.confidence() < MIN_CONFIDENCE || candidate.confidence() > 1
                || anchor.senderId() == null) {
            return Optional.empty();
        }
        ExtractedFields fields = candidate.fields() == null
                ? new ExtractedFields(null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null)
                : candidate.fields();
        ZonedDateTime sentAt = anchor.sentAt().atZone(FamlogTime.DISPLAY_ZONE);
        return switch (candidate.type()) {
            case SCHEDULE -> schedule(fields, anchor, sentAt, context, candidate.confidence());
            case TODO -> todo(fields, anchor, sentAt, context, candidate.confidence());
            case ANNIVERSARY -> anniversary(fields, sentAt, context, candidate.confidence());
            case PLACE -> place(fields, anchor, context, candidate.confidence());
            case SCHEDULE_CHANGE -> scheduleChange(fields, anchor, sentAt, context, candidate.confidence());
            case TODO_DONE -> todoDone(fields, context, candidate.confidence());
        };
    }

    /** 날짜 표현이 들어 있는 근거 메시지를 기준으로 삼는다. 없으면 첫 근거 */
    public static EvidenceMessage anchorOf(ExtractedCandidate candidate, List<EvidenceMessage> evidence) {
        ExtractedFields fields = candidate.fields();
        if (fields != null) {
            for (String text : new String[] { fields.dateText(), fields.dueText(), fields.startTimeText(),
                    fields.name() }) {
                if (text == null || text.isBlank()) {
                    continue;
                }
                Optional<EvidenceMessage> found = evidence.stream()
                        .filter(m -> m.body() != null && m.body().contains(text.strip())).findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            }
        }
        return evidence.get(0);
    }

    private Optional<Built> schedule(ExtractedFields f, EvidenceMessage anchor, ZonedDateTime sentAt,
            Context context, double confidence) {
        List<String> inferred = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        String title = text(f.title());
        if (title == null) {
            missing.add("title");
        }
        LocalDate date = null;
        LocalTime startTime = null;
        switch (DateTimeNormalizer.schedule(f.dateText(), f.startTimeText(), anchor.body(), sentAt)) {
            case TimingResult.Past past -> {
                return Optional.empty();
            }
            case TimingResult.Unknown unknown -> {
                missing.add("date");
                Optional<TimeResult> time = TimeNormalizer.resolve(f.startTimeText(), anchor.body())
                        .filter(t -> !t.nextDay());
                startTime = time.map(TimeResult::time).orElse(null);
                time.filter(TimeResult::ampmInferred).ifPresent(t -> inferred.add(DateTimeNormalizer.INFERRED_AMPM));
            }
            case TimingResult.Resolved resolved -> {
                date = resolved.date();
                startTime = resolved.startTime();
                resolved.inferred().stream().sorted().forEach(inferred::add);
            }
        }
        LocalTime endTime = startTime == null ? null
                : TimeNormalizer.resolve(f.endTimeText(), anchor.body())
                        .filter(t -> !t.nextDay())
                        .map(TimeResult::time)
                        .filter(startTime::isBefore)
                        .orElse(null);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("date", date == null ? null : date.toString());
        fields.put("startTime", startTime == null ? null : startTime.format(HH_MM));
        fields.put("endTime", endTime == null ? null : endTime.format(HH_MM));
        fields.put("allDay", startTime == null);
        fields.put("location", location(f.locationText(), context));
        putMembers(fields, "attendee", MemberMatcher.match(f.attendeeTexts(), anchor.senderId(), context.members()),
                inferred, INFERRED_ATTENDEES);

        Instant expiresAt = date == null ? context.now().plus(DEFAULT_TTL)
                : startTime == null ? date.plusDays(1).atStartOfDay(FamlogTime.DISPLAY_ZONE).toInstant()
                        : date.atTime(startTime).atZone(FamlogTime.DISPLAY_ZONE).toInstant();
        return Optional.of(new Built(CandidateType.SCHEDULE, fields, inferred, missing, confidence,
                DedupKeys.of(CandidateType.SCHEDULE, title, date), expiresAt));
    }

    private Optional<Built> todo(ExtractedFields f, EvidenceMessage anchor, ZonedDateTime sentAt, Context context,
            double confidence) {
        List<String> inferred = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        String title = text(f.title());
        if (title == null) {
            missing.add("title");
        }
        LocalDate due = null;
        if (text(f.dueText()) != null) {
            switch (DateNormalizer.deadline(f.dueText(), sentAt)) {
                case DateResult.Past past -> {
                    return Optional.empty();
                }
                case DateResult.Unknown unknown -> {
                }
                case DateResult.Resolved resolved -> {
                    due = resolved.date();
                    if (resolved.inferred()) {
                        inferred.add(INFERRED_DUE);
                    }
                }
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("dueDate", due == null ? null : due.toString());
        putMembers(fields, "assignee", MemberMatcher.match(f.assigneeTexts(), anchor.senderId(), context.members()),
                inferred, INFERRED_ASSIGNEES);
        return Optional.of(new Built(CandidateType.TODO, fields, inferred, missing, confidence,
                DedupKeys.of(CandidateType.TODO, title, due), context.now().plus(DEFAULT_TTL)));
    }

    private Optional<Built> anniversary(ExtractedFields f, ZonedDateTime sentAt, Context context,
            double confidence) {
        List<String> inferred = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        String title = text(f.title());
        if (title == null) {
            missing.add("title");
        }
        MonthDay monthDay = null;
        switch (DateNormalizer.scheduleDate(f.dateText(), sentAt, null)) {
            case DateResult.Past past -> {
                return Optional.empty();
            }
            case DateResult.Unknown unknown -> missing.add("date");
            case DateResult.Resolved resolved -> {
                monthDay = MonthDay.from(resolved.date());
                if (resolved.inferred()) {
                    inferred.add(DateTimeNormalizer.INFERRED_DATE);
                }
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("month", monthDay == null ? null : monthDay.getMonthValue());
        fields.put("day", monthDay == null ? null : monthDay.getDayOfMonth());
        fields.put("personText", text(f.personText()));
        return Optional.of(new Built(CandidateType.ANNIVERSARY, fields, inferred, missing, confidence,
                DedupKeys.anniversary(title, monthDay), context.now().plus(DEFAULT_TTL)));
    }

    private Optional<Built> place(ExtractedFields f, EvidenceMessage anchor, Context context, double confidence) {
        List<String> missing = new ArrayList<>();
        String name = text(f.name());
        String regionHint = text(f.regionHint());
        List<Map<String, Object>> options = List.of();
        if (name == null) {
            missing.add("name");
        } else {
            options = placeResolver.search(name, regionHint);
        }
        String kakaoPlaceId = options.size() == 1 ? (String) options.get(0).get("kakaoPlaceId") : null;
        String opinion = text(f.opinion());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", name);
        fields.put("regionHint", regionHint);
        fields.put("kakaoPlaceId", kakaoPlaceId);
        fields.put("options", options);
        fields.put("opinion", opinion == null || opinion.length() <= MAX_OPINION ? opinion
                : opinion.substring(0, MAX_OPINION));
        fields.put("recommendedBy", anchor.senderId());
        return Optional.of(new Built(CandidateType.PLACE, fields, List.of(), missing, confidence,
                DedupKeys.place(kakaoPlaceId, name), context.now().plus(DEFAULT_TTL)));
    }

    /**
     * 일정 변경 표현 ("11시 말고 12시로"). 바꿀 날짜·시각이 없거나 바꿀 날짜가 지났으면 만들지 않는다.
     * 대상은 아직 모른다 (CandidateTargets)
     */
    private Optional<Built> scheduleChange(ExtractedFields f, EvidenceMessage anchor, ZonedDateTime sentAt,
            Context context, double confidence) {
        LocalDate fromDate = DateNormalizer.scheduleDate(f.fromDateText(), sentAt, null)
                instanceof DateResult.Resolved resolved ? resolved.date() : null;
        TimeResult fromTime = TimeNormalizer.resolve(f.fromTimeText(), anchor.body()).filter(t -> !t.nextDay())
                .orElse(null);
        LocalDate date = null;
        boolean dateInferred = false;
        if (text(f.dateText()) != null) {
            switch (DateNormalizer.scheduleDate(f.dateText(), sentAt, null)) {
                case DateResult.Past past -> {
                    return Optional.empty();
                }
                case DateResult.Unknown unknown -> {
                }
                case DateResult.Resolved resolved -> {
                    date = resolved.date();
                    dateInferred = resolved.inferred();
                }
            }
        }
        TimeResult startTime = TimeNormalizer.resolve(f.startTimeText(), anchor.body()).filter(t -> !t.nextDay())
                .orElse(null);
        if (date == null && startTime == null) {
            return Optional.empty();
        }
        Matcher weekday = BARE_WEEKDAY.matcher(f.dateText() == null ? "" : f.dateText());
        CandidateTargets.Query query = new CandidateTargets.Query(CandidateTargets.CHANGE, text(f.title()),
                fromDate, fromTime, date, startTime, CandidateTargets.inferredOf(startTime, dateInferred),
                weekday.matches() ? WEEKDAYS.get("월화수목금토일".indexOf(weekday.group(1))) : null);
        return Optional.of(new Built(CandidateType.SCHEDULE, Map.of(), List.of(), List.of(), confidence, "",
                context.now().plus(DEFAULT_TTL), query, null));
    }

    /** 할 일 완료 표현 ("세제 샀어"). 핵심어가 없으면 만들지 않는다. 대상은 아직 모른다 (CandidateTargets) */
    private Optional<Built> todoDone(ExtractedFields f, Context context, double confidence) {
        String keyword = text(f.title());
        if (keyword == null) {
            return Optional.empty();
        }
        CandidateTargets.Query query = new CandidateTargets.Query(CandidateTargets.COMPLETE, keyword, null, null,
                null, null, List.of(), null);
        return Optional.of(new Built(CandidateType.TODO, Map.of(), List.of(), List.of(), confidence, "",
                context.now().plus(DEFAULT_TTL), query, null));
    }

    /** {text, aliasId, placeId}. 별칭과 맞으면 그 별칭과 연결된 장소 */
    private Map<String, Object> location(String locationText, Context context) {
        String text = text(locationText);
        if (text == null) {
            return null;
        }
        Optional<PlaceAlias> alias = placeResolver.matchAlias(text, context.aliases());
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("text", text);
        location.put("aliasId", alias.map(PlaceAlias::getId).orElse(null));
        location.put("placeId", alias.map(PlaceAlias::getPlaceId).orElse(null));
        return location;
    }

    /**
     * {prefix}Ids: 정해진 구성원, 가족 전체면 ["ALL"](추정). {prefix}Choices: 같은 역할이 여러 명이라 고를 묶음.
     * unmatched{Prefix}s: 찾지 못한 표현 (글자로 보여 준다)
     */
    private static void putMembers(Map<String, Object> fields, String prefix, MatchResult match,
            List<String> inferred, String inferredKey) {
        if (match.everyone()) {
            fields.put(prefix + "Ids", List.of(ALL_MEMBERS));
            inferred.add(inferredKey);
        } else {
            fields.put(prefix + "Ids", match.userIds());
        }
        fields.put(prefix + "Choices", match.choices());
        fields.put("unmatched" + Character.toUpperCase(prefix.charAt(0)) + prefix.substring(1) + "s",
                match.unmatched());
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
