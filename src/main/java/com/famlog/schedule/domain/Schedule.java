package com.famlog.schedule.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.famlog.common.domain.BaseTimeEntity;
import com.famlog.common.time.FamlogTime;
import com.famlog.group.domain.GroupRole;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 일정 (사양서 4.3 SCH-01, 10.2 schedules·schedule_participants).
 * 반복 일정은 첫 회차만 저장하고 조회할 때 펼친다. 종일 일정은 서울 시각 시작일 00:00 ~ 끝날 다음 날 00:00 이다.
 */
@Getter
@Entity
@Table(name = "schedules")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Schedule extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Column(nullable = false)
    private String title;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "all_day", nullable = false)
    private boolean allDay;

    private String rrule;

    @Enumerated(EnumType.STRING)
    @Column(name = "calendar_type", nullable = false)
    private CalendarType calendarType = CalendarType.SOLAR;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "lunar_month_day", length = 4)
    private String lunarMonthDay;

    @Column(name = "place_id")
    private Long placeId;

    private String memo;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "varchar(20)[]")
    private String[] reminders;

    @ElementCollection
    @CollectionTable(name = "schedule_participants", joinColumns = @JoinColumn(name = "schedule_id"))
    @Column(name = "user_id", nullable = false)
    private Set<Long> participantIds = new HashSet<>();

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Version
    @Column(nullable = false)
    private long version;

    /** 시작·종료와 종일 여부 (서비스가 요청을 검증해 만든다) */
    public record Timing(Instant startsAt, Instant endsAt, boolean allDay) {
    }

    public Schedule(long groupId, long createdBy, String title, Timing timing, Recurrence recurrence, String memo,
            List<Reminder> reminders, Set<Long> participantIds) {
        this.groupId = groupId;
        this.createdBy = createdBy;
        update(title, timing, recurrence, memo, reminders, participantIds);
    }

    public void update(String title, Timing timing, Recurrence recurrence, String memo, List<Reminder> reminders,
            Set<Long> participantIds) {
        this.title = title;
        this.startsAt = timing.startsAt();
        this.endsAt = timing.endsAt();
        this.allDay = timing.allDay();
        this.rrule = recurrence == null ? null : recurrence.toRrule();
        this.memo = memo;
        this.reminders = reminders.stream().map(Reminder::name).toArray(String[]::new);
        this.participantIds.retainAll(participantIds);
        this.participantIds.addAll(participantIds);
    }

    /** 사양서 2.2: 남이 등록한 일정은 ADMIN 이상만 고치거나 지운다 */
    public boolean isEditableBy(long userId, GroupRole role) {
        return createdBy == userId || role.atLeast(GroupRole.ADMIN);
    }

    public List<Reminder> reminderList() {
        return Arrays.stream(reminders).map(Reminder::valueOf).toList();
    }

    public Recurrence recurrence() {
        return rrule == null ? null : Recurrence.parse(rrule);
    }

    public Duration duration() {
        return Duration.between(startsAt, endsAt);
    }

    /** [from, to) 와 겹치는 회차의 시작 시각 */
    public List<Instant> occurrenceStarts(Instant from, Instant to) {
        Recurrence recurrence = recurrence();
        if (recurrence == null) {
            return Recurrence.overlaps(startsAt, endsAt, from, to) ? List.of(startsAt) : List.of();
        }
        return recurrence.startsOverlapping(startsAt, duration(), from, to);
    }

    /** 종일 일정의 시작일 (서울) */
    public static LocalDate dateOf(Instant at) {
        return LocalDate.ofInstant(at, FamlogTime.DISPLAY_ZONE);
    }
}
