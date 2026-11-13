package com.famlog.common.error;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.web.TraceIdFilter;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 컨트롤러 밖(필터 등)에서 난 에러도 같은 { code, message, traceId } 형식으로 응답한다.
 * Spring Boot 기본 /error 응답(timestamp, path 등)을 대체한다.
 */
@RestController
public class FamlogErrorController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        HttpStatus status = resolveStatus(request);
        ErrorCode code = switch (status) {
            case BAD_REQUEST -> ErrorCode.VALIDATION_FAILED;
            case UNAUTHORIZED -> ErrorCode.AUTH_INVALID;
            case FORBIDDEN -> ErrorCode.GROUP_NOT_MEMBER;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
            case TOO_MANY_REQUESTS -> ErrorCode.RATE_LIMITED;
            default -> ErrorCode.INTERNAL_ERROR;
        };
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, TraceIdFilter.currentTraceId(request)));
    }

    private HttpStatus resolveStatus(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (status instanceof Integer value) {
            HttpStatus resolved = HttpStatus.resolve(value);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
