package com.famlog.candidate.repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.famlog.candidate.domain.AiJob;
import com.famlog.candidate.service.CandidateTargets;
import com.famlog.candidate.service.CandidateTargets.Items;
import com.famlog.candidate.service.CandidateTargets.ScheduleItem;
import com.famlog.candidate.service.CandidateTargets.TodoItem;

import lombok.RequiredArgsConstructor;

/**
 * 변경·완료 후보의 대상이 될 수 있는 그룹 항목 (S19-2, CandidateTargets). 그룹 밖 항목은 읽지 않는다.
 * <ul>
 * <li>일정: 지금부터 60일 안에 시작하는 반복 아닌 일정</li>
 * <li>할 일: 끝나지 않은 할 일</li>
 * <li>최근 대화의 일정: 분석 구간과 그 앞 맥락(최대 20개 메시지)을 근거로 승인한 후보가 만들거나 바꾼 일정</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class TargetItemReader {

    static final int MAX_ITEMS = 200;
    static final int CONTEXT_MESSAGES = 20;

    private final JdbcTemplate jdbcTemplate;

    public Items load(AiJob job, Instant now) {
        List<ScheduleItem> schedules = jdbcTemplate.query("""
                SELECT id, title, starts_at, ends_at, all_day, created_by FROM schedules
                WHERE group_id = ? AND rrule IS NULL AND starts_at >= ? AND starts_at < ?
                ORDER BY starts_at, id LIMIT ?
                """, (rs, i) -> new ScheduleItem(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                rs.getTimestamp(4).toInstant(), rs.getBoolean(5), rs.getLong(6)), job.getGroupId(), Timestamp.from(now),
                Timestamp.from(now.plus(Duration.ofDays(CandidateTargets.SCHEDULE_DAYS))), MAX_ITEMS);
        List<TodoItem> todos = jdbcTemplate.query("""
                SELECT id, title, assignee_id, due_date FROM todos
                WHERE group_id = ? AND kind = 'TASK' AND done_at IS NULL
                ORDER BY id DESC LIMIT ?
                """, (rs, i) -> new TodoItem(rs.getLong(1), rs.getString(2), rs.getObject(3, Long.class),
                rs.getObject(4, LocalDate.class)), job.getGroupId(), MAX_ITEMS);
        Set<Long> recent = new HashSet<>(jdbcTemplate.queryForList("""
                SELECT DISTINCT c.created_entity_id FROM registration_candidates c
                JOIN candidate_evidence e ON e.candidate_id = c.id
                JOIN messages m ON m.id = e.message_id
                WHERE c.group_id = ? AND c.status = 'APPROVED' AND c.created_entity_type = 'SCHEDULE'
                  AND m.room_id = ? AND m.seq > ? AND m.seq <= ?
                """, Long.class, job.getGroupId(), job.getRoomId(), job.getFromSeq() - CONTEXT_MESSAGES,
                job.getToSeq()));
        return new Items(schedules, todos, recent);
    }
}
