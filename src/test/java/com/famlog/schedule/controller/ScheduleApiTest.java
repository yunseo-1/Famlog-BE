package com.famlog.schedule.controller;

import static com.famlog.support.ScheduleApi.allDay;
import static com.famlog.support.ScheduleApi.timed;
import static com.famlog.support.ScheduleApi.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.famlog.support.Family;
import com.famlog.support.GroupApi;
import com.famlog.support.IntegrationTest;
import com.famlog.support.ScheduleApi;

/** 일정 등록·수정·삭제 (사양서 4.3 SCH-01, 2.2 권한, 9.3 인가, 11.2 VERSION_CONFLICT). */
class ScheduleApiTest extends IntegrationTest {

    private static final String ONE = "/api/v1/schedules/{id}";

    private Family family;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
    }

    @Test
    void 시간_일정을_등록하고_조회한다() throws Exception {
        String body = with(timed("할머니 병원", "2026-11-21T10:00", "2026-11-21T11:30", family.mom(), family.dad()),
                "\"memo\":\"  진료 카드 챙기기 \"");
        ScheduleApi.post(mockMvc, family.groupId(), family.dad(), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("할머니 병원"))
                .andExpect(jsonPath("$.allDay").value(false))
                .andExpect(jsonPath("$.startsAt").value("2026-11-21T10:00:00+09:00"))
                .andExpect(jsonPath("$.endsAt").value("2026-11-21T11:30:00+09:00"))
                .andExpect(jsonPath("$.startDate").doesNotExist())
                .andExpect(jsonPath("$.participantIds", contains(asInt(Math.min(family.mom(), family.dad())),
                        asInt(Math.max(family.mom(), family.dad())))))
                .andExpect(jsonPath("$.memo").value("진료 카드 챙기기"))
                .andExpect(jsonPath("$.reminders", contains("PREV_DAY_20", "HOUR_1")))
                .andExpect(jsonPath("$.createdBy").value(family.dad()))
                .andExpect(jsonPath("$.version").value(0));

        long id = ScheduleApi.create(mockMvc, family.groupId(), family.dad(), body);
        mockMvc.perform(get(ONE, id).with(asUser(family.son())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(family.groupId()))
                .andExpect(jsonPath("$.rrule").doesNotExist());
    }

    @Test
    void 종일_일정은_날짜로_주고받고_서울_자정으로_저장된다() throws Exception {
        long id = ScheduleApi.create(mockMvc, family.groupId(), family.mom(),
                allDay("제주 여행", "2026-11-21", "2026-11-22", family.mom()));

        mockMvc.perform(get(ONE, id).with(asUser(family.mom())))
                .andExpect(jsonPath("$.allDay").value(true))
                .andExpect(jsonPath("$.startDate").value("2026-11-21"))
                .andExpect(jsonPath("$.endDate").value("2026-11-22"))
                .andExpect(jsonPath("$.startsAt").value("2026-11-21T00:00:00+09:00"))
                .andExpect(jsonPath("$.endsAt").value("2026-11-23T00:00:00+09:00"))
                .andExpect(jsonPath("$.reminders", contains("PREV_DAY_20")));
    }

    @Test
    void 알림을_고르거나_끌_수_있고_반복_규칙은_정해진_형식으로_저장된다() throws Exception {
        String body = with(timed("피아노 학원", "2026-11-02T16:00", "2026-11-02T17:00", family.son()),
                "\"rrule\":\"UNTIL=20270228;FREQ=WEEKLY\",\"reminders\":[\"MIN_30\",\"MIN_30\",\"DAY_1\"]");
        ScheduleApi.post(mockMvc, family.groupId(), family.son(), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rrule").value("FREQ=WEEKLY;UNTIL=20270228"))
                .andExpect(jsonPath("$.reminders", contains("MIN_30", "DAY_1")));

        ScheduleApi.post(mockMvc, family.groupId(), family.son(),
                        with(timed("조용히", "2026-11-02T16:00", "2026-11-02T17:00", family.son()), "\"reminders\":[]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reminders").isEmpty());
    }

    @Test
    void 수정하면_version이_오르고_예전_version으로_고치면_409() throws Exception {
        long id = ScheduleApi.create(mockMvc, family.groupId(), family.mom(),
                timed("장보기", "2026-11-21T10:00", "2026-11-21T11:00", family.mom()));
        String edited = timed("마트 장보기", "2026-11-21T14:00", "2026-11-21T15:00", family.mom(), family.son());

        update(id, family.mom(), with(edited, "\"version\":0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("마트 장보기"))
                .andExpect(jsonPath("$.startsAt").value("2026-11-21T14:00:00+09:00"))
                .andExpect(jsonPath("$.participantIds.length()").value(2))
                .andExpect(jsonPath("$.version").value(1));

        update(id, family.mom(), with(edited, "\"version\":0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        update(id, family.mom(), edited)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 삭제하면_더_찾을_수_없다() throws Exception {
        long id = ScheduleApi.create(mockMvc, family.groupId(), family.mom(),
                timed("장보기", "2026-11-21T10:00", "2026-11-21T11:00", family.mom()));

        mockMvc.perform(delete(ONE, id).with(asUser(family.mom()))).andExpect(status().isNoContent());
        mockMvc.perform(get(ONE, id).with(asUser(family.mom()))).andExpect(status().isNotFound());
    }

    @Test
    void 입력이_틀리면_400() throws Exception {
        long stranger = createUser();
        String[] invalid = {
            timed("거꾸로", "2026-11-21T11:00", "2026-11-21T10:00", family.mom()),
            timed("참여자 없음", "2026-11-21T10:00", "2026-11-21T11:00"),
            timed("남의 집 사람", "2026-11-21T10:00", "2026-11-21T11:00", family.mom(), stranger),
            timed(" ", "2026-11-21T10:00", "2026-11-21T11:00", family.mom()),
            timed("너무 긺", "2026-11-21T10:00", "2027-11-23T10:00", family.mom()),
            with(timed("매일", "2026-11-21T10:00", "2026-11-21T11:00", family.mom()), "\"rrule\":\"FREQ=DAILY\""),
            with(timed("알림 4개", "2026-11-21T10:00", "2026-11-21T11:00", family.mom()),
                    "\"reminders\":[\"MIN_10\",\"MIN_30\",\"HOUR_1\",\"DAY_1\"]"),
            with(timed("모르는 알림", "2026-11-21T10:00", "2026-11-21T11:00", family.mom()),
                    "\"reminders\":[\"WEEK_1\"]"),
            with(allDay("종일 1시간 전", "2026-11-21", "2026-11-21", family.mom()), "\"reminders\":[\"HOUR_1\"]"),
            allDay("종일 거꾸로", "2026-11-22", "2026-11-21", family.mom()),
            """
            {"title":"날짜 없음","allDay":true,"participantIds":[%d]}""".formatted(family.mom()),
            """
            {"title":"시각 없음","allDay":false,"participantIds":[%d]}""".formatted(family.mom()),
            """
            {"title":"종일 여부 없음","startsAt":"2026-11-21T10:00:00+09:00","endsAt":"2026-11-21T11:00:00+09:00",
             "participantIds":[%d]}""".formatted(family.mom()),
        };
        for (String body : invalid) {
            ScheduleApi.post(mockMvc, family.groupId(), family.mom(), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertNoSchedules(family.groupId());
    }

    @Test
    void 구성원이_아니면_그룹_일정에_접근할_수_없다() throws Exception {
        long stranger = createUser();

        ScheduleApi.post(mockMvc, family.groupId(), stranger,
                        timed("몰래", "2026-11-21T10:00", "2026-11-21T11:00", stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));
        mockMvc.perform(get("/api/v1/groups/{g}/schedules", family.groupId()).with(asUser(stranger))
                        .param("from", "2026-11-01").param("to", "2026-11-30"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));
    }

    @Test
    void 다른_그룹_일정_id로_요청하면_404로_존재를_숨긴다() throws Exception {
        long id = ScheduleApi.create(mockMvc, family.groupId(), family.mom(),
                timed("우리 집 일정", "2026-11-21T10:00", "2026-11-21T11:00", family.mom()));
        long other = createUser();
        GroupApi.createGroup(mockMvc, other, "다른 가족");
        String body = with(timed("바꿔치기", "2026-11-21T10:00", "2026-11-21T11:00", other), "\"version\":0");

        mockMvc.perform(get(ONE, id).with(asUser(other))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        update(id, other, body).andExpect(status().isNotFound());
        mockMvc.perform(delete(ONE, id).with(asUser(other))).andExpect(status().isNotFound());
        mockMvc.perform(get(ONE, Long.MAX_VALUE).with(asUser(family.mom()))).andExpect(status().isNotFound());
        mockMvc.perform(get(ONE, id).with(asUser(family.mom()))).andExpect(jsonPath("$.title").value("우리 집 일정"));
    }

    @Test
    void 남이_등록한_일정은_MEMBER는_못_고치고_ADMIN_이상은_고친다() throws Exception {
        long id = ScheduleApi.create(mockMvc, family.groupId(), family.dad(),
                timed("아빠 회식", "2026-11-21T19:00", "2026-11-21T22:00", family.dad()));
        String edited = with(timed("아빠 회식 (취소?)", "2026-11-21T19:00", "2026-11-21T22:00", family.dad()),
                "\"version\":0");

        update(id, family.son(), edited)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_REQUIRED"));
        mockMvc.perform(delete(ONE, id).with(asUser(family.son()))).andExpect(status().isForbidden());

        GroupApi.changeRole(mockMvc, family.groupId(), family.mom(), family.son(), "ADMIN").andExpect(status().isOk());
        update(id, family.son(), edited).andExpect(status().isOk());
        mockMvc.perform(delete(ONE, id).with(asUser(family.mom()))).andExpect(status().isNoContent());
    }

    @Test
    void 나간_참여자는_기록에_남고_수정할_때_그대로_둘_수_있지만_새로_넣을_수는_없다() throws Exception {
        long id = ScheduleApi.create(mockMvc, family.groupId(), family.mom(),
                timed("가족 사진", "2026-11-21T10:00", "2026-11-21T11:00", family.mom(), family.son()));
        mockMvc.perform(post("/api/v1/groups/{g}/leave", family.groupId()).with(asUser(family.son())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(ONE, id).with(asUser(family.mom())))
                .andExpect(jsonPath("$.participantIds.length()").value(2));
        update(id, family.mom(), with(timed("가족 사진 (장소 변경)", "2026-11-21T10:00", "2026-11-21T11:00",
                family.mom(), family.son()), "\"version\":0"))
                .andExpect(status().isOk());

        long another = ScheduleApi.create(mockMvc, family.groupId(), family.mom(),
                timed("새 일정", "2026-11-22T10:00", "2026-11-22T11:00", family.mom()));
        update(another, family.mom(), with(timed("새 일정", "2026-11-22T10:00", "2026-11-22T11:00", family.mom(),
                family.son()), "\"version\":0"))
                .andExpect(status().isBadRequest());
    }

    private ResultActions update(long id, long userId, String body) throws Exception {
        return mockMvc.perform(patch(ONE, id).with(asUser(userId)).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void assertNoSchedules(long groupId) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM schedules WHERE group_id = ?", Long.class, groupId)).isZero();
    }

    /** jsonPath 는 작은 숫자를 Integer 로 읽는다 */
    private static Object asInt(long value) {
        return value <= Integer.MAX_VALUE ? (Object) (int) value : (Object) value;
    }
}
