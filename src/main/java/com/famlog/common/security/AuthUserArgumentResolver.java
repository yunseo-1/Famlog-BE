package com.famlog.common.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;

/** 컨트롤러 파라미터 AuthUser 를 SecurityContext의 JWT에서 채운다. */
public class AuthUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthUser.class.equals(parameter.getParameterType());
    }

    @Override
    public AuthUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return AuthUser.from(SecurityContextHolder.getContext().getAuthentication())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID));
    }
}
