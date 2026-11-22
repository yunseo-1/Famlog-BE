package com.famlog.user.dto;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import com.famlog.user.domain.DataExportRequest;
import com.famlog.user.service.DataExportService.ExportView;

import io.swagger.v3.oas.annotations.media.Schema;

/** 내 데이터 내보내기 API 응답 (사양서 4.1 ACC-02, 11.3 /users/me/data-exports). */
public final class DataExportDtos {

    private DataExportDtos() {
    }

    /**
     * 내보내기 요청 하나.
     *
     * @param status      REQUESTED·PROCESSING(준비 중), READY(받을 수 있음), FAILED(다시 요청), EXPIRED(7일 지남)
     * @param readyAt     준비된 시각 (READY·EXPIRED)
     * @param expiresAt   ZIP 과 그 안의 원본 링크가 만료되는 시각 (준비 후 7일)
     * @param sizeBytes   ZIP 크기
     * @param downloadUrl 상세 조회에서 READY 일 때만: 10분 동안 쓸 수 있는 다운로드 링크 (조회할 때마다 새로 만든다)
     * @param fileName    저장할 파일 이름 (famlog-export-yyyyMMdd.zip)
     */
    public record DataExportResponse(long id,
            @Schema(allowableValues = { "REQUESTED", "PROCESSING", "READY", "FAILED", "EXPIRED" }) String status,
            Instant requestedAt, Instant readyAt, Instant expiresAt, Long sizeBytes, URI downloadUrl,
            String fileName) {

        public static DataExportResponse of(DataExportRequest request) {
            return new DataExportResponse(request.getId(), request.getStatus(), request.getRequestedAt(),
                    request.getReadyAt(), request.getExpiresAt(), request.getSizeBytes(), null, null);
        }

        public static DataExportResponse of(ExportView view) {
            DataExportRequest request = view.request();
            return new DataExportResponse(request.getId(), request.getStatus(), request.getRequestedAt(),
                    request.getReadyAt(), request.getExpiresAt(), request.getSizeBytes(), view.downloadUrl(),
                    view.fileName());
        }
    }

    /** 최근 요청 최신순 5개 (다운로드 링크 없음) */
    public record DataExportListResponse(List<DataExportResponse> items) {
    }
}
