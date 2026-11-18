package com.famlog.schedule.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.famlog.support.IntegrationTest;

/** 공휴일 조회 (사양서 4.3 SCH-04). 초기 데이터는 V7 마이그레이션. */
class HolidayApiTest extends IntegrationTest {

    private static final String HOLIDAYS = "/api/v1/holidays";

    @Test
    void 그해_공휴일과_대체공휴일을_날짜_순으로_준다() throws Exception {
        mockMvc.perform(get(HOLIDAYS).param("year", "2026").with(asUser(createUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.items[0].date").value("2026-01-01"))
                .andExpect(jsonPath("$.items[0].name").value("신정"))
                .andExpect(jsonPath("$.items[0].substitute").value(false))
                .andExpect(jsonPath("$.items[5].date").value("2026-03-02"))
                .andExpect(jsonPath("$.items[5].name").value("대체공휴일(삼일절)"))
                .andExpect(jsonPath("$.items[5].substitute").value(true))
                .andExpect(jsonPath("$.items[19].date").value("2026-12-25"));

        mockMvc.perform(get(HOLIDAYS).param("year", "2027").with(asUser(createUser())))
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.items[1].date").value("2027-02-05"))
                .andExpect(jsonPath("$.items[4].name").value("대체공휴일(설날)"));
    }

    @Test
    void 데이터가_없는_해는_빈_목록이다() throws Exception {
        mockMvc.perform(get(HOLIDAYS).param("year", "2040").with(asUser(createUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void 연도가_없거나_범위를_벗어나면_400() throws Exception {
        long userId = createUser();
        for (String year : new String[] { "1999", "2101", "올해" }) {
            mockMvc.perform(get(HOLIDAYS).param("year", year).with(asUser(userId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(get(HOLIDAYS).with(asUser(userId))).andExpect(status().isBadRequest());
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(get(HOLIDAYS).param("year", "2026")).andExpect(status().isUnauthorized());
    }
}
