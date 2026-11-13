package com.famlog.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void 요청_처리_중에는_MDC와_응답_헤더와_요청_속성에_같은_traceId가_있다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> traceIdInChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(traceIdInChain.get()).matches("[0-9a-f]{32}");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(traceIdInChain.get());
        assertThat(request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)).isEqualTo(traceIdInChain.get());
    }

    @Test
    void 요청이_끝나면_예외가_나도_MDC를_비운다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("실패");
            });
        } catch (Exception ignored) {
            // 예외 전파 여부가 아니라 MDC 정리를 확인한다
        }

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void 클라이언트가_보낸_traceId_헤더는_쓰지_않는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "forged-trace-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isNotEqualTo("forged-trace-id");
    }

    @Test
    void 요청마다_다른_traceId를_만든다() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), first, (req, res) -> {
        });
        filter.doFilter(new MockHttpServletRequest(), second, (req, res) -> {
        });

        assertThat(first.getHeader(TraceIdFilter.HEADER)).isNotEqualTo(second.getHeader(TraceIdFilter.HEADER));
    }
}
