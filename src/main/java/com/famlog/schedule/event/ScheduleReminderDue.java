package com.famlog.schedule.event;

import java.time.Instant;
import java.util.Set;

import com.famlog.schedule.domain.Reminder;

/**
 * 일정 회차의 리마인드 시각이 됐다 (사양서 4.3 SCH-03). NOTI 가 참여 구성원에게 푸시를 보낸다.
 * 회차·종류마다 한 번만 발행된다 (schedule_reminder_sends).
 */
public record ScheduleReminderDue(long groupId, long scheduleId, String title, Instant occurrenceStart,
        boolean allDay, Reminder reminder, Set<Long> participantIds) {

    @Override
    public String toString() {
        // 일정 제목이 로그에 찍히지 않게 (사양서 9.8)
        return "ScheduleReminderDue[scheduleId=" + scheduleId + ", reminder=" + reminder + "]";
    }
}
