package com.famlog.admin.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.admin.dto.AdminDtos.AddHolidayRequest;
import com.famlog.admin.dto.AdminDtos.AdminHoliday;
import com.famlog.admin.dto.AdminDtos.AuditLogItem;
import com.famlog.admin.dto.AdminDtos.FeatureFlagItem;
import com.famlog.admin.dto.AdminDtos.ReportDetail;
import com.famlog.admin.dto.AdminDtos.ReportPage;
import com.famlog.admin.dto.AdminDtos.ResolveReportRequest;
import com.famlog.admin.dto.AdminDtos.ResolveReportResponse;
import com.famlog.admin.dto.AdminDtos.UpdateAppVersionRequest;
import com.famlog.admin.dto.AdminDtos.UpdateFeatureFlagRequest;
import com.famlog.admin.service.AdminReportService;
import com.famlog.admin.service.AdminSettingsService;
import com.famlog.common.appversion.AppVersions.AppVersion;
import com.famlog.report.domain.ReportStatus;
import com.famlog.user.domain.AccountDeletionRequest;
import com.famlog.user.service.AccountDeletionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 최소 관리자 도구 API (사양서 14장). 모든 요청에 X-Admin-Token. 페이지는 /admin/ */
@Tag(name = "admin", description = "관리자 도구 (X-Admin-Token)")
@RestController
@RequestMapping("/admin/api")
@RequiredArgsConstructor
public class AdminController {

    private final AdminReportService reportService;
    private final AdminSettingsService settingsService;
    private final AccountDeletionService accountDeletionService;

    @Operation(summary = "신고 목록", description = "아동 안전 먼저, 그다음 최신 순. 콘텐츠는 넣지 않는다")
    @GetMapping("/reports")
    ReportPage reports(@RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) Long cursor, @RequestParam(required = false) Integer size) {
        return reportService.list(status, cursor, size);
    }

    @Operation(summary = "신고 상세", description = "신고된 콘텐츠 하나를 보여 주고 감사 로그(ADMIN_REPORT_VIEWED)를 남긴다")
    @GetMapping("/reports/{reportId}")
    ReportDetail report(@PathVariable long reportId) {
        return reportService.detail(reportId);
    }

    @Operation(summary = "신고 처리", description = "REVIEWED·ACTIONED·REJECTED. ACTIONED 조치: 메시지·사진 HIDE_MESSAGE, "
            + "AI 후보 DISMISS_CANDIDATE. 같은 대상의 처리 전 신고를 함께 처리한다")
    @PostMapping("/reports/{reportId}/resolve")
    ResolveReportResponse resolve(@PathVariable long reportId, @Valid @RequestBody ResolveReportRequest request) {
        return reportService.resolve(reportId, request);
    }

    @GetMapping("/holidays")
    List<AdminHoliday> holidays(@RequestParam int year) {
        return settingsService.holidays(year);
    }

    @PostMapping("/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    AdminHoliday addHoliday(@Valid @RequestBody AddHolidayRequest request) {
        return settingsService.addHoliday(request);
    }

    @DeleteMapping("/holidays/{holidayId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteHoliday(@PathVariable long holidayId) {
        settingsService.deleteHoliday(holidayId);
    }

    @GetMapping("/app-versions")
    List<AppVersion> appVersions() {
        return settingsService.appVersions();
    }

    @Operation(summary = "앱 버전", description = "최소 지원 버전보다 낮은 앱(X-App-Version)은 426 UPGRADE_REQUIRED")
    @PutMapping("/app-versions/{platform}")
    AppVersion updateAppVersion(@PathVariable String platform, @Valid @RequestBody UpdateAppVersionRequest request) {
        return settingsService.updateAppVersion(platform, request);
    }

    @GetMapping("/feature-flags")
    List<FeatureFlagItem> featureFlags() {
        return settingsService.featureFlags();
    }

    @PutMapping("/feature-flags/{key}")
    FeatureFlagItem updateFeatureFlag(@PathVariable String key, @Valid @RequestBody UpdateFeatureFlagRequest request) {
        return settingsService.updateFeatureFlag(key, request.enabled());
    }

    /** @param deleteMedia 내가 올린 사진·동영상도 지운다 */
    public record AdminDeleteAccountRequest(Boolean deleteMedia) {
    }

    @Operation(summary = "계정 삭제 대신 처리", description = "웹 안내 페이지(/account-deletion)로 이메일 요청이 왔을 때, 본인 확인 뒤 "
            + "앱의 계정 삭제와 같은 흐름으로 처리한다 (30일 유예, 그룹장 규칙 409)")
    @PostMapping("/users/{userId}/deletion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAccount(@PathVariable long userId, @RequestBody(required = false) AdminDeleteAccountRequest request) {
        accountDeletionService.request(userId, request != null && Boolean.TRUE.equals(request.deleteMedia()),
                AccountDeletionRequest.SOURCE_ADMIN);
    }

    @GetMapping("/audit-logs")
    List<AuditLogItem> auditLogs(@RequestParam(required = false) Integer limit) {
        return settingsService.auditLogs(limit);
    }
}
