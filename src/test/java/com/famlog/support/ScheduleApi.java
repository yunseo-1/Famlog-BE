package com.famlog.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;

/** 일정 API 테스트 도우미. 요청 본문 만들기와 등록. */
public final class ScheduleApi {

    private ScheduleApi() {
    }

    /** 시간 일정 본문. 시각은 "2026-11-21T10:00" 처럼 서울 시각으로 쓴다 */
    public static String timed(String title, String start, String end, long... participants) {
        return """
                {"title":"%s","allDay":false,"startsAt":"%s:00+09:00","endsAt":"%s:00+09:00","participantIds":%s}"""
                .formatted(title, start, end, ids(participants));
    }

    /** 시간 일정 본문에 다른 키(rrule, reminders, version 등)를 덧붙인다: extra = "\"rrule\":\"FREQ=WEEKLY\"" */
    public static String with(String body, String extra) {
        return body.substring(0, body.length() - 1) + "," + extra + "}";
    }

    public static String allDay(String title, String startDate, String endDate, long... participants) {
        return """
                {"title":"%s","allDay":true,"startDate":"%s","endDate":"%s","participantIds":%s}"""
                .formatted(title, startDate, endDate, ids(participants));
    }

    public static ResultActions post(MockMvc mockMvc, long groupId, long userId, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/groups/{g}/schedules", groupId)
                .with(GroupApi.as(userId)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    public static long create(MockMvc mockMvc, long groupId, long userId, String body) throws Exception {
        String response = post(mockMvc, groupId, userId, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    public static String ids(long... ids) {
        return Arrays.stream(ids).mapToObj(Long::toString).collect(Collectors.joining(",", "[", "]"));
    }
}
