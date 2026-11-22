package com.famlog.user.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.user.domain.DataExportRequest;

public interface DataExportRequestRepository extends JpaRepository<DataExportRequest, Long> {

    Optional<DataExportRequest> findByIdAndUserId(Long id, Long userId);

    List<DataExportRequest> findByUserIdOrderByIdDesc(Long userId, Limit limit);

    /** 진행 중(REQUESTED·PROCESSING)인 요청. 사용자당 하나 */
    @Query("select r from DataExportRequest r where r.userId = :userId and r.status in ('REQUESTED', 'PROCESSING')")
    Optional<DataExportRequest> findOpen(@Param("userId") Long userId);

    /** 쿨다운 안에 만든(실패하지 않은) 요청이 있는지 */
    @Query("""
            select count(r) > 0 from DataExportRequest r
            where r.userId = :userId and r.status in ('READY', 'EXPIRED') and r.requestedAt > :since
            """)
    boolean existsRecent(@Param("userId") Long userId, @Param("since") Instant since);

    /** 링크가 만료된 요청 (만료 배치) */
    @Query("select r.id from DataExportRequest r where r.status = 'READY' and r.expiresAt <= :now order by r.id")
    List<Long> findExpiredIds(@Param("now") Instant now, Limit limit);

    /** 파일이 남아 있는 요청 (계정 파기 때 함께 지운다) */
    @Query("select r from DataExportRequest r where r.userId = :userId and r.status = 'READY'")
    List<DataExportRequest> findReady(@Param("userId") Long userId);
}
