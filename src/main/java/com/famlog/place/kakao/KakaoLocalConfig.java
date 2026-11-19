package com.famlog.place.kakao;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.famlog.place.service.PlaceProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlaceProperties.class)
public class KakaoLocalConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    KakaoLocalClient kakaoLocalClient(PlaceProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.kakaoBaseUrl())
                .requestFactory(requestFactory)
                .build();
        return new RestKakaoLocalClient(restClient, properties.kakaoRestApiKey());
    }
}
