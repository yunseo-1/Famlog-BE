package com.famlog.admin.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.admin.service.AdminSettingsService;
import com.famlog.common.appversion.AppVersionFilter;
import com.famlog.common.appversion.AppVersions;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/** 앱 설정 (사양서 11.3 GET /app/config, 14장 강제 업데이트·점검 공지). 로그인 없이 부른다 */
@RestController
@RequiredArgsConstructor
public class AppConfigController {

    private final AppVersions appVersions;
    private final AdminSettingsService settingsService;

    /**
     * @param features nearby(내 주변), photoLocation(사진 위치), aiAnalysis(AI 분석 전체)
     */
    public record AppConfigResponse(String platform, String minSupportedVersion, String latestVersion,
            @Schema(description = "점검·안내 공지 (없으면 null)") String notice,
            @Schema(description = "공지를 바꾼 시각. 앱은 이 값으로 이미 본 공지인지 판단한다 (공지 없으면 null)")
            Instant noticeUpdatedAt,
            Map<String, Boolean> features) {
    }

    @Operation(summary = "앱 설정", description = "X-Platform(기본 ANDROID) 기준 최소·최신 버전, 공지, 기능 상태. "
            + "앱 버전(X-App-Version)이 minSupportedVersion 보다 낮으면 다른 API 는 426 UPGRADE_REQUIRED")
    @GetMapping("/api/v1/app/config")
    AppConfigResponse appConfig(HttpServletRequest request) {
        String platform = AppVersionFilter.platformOf(request);
        AppVersions.AppVersion version = appVersions.find(platform)
                .orElse(new AppVersions.AppVersion(platform, "0.0.0", null, null, null));
        return new AppConfigResponse(platform, version.minSupportedVersion(), version.latestVersion(),
                version.notice(), version.noticeUpdatedAt(), settingsService.features());
    }
}
