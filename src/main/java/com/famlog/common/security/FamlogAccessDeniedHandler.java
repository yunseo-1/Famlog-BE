package com.famlog.common.security;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.famlog.common.error.ErrorCode;
import com.famlog.common.error.ErrorResponseWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** Spring Security 권한 거부 → 403 공통 형식. 그룹 권한은 GroupAccessChecker가 따로 판단한다. */
@Component
@RequiredArgsConstructor
public class FamlogAccessDeniedHandler implements AccessDeniedHandler {

    private final ErrorResponseWriter errorResponseWriter;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        errorResponseWriter.write(request, response, ErrorCode.GROUP_ROLE_REQUIRED);
    }
}
