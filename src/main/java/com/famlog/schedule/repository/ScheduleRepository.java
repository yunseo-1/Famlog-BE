package com.famlog.schedule.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.schedule.domain.Schedule;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    /** [from, to) 와 겹칠 수 있는 그룹 일정. 반복 일정은 첫 회차가 to 전이면 모두 가져와 서비스가 펼친다 */
    @Query("""
            select distinct s from Schedule s left join fetch s.participantIds
            where s.groupId = :groupId and s.startsAt < :to
              and (s.rrule is not null or s.endsAt > :from or s.startsAt >= :from)
            """)
    List<Schedule> findCandidates(@Param("groupId") long groupId, @Param("from") Instant from,
            @Param("to") Instant to);

    /** 리마인드: 회차 시작이 (from, to] 일 수 있는 모든 그룹의 일정 */
    @Query("""
            select distinct s from Schedule s left join fetch s.participantIds
            where s.startsAt <= :to and (s.rrule is not null or s.startsAt > :from)
            """)
    List<Schedule> findReminderCandidates(@Param("from") Instant from, @Param("to") Instant to);
}
