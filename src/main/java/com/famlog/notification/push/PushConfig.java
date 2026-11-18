package com.famlog.notification.push;

import java.net.http.HttpClient;
import java.nio.file.Path;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * 발송기와 발송 스레드. 푸시는 커밋 뒤 이 스레드에서 보낸다 (메시지 전송 응답이 FCM을 기다리지 않게).
 * 큐가 넘치면 버린다 (놓친 메시지는 앱이 다시 열 때 채팅 내역으로 받는다).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PushProperties.class)
public class PushConfig {

    @Bean
    PushSender pushSender(PushProperties properties) {
        if (!properties.fcmEnabled()) {
            log.info("FCM 키가 설정되지 않아 푸시를 보내지 않습니다 (famlog.push.credentials-path)");
            return new LoggingPushSender();
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.fcmBaseUrl())
                .requestFactory(requestFactory)
                .build();
        return FcmPushSender.fromServiceAccount(expandHome(properties.credentialsPath()), restClient);
    }

    /** ~/ 로 시작하면 홈 디렉터리로 바꾼다 (.env 에 ~/.famlog/... 로 적어도 되게) */
    static Path expandHome(String path) {
        String trimmed = path.strip();
        return trimmed.startsWith("~/") ? Path.of(System.getProperty("user.home"), trimmed.substring(2)) : Path.of(trimmed);
    }

    @Bean
    ThreadPoolTaskExecutor pushExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("push-");
        executor.setRejectedExecutionHandler((task, pool) -> log.warn("푸시 큐가 가득 차서 버립니다"));
        return executor;
    }
}
