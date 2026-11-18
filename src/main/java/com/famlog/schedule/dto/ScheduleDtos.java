package com.famlog.schedule.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import com.famlog.schedule.domain.Holiday;
import com.famlog.schedule.domain.Reminder;
import com.famlog.schedule.domain.Schedule;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 일정·공휴일 API 요청·응답 (사양서 11.3 SCH-01·02·04·05). */
public final class ScheduleDtos {

    private ScheduleDtos() {
    }

    /**
     * 등록·수정 요청. 수정할 때도 수정 화면의 값 전체를 보낸다 (반복 일정은 시리즈 전체가 바뀐다).
     * 시간 일정은 startsAt·endsAt, 종일 일정은 startDate·endDate(마지막 날 포함)를 보낸다.
     */
    public record ScheduleRequest(
            @Schema(example = "할머니 병원") @NotBlank @Size(max = 100) String title,
            @Schema(description = "종일 일정") @NotNull Boolean allDay,
            @Schema(description = "시간 일정의 시작", example = "2026-11-21T11:00:00+09:00") OffsetDateTime startsAt,
            @Schema(description = "시간 일정의 끝 (시작과 같아도 된다)", example = "2026-11-21T12:00:00+09:00")
            OffsetDateTime endsAt,
            @Schema(description = "종일 일정의 첫날", example = "2026-11-21") LocalDate startDate,
            @Schema(description = "종일 일정의 마지막 날 (포함)", example = "2026-11-22") LocalDate endDate,
            @Schema(description = "참여 구성원 userId (1명 이상, 이 그룹의 가족)")
            @NotEmpty @Size(max = 20) Set<@NotNull Long> participantIds,
            @Schema(description = "반복 RRULE. 없으면 반복 안 함", example = "FREQ=WEEKLY;UNTIL=20270228")
            @Size(max = 200) String rrule,
            @Size(max = 1000) String memo,
            @Schema(description = "리마인드. 보내지 않으면 기본값(시간 일정: 전날 20:00 + 1시간 전, 종일: 전날 20:00), "
                    + "빈 배열이면 알림 없음. 종일 일정은 PREV_DAY_20만")
            @Size(max = Reminder.MAX_PER_SCHEDULE) List<@NotNull Reminder> reminders,
            @Schema(description = "수정할 때 필수: 조회한 일정의 version (다르면 409 VERSION_CONFLICT)") Long version) {
    }

    /** 일정 하나 (시리즈) */
    public record ScheduleResponse(long id, long groupId, String title, boolean allDay, Instant startsAt,
            Instant endsAt,
            @Schema(description = "종일 일정의 첫날 (시간 일정이면 null)") LocalDate startDate,
            @Schema(description = "종일 일정의 마지막 날 (포함)") LocalDate endDate,
            List<Long> participantIds, String rrule, String memo, List<Reminder> reminders, long createdBy,
            long version, Instant createdAt, Instant updatedAt) {

        public static ScheduleResponse from(Schedule schedule) {
            return new ScheduleResponse(schedule.getId(), schedule.getGroupId(), schedule.getTitle(),
                    schedule.isAllDay(), schedule.getStartsAt(), schedule.getEndsAt(),
                    firstDay(schedule, schedule.getStartsAt()), lastDay(schedule, schedule.getEndsAt()),
                    sorted(schedule.getParticipantIds()), schedule.getRrule(), schedule.getMemo(),
                    schedule.reminderList(), schedule.getCreatedBy(), schedule.getVersion(), schedule.getCreatedAt(),
                    schedule.getUpdatedAt());
        }
    }

    /** 기간 조회의 회차 하나. 반복 일정은 회차마다 한 줄이고 scheduleId 가 같다 */
    public record OccurrenceResponse(long scheduleId, String title, boolean allDay, Instant startsAt, Instant endsAt,
            LocalDate startDate, LocalDate endDate, List<Long> participantIds,
            @Schema(description = "반복 일정의 회차") boolean recurring, long createdBy) {

        public static OccurrenceResponse of(Schedule schedule, Instant start) {
            Instant end = start.plus(schedule.duration());
            return new OccurrenceResponse(schedule.getId(), schedule.getTitle(), schedule.isAllDay(), start, end,
                    firstDay(schedule, start), lastDay(schedule, end), sorted(schedule.getParticipantIds()),
                    schedule.getRrule() != null, schedule.getCreatedBy());
        }
    }

    /** 시작 시각 순 (같으면 일정 id 순). 기간 안 회차 전체라 페이징하지 않는다 */
    public record OccurrenceListResponse(List<OccurrenceResponse> items) {
    }

    /**
     * 겹치는 일정 (SCH-05): "아빠는 이 시간에 ○○ 일정이 있어요"
     *
     * @param userIds 요청한 구성원 중 이 일정에 참여하는 사람
     */
    public record ConflictResponse(long scheduleId, String title, boolean allDay, Instant startsAt, Instant endsAt,
            List<Long> userIds) {
    }

    public record ConflictListResponse(List<ConflictResponse> items) {
    }

    public record HolidayResponse(LocalDate date, String name,
            @Schema(description = "대체공휴일") boolean substitute) {

        public static HolidayResponse from(Holiday holiday) {
            return new HolidayResponse(holiday.getDate(), holiday.getName(), holiday.isSubstitute());
        }
    }

    public record HolidayListResponse(List<HolidayResponse> items) {
    }

    private static LocalDate firstDay(Schedule schedule, Instant start) {
        return schedule.isAllDay() ? Schedule.dateOf(start) : null;
    }

    /** 종일 일정의 끝은 마지막 날 다음 날 00:00 으로 저장돼 있다 */
    private static LocalDate lastDay(Schedule schedule, Instant end) {
        return schedule.isAllDay() ? Schedule.dateOf(end).minusDays(1) : null;
    }

    static List<Long> sorted(Set<Long> ids) {
        return ids.stream().sorted().toList();
    }
}
