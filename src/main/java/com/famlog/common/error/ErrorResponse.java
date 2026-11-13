package com.famlog.common.error;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 공통 에러 응답 (사양서 11.1). fieldErrors는 VALIDATION_FAILED일 때만 들어간다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String traceId, List<FieldError> fieldErrors) {

    public record FieldError(String field, String reason) {
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, String traceId) {
        return new ErrorResponse(errorCode.name(), message, traceId, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String traceId) {
        return of(errorCode, errorCode.getMessage(), traceId);
    }

    public static ErrorResponse validation(List<FieldError> fieldErrors, String traceId) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return new ErrorResponse(code.name(), code.getMessage(), traceId, fieldErrors);
    }
}
