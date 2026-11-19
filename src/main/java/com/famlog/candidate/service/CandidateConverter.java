package com.famlog.candidate.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.famlog.candidate.domain.RegistrationCandidate;
import com.famlog.common.time.FamlogTime;
import com.famlog.place.domain.Place;
import com.famlog.place.domain.PlaceCategory;
import com.famlog.schedule.dto.ScheduleDtos.ScheduleRequest;
import com.famlog.todo.domain.TodoKind;
import com.famlog.todo.dto.TodoDtos.CreateTodoRequest;

import tools.jackson.databind.JsonNode;

/**
 * 후보 값 → 등록 요청 (수정 없이 [등록], 사양서 6.2 ④⑤). 값이 모자라면 missing 에 채울 항목을 담고 요청은 null.
 * <ul>
 * <li>일정: 참석자 없으면 말한 사람, "ALL" 이면 지금 가족 전원. 같은 역할이 여러 명이면 골라야 한다(attendeeIds).
 * 시각 없으면 종일, 끝 시각 없으면 시작과 같게. 별칭 장소에 연결된 장소가 없으면 메모 "장소: …"</li>
 * <li>기념일: 매년 반복 종일 일정, 가족 전원, 다가오는 그 월·일 (음력·1주 전 알림은 v1.1)</li>
 * <li>할 일: 담당자는 한 명. 여러 명·가족 전체·골라야 하면 assigneeIds 를 채워야 한다</li>
 * <li>장소: 카카오 장소가 정해져 있어야 한다 (후보가 여럿이거나 없으면 kakaoPlaceId)</li>
 * </ul>
 * 지금 구성원이 아닌 사람은 뺀다.
 */
final class CandidateConverter {

    static final String ALL_MEMBERS = "ALL";

    private CandidateConverter() {
    }

    /** 장소 승인 값 (후보의 options 중 하나) */
    record PlaceDraft(Place.KakaoInfo info, PlaceCategory category, String oneLineReview) {
    }

    record Converted(ScheduleRequest schedule, CreateTodoRequest todo, PlaceDraft place, List<String> missing) {

        boolean complete() {
            return missing.isEmpty();
        }
    }

    /**
     * @param members  지금 가족 (들어온 순)
     * @param senderId 기준 메시지(마지막 근거)를 보낸 사람
     */
    static Converted convert(RegistrationCandidate candidate, JsonNode f, List<Long> members, long senderId,
            LocalDate today) {
        List<String> missing = new ArrayList<>(List.of(candidate.getMissingFields()));
        return switch (candidate.getType()) {
            case SCHEDULE -> schedule(f, members, senderId, missing);
            case ANNIVERSARY -> anniversary(f, members, today, missing);
            case TODO -> todo(f, members, missing);
            case PLACE -> place(f, missing);
        };
    }

    private static Converted schedule(JsonNode f, List<Long> members, long senderId, List<String> missing) {
        Set<Long> participants = people(f.path("attendeeIds"), members);
        if (!f.path("attendeeChoices").isEmpty()) {
            missing.add("attendeeIds");
        }
        if (participants.isEmpty() && members.contains(senderId)) {
            participants.add(senderId);
        }
        if (participants.isEmpty()) {
            missing.add("attendeeIds");
        }
        String title = text(f, "title");
        String date = text(f, "date");
        if (!missing.isEmpty() || title == null || date == null) {
            return incomplete(missing);
        }
        LocalDate day = LocalDate.parse(date);
        String start = text(f, "startTime");
        JsonNode location = f.path("location");
        Long placeId = location.path("placeId").isNumber() ? location.path("placeId").asLong() : null;
        String locationText = text(location, "text");
        String memo = placeId == null && locationText != null ? "장소: " + locationText : null;
        ScheduleRequest request;
        if (start == null) {
            request = new ScheduleRequest(title, true, null, null, day, day, participants, null, memo, null, null,
                    placeId);
        } else {
            OffsetDateTime startsAt = at(day, start);
            String end = text(f, "endTime");
            request = new ScheduleRequest(title, false, startsAt, end == null ? startsAt : at(day, end), null, null,
                    participants, null, memo, null, null, placeId);
        }
        return new Converted(request, null, null, missing);
    }

