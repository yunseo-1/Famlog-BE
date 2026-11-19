package com.famlog.place.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.place.domain.LocationAccessLog;

public interface LocationAccessLogRepository extends JpaRepository<LocationAccessLog, Long> {
}
