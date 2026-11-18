package com.famlog.schedule.controller;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;
import com.famlog.group.domain.FamilyRole;
import com.famlog.schedule.dto.ScheduleDtos.ConflictListResponse;
import com.famlog.schedule.dto.ScheduleDtos.OccurrenceListResponse;
import com.famlog.schedule.dto.ScheduleDtos.ScheduleRequest;
import com.famlog.schedule.dto.ScheduleDtos.ScheduleResponse;
import com.famlog.schedule.service.ScheduleQueryService;
import com.famlog.schedule.service.ScheduleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;

/**
 * 일정 (사양서 4.3 SCH-01·02·05, 11.3). /groups/{groupId}/** 는 GroupPathAccessFilter 가 구성원 여부를 먼저 확인하고,
 * /schedules/{id} 는 서비스가 일정의 그룹으로 확인한다 (다른 그룹이면 404).
 */
@RestController
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final ScheduleQueryService queryService;

    @Operation(summary = "일정 등록", description = "참여 구성원 1명 이상. 반복은 RRULE(FREQ=WEEKLY|MONTHLY|YEARLY, "
            + "INTERVAL, UNTIL|COUNT)")
    @PostMapping("/api/v1/groups/{groupId}/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    ScheduleResponse createSchedule(AuthUser user, @PathVariable long groupId,
            @Valid @RequestBody ScheduleRequest request) {
        return scheduleService.create(groupId, user.userId(), request);
    }

    @Operation(summary = "기간 일정", description = "서울 날짜 from~to (포함, 최대 100일). 반복 일정은 회차로 펼친다. "
            + "memberIds·roles 를 주면 그 구성원이 참여하는 일정만 (둘 다 주면 합집합)")
    @GetMapping("/api/v1/groups/{groupId}/schedules")
    OccurrenceListResponse listSchedules(AuthUser user, @PathVariable long groupId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "구성원 userId 여러 개") @RequestParam(required = false) Set<Long> memberIds,
            @Parameter(description = "가족 역할 여러 개 (MOTHER 등)") @RequestParam(required = false)
            Set<FamilyRole> roles) {
        return queryService.list(groupId, user.userId(), from, to, memberIds, roles);
    }

    @Operation(summary = "겹치는 일정", description = "등록 화면용 (SCH-05): 이 시간에 memberIds 가 참여하는 다른 일정. "
            + "딱 붙은 일정은 겹친 것으로 보지 않는다")
    @GetMapping("/api/v1/groups/{groupId}/schedules/conflicts")
    ConflictListResponse scheduleConflicts(AuthUser user, @PathVariable long groupId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startsAt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endsAt,
            @RequestParam @NotEmpty Set<Long> memberIds,
            @Parameter(description = "수정 중인 일정 id (빼고 본다)") @RequestParam(required = false) Long excludeId) {
        return queryService.conflicts(groupId, user.userId(), startsAt, endsAt, memberIds, excludeId);
    }

    @Operation(summary = "일정 하나", description = "다른 그룹 일정이면 404")
    @GetMapping("/api/v1/schedules/{scheduleId}")
    ScheduleResponse getSchedule(AuthUser user, @PathVariable long scheduleId) {
        return scheduleService.get(scheduleId, user.userId());
    }

    @Operation(summary = "일정 수정", description = "값 전체와 조회한 version 을 보낸다. 반복 일정은 시리즈 전체가 바뀐다. "
            + "MEMBER 는 본인이 등록한 일정만 (403), version 이 다르면 409")
    @PatchMapping("/api/v1/schedules/{scheduleId}")
    ScheduleResponse updateSchedule(AuthUser user, @PathVariable long scheduleId,
            @Valid @RequestBody ScheduleRequest request) {
        return scheduleService.update(scheduleId, user.userId(), request);
    }

    @Operation(summary = "일정 삭제", description = "반복 일정은 시리즈 전체를 지운다. MEMBER 는 본인이 등록한 일정만")
    @DeleteMapping("/api/v1/schedules/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteSchedule(AuthUser user, @PathVariable long scheduleId) {
        scheduleService.delete(scheduleId, user.userId());
    }
}
