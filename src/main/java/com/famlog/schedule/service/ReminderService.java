package com.famlog.schedule.service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.schedule.domain.Reminder;
import com.famlog.schedule.domain.Schedule;
import com.famlog.schedule.event.ScheduleReminderDue;
import com.famlog.schedule.repository.ScheduleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 리마인드 시각이 된 일정 회차를 찾아 알림 이벤트를 낸다 (사양서 4.3 SCH-03). 1분마다 {@link ReminderTicker} 가 부른다.
 * <ul>
 * <li>지난 {@link #LOOKBACK} 안에 놓친 알림까지 보낸다 (재시작·지연 대비)</li>
 * <li>회차·종류마다 schedule_reminder_sends 에 먼저 넣은 서버만 보낸다 (서버 여러 대, 같은 회차 두 번 실행)</li>
 * <li>알림 시각이 일정을 등록·수정한 때보다 앞이면 보내지 않는다 (20:30에 내일 일정을 넣으면 전날 20:00 알림은 없다)</li>
 * </ul>
 * 푸시는 커밋 뒤에 나가므로 처리 중 실패하면 표시도 함께 취소되고 다음 실행에서 다시 시도한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    static final Duration LOOKBACK = Duration.ofMinutes(5);

    private final ScheduleRepository scheduleRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher events;

    /** @return 이번에 보낸 리마인드 수 */
    @Transactional
    public int sendDue(Instant now) {
        Instant from = now.minus(LOOKBACK);
        int sent = 0;
        for (Schedule schedule : scheduleRepository.findReminderCandidates(from, now.plus(Reminder.MAX_LEAD))) {
            for (Instant start : schedule.occurrenceStarts(from, now.plus(Reminder.MAX_LEAD).plusNanos(1))) {
                for (Reminder reminder : schedule.reminderList()) {
                    Instant at = reminder.remindAt(start);
                    if (at.isAfter(from) && !at.isAfter(now) && !at.isBefore(schedule.getUpdatedAt())
                            && claim(schedule.getId(), start, reminder)) {
                        events.publishEvent(new ScheduleReminderDue(schedule.getGroupId(), schedule.getId(),
                                schedule.getTitle(), start, schedule.isAllDay(), reminder,
                                Set.copyOf(schedule.getParticipantIds())));
                        sent++;
                    }
                }
            }
        }
        if (sent > 0) {
            log.info("일정 리마인드를 보냅니다: count={}", sent);
        }
        return sent;
    }

    private boolean claim(long scheduleId, Instant occurrenceStart, Reminder reminder) {
        return jdbcTemplate.update("""
                INSERT INTO schedule_reminder_sends (schedule_id, occurrence_start, kind) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, scheduleId, OffsetDateTime.ofInstant(occurrenceStart, ZoneOffset.UTC), reminder.name()) == 1;
    }
}
