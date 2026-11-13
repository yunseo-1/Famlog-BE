package com.famlog.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.famlog.common.web.TraceIdFilter;

import jakarta.servlet.RequestDispatcher;

/**
 * 컨트롤러 밖(필터 등)에서 난 에러가 /error 로 넘어왔을 때도 공통 형식으로 응답하는지 확인한다.
 * 실제 에러 디스패치에서는 TraceIdFilter가 다시 실행되지 않고 MDC도 비어 있으므로, 필터를 빼고
 * 원래 요청에서 남긴 요청 속성의 traceId를 쓰는지 본다.
 */
@WebMvcTest(FamlogErrorController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(FamlogErrorController.class)
class FamlogErrorControllerTest {

    @Autowired
    MockMvc mockMvc;

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
