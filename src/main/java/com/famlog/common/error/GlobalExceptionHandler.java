package com.famlog.common.error;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.famlog.common.web.TraceIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import lombok.extern.slf4j.Slf4j;

/**
 * 컨트롤러에서 나온 예외를 { code, message, traceId } 형식으로 바꾼다 (사양서 11.1, 11.2).
 * 예상하지 못한 예외는 내부 정보를 숨기고 INTERNAL_ERROR로 응답한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ErrorResponse> handleRateLimited(RateLimitedException e, HttpServletRequest request) {
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.getRetryAfterSeconds()))
                .body(ErrorResponse.of(e.getErrorCode(), e.getMessage(), TraceIdFilter.currentTraceId(request)));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handleBusiness(BusinessException e, HttpServletRequest request) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, e.getMessage(), TraceIdFilter.currentTraceId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return validation(fieldErrors, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorResponse> handleParameterValidation(HandlerMethodValidationException e,
            HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ErrorResponse.FieldError(
                                result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return validation(fieldErrors, request);
    }

    /** 클래스에 @Validated가 붙은 빈(컨트롤러·서비스)의 메서드 검증 실패. */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e,
            HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = e.getConstraintViolations().stream()
                .map(violation -> new ErrorResponse.FieldError(lastNode(violation.getPropertyPath()),
                        violation.getMessage()))
                .toList();
        return validation(fieldErrors, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e,
            HttpServletRequest request) {
        return validation(List.of(new ErrorResponse.FieldError(e.getParameterName(), "필수 값이에요.")), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e,
            HttpServletRequest request) {
        return validation(List.of(new ErrorResponse.FieldError(e.getName(), "형식이 올바르지 않아요.")), request);
    }

    /** JSON 문법 오류·타입 불일치. 원인 메시지에 요청 본문이 들어갈 수 있어 응답·로그에 남기지 않는다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpServletRequest request) {
        return validation(null, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNoResource(HttpServletRequest request) {
        return error(ErrorCode.NOT_FOUND, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpServletRequest request) {
        return error(ErrorCode.METHOD_NOT_ALLOWED, request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> handleOptimisticLock(HttpServletRequest request) {
        return error(ErrorCode.VERSION_CONFLICT, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("처리하지 못한 예외: {}", e.getClass().getName(), e);
        return error(ErrorCode.INTERNAL_ERROR, request);
    }

    /** "param.arg0.size" 같은 경로에서 마지막 이름(size)만 쓴다. */
    private static String lastNode(Path path) {
        String name = null;
        for (Path.Node node : path) {
            name = node.getName();
        }
        return name;
    }

    private ResponseEntity<ErrorResponse> validation(List<ErrorResponse.FieldError> fieldErrors,
            HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getStatus())
                .body(ErrorResponse.validation(fieldErrors, TraceIdFilter.currentTraceId(request)));
    }

    private ResponseEntity<ErrorResponse> error(ErrorCode code, HttpServletRequest request) {
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, TraceIdFilter.currentTraceId(request)));
    }
}
