package com.famlog.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.famlog.admin.service.AdminProperties;
import com.famlog.common.error.ErrorResponseWriter;

import tools.jackson.databind.json.JsonMapper;

/** 관리자 토큰: 설정이 비어 있으면 관리자 API 가 꺼진다(404), 관리자 API 가 아닌 경로는 보지 않는다 */
class AdminTokenFilterTest {

    private final ErrorResponseWriter writer = new ErrorResponseWriter(JsonMapper.builder().build());

    @Test
    void 토큰_설정이_비어_있으면_404() throws Exception {
        MockHttpServletResponse response = run(new AdminProperties(""), "/admin/api/reports", "anything");

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void 맞는_토큰이면_통과하고_관리자_API가_아니면_보지_않는다() throws Exception {
        assertThat(run(new AdminProperties("secret"), "/admin/api/reports", "secret").getStatus()).isEqualTo(200);
        assertThat(run(new AdminProperties("secret"), "/admin/api/reports", "secreT").getStatus()).isEqualTo(401);
        assertThat(run(new AdminProperties(""), "/api/v1/users/me", null).getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse run(AdminProperties properties, String uri, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (token != null) {
            request.addHeader(AdminTokenFilter.HEADER, token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        new AdminTokenFilter(properties, writer).doFilter(request, response, new MockFilterChain());
        return response;
    }
}
