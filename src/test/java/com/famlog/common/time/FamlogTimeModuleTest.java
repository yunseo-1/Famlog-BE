package com.famlog.common.time;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.famlog.support.IntegrationTest;

/** 저장은 UTC, API 응답은 +09:00 (엔드포인트: support.web.TimeTestController). 사양서 11.1 */
class FamlogTimeModuleTest extends IntegrationTest {

    private static final long USER_ID = 1;

    @Test
    void 시각은_Asia_Seoul_오프셋_ISO_8601로_응답한다() throws Exception {
        mockMvc.perform(get("/test/time").with(asUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instant").value("2026-11-14T11:00:00+09:00"))
                .andExpect(jsonPath("$.offsetUtc").value("2026-11-14T11:00:00+09:00"))
                .andExpect(jsonPath("$.zonedUtc").value("2026-11-14T11:00:00+09:00"))
                .andExpect(jsonPath("$.date").value("2026-11-14"));
    }

    @Test
    void 날짜가_바뀌는_시각도_서울_기준으로_표시한다() throws Exception {
        // UTC 2026-12-31 15:30 = 서울 2027-01-01 00:30
        mockMvc.perform(get("/test/time-boundary").with(asUser(USER_ID)))
                .andExpect(jsonPath("$.instant").value("2027-01-01T00:30:00+09:00"));
    }
}
