package com.famlog.group.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;
import com.famlog.group.dto.GroupDtos.ChangeRoleRequest;
import com.famlog.group.dto.GroupDtos.CreateGroupRequest;
import com.famlog.group.dto.GroupDtos.GroupDetailResponse;
import com.famlog.group.dto.GroupDtos.GroupSummaryResponse;
import com.famlog.group.dto.GroupDtos.InviteResponse;
import com.famlog.group.dto.GroupDtos.JoinRequest;
import com.famlog.group.dto.GroupDtos.MemberResponse;
import com.famlog.group.dto.GroupDtos.UpdateMyMemberRequest;
import com.famlog.group.service.GroupService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 가족 그룹 (사양서 11.3 GRP-01~05). /groups/{groupId}/** 는 GroupPathAccessFilter가 구성원 여부를 먼저 확인한다. */
@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @Operation(summary = "그룹 만들기", description = "만든 사람은 그룹장(OWNER). 단체 채팅방이 함께 생기고 현재 그룹이 된다")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GroupDetailResponse create(AuthUser user, @Valid @RequestBody CreateGroupRequest request) {
        return groupService.create(user.userId(), request.name());
    }

    @Operation(summary = "내 그룹 목록", description = "채팅 탭(SCR-10)·그룹 전환(SCR-70)용")
    @GetMapping("/me")
    List<GroupSummaryResponse> myGroups(AuthUser user) {
        return groupService.myGroups(user.userId());
    }

    @Operation(summary = "그룹과 구성원")
    @GetMapping("/{groupId}")
    GroupDetailResponse detail(AuthUser user, @PathVariable long groupId) {
        return groupService.detail(groupId, user.userId());
    }

    @Operation(summary = "그룹 삭제 (그룹장)", description = "즉시 접근이 막히고 30일 뒤 파기된다")
    @DeleteMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(AuthUser user, @PathVariable long groupId) {
        groupService.delete(groupId, user.userId());
    }

    @Operation(summary = "나가기", description = "다른 구성원이 있는 그룹장은 409 OWNER_TRANSFER_REQUIRED. 혼자 남은 그룹장이 나가면 그룹 삭제 예정")
    @PostMapping("/{groupId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(AuthUser user, @PathVariable long groupId) {
        groupService.leave(groupId, user.userId());
    }

    @Operation(summary = "초대 코드 발급·재발급 (그룹장·관리자)", description = "7일 유효. 재발급하면 이전 코드는 바로 만료")
    @PostMapping("/{groupId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    InviteResponse issueInvite(AuthUser user, @PathVariable long groupId) {
        return groupService.issueInvite(groupId, user.userId());
    }

    @Operation(summary = "초대 코드로 참여",
            description = "없는 코드 404, 만료 410 INVITE_EXPIRED, 연속 5회 실패 시 10분 잠금 429. 이미 구성원이면 그대로 200")
    @PostMapping("/join")
    GroupDetailResponse join(AuthUser user, @Valid @RequestBody JoinRequest request) {
        return groupService.join(user.userId(), request.code());
    }

    @Operation(summary = "내 가족 역할·호칭·캘린더 색", description = "보낸 값만 바꾼다 (SCR-03)")
    @PatchMapping("/{groupId}/members/me")
    MemberResponse updateMe(AuthUser user, @PathVariable long groupId,
            @Valid @RequestBody UpdateMyMemberRequest request) {
        return groupService.updateMyProfile(groupId, user.userId(), request);
    }

    @Operation(summary = "권한 변경·그룹장 위임 (그룹장·관리자)", description = "role=OWNER 는 위임이며 그룹장만 가능")
    @PatchMapping("/{groupId}/members/{userId}")
    MemberResponse changeRole(AuthUser user, @PathVariable long groupId, @PathVariable long userId,
            @Valid @RequestBody ChangeRoleRequest request) {
        return groupService.changeRole(groupId, user.userId(), userId, request.role());
    }

    @Operation(summary = "내보내기 (그룹장·관리자)", description = "그룹장은 내보낼 수 없다")
    @DeleteMapping("/{groupId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void kick(AuthUser user, @PathVariable long groupId, @PathVariable long userId) {
        groupService.kick(groupId, user.userId(), userId);
    }
}
