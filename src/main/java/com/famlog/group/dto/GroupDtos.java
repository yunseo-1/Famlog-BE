package com.famlog.group.dto;

import java.time.Instant;
import java.util.List;

import com.famlog.group.domain.FamilyRole;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.domain.GroupRole;
import com.famlog.user.domain.User;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 가족 그룹 API 요청·응답 (사양서 11.3 GRP-01~05). */
public final class GroupDtos {

    private GroupDtos() {
    }

    public record CreateGroupRequest(@Schema(example = "우리 가족") @NotBlank @Size(max = 30) String name) {
    }

    public record JoinRequest(
            @Schema(description = "8자리 초대 코드. 대소문자·공백·하이픈은 무시", example = "K7MP-Q2XA")
            @NotBlank @Size(max = 20) String code) {
    }

    /** 바꿀 값만 보낸다. familyTitle 을 빈 문자열로 보내면 호칭을 지운다 */
    public record UpdateMyMemberRequest(
            FamilyRole familyRole,
            @Schema(description = "호칭 (큰아들, 막내)") @Size(max = 20) String familyTitle,
            @Schema(description = "팔레트 색 #RRGGBB", example = "#14A3B8")
            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "#RRGGBB 형식이어야 해요.") String calendarColor) {
    }

    public record ChangeRoleRequest(
            @Schema(description = "OWNER로 바꾸면 그룹장 위임 (그룹장만 가능)") @NotNull GroupRole role) {
    }

    public record CurrentGroupRequest(@NotNull Long groupId) {
    }

    public record GroupSummaryResponse(long id, String name, String imageKey, long memberCount, GroupRole myRole) {
    }

    /** @param familyRole null 이면 아직 설정 전 → 앱이 SCR-03(내 가족 역할 설정)으로 안내 */
    public record MemberResponse(long userId, String nickname, String profileImageKey, GroupRole role,
            FamilyRole familyRole, String familyTitle, String calendarColor) {

        public static MemberResponse of(GroupMember member, User user) {
            return new MemberResponse(member.getUserId(), user.getNickname(), user.getProfileImageKey(),
                    member.getRole(), member.getFamilyRole(), member.getFamilyTitle(), member.getCalendarColor());
        }
    }

    public record GroupDetailResponse(long id, String name, String imageKey, GroupRole myRole,
            List<MemberResponse> members) {
    }

    /** @param link App Links 초대 링크 (도메인이 정해지기 전까지는 설정값) */
    public record InviteResponse(String code, String link, Instant expiresAt) {
    }
}
