package com.famlog.notification.dto;

import java.time.LocalTime;

import com.famlog.group.domain.GroupMember;
import com.famlog.notification.domain.NotificationSettings;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 푸시 토큰·알림 설정 API 요청·응답 (사양서 11.3 NOTI-01, SET-02). */
public final class NotificationDtos {

    private static final String HH_MM = "^([01]\\d|2[0-3]):[0-5]\\d$";

    private NotificationDtos() {
    }

    /** 플랫폼은 로그인할 때 보낸 기기 정보(세션)를 쓴다 */
    public record RegisterTokenRequest(
            @Schema(description = "FCM 등록 토큰") @NotBlank @Size(max = 512) String token,
            @Schema(description = "앱 버전", example = "1.0.0") @Size(max = 20) String appVersion) {

        @Override
        public String toString() {
            // 토큰이 로그에 찍히지 않게 (사양서 9.8)
            return "RegisterTokenRequest[appVersion=" + appVersion + "]";
        }
    }

    /** 바꿀 값만 보낸다. 시각은 서울 기준 HH:mm, 자정을 넘겨도 된다 (22:00~07:00) */
    public record NotificationSettingsRequest(
            @Schema(description = "알림에 메시지 내용을 보이지 않는다") Boolean previewHidden,
            @Schema(description = "방해 금지 시간 사용") Boolean quietHoursEnabled,
            @Schema(description = "방해 금지 시작", example = "22:00")
            @Pattern(regexp = HH_MM, message = "HH:mm 형식이어야 해요.") String quietStart,
            @Schema(description = "방해 금지 끝", example = "07:00")
            @Pattern(regexp = HH_MM, message = "HH:mm 형식이어야 해요.") String quietEnd) {

        public LocalTime quietStartTime() {
            return quietStart == null ? null : LocalTime.parse(quietStart);
        }

        public LocalTime quietEndTime() {
            return quietEnd == null ? null : LocalTime.parse(quietEnd);
        }
    }

    /**
     * @param previewHidden     true 면 푸시에 본문이 실리지 않는다
     * @param quietHoursEnabled true 면 이 시간의 푸시는 silent=true (소리·진동 없이)
     */
    public record NotificationSettingsResponse(boolean previewHidden, boolean quietHoursEnabled,
            @Schema(example = "22:00") String quietStart, @Schema(example = "07:00") String quietEnd) {

        public static NotificationSettingsResponse from(NotificationSettings settings) {
            return new NotificationSettingsResponse(settings.isPreviewHidden(), settings.isQuietEnabled(),
                    settings.getQuietStart().toString(), settings.getQuietEnd().toString());
        }
    }

    public record GroupPreferencesRequest(
            @Schema(description = "이 그룹 알림 끄기") @NotNull Boolean notifMuted) {
    }

    public record GroupPreferencesResponse(boolean notifMuted) {

        public static GroupPreferencesResponse from(GroupMember member) {
            return new GroupPreferencesResponse(member.isNotifMuted());
        }
    }
}
