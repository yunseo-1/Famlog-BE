package com.famlog.support.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** FamlogTimeModuleTest용: UTC 시각을 그대로 응답에 담는다. */
@RestController
public class TimeTestController {

    public record TimeResponse(Instant instant, OffsetDateTime offsetUtc, ZonedDateTime zonedUtc, LocalDate date) {
    }

    public record BoundaryResponse(Instant instant) {
    }

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
