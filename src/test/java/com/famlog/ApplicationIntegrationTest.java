package com.famlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.famlog.common.web.TraceIdFilter;
import com.famlog.support.IntegrationTest;

/** 전체 컨텍스트가 실제 DB로 뜨고 운영 경계(Actuator 노출, API 문서, 에러 형식, 로그 마스킹)가 맞는지 확인한다. */
@ExtendWith(OutputCaptureExtension.class)
class ApplicationIntegrationTest extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ApplicationIntegrationTest.class);

    @Test
    void health는_공개되고_상세_정보는_숨긴다() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void health_외_Actuator_엔드포인트는_노출하지_않는다() throws Exception {
        for (String path : new String[] { "/actuator/env", "/actuator/beans", "/actuator/configprops" }) {
            mockMvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void OpenAPI_문서를_제공한다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists());
    }

    @Test
    void 없는_API는_공통_에러_형식과_traceId로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v1/no-such-api"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists(TraceIdFilter.HEADER))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 실제_로그_출력에서_토큰과_메시지_본문이_가려진다(CapturedOutput output) {
        log.info("로그인 처리 refreshToken={} body={}", "r4nd0m-refresh-value", "이번 토요일 할머니 댁 가자");

        assertThat(output.getOut())
                .contains("로그인 처리")
                .doesNotContain("r4nd0m-refresh-value")
                .doesNotContain("할머니 댁");
    }
}
