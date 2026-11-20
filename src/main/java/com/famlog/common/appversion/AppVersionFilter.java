package com.famlog.common.appversion;

import java.io.IOException;
import java.util.Locale;

import org.springframework.web.filter.OncePerRequestFilter;

import com.famlog.common.error.ErrorCode;
import com.famlog.common.error.ErrorResponseWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 강제 업데이트 (사양서 11.1 X-App-Version·X-Platform, 14장): 앱 버전이 그 플랫폼의 최소 지원 버전보다 낮으면 426 UPGRADE_REQUIRED.
 * 헤더가 없으면(테스트·curl·웹) 통과시킨다. GET /app/config 는 업데이트 화면을 띄울 수 있게 언제나 통과시킨다
 */
@RequiredArgsConstructor
public class AppVersionFilter extends OncePerRequestFilter {

    public static final String VERSION_HEADER = "X-App-Version";
    public static final String PLATFORM_HEADER = "X-Platform";
    static final String CONFIG_PATH = "/api/v1/app/config";

    private final AppVersions appVersions;
    private final ErrorResponseWriter errorResponseWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return request.getHeader(VERSION_HEADER) == null || !path.startsWith("/api/") || path.equals(CONFIG_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String platform = platformOf(request);
        String version = request.getHeader(VERSION_HEADER);
        boolean outdated = AppVersions.isVersion(version) && appVersions.find(platform)
                .map(v -> AppVersions.compare(version, v.minSupportedVersion()) < 0).orElse(false);
        if (outdated) {
            errorResponseWriter.write(request, response, ErrorCode.UPGRADE_REQUIRED);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 헤더가 없거나 모르는 값이면 ANDROID */
    public static String platformOf(HttpServletRequest request) {
        String platform = request.getHeader(PLATFORM_HEADER);
        String upper = platform == null ? "" : platform.toUpperCase(Locale.ROOT);
        return AppVersions.PLATFORMS.contains(upper) ? upper : AppVersions.ANDROID;
    }
}
