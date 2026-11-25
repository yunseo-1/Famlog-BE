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
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.famlog.common.web.TraceIdFilter;
import com.famlog.support.IntegrationTest;

/** 전체 컨텍스트가 실제 DB로 뜨고 운영 경계(Actuator 노출, API 문서, 에러 형식, 로그 마스킹)가 맞는지 확인한다. */
@ExtendWith(OutputCaptureExtension.class)
class ApplicationIntegrationTest extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ApplicationIntegrationTest.class);

    @LocalManagementPort
    int managementPort;

    @Test
    void health는_관리_포트에서_공개되고_상세_정보는_숨긴다() {
        ResponseEntity<String> health = management().get().uri("/actuator/health").retrieve().toEntity(String.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).contains("\"status\":\"UP\"").doesNotContain("components");
    }

    @Test
    void 앱_포트에는_상태_확인_경로만_있고_Actuator는_없다() throws Exception {
        mockMvc.perform(get("/livez")).andExpect(status().isOk());
        mockMvc.perform(get("/readyz")).andExpect(status().isOk());
        RestClient app = RestClient.create("http://localhost:" + port);
        for (String path : new String[] { "/actuator/health", "/actuator/prometheus" }) {
            int status = app.get().uri(path).exchange((request, response) -> response.getStatusCode().value());
            assertThat(status).as(path).isEqualTo(404);
        }
    }

    @Test
    void Prometheus_메트릭은_관리_포트에서_나오고_운영_지표가_들어_있다() {
        String metrics = management().get().uri("/actuator/prometheus").retrieve().body(String.class);
        assertThat(metrics).contains("http_server_requests_seconds", "famlog_auth_refresh_reuse_total",
                "famlog_batch_failures_total{", "famlog_push_failures_total{", "famlog_ai_cost_month_usd",
                "famlog_ai_budget_usd", "famlog_ai_jobs_queued", "famlog_stream_dlq_length{", "famlog_worker_alive",
                "application=\"famlog-api\"");
    }

    private RestClient management() {
        return RestClient.create("http://localhost:" + managementPort);
    }

    @Test
    void health_외_Actuator_엔드포인트는_로그인해도_노출하지_않는다() throws Exception {
        for (String path : new String[] { "/actuator/env", "/actuator/beans", "/actuator/configprops" }) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).with(asUser(1)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void OpenAPI_문서는_토큰_없이_볼_수_있다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists());
    }

    @Test
    void 토큰_없이_API를_부르면_401_AUTH_INVALID() throws Exception {
        mockMvc.perform(get("/api/v1/no-such-api"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(TraceIdFilter.HEADER))
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 없는_API는_공통_에러_형식과_traceId로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v1/no-such-api").with(asUser(1)))
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
