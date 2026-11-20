package com.famlog.group.dto;

import java.time.Instant;
import java.util.List;

import com.famlog.chat.dto.ChatDtos.LastMessage;
import com.famlog.group.domain.FamilyRole;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.domain.GroupRole;
import com.famlog.group.domain.GroupSettings;
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

    /**
     * @param lastMessage 채팅 탭에 보일 마지막 메시지 (들어온 뒤 메시지가 없으면 null)
     * @param unreadCount 안 읽은 메시지 수 (내가 보낸 것·시스템·삭제된 메시지 제외)
     */
    public record GroupSummaryResponse(long id, String name, String imageKey, long memberCount, GroupRole myRole,
            LastMessage lastMessage, long unreadCount) {
    }

    /**
     * @param familyRole null 이면 아직 설정 전 → 앱이 SCR-03(내 가족 역할 설정)으로 안내
     * @param aiOptOut   내 메시지 AI 분석 제외. 그룹 상세에서 ADMIN 이상에게만 값이 있고 그 밖에는 null (사양서 8.8: 볼 수만 있다)
     */
    public record MemberResponse(long userId, String nickname, String profileImageKey, GroupRole role,
            FamilyRole familyRole, String familyTitle, String calendarColor,
            @Schema(description = "AI 분석 제외 (그룹 상세에서 ADMIN 이상에게만, 아니면 null)") Boolean aiOptOut) {

        public static MemberResponse of(GroupMember member, User user) {
            return of(member, user, false);
        }

        public static MemberResponse of(GroupMember member, User user, boolean showAiOptOut) {
            return new MemberResponse(member.getUserId(), user.getNickname(), user.getProfileImageKey(),
                    member.getRole(), member.getFamilyRole(), member.getFamilyTitle(), member.getCalendarColor(),
                    showAiOptOut ? member.isAiOptOut() : null);
        }
    }

    public record GroupDetailResponse(long id, String name, String imageKey, GroupRole myRole,
            List<MemberResponse> members) {
    }

    /** @param link App Links 초대 링크 (도메인이 정해지기 전까지는 설정값) */
    public record InviteResponse(String code, String link, Instant expiresAt) {
    }

    // --- SET-01 그룹 설정 ---

    /** @param aiNoticeVersion 꺼진 그룹을 켤 때 필수: 관리자에게 보여 준 AI 분석 안내 버전 (GET /ai-notice) */
    public record UpdateGroupSettingsRequest(
            @Schema(description = "AI 분석 켜기 (채팅에서 일정·할 일·장소 후보를 찾는다)") @NotNull Boolean aiAnalysisEnabled,
            @Schema(description = "켤 때 필수: 보여 준 AI 분석 안내 버전", example = "1.0") @Size(max = 20)
            String aiNoticeVersion) {
    }

    /**
     * @param aiEnabledAt     마지막으로 켠 시각 (한 번도 안 켰으면 null)
     * @param aiNoticeVersion 현재 AI 분석 안내 버전
     * @param aiNoticePending AI 분석이 켜져 있는데 내가 현재 안내를 확인하지 않았다 → 앱이 안내를 한 번 보여 준다
     */
    public record GroupSettingsResponse(boolean aiAnalysisEnabled, Instant aiEnabledAt, String aiNoticeVersion,
            boolean aiNoticePending) {

        public static GroupSettingsResponse from(GroupSettings settings, String aiNoticeVersion, boolean noticed) {
            return new GroupSettingsResponse(settings.isAiAnalysisEnabled(), settings.getAiEnabledAt(),
                    aiNoticeVersion, settings.isAiAnalysisEnabled() && !noticed);
        }
    }
}
