package com.famlog.support.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;

/**
 * GlobalExceptionHandlerTest용 엔드포인트 (테스트 소스에만 있다).
 * 테스트 클래스 안에 중첩하면 컴포넌트 스캔에서 빠지므로 별도 클래스로 둔다.
 * {@code @Validated} 없이 Spring MVC 기본 메서드 검증 → HandlerMethodValidationException.
 */
@RestController
public class ErrorTestController {

    public record CreateRequest(@NotBlank String title) {
    }

    @GetMapping("/test/business")
    void business() {
        throw new BusinessException(ErrorCode.GROUP_NOT_MEMBER);
    }

    @PostMapping("/test/body")
    void body(@Valid @RequestBody CreateRequest request) {
    }

    @GetMapping("/test/param")
    void param(@RequestParam @Max(100) int size) {
    }

    @GetMapping("/test/unexpected")
    void unexpected() {
        throw new IllegalStateException("db-password=secret 연결 실패");
    }
}
