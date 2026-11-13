package com.famlog.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.auth.dto.AuthDtos.AuthResponse;
import com.famlog.auth.dto.AuthDtos.LoginRequest;
import com.famlog.auth.dto.AuthDtos.RefreshRequest;
import com.famlog.auth.dto.AuthDtos.SignupRequest;
import com.famlog.auth.dto.AuthDtos.TokenResponse;
import com.famlog.auth.service.AuthService;
import com.famlog.auth.service.SessionTokenService;
import com.famlog.auth.service.SignupCommand;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.user.domain.SocialProvider;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 로그인·가입·토큰 (사양서 11.3 AUTH-01·02). 모두 토큰 없이 호출한다. */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SessionTokenService sessionTokenService;

    @Operation(summary = "소셜 로그인", description = "가입되지 않은 계정이면 404 USER_NOT_REGISTERED → 가입 API로")
    @PostMapping("/{provider}/login")
    AuthResponse login(@PathVariable String provider, @Valid @RequestBody LoginRequest request) {
        return AuthResponse.from(authService.login(parseProvider(provider), request.providerToken(),
                request.device().toDeviceInfo()));
    }

    @Operation(summary = "소셜 가입", description = "약관 동의 + 닉네임 + 출생연도월. 만 14세 미만이면 403 AGE_RESTRICTED")
    @PostMapping("/{provider}/signup")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResponse signup(@PathVariable String provider, @Valid @RequestBody SignupRequest request) {
        SignupCommand command = new SignupCommand(request.nickname(), request.birthYm(),
                request.agreements().termsVersion(), request.agreements().privacyVersion());
        return AuthResponse.from(authService.signup(parseProvider(provider), request.providerToken(),
                request.device().toDeviceInfo(), command));
    }

    @Operation(summary = "토큰 재발급", description = "Refresh 토큰을 교체한다. 같은 토큰으로 동시에 두 번 부르지 않는다")
    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.from(sessionTokenService.refresh(request.refreshToken()));
    }

    @Operation(summary = "로그아웃", description = "이 기기의 세션을 폐기한다. 모르는 토큰이어도 204")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshRequest request) {
        sessionTokenService.revokeByRefreshToken(request.refreshToken());
    }

    private static SocialProvider parseProvider(String value) {
        return SocialProvider.fromPath(value)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "지원하지 않는 로그인 방식이에요."));
    }
}
