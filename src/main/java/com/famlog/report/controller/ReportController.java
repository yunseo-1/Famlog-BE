package com.famlog.report.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;
import com.famlog.report.dto.ReportDtos.CreateReportRequest;
import com.famlog.report.dto.ReportDtos.ReportResponse;
import com.famlog.report.service.ReportService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 신고 (사양서 4.2 MOD-01, 11.3) */
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "신고", description = "메시지(MESSAGE)·사진/동영상(MEDIA)·AI 후보(AI_OUTPUT)·구성원(USER, groupId 필요). "
            + "내가 볼 수 없는 대상은 404, 구성원이 아닌 그룹은 403. OTHER 는 detail 필수. "
            + "처리 전에 같은 대상을 다시 신고하면 그 신고를 그대로 돌려준다 (201)")
    @PostMapping("/api/v1/reports")
    @ResponseStatus(HttpStatus.CREATED)
    ReportResponse createReport(AuthUser user, @Valid @RequestBody CreateReportRequest request) {
        return reportService.create(user.userId(), request);
    }
}
