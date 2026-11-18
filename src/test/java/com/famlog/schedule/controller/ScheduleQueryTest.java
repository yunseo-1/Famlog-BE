package com.famlog.schedule.controller;

import static com.famlog.support.ScheduleApi.allDay;
import static com.famlog.support.ScheduleApi.timed;
import static com.famlog.support.ScheduleApi.with;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isA;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.famlog.support.Family;
import com.famlog.support.IntegrationTest;
import com.famlog.support.ScheduleApi;

/** 기간 조회·구성원/역할 필터 (사양서 4.3 SCH-02, 18.1 SCH-02)와 겹침 조회 (SCH-05). */
class ScheduleQueryTest extends IntegrationTest {

    private static final String LIST = "/api/v1/groups/{g}/schedules";
    private static final String CONFLICTS = "/api/v1/groups/{g}/schedules/conflicts";

    private Family family;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
    }

    @Test
    void 인수기준_SCH02_엄마_필터는_엄마_일정_3개_전체는_5개() throws Exception {
        for (int day = 1; day <= 3; day++) {
            create(family.mom(), timed("엄마 일정 " + day, "2026-11-0%dT10:00".formatted(day),
                    "2026-11-0%dT11:00".formatted(day), family.mom()));
        }
        for (int day = 4; day <= 5; day++) {
            create(family.dad(), timed("아빠 일정 " + day, "2026-11-0%dT10:00".formatted(day),
                    "2026-11-0%dT11:00".formatted(day), family.dad()));
        }

        list(november().param("roles", "MOTHER"))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[*].title", contains("엄마 일정 1", "엄마 일정 2", "엄마 일정 3")));
        list(november()).andExpect(jsonPath("$.items", hasSize(5)));
        list(november().param("memberIds", Long.toString(family.dad())))
                .andExpect(jsonPath("$.items[*].title", contains("아빠 일정 4", "아빠 일정 5")));
    }

    @Test
    void 구성원과_역할_필터는_합집합이고_같이_참여한_일정은_양쪽에_보인다() throws Exception {
        create(family.mom(), timed("엄마 혼자", "2026-11-02T10:00", "2026-11-02T11:00", family.mom()));
        create(family.son(), timed("아들 혼자", "2026-11-03T10:00", "2026-11-03T11:00", family.son()));
        create(family.dad(), timed("아빠 혼자", "2026-11-04T10:00", "2026-11-04T11:00", family.dad()));
        create(family.mom(), timed("엄마와 아빠", "2026-11-05T10:00", "2026-11-05T11:00", family.mom(),
                family.dad()));

        list(november().param("roles", "MOTHER").param("memberIds", Long.toString(family.son())))
                .andExpect(jsonPath("$.items[*].title", contains("엄마 혼자", "아들 혼자", "엄마와 아빠")));
        list(november().param("roles", "FATHER"))
                .andExpect(jsonPath("$.items[*].title", contains("아빠 혼자", "엄마와 아빠")));
        list(november().param("roles", "MOTHER", "SON"))
                .andExpect(jsonPath("$.items[*].title", contains("엄마 혼자", "아들 혼자", "엄마와 아빠")));
        list(november().param("roles", "GRANDMOTHER")).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void 반복_일정은_기간_안의_회차로_펼쳐_시작_순으로_준다() throws Exception {
        long weekly = create(family.son(), with(timed("피아노", "2026-11-02T16:00", "2026-11-02T17:00", family.son()),
                "\"rrule\":\"FREQ=WEEKLY\""));
        create(family.mom(), timed("병원", "2026-11-09T10:00", "2026-11-09T11:00", family.mom()));

        list(november())
                .andExpect(jsonPath("$.items", hasSize(6)))
                .andExpect(jsonPath("$.items[*].startsAt", contains("2026-11-02T16:00:00+09:00",
                        "2026-11-09T10:00:00+09:00", "2026-11-09T16:00:00+09:00", "2026-11-16T16:00:00+09:00",
                        "2026-11-23T16:00:00+09:00", "2026-11-30T16:00:00+09:00")))
                .andExpect(jsonPath("$.items[2].scheduleId").value(weekly))
                .andExpect(jsonPath("$.items[2].recurring").value(true))
                .andExpect(jsonPath("$.items[2].endsAt").value("2026-11-09T17:00:00+09:00"))
                .andExpect(jsonPath("$.items[1].recurring").value(false));

        // 첫 회차 전 기간에는 없다
        list(get(LIST, family.groupId()).param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void 기간_경계에_걸친_일정과_종일_일정도_보인다() throws Exception {
        create(family.mom(), allDay("제주 여행", "2026-10-30", "2026-11-01", family.mom()));
        create(family.mom(), timed("밤샘", "2026-11-30T22:00", "2026-12-01T02:00", family.mom()));
        create(family.mom(), allDay("12월", "2026-12-01", "2026-12-01", family.mom()));

        list(november())
                .andExpect(jsonPath("$.items[*].title", contains("제주 여행", "밤샘")))
                .andExpect(jsonPath("$.items[0].startDate").value("2026-10-30"))
                .andExpect(jsonPath("$.items[0].endDate").value("2026-11-01"));
    }

    @Test
    void 조회_기간은_1일에서_100일까지다() throws Exception {
        list(get(LIST, family.groupId()).param("from", "2026-11-01").param("to", "2027-02-08"))
                .andExpect(status().isOk());
        for (String[] range : new String[][] { { "2026-11-01", "2027-02-09" }, { "2026-11-02", "2026-11-01" },
            { "2026-11-01", "11월 말" } }) {
            mockMvc.perform(get(LIST, family.groupId()).with(asUser(family.mom()))
                            .param("from", range[0]).param("to", range[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(get(LIST, family.groupId()).with(asUser(family.mom())).param("from", "2026-11-01")
                        .param("to", "2026-11-30").param("roles", "UNCLE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 겹침_조회는_요청한_구성원이_참여하는_겹치는_일정만_준다() throws Exception {
        long dinner = create(family.dad(), timed("회식", "2026-11-21T18:00", "2026-11-21T21:00", family.dad()));
        create(family.dad(), timed("붙어 있는 일정", "2026-11-21T21:00", "2026-11-21T22:00", family.dad()));
        create(family.mom(), timed("엄마 약속", "2026-11-21T19:00", "2026-11-21T20:00", family.mom()));
        long gym = create(family.son(), with(timed("운동", "2026-11-07T20:00", "2026-11-07T21:00", family.son(),
                family.dad()), "\"rrule\":\"FREQ=WEEKLY\""));

        conflicts("2026-11-21T20:00:00+09:00", "2026-11-21T21:00:00+09:00", family.dad())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].scheduleId", contains((int) dinner, (int) gym)))
                .andExpect(jsonPath("$.items[0].title").value("회식"))
                .andExpect(jsonPath("$.items[0].userIds", contains((int) family.dad())))
                .andExpect(jsonPath("$.items[1].startsAt").value("2026-11-21T20:00:00+09:00"));

        // 수정 중인 일정은 뺀다
        mockMvc.perform(get(CONFLICTS, family.groupId()).with(asUser(family.dad()))
                        .param("startsAt", "2026-11-21T20:00:00+09:00").param("endsAt", "2026-11-21T21:00:00+09:00")
                        .param("memberIds", Long.toString(family.dad())).param("excludeId", Long.toString(dinner)))
                .andExpect(jsonPath("$.items[*].scheduleId", contains((int) gym)));
        // 여러 구성원을 물으면 겹치는 사람을 함께 알려 준다
        conflicts("2026-11-21T19:30:00+09:00", "2026-11-21T20:30:00+09:00", family.dad(), family.mom())
                .andExpect(jsonPath("$.items[*].title", contains("회식", "엄마 약속", "운동")))
                .andExpect(jsonPath("$.items[1].userIds", contains((int) family.mom())));
        conflicts("2026-11-22T10:00:00+09:00", "2026-11-22T11:00:00+09:00", family.dad())
                .andExpect(jsonPath("$.items").isEmpty());
        conflicts("2026-11-21T20:00:00+09:00", "2026-11-21T20:00:00+09:00", family.dad())
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder november() {
        return get(LIST, family.groupId()).param("from", "2026-11-01").param("to", "2026-11-30");
    }

    private ResultActions list(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(asUser(family.son()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].allDay", everyItem(isA(Boolean.class))));
    }

    private ResultActions conflicts(String startsAt, String endsAt, long... memberIds) throws Exception {
        String[] ids = Arrays.stream(memberIds).mapToObj(Long::toString).toArray(String[]::new);
        return mockMvc.perform(get(CONFLICTS, family.groupId()).with(asUser(family.son()))
                .param("startsAt", startsAt).param("endsAt", endsAt).param("memberIds", ids));
    }

    private long create(long userId, String body) throws Exception {
        return ScheduleApi.create(mockMvc, family.groupId(), userId, body);
    }
}
