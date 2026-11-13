package com.famlog.common.time;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 저장은 UTC, API 응답은 +09:00 (사양서 11.1: 2026-11-14T11:00:00+09:00). */
@WebMvcTest(FamlogTimeModuleTest.TimeTestController.class)
@Import(FamlogTimeModuleTest.TimeTestController.class)
class FamlogTimeModuleTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 시각은_Asia_Seoul_오프셋_ISO_8601로_응답한다() throws Exception {
        mockMvc.perform(get("/test/time"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instant").value("2026-11-14T11:00:00+09:00"))
                .andExpect(jsonPath("$.offsetUtc").value("2026-11-14T11:00:00+09:00"))
                .andExpect(jsonPath("$.zonedUtc").value("2026-11-14T11:00:00+09:00"))
                .andExpect(jsonPath("$.date").value("2026-11-14"));
    }

    @Test
    void 날짜가_바뀌는_시각도_서울_기준으로_표시한다() throws Exception {
        // UTC 2026-12-31 15:30 = 서울 2027-01-01 00:30
        mockMvc.perform(get("/test/time-boundary"))
                .andExpect(jsonPath("$.instant").value("2027-01-01T00:30:00+09:00"));
    }

    record TimeResponse(Instant instant, OffsetDateTime offsetUtc, ZonedDateTime zonedUtc, LocalDate date) {
    }

    record BoundaryResponse(Instant instant) {
    }

    @RestController
    static class TimeTestController {

        @GetMapping("/test/time")
        TimeResponse time() {
            Instant instant = Instant.parse("2026-11-14T02:00:00Z");
            return new TimeResponse(instant,
                    instant.atOffset(ZoneOffset.UTC),
                    instant.atZone(ZoneId.of("UTC")),
                    LocalDate.of(2026, 11, 14));
        }

        @GetMapping("/test/time-boundary")
        BoundaryResponse boundary() {
            return new BoundaryResponse(Instant.parse("2026-12-31T15:30:00Z"));
        }
    }
}
