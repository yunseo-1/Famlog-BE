package com.famlog.schedule.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.schedule.dto.ScheduleDtos.HolidayListResponse;
import com.famlog.schedule.service.HolidayService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** 공휴일 (사양서 4.3 SCH-04, 11.3). 로그인한 사용자 누구나. */
@RestController
@RequiredArgsConstructor
public class HolidayController {

    private final HolidayService holidayService;

    @Operation(summary = "공휴일", description = "그해 공휴일·대체공휴일 (날짜 순). 데이터가 없는 해는 빈 목록")
    @GetMapping("/api/v1/holidays")
    HolidayListResponse holidays(@RequestParam @Min(2000) @Max(2100) int year) {
        return holidayService.ofYear(year);
    }
}
