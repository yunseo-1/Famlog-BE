package com.famlog.common.error;

import java.util.List;

import lombok.Getter;

/**
 * 서비스 계층에서 던지는 예상된 실패. GlobalExceptionHandler가 { code, message, traceId } 응답으로 바꾼다.
 * message에 개인정보·메시지 본문을 넣지 않는다 (사양서 9.8).
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 응답 fieldErrors (예: 후보의 채워야 할 항목). 없으면 null */
    private final List<ErrorResponse.FieldError> fieldErrors;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, List<ErrorResponse.FieldError> fieldErrors) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors;
    }
}
