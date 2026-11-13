package com.famlog.auth.dto;

import java.time.Instant;

import com.famlog.auth.domain.Platform;
import com.famlog.auth.service.AuthResult;
import com.famlog.auth.service.DeviceInfo;
import com.famlog.auth.service.TokenPair;
import com.famlog.user.domain.User;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 인증 API 요청·응답 (사양서 11.3 AUTH-01). 토큰이 들어간 record는 toString에서 토큰을 뺀다 (사양서 9.8).
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record DeviceRequest(
            @Schema(description = "앱 설치마다 만든 무작위 ID") @NotBlank @Size(max = 100) String deviceId,
            @Schema(description = "기기 관리 화면에 보일 이름 (예: Galaxy S25)") @Size(max = 100) String deviceName,
            @NotNull Platform platform) {

        public DeviceInfo toDeviceInfo() {
            return new DeviceInfo(deviceId, deviceName, platform);
        }
    }

    public record LoginRequest(
            @Schema(description = "카카오: 액세스 토큰 / Google: ID 토큰") @NotBlank @Size(max = 4096) String providerToken,
            @Valid @NotNull DeviceRequest device) {

        @Override
        public String toString() {
            return "LoginRequest[device=" + device + "]";
        }
    }

    public record AgreementsRequest(
            @Schema(description = "동의한 이용약관 버전") @NotBlank @Size(max = 20) String termsVersion,
            @Schema(description = "동의한 개인정보 수집·이용 버전") @NotBlank @Size(max = 20) String privacyVersion) {
    }

    public record SignupRequest(
            @NotBlank @Size(max = 4096) String providerToken,
            @Valid @NotNull DeviceRequest device,
            @Schema(description = "가족에게 보일 이름 (1~30자)") @NotBlank @Size(max = 30) String nickname,
            @Schema(description = "출생연도·월 YYYYMM. 만 14세 확인에만 쓴다", example = "199003")
            @NotBlank @Pattern(regexp = "\\d{6}", message = "YYYYMM 형식으로 입력해 주세요.") String birthYm,
            @Valid @NotNull AgreementsRequest agreements) {

        @Override
        public String toString() {
            return "SignupRequest[device=" + device + ", agreements=" + agreements + "]";
        }
    }

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {

        @Override
        public String toString() {
            return "RefreshRequest[]";
        }
    }

    public record TokenResponse(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
            Instant refreshTokenExpiresAt) {

        public static TokenResponse from(TokenPair tokens) {
            return new TokenResponse(tokens.accessToken(), tokens.accessTokenExpiresAt(), tokens.refreshToken(),
                    tokens.refreshTokenExpiresAt());
        }

        @Override
        public String toString() {
            return "TokenResponse[accessTokenExpiresAt=" + accessTokenExpiresAt + ", refreshTokenExpiresAt="
                    + refreshTokenExpiresAt + "]";
        }
    }

    /** @param lastGroupId 마지막으로 본 그룹. null이면 앱이 그룹 시작 화면(SCR-02)으로 간다 (18.1 AUTH-01) */
    public record UserResponse(long id, String nickname, Long lastGroupId) {

        public static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getNickname(), user.getLastGroupId());
        }
    }

    public record AuthResponse(TokenResponse tokens, UserResponse user) {

        public static AuthResponse from(AuthResult result) {
            return new AuthResponse(TokenResponse.from(result.tokens()), UserResponse.from(result.user()));
        }
    }
}
