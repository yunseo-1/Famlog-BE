package com.famlog.admin.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.web.filter.OncePerRequestFilter;

import com.famlog.admin.service.AdminProperties;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.error.ErrorResponseWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * /admin/api/** 인증: X-Admin-Token 이 설정값과 같아야 한다 (상수 시간 비교). 설정값이 비어 있으면 404(꺼짐),
 * 헤더가 없거나 다르면 401. 앱 사용자 토큰(Bearer)으로는 들어올 수 없다
 */
@RequiredArgsConstructor
public class AdminTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Admin-Token";
    static final String API_PREFIX = "/admin/api/";

    private final AdminProperties properties;
    private final ErrorResponseWriter errorResponseWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.enabled()) {
            errorResponseWriter.write(request, response, ErrorCode.NOT_FOUND);
            return;
        }
        String given = request.getHeader(HEADER);
        if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                properties.token().getBytes(StandardCharsets.UTF_8))) {
            errorResponseWriter.write(request, response, ErrorCode.AUTH_INVALID);
            return;
        }
        chain.doFilter(request, response);
    }
}
