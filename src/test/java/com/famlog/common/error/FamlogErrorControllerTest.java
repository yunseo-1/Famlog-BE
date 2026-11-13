package com.famlog.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.famlog.common.web.TraceIdFilter;
import com.famlog.support.IntegrationTest;

import jakarta.servlet.RequestDispatcher;

/**
 * 컨트롤러 밖(필터 등)에서 난 에러가 /error 로 넘어왔을 때도 공통 형식으로 응답하는지 확인한다.
 * 에러 디스패치는 같은 요청이므로 처음 요청에서 만든 traceId(요청 속성)를 그대로 써야 한다.
 * /error 는 토큰 없이 접근 가능해야 한다 (인증 실패 응답도 이 경로를 탈 수 있다).
 */
class FamlogErrorControllerTest extends IntegrationTest {

    @ParameterizedTest
    @CsvSource({
            "400, VALIDATION_FAILED",
            "401, AUTH_INVALID",
            "403, GROUP_NOT_MEMBER",
            "404, NOT_FOUND",
            "429, RATE_LIMITED",
            "500, INTERNAL_ERROR",
            "503, INTERNAL_ERROR",
    })
    void 에러_디스패치를_공통_에러_형식으로_바꾼다(int servletStatus, String expectedCode) throws Exception {
        ErrorCode expected = ErrorCode.valueOf(expectedCode);
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, servletStatus)
                        .requestAttr(TraceIdFilter.REQUEST_ATTRIBUTE, "trace-from-filter"))
                .andExpect(status().is(expected.getStatus().value()))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.message").value(expected.getMessage()))
                .andExpect(jsonPath("$.traceId").value("trace-from-filter"))
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());
    }
}
