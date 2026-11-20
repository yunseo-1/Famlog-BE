package com.famlog.report.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.report.domain.Report;
import com.famlog.report.domain.ReportStatus;
import com.famlog.report.domain.ReportTargetType;

public interface ReportRepository extends JpaRepository<Report, Long> {

    Optional<Report> findByReporterIdAndTargetTypeAndTargetIdAndStatus(Long reporterId, ReportTargetType targetType,
            Long targetId, ReportStatus status);

    List<Report> findByTargetTypeAndTargetIdAndStatus(ReportTargetType targetType, Long targetId, ReportStatus status);

    /** 관리자 목록: 아동 안전 먼저, 그다음 최신 순. cursor 는 마지막으로 받은 id (그보다 작은 것) */
    @Query("""
            select r from Report r
            where (:status is null or r.status = :status) and (:cursor is null or r.id < :cursor)
            order by case when r.reason = com.famlog.report.domain.ReportReason.CHILD_SAFETY then 0 else 1 end,
                     r.id desc
            """)
    List<Report> findForAdmin(@Param("status") ReportStatus status, @Param("cursor") Long cursor, Limit limit);
}
