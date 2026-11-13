package com.famlog.common.error;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * API 에러 코드 (사양서 11.2). 앱은 code 값으로 분기하고, message는 사용자에게 그대로 보여줄 수 있는 문장이다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력한 내용을 다시 확인해 주세요."),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료됐어요."),
    AUTH_INVALID(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),
    GROUP_NOT_MEMBER(HttpStatus.FORBIDDEN, "이 가족 그룹의 구성원이 아니에요."),
    GROUP_ROLE_REQUIRED(HttpStatus.FORBIDDEN, "그룹 관리자만 할 수 있어요."),
    AGE_RESTRICTED(HttpStatus.FORBIDDEN, "만 14세 이상만 가입할 수 있어요. 보호자와 함께 사용하는 기능은 곧 제공돼요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없어요."),
    USER_NOT_REGISTERED(HttpStatus.NOT_FOUND, "처음 오셨네요. 가입을 진행해 주세요."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청이에요."),
    CANDIDATE_ALREADY_DECIDED(HttpStatus.CONFLICT, "다른 가족이 먼저 처리했어요."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 가족이 먼저 수정했어요. 새로고침 후 다시 시도해 주세요."),
    INVITE_EXPIRED(HttpStatus.GONE, "만료된 초대 코드예요."),
    CANDIDATE_MISSING_FIELDS(HttpStatus.UNPROCESSABLE_CONTENT, "필수 항목을 채워 주세요."),
    UPGRADE_REQUIRED(HttpStatus.UPGRADE_REQUIRED, "앱을 최신 버전으로 업데이트해 주세요."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 생겼어요. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;
}
