package com.famlog.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.famlog.common.metrics.FamlogMetrics;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** FCM HTTP v1 요청 형식과 오류 응답 처리 (사양서 4.2 NOTI-01). 실제 FCM 대신 MockRestServiceServer. */
class FcmPushSenderTest {

    private static final String SEND_URL = "https://fcm.test/v1/projects/famlog-test/messages:send";

    private final List<Duration> sleeps = new ArrayList<>();
    private MockRestServiceServer server;
    private FcmPushSender sender;

    /** 단위 테스트에는 Spring 레지스트리가 없으므로 전역 레지스트리에 잠깐 붙인다 */
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void detachMeters() {
        Metrics.removeRegistry(meters);
    }

    @BeforeEach
    void setUp() {
        Metrics.addRegistry(meters);
        RestClient.Builder builder = RestClient.builder().baseUrl("https://fcm.test");
        server = MockRestServiceServer.bindTo(builder).build();
        sleeps.clear();
        sender = new FcmPushSender(builder.build(), "famlog-test", () -> "oauth-access-token", sleeps::add);
    }

    @Test
    void data_메시지만_우선순위_HIGH로_보낸다() {
        server.expect(requestTo(SEND_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer oauth-access-token"))
                .andExpect(jsonPath("$.message.token").value("device-token"))
                .andExpect(jsonPath("$.message.data.type").value("CHAT_MESSAGE"))
                .andExpect(jsonPath("$.message.data.groupId").value("7"))
                .andExpect(jsonPath("$.message.android.priority").value("HIGH"))
                .andExpect(jsonPath("$.message.notification").doesNotExist())
                .andRespond(withSuccess("{\"name\":\"projects/famlog-test/messages/1\"}", MediaType.APPLICATION_JSON));

        assertThat(sender.send(List.of(message("device-token")))).isEmpty();
        server.verify();
    }

    @Test
    void 지워진_앱_다른_프로젝트_잘못된_형식의_토큰은_쓸_수_없는_토큰으로_돌려준다() {
        respond(HttpStatus.NOT_FOUND, error(404, "NOT_FOUND", "Requested entity was not found.", "UNREGISTERED"));
        respond(HttpStatus.FORBIDDEN, error(403, "PERMISSION_DENIED", "SenderId mismatch", "SENDER_ID_MISMATCH"));
        respond(HttpStatus.BAD_REQUEST, error(400, "INVALID_ARGUMENT",
                "The registration token is not a valid FCM registration token", "INVALID_ARGUMENT"));

        assertThat(sender.send(List.of(message("a"), message("b"), message("c")))).containsExactlyInAnyOrder("a", "b",
                "c");
        server.verify();
    }

    @Test
    void 토큰_문제가_아닌_400은_다시_보내지_않고_토큰도_지우지_않는다() {
        respond(HttpStatus.BAD_REQUEST, error(400, "INVALID_ARGUMENT", "Invalid JSON payload received.",
                "INVALID_ARGUMENT"));
        server.expect(requestTo(SEND_URL)).andRespond(withSuccess());

        assertThat(sender.send(List.of(message("a"), message("b")))).isEmpty();
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void 서버_오류는_1초_2초_뒤_다시_보내고_세_번째에_성공하면_끝난다() {
        server.expect(requestTo(SEND_URL)).andRespond(withServerError());
        server.expect(requestTo(SEND_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(SEND_URL)).andRespond(withSuccess());

        assertThat(sender.send(List.of(message("a")))).isEmpty();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        server.verify();
    }

    @Test
    void 요청_한도_초과는_Retry_After만큼_기다리고_세_번_실패하면_포기하고_다음_기기로_보낸다() {
        HttpHeaders retryAfter = new HttpHeaders();
        retryAfter.set(HttpHeaders.RETRY_AFTER, "3");
        HttpHeaders tooLong = new HttpHeaders();
        tooLong.set(HttpHeaders.RETRY_AFTER, "120");
        server.expect(requestTo(SEND_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryAfter));
        server.expect(requestTo(SEND_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(tooLong));
        server.expect(requestTo(SEND_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(SEND_URL)).andRespond(withSuccess());

        double gaveUpBefore = Metrics.counter(FamlogMetrics.PUSH_FAILURES, "reason", "gave_up").count();

        assertThat(sender.send(List.of(message("a"), message("b")))).isEmpty();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3), Duration.ofSeconds(10));
        assertThat(Metrics.counter(FamlogMetrics.PUSH_FAILURES, "reason", "gave_up").count())
                .isEqualTo(gaveUpBefore + 1);
        server.verify();
    }

    @Test
    void 로그용_문자열에_토큰과_본문이_없다() {
        PushMessage message = new PushMessage("secret-token", Map.of("type", "CHAT_MESSAGE", "body", "비밀 이야기"));

        assertThat(message.toString()).doesNotContain("secret-token").doesNotContain("비밀");
    }

    @Test
    void 키_경로의_물결표는_홈_디렉터리로_바꾼다() {
        assertThat(PushConfig.expandHome("~/.famlog/key.json"))
                .isEqualTo(Path.of(System.getProperty("user.home"), ".famlog", "key.json"));
        assertThat(PushConfig.expandHome("/etc/famlog/key.json")).isEqualTo(Path.of("/etc/famlog/key.json"));
    }

    private void respond(HttpStatus status, String body) {
        server.expect(requestTo(SEND_URL))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private static PushMessage message(String token) {
        return new PushMessage(token, Map.of("type", "CHAT_MESSAGE", "groupId", "7"));
    }

    private static String error(int code, String status, String message, String fcmErrorCode) {
        return """
                {"error":{"code":%d,"message":"%s","status":"%s","details":[
                  {"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"%s"}]}}
                """.formatted(code, message, status, fcmErrorCode);
    }
}
