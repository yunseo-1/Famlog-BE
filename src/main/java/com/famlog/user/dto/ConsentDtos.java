package com.famlog.user.dto;

import java.time.Instant;
import java.util.List;

import com.famlog.user.domain.ConsentType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 동의 API 요청·응답 (사양서 8.3, 11.3 /users/me/consents). */
public final class ConsentDtos {

    private ConsentDtos() {
    }

    /**
     * @param agreed     true 동의, false 철회
     * @param docVersion 동의할 때 필수: 앱이 보여 준 문서 버전 (현재 버전과 다르면 400)
     */
    public record UpdateConsentRequest(@NotNull Boolean agreed,
            @Schema(description = "동의할 때 앱이 보여 준 문서 버전", example = "1.0") @Size(max = 20) String docVersion) {
    }

    /**
     * 동의 하나의 현재 상태.
     *
     * @param docVersion        동의한 문서 버전 (동의 안 했으면 null)
     * @param currentVersion    지금 문서 버전 (아직 API 로 받지 않는 동의는 null)
     * @param reconsentRequired 동의했지만 그 뒤 문서가 바뀌어 다시 받아야 한다
     */
    public record ConsentResponse(ConsentType type, boolean agreed, String docVersion, Instant agreedAt,
            String currentVersion, boolean reconsentRequired) {
    }

    /** 종류 5개 모두 (TERMS, PRIVACY, AI_ANALYSIS, OVERSEAS_TRANSFER, LOCATION 순) */
    public record ConsentListResponse(List<ConsentResponse> items) {
    }
}
