package com.famlog.user.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;
import com.famlog.user.dto.DataExportDtos.DataExportListResponse;
import com.famlog.user.dto.DataExportDtos.DataExportResponse;
import com.famlog.user.service.DataExportService;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

/** 내 데이터 내보내기 (사양서 4.1 ACC-02, 8.4, 11.3). */
@RestController
@RequestMapping("/api/v1/users/me/data-exports")
@RequiredArgsConstructor
public class DataExportController {

    private final DataExportService exportService;

    @Operation(summary = "내 데이터 내보내기 요청", description = "프로필·그룹·내가 쓴 메시지·내가 올린 사진 목록(원본 링크)·일정·할 일·"
            + "장소·동의 내역을 ZIP(JSON)으로 만든다. 보통 몇 분 안에 준비되고, 준비되면 푸시(type=DATA_EXPORT_READY, exportId)와 "
            + "/user/queue/events 의 data-export.ready {id} 로 알린다. 진행 중인 요청이 있으면 그 요청을 돌려준다. "
            + "준비된 지 24시간이 안 됐으면 429 RATE_LIMITED (실패한 요청은 세지 않는다)")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    DataExportResponse requestExport(AuthUser user) {
        return DataExportResponse.of(exportService.request(user.userId()));
    }

    @Operation(summary = "내 데이터 내보내기 목록", description = "최근 요청 최신순 5개. 다운로드 링크는 상세 조회에서 받는다")
    @GetMapping
    DataExportListResponse listExports(AuthUser user) {
        return new DataExportListResponse(exportService.list(user.userId()).stream()
                .map(DataExportResponse::of).toList());
    }

    @Operation(summary = "내 데이터 내보내기 상태", description = "READY 면 10분짜리 downloadUrl 과 fileName 을 준다(조회할 때마다 새로). "
            + "ZIP 과 그 안의 원본 링크는 expiresAt(준비 후 7일)에 만료된다. 다른 사람의 요청이면 404")
    @GetMapping("/{exportId}")
    DataExportResponse getExport(AuthUser user, @PathVariable long exportId) {
        return DataExportResponse.of(exportService.get(user.userId(), exportId));
    }
}
