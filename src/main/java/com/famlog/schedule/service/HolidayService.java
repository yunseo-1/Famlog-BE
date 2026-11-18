package com.famlog.schedule.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.schedule.dto.ScheduleDtos.HolidayListResponse;
import com.famlog.schedule.dto.ScheduleDtos.HolidayResponse;
import com.famlog.schedule.repository.HolidayRepository;

import lombok.RequiredArgsConstructor;

/** 공휴일 조회 (사양서 4.3 SCH-04). 그룹과 상관없는 공용 데이터다. */
@Service
@RequiredArgsConstructor
public class HolidayService {

    private final HolidayRepository holidayRepository;

    @Transactional(readOnly = true)
    public HolidayListResponse ofYear(int year) {
        return new HolidayListResponse(holidayRepository
                .findByDateBetweenOrderByDateAscIdAsc(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)).stream()
                .map(HolidayResponse::from)
                .toList());
    }
}
