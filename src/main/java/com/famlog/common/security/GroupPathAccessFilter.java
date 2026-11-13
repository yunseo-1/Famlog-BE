package com.famlog.common.security;

import java.io.IOException;

import org.springframework.http.server.PathContainer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorResponseWriter;
import com.famlog.group.service.GroupAccessChecker;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * /api/v1/groups/{groupId}/** 요청은 컨트롤러에 닿기 전에 구성원인지 확인한다 (사양서 9.3).
 * groupId가 숫자가 아닌 경로(/groups/join, /groups/me)는 그룹 범위가 아니므로 통과시킨다.
 * 관리자 역할이 필요한 동작은 서비스에서 requireRole로 따로 검사한다.
 */
@RequiredArgsConstructor
public class GroupPathAccessFilter extends OncePerRequestFilter {

    private static final PathPattern GROUP_PATH =
            PathPatternParser.defaultInstance.parse("/api/v1/groups/{groupId}/{*rest}");

    private final GroupAccessChecker groupAccessChecker;
    private final ErrorResponseWriter errorResponseWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        PathPattern.PathMatchInfo match = GROUP_PATH.matchAndExtract(PathContainer.parsePath(request.getRequestURI()));
        Long groupId = match == null ? null : parseGroupId(match.getUriVariables().get("groupId"));
        if (groupId != null) {
            var user = AuthUser.from(SecurityContextHolder.getContext().getAuthentication());
            // 인증 필수 경로라 user가 없으면 이후 보안 단계에서 401이 된다
            if (user.isPresent()) {
                try {
                    groupAccessChecker.requireMember(groupId, user.get().userId());
                } catch (BusinessException e) {
                    errorResponseWriter.write(request, response, e.getErrorCode());
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private static Long parseGroupId(String value) {
        if (value == null || value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
