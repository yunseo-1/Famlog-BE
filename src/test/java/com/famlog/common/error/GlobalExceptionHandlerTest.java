package com.famlog.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.famlog.common.web.TraceIdFilter;
import com.famlog.support.IntegrationTest;

/** 컨트롤러 예외 → 공통 에러 형식 (사양서 11.1, 11.2). 실제 보안 필터 체인을 거친다. */
class GlobalExceptionHandlerTest extends IntegrationTest {

    private static final long USER_ID = 1;

    @Test
    void 업무_예외는_에러코드의_HTTP_상태와_code로_응답한다() throws Exception {
        mockMvc.perform(get("/test/business").with(asUser(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"))
                .andExpect(jsonPath("$.message").value("이 가족 그룹의 구성원이 아니에요."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void 요청_본문_검증_실패는_400_VALIDATION_FAILED와_fieldErrors로_응답한다() throws Exception {
        mockMvc.perform(post("/test/body").with(asUser(USER_ID)).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").isNotEmpty());
    }

    @Test
    void 요청_파라미터_검증_실패도_400_VALIDATION_FAILED로_응답한다() throws Exception {
        mockMvc.perform(get("/test/param").with(asUser(USER_ID)).param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
    }

    @Test
    void Validated_클래스의_파라미터_검증_실패도_400_VALIDATION_FAILED로_응답한다() throws Exception {
        mockMvc.perform(get("/test/validated-param").with(asUser(USER_ID)).param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
    }

    @Test
    void 필수_파라미터_누락과_타입_불일치는_400으로_응답한다() throws Exception {
        mockMvc.perform(get("/test/param").with(asUser(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
        mockMvc.perform(get("/test/param").with(asUser(USER_ID)).param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
    }

    @Test
    void 잘못된_JSON은_요청_내용을_드러내지_않고_400으로_응답한다() throws Exception {
        MvcResult result = mockMvc.perform(post("/test/body").with(asUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"비밀 메시지"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("비밀 메시지");
    }

    @Test
    void 예상하지_못한_예외는_내부_정보를_숨기고_500_INTERNAL_ERROR로_응답한다() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/unexpected").with(asUser(USER_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("db-password", "IllegalStateException", "at com.famlog");
    }

    @Test
    void 없는_경로는_404_NOT_FOUND로_응답한다() throws Exception {
        mockMvc.perform(get("/test/no-such-path").with(asUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 지원하지_않는_메서드는_405로_응답한다() throws Exception {
        mockMvc.perform(post("/test/business").with(asUser(USER_ID)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 에러_응답의_traceId는_응답_헤더_X_Trace_Id와_같다() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/business").with(asUser(USER_ID)))
                .andExpect(header().exists(TraceIdFilter.HEADER))
                .andReturn();
        String headerTraceId = result.getResponse().getHeader(TraceIdFilter.HEADER);
        assertThat(result.getResponse().getContentAsString()).contains("\"traceId\":\"" + headerTraceId + "\"");
    }
}
