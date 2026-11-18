package com.famlog.schedule.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.schedule.domain.Holiday;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    List<Holiday> findByDateBetweenOrderByDateAscIdAsc(LocalDate from, LocalDate to);
}