    private static Converted anniversary(JsonNode f, List<Long> members, LocalDate today, List<String> missing) {
        String title = text(f, "title");
        if (!missing.isEmpty() || title == null || !f.path("month").isNumber() || members.isEmpty()) {
            return incomplete(missing);
        }
        MonthDay monthDay = MonthDay.of(f.path("month").asInt(), f.path("day").asInt());
        LocalDate next = monthDay.atYear(today.getYear());
        if (next.isBefore(today)) {
            next = monthDay.atYear(today.getYear() + 1);
        }
        String person = text(f, "personText");
        return new Converted(new ScheduleRequest(title, true, null, null, next, next,
                new LinkedHashSet<>(members), "FREQ=YEARLY", person == null ? null : "대상: " + person, null, null,
                null), null, null, missing);
    }

    private static Converted todo(JsonNode f, List<Long> members, List<String> missing) {
        JsonNode ids = f.path("assigneeIds");
        Set<Long> assignees = people(ids, members);
        boolean everyone = ids.isArray() && ids.size() > 0 && ALL_MEMBERS.equals(ids.get(0).asString());
        if (everyone || assignees.size() > 1 || !f.path("assigneeChoices").isEmpty()) {
            missing.add("assigneeIds");
        }
        String title = text(f, "title");
        if (!missing.isEmpty() || title == null) {
            return incomplete(missing);
        }
        String due = text(f, "dueDate");
        return new Converted(null, new CreateTodoRequest(title, assignees.isEmpty() ? null : assignees.iterator()
                .next(), due == null ? null : LocalDate.parse(due), TodoKind.TASK), null, missing);
    }

    private static Converted place(JsonNode f, List<String> missing) {
        JsonNode options = f.path("options");
        String chosen = text(f, "kakaoPlaceId");
        if (chosen == null && options.size() == 1) {
            chosen = text(options.get(0), "kakaoPlaceId");
        }
        if (chosen == null) {
            missing.add("kakaoPlaceId");
        }
        if (!missing.isEmpty()) {
            return incomplete(missing);
        }
        JsonNode option = option(options, chosen);
        return new Converted(null, null, new PlaceDraft(info(option), PlaceCategory.valueOf(text(option, "category")),
                text(f, "opinion")), missing);
    }

    /** 후보의 options 중 kakaoPlaceId 가 같은 것 (없으면 null) */
    static JsonNode option(JsonNode options, String kakaoPlaceId) {
        for (JsonNode option : options) {
            if (kakaoPlaceId.equals(text(option, "kakaoPlaceId"))) {
                return option;
            }
        }
        return null;
    }

    static Place.KakaoInfo info(JsonNode o) {
        return new Place.KakaoInfo(text(o, "kakaoPlaceId"), text(o, "name"), text(o, "categoryDetail"),
                text(o, "address"), text(o, "region1"), text(o, "region2"), text(o, "region3"),
                o.path("lat").asDouble(), o.path("lng").asDouble(), text(o, "phone"), text(o, "placeUrl"));
    }

    /** 사용자 ID 목록 ("ALL" 이면 가족 전원). 지금 가족만 */
    private static Set<Long> people(JsonNode ids, List<Long> members) {
        Set<Long> people = new LinkedHashSet<>();
        for (JsonNode id : ids) {
            if (id.isString() && ALL_MEMBERS.equals(id.asString())) {
                people.addAll(members);
            } else if (id.isNumber() && members.contains(id.asLong())) {
                people.add(id.asLong());
            }
        }
        return people;
    }

    private static OffsetDateTime at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(FamlogTime.DISPLAY_ZONE).toOffsetDateTime();
    }

    private static Converted incomplete(List<String> missing) {
        if (missing.isEmpty()) {
            missing.add("fields");
        }
        return new Converted(null, null, null, List.copyOf(new LinkedHashSet<>(missing)));
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
