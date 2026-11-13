package com.famlog.common.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.famlog.auth.jwt.AccessTokenValidators;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.error.ErrorResponseWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 인증 실패 → 401 (사양서 11.2). 만료면 AUTH_TOKEN_EXPIRED(앱이 Refresh 재발급 시도), 그 밖은 AUTH_INVALID(재로그인).
 */
@Component
@RequiredArgsConstructor
public class FamlogAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ErrorResponseWriter errorResponseWriter;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        boolean expired = isExpired(exception);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                request.getHeader(HttpHeaders.AUTHORIZATION) == null ? "Bearer" : "Bearer error=\"invalid_token\"");
        errorResponseWriter.write(request, response, expired ? ErrorCode.AUTH_TOKEN_EXPIRED : ErrorCode.AUTH_INVALID);
    }

    private static boolean isExpired(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof JwtValidationException validation) {
                // 만료와 다른 문제가 함께 있으면(서명은 맞지만 폐기된 세션 등) 재발급해도 소용없으니 AUTH_INVALID
                return !validation.getErrors().isEmpty() && validation.getErrors().stream()
                        .allMatch(error -> AccessTokenValidators.TOKEN_EXPIRED.equals(error.getErrorCode()));
            }
        }
        return false;
    }
}
