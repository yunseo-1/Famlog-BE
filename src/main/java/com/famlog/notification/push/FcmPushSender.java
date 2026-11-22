package com.famlog.notification.push;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

import lombok.extern.slf4j.Slf4j;

/**
 * FCM HTTP v1 발송기. 서비스 계정 키로 OAuth 토큰을 받아 기기마다 messages:send 를 부른다.
 * <ul>
 * <li>data 메시지만 보낸다 (notification 없음, Android 우선순위 HIGH). 채널 선택·묶음·화면에 있을 때 숨기기는 앱이 한다</li>
 * <li>404 UNREGISTERED, 403 SENDER_ID_MISMATCH, 400 INVALID_ARGUMENT(토큰 형식)는 쓸 수 없는 토큰으로 돌려준다</li>
 * <li>429·5xx·네트워크 오류는 3번까지 보낸다 (1초, 2초 뒤. Retry-After 가 있으면 그만큼, 최대 10초). 메모리에서만 다시 보낸다</li>
 * <li>로그에는 상태 코드만 남긴다 (토큰·본문·응답 본문을 남기지 않는다)</li>
 * </ul>
 */
@Slf4j
public class FcmPushSender implements PushSender {

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    static final int MAX_ATTEMPTS = 3;
    private static final Duration MAX_WAIT = Duration.ofSeconds(10);

    /** 다시 보내기 전 기다리기 (테스트는 바꿔 끼운다) */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final RestClient restClient;
    private final String projectId;
    private final Supplier<String> accessToken;
    private final Sleeper sleeper;

    FcmPushSender(RestClient restClient, String projectId, Supplier<String> accessToken) {
        this(restClient, projectId, accessToken, duration -> Thread.sleep(duration));
    }

    FcmPushSender(RestClient restClient, String projectId, Supplier<String> accessToken, Sleeper sleeper) {
        this.restClient = restClient;
        this.projectId = projectId;
        this.accessToken = accessToken;
        this.sleeper = sleeper;
    }

    /** 서비스 계정 키 파일로 만든다. 프로젝트 ID는 키 파일에서 읽는다 */
    public static FcmPushSender fromServiceAccount(Path credentialsPath, RestClient restClient) {
        try (InputStream in = Files.newInputStream(credentialsPath)) {
            ServiceAccountCredentials account = ServiceAccountCredentials.fromStream(in);
            GoogleCredentials credentials = account.createScoped(SCOPE);
            return new FcmPushSender(restClient, account.getProjectId(), () -> tokenOf(credentials));
        } catch (IOException e) {
            throw new IllegalStateException("FCM 서비스 계정 키를 읽지 못했습니다: " + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public Set<String> send(List<PushMessage> messages) {
        Set<String> invalid = new HashSet<>();
        for (PushMessage message : messages) {
            if (!sendWithRetry(message)) {
                invalid.add(message.token());
            }
        }
        return invalid;
    }

    /** @return 토큰을 쓸 수 없으면 false */
    private boolean sendWithRetry(PushMessage message) {
        for (int attempt = 1; ; attempt++) {
            Duration wait;
            try {
                sendOnce(message);
                return true;
            } catch (HttpClientErrorException e) {
                if (isInvalidToken(e)) {
                    return false;
                }
                if (e.getStatusCode().value() != 429) {
                    log.warn("푸시 발송 실패: status={}", e.getStatusCode().value());
                    return true;
                }
                wait = retryAfter(e, attempt);
                log.warn("푸시 발송 실패: status=429, attempt={}", attempt);
            } catch (HttpServerErrorException e) {
                wait = retryAfter(e, attempt);
                log.warn("푸시 발송 실패: status={}, attempt={}", e.getStatusCode().value(), attempt);
            } catch (RestClientException | UncheckedIOException e) {
                wait = backoff(attempt);
                log.warn("푸시 발송 실패: error={}, attempt={}", e.getClass().getSimpleName(), attempt);
            }
            if (attempt >= MAX_ATTEMPTS) {
                return true;
            }
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
    }

    private void sendOnce(PushMessage message) {
        restClient.post()
                .uri("/v1/projects/{projectId}/messages:send", projectId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken.get())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", Map.of(
                        "token", message.token(),
                        "data", message.data(),
                        "android", Map.of("priority", "HIGH"))))
                .retrieve()
                .toBodilessEntity();
    }

    /** Retry-After(초)가 있으면 그만큼(최대 10초), 없으면 1초·2초 */
    static Duration retryAfter(HttpStatusCodeException e, int attempt) {
        HttpHeaders headers = e.getResponseHeaders();
        String header = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (header != null && header.strip().matches("\\d{1,5}")) {
            Duration requested = Duration.ofSeconds(Long.parseLong(header.strip()));
            return requested.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : requested;
        }
        return backoff(attempt);
    }

    private static Duration backoff(int attempt) {
        return Duration.ofSeconds(1L << (attempt - 1));
    }

    static boolean isInvalidToken(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        return switch (e.getStatusCode().value()) {
            case 404 -> body.contains("UNREGISTERED");
            case 403 -> body.contains("SENDER_ID_MISMATCH");
            case 400 -> body.contains("INVALID_ARGUMENT") && body.contains("registration token");
            default -> false;
        };
    }

    private static String tokenOf(GoogleCredentials credentials) {
        synchronized (credentials) {
            try {
                credentials.refreshIfExpired();
                return credentials.getAccessToken().getTokenValue();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
