package com.famlog.auth.social;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SocialLoginProperties.class)
public class SocialLoginConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    KakaoTokenVerifier kakaoTokenVerifier(SocialLoginProperties properties) {
        SocialLoginProperties.Kakao kakao = properties.kakao();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        RestClient restClient = RestClient.builder()
                .baseUrl(kakao == null ? "https://kapi.kakao.com" : kakao.baseUrl())
                .requestFactory(requestFactory)
                .build();
        return new KakaoTokenVerifier(restClient, kakao == null ? null : kakao.appId());
    }

    /** Google 공개 키는 첫 검증 때 가져와 캐시한다 (기동 시 외부 호출 없음) */
    @Bean
    GoogleIdTokenVerifier googleIdTokenVerifier(SocialLoginProperties properties) {
        SocialLoginProperties.Google google = properties.google();
        String jwkSetUri = google == null ? "https://www.googleapis.com/oauth2/v3/certs" : google.jwkSetUri();
        List<String> clientIds = google == null ? List.of()
                : google.clientIds().stream().filter(id -> id != null && !id.isBlank()).toList();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(GoogleIdTokenVerifier.validator(clientIds));
        return new GoogleIdTokenVerifier(decoder, !clientIds.isEmpty());
    }
}
