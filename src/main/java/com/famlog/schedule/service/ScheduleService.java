package com.famlog.schedule.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.time.FamlogTime;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.domain.GroupRole;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.group.service.GroupAccessChecker;
import com.famlog.schedule.domain.Recurrence;
import com.famlog.schedule.domain.Reminder;
import com.famlog.schedule.domain.Schedule;
import com.famlog.schedule.dto.ScheduleDtos.ScheduleRequest;
import com.famlog.schedule.dto.ScheduleDtos.ScheduleResponse;
import com.famlog.schedule.repository.ScheduleRepository;

import lombok.RequiredArgsConstructor;

/**
 * 일정 등록·수정·삭제 (사양서 4.3 SCH-01, 2.2 권한, 9.3 인가).
 * <ul>
 * <li>/schedules/{id}: 다른 그룹(또는 나간 그룹) 일정이면 404</li>
 * <li>MEMBER 는 본인이 등록한 일정만, ADMIN 이상은 모두 고치거나 지운다</li>
 * <li>수정은 조회한 version 을 함께 보내고, 그사이 다른 사람이 고쳤으면 409</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {

    /** 일정 한 회차의 최대 길이 */
    static final Duration MAX_DURATION = Duration.ofDays(366);

    private final ScheduleRepository scheduleRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupAccessChecker accessChecker;

    @Transactional
    public ScheduleResponse create(long groupId, long userId, ScheduleRequest request) {
        accessChecker.requireMember(groupId, userId);
        Schedule.Timing timing = timing(request);
        Schedule schedule = new Schedule(groupId, userId, request.title().strip(), timing, recurrence(request),
                blankToNull(request.memo()), reminders(request, timing),
                participants(groupId, request.participantIds(), Set.of()));
        return ScheduleResponse.from(scheduleRepository.saveAndFlush(schedule));
    }

    @Transactional(readOnly = true)
    public ScheduleResponse get(long scheduleId, long userId) {
        Schedule schedule = find(scheduleId);
        accessChecker.requireMemberOrNotFound(schedule.getGroupId(), userId);
        return ScheduleResponse.from(schedule);
    }

    @Transactional
    public ScheduleResponse update(long scheduleId, long userId, ScheduleRequest request) {
        Schedule schedule = editable(scheduleId, userId);
        if (request.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "수정할 일정의 version 이 필요해요.");
        }
        if (request.version() != schedule.getVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        Schedule.Timing timing = timing(request);
        schedule.update(request.title().strip(), timing, recurrence(request), blankToNull(request.memo()),
                reminders(request, timing),
                participants(schedule.getGroupId(), request.participantIds(), schedule.getParticipantIds()));
        return ScheduleResponse.from(scheduleRepository.saveAndFlush(schedule));
    }

    @Transactional
    public void delete(long scheduleId, long userId) {
        scheduleRepository.delete(editable(scheduleId, userId));
    }

    private Schedule editable(long scheduleId, long userId) {
        Schedule schedule = find(scheduleId);
        GroupRole role = accessChecker.requireMemberOrNotFound(schedule.getGroupId(), userId);
        if (!schedule.isEditableBy(userId, role)) {
            throw new BusinessException(ErrorCode.GROUP_ROLE_REQUIRED, "내가 등록한 일정만 고치거나 지울 수 있어요.");
        }
        return schedule;
    }

    private Schedule find(long scheduleId) {
        return scheduleRepository.findById(scheduleId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /** 시간 일정은 startsAt·endsAt, 종일 일정은 startDate·endDate(포함) → 서울 00:00 ~ 다음 날 00:00 */
    static Schedule.Timing timing(ScheduleRequest request) {
        Instant start;
        Instant end;
        if (request.allDay()) {
            if (request.startDate() == null || request.endDate() == null) {
                throw invalid("종일 일정은 startDate 와 endDate 가 필요해요.");
            }
            if (request.endDate().isBefore(request.startDate())) {
                throw invalid("마지막 날이 첫날보다 앞이에요.");
            }
            start = request.startDate().atStartOfDay(FamlogTime.DISPLAY_ZONE).toInstant();
            end = request.endDate().plusDays(1).atStartOfDay(FamlogTime.DISPLAY_ZONE).toInstant();
        } else {
            if (request.startsAt() == null || request.endsAt() == null) {
                throw invalid("시간 일정은 startsAt 과 endsAt 이 필요해요.");
            }
            start = request.startsAt().toInstant();
            end = request.endsAt().toInstant();
            if (end.isBefore(start)) {
                throw invalid("끝나는 시각이 시작보다 앞이에요.");
            }
        }
        if (Duration.between(start, end).compareTo(MAX_DURATION) > 0) {
            throw invalid("일정은 1년보다 길 수 없어요.");
        }
        return new Schedule.Timing(start, end, request.allDay());
    }

    private static Recurrence recurrence(ScheduleRequest request) {
        if (request.rrule() == null || request.rrule().isBlank()) {
            return null;
        }
        try {
            return Recurrence.parse(request.rrule());
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
    }

    private static List<Reminder> reminders(ScheduleRequest request, Schedule.Timing timing) {
        if (request.reminders() == null) {
            return timing.allDay() ? Reminder.ALL_DAY_DEFAULT : Reminder.TIMED_DEFAULT;
        }
        if (timing.allDay() && !request.reminders().stream().allMatch(Reminder::allowedForAllDay)) {
            throw invalid("종일 일정은 전날 20:00 알림만 고를 수 있어요.");
        }
        return List.copyOf(new LinkedHashSet<>(request.reminders()));
    }

    /** 새로 넣는 참여자는 이 그룹의 활성 구성원이어야 한다. 이미 참여자였던 사람은 나갔어도 남겨 둘 수 있다 */
    private Set<Long> participants(long groupId, Set<Long> requested, Set<Long> existing) {
        Set<Long> active = memberRepository.findByGroupIdAndLeftAtIsNullOrderByJoinedAtAsc(groupId).stream()
                .map(GroupMember::getUserId)
                .collect(Collectors.toSet());
        for (Long userId : requested) {
            if (!active.contains(userId) && !existing.contains(userId)) {
                throw invalid("참여 구성원은 이 그룹의 가족이어야 해요.");
            }
        }
        return new HashSet<>(requested);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
