package com.famlog.common.web;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 모든 요청에 traceId를 붙인다 (사양서 9.8). 로그(MDC), 응답 헤더, 에러 응답 본문에 같은 값이 들어간다.
 * 클라이언트가 보낸 값은 쓰지 않는다 (로그 위조 방지).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    public static final String REQUEST_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(REQUEST_ATTRIBUTE, traceId);
        response.setHeader(HEADER, traceId);
        MDC.put(MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 현재 요청의 traceId. MDC가 비워진 뒤(에러 디스패치)에도 요청 속성에서 찾는다. */
    public static String currentTraceId(HttpServletRequest request) {
        String traceId = MDC.get(MDC_KEY);
        if (traceId == null && request != null) {
            traceId = (String) request.getAttribute(REQUEST_ATTRIBUTE);
        }
        return traceId;
    }
}
