package com.famlog.auth.social;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 소셜 로그인 설정. 값 자체는 비밀이 아니지만 환경마다 다르므로 local은 infra/.env, dev·prod는 환경 변수로 넣는다.
 *
 * @param kakao  카카오 앱 ID (내 애플리케이션 > 앱 ID, 숫자). 다른 앱에서 발급된 토큰을 거부하는 데 쓴다
 * @param google Google OAuth 웹(서버) 클라이언트 ID. ID 토큰의 aud와 비교한다
 */
@ConfigurationProperties("famlog.social")
public record SocialLoginProperties(Kakao kakao, Google google) {

    public record Kakao(Long appId, @DefaultValue("https://kapi.kakao.com") String baseUrl) {
    }

    public record Google(@DefaultValue List<String> clientIds,
            @DefaultValue("https://www.googleapis.com/oauth2/v3/certs") String jwkSetUri) {
    }
}
