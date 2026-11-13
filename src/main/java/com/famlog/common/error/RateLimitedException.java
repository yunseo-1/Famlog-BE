package com.famlog.common.error;

import lombok.Getter;

/** 서비스 계층에서 판단한 요청 제한 (예: 초대 코드 연속 실패 잠금). 응답에 Retry-After 를 붙인다. */
@Getter
public class RateLimitedException extends BusinessException {

    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
}
