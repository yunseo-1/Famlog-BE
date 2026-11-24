package com.famlog.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.famlog.candidate.service.AiTriggerService;
import com.famlog.support.AuthApi;
import com.famlog.support.FakeSocialLoginVerifier;
import com.famlog.support.GroupApi;
import com.famlog.support.PushIntegrationTest;
import com.famlog.user.service.DataExportService;

/**
 * SEC-T9 (사양서 9.8, 9.9): 개인정보 표식(카나리)을 로그인·채팅·푸시·AI 분석 요청·내보내기 경로로 흘려보내고, 그동안 나온 로그에
 * 하나도 없는지 확인한다. 로컬과 같은 DEBUG 수준으로 본다. 테스트 전체 로그는 scripts/scan-logs.sh 가 CI 에서 다시 검사한다
 */
@ExtendWith(OutputCaptureExtension.class)
class LogCanaryTest extends PushIntegrationTest {

    /** scripts/scan-logs.sh 의 카나리와 같아야 한다 */
    static final String CANARY = "kanari7f3a9c";

    @Autowired
    AiTriggerService triggerService;

    @Autowired
    DataExportService exportService;

    @Test
    void 메시지_본문_토큰_출생연도월이_로그에_남지_않는다(CapturedOutput output) throws Exception {
        // 컨텍스트를 새로 띄우지 않도록 수준만 잠깐 바꾼다 (다른 테스트와 컨텍스트를 같이 쓴다)
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        logging.setLogLevel("com.famlog", LogLevel.DEBUG);
        try {
            flow(output);
        } finally {
            logging.setLogLevel("com.famlog", null);
        }
    }

    private void flow(CapturedOutput output) throws Exception {
        String providerToken = FakeSocialLoginVerifier.token("provider-" + CANARY);
        String body = mockMvc.perform(AuthApi.signup("kakao", providerToken, "device-" + CANARY, "엄마",
                        "198707", AuthApi.TERMS_VERSION, AuthApi.PRIVACY_VERSION))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long mom = Long.parseLong(AuthApi.read(body, "$.user.id"));
        String refreshToken = AuthApi.read(body, "$.tokens.refreshToken");
        String accessToken = AuthApi.read(body, "$.tokens.accessToken");
        mockMvc.perform(AuthApi.refresh(refreshToken)).andExpect(status().isOk());
        mockMvc.perform(AuthApi.login("kakao", providerToken, "device-" + CANARY)).andExpect(status().isOk());

        long dad = createUser();
        String dadDevice = "fcm-" + CANARY + "-dad";
        registerToken(dad, createSession(dad), dadDevice);
        long group = GroupApi.createGroup(mockMvc, mom, "우리 가족");
        GroupApi.addMember(mockMvc, group, mom, dad);
        GroupApi.setAiAnalysis(mockMvc, group, mom, true);

        String message = "이번 토요일 11시에 " + CANARY + " 댁 가자";
        send(group, mom, message);
        pushSender.await(dadDevice, data -> "CHAT_MESSAGE".equals(data.get("type")));
        triggerService.runDue(Instant.now().plusSeconds(60));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ai_jobs WHERE group_id = ?", Integer.class,
                group)).isPositive();

        mockMvc.perform(post("/api/v1/users/me/data-exports").with(asUser(mom))).andExpect(status().isAccepted());
        for (Optional<Long> next = exportService.claimNext(); next.isPresent(); next = exportService.claimNext()) {
            exportService.process(next.get());
        }

        String logs = output.getAll();
        assertThat(logs).as("로그가 잡혔는지").contains("내보내기 준비");
        assertThat(logs).doesNotContain(CANARY, accessToken, refreshToken, "198707", "1987-07");
    }
}
