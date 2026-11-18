package com.famlog.schedule.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.time.FamlogTime;
import com.famlog.group.domain.FamilyRole;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.group.service.GroupAccessChecker;
import com.famlog.schedule.domain.Schedule;
import com.famlog.schedule.dto.ScheduleDtos.ConflictListResponse;
import com.famlog.schedule.dto.ScheduleDtos.ConflictResponse;
import com.famlog.schedule.dto.ScheduleDtos.OccurrenceListResponse;
import com.famlog.schedule.dto.ScheduleDtos.OccurrenceResponse;
import com.famlog.schedule.repository.ScheduleRepository;

import lombok.RequiredArgsConstructor;

/**
 * 기간 조회 (사양서 4.3 SCH-02)와 겹침 조회 (SCH-05).
 * <ul>
 * <li>기간은 서울 날짜 from~to (둘 다 포함, 최대 100일). 반복 일정은 기간 안의 회차로 펼친다</li>
 * <li>구성원·역할 필터는 참여 구성원 기준이고 합집합이다: memberIds 중 누가 참여하거나, 참여자 중 roles 인 사람이 있으면 보인다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ScheduleQueryService {

    static final int MAX_RANGE_DAYS = 100;

    private final ScheduleRepository scheduleRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupAccessChecker accessChecker;

    @Transactional(readOnly = true)
    public OccurrenceListResponse list(long groupId, long userId, LocalDate from, LocalDate to, Set<Long> memberIds,
            Set<FamilyRole> roles) {
        accessChecker.requireMember(groupId, userId);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw invalid("조회 기간은 1~" + MAX_RANGE_DAYS + "일이어야 해요.");
        }
        Instant start = from.atStartOfDay(FamlogTime.DISPLAY_ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(FamlogTime.DISPLAY_ZONE).toInstant();
        Set<Long> wanted = wantedMembers(groupId, memberIds, roles);

        List<OccurrenceResponse> items = new ArrayList<>();
        for (Schedule schedule : scheduleRepository.findCandidates(groupId, start, end)) {
            if (wanted != null && schedule.getParticipantIds().stream().noneMatch(wanted::contains)) {
                continue;
            }
            schedule.occurrenceStarts(start, end).forEach(at -> items.add(OccurrenceResponse.of(schedule, at)));
        }
        items.sort(Comparator.comparing(OccurrenceResponse::startsAt).thenComparing(OccurrenceResponse::scheduleId));
        return new OccurrenceListResponse(items);
    }

    /** startsAt~endsAt 과 겹치는, memberIds 가 참여하는 일정 (수정 중인 일정 excludeId 는 뺀다) */
    @Transactional(readOnly = true)
    public ConflictListResponse conflicts(long groupId, long userId, OffsetDateTime startsAt, OffsetDateTime endsAt,
            Set<Long> memberIds, Long excludeId) {
        accessChecker.requireMember(groupId, userId);
        Instant start = startsAt.toInstant();
        Instant end = endsAt.toInstant();
        if (!end.isAfter(start) || Duration.between(start, end).compareTo(ScheduleService.MAX_DURATION) > 0) {
            throw invalid("끝나는 시각은 시작보다 뒤이고 1년 안이어야 해요.");
        }
        List<ConflictResponse> items = new ArrayList<>();
        for (Schedule schedule : scheduleRepository.findCandidates(groupId, start, end)) {
            List<Long> overlapping = schedule.getParticipantIds().stream().filter(memberIds::contains).sorted()
                    .toList();
            if (overlapping.isEmpty() || schedule.getId().equals(excludeId)) {
                continue;
            }
            for (Instant at : schedule.occurrenceStarts(start, end)) {
                Instant occurrenceEnd = at.plus(schedule.duration());
                // 딱 붙은 일정(10~11시, 11~12시)이나 길이 0인 일정은 겹친 것으로 보지 않는다
                if (at.isBefore(end) && occurrenceEnd.isAfter(start)) {
                    items.add(new ConflictResponse(schedule.getId(), schedule.getTitle(), schedule.isAllDay(), at,
                            occurrenceEnd, overlapping));
                }
            }
        }
        items.sort(Comparator.comparing(ConflictResponse::startsAt).thenComparing(ConflictResponse::scheduleId));
        return new ConflictListResponse(items);
    }

    /** 필터가 없으면 null (전체) */
    private Set<Long> wantedMembers(long groupId, Set<Long> memberIds, Set<FamilyRole> roles) {
        boolean byMember = memberIds != null && !memberIds.isEmpty();
        boolean byRole = roles != null && !roles.isEmpty();
        if (!byMember && !byRole) {
            return null;
        }
        Set<Long> wanted = byMember ? new HashSet<>(memberIds) : new HashSet<>();
        if (byRole) {
            // 나간 구성원도 그 역할로 참여했던 일정이 보이게 모든 구성원 행에서 찾는다
            memberRepository.findByGroupId(groupId).stream()
                    .filter(member -> member.getFamilyRole() != null && roles.contains(member.getFamilyRole()))
                    .map(GroupMember::getUserId)
                    .forEach(wanted::add);
        }
        return wanted;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
