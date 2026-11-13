package com.famlog.support.web;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;

/** 클래스에 {@code @Validated} → AOP 메서드 검증 → ConstraintViolationException (GlobalExceptionHandlerTest용). */
@RestController
@Validated
public class ValidatedTestController {

    @GetMapping("/test/validated-param")
    void param(@RequestParam @Max(100) int size) {
    }
}
