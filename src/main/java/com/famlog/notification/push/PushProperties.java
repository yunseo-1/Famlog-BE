package com.famlog.notification.push;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 푸시 설정 (application.yml famlog.push, 사양서 4.2 NOTI-01).
 *
 * @param credentialsPath  FCM 서비스 계정 키 파일 경로 (저장소 밖). 비우면 보내지 않고 로그만 남긴다
 * @param fcmBaseUrl       FCM HTTP v1 주소
 * @param connectTimeout   FCM 연결 제한 시간
 * @param readTimeout      FCM 응답 제한 시간
 * @param previewMaxLength 미리보기 본문 최대 글자 수
 */
@ConfigurationProperties("famlog.push")
public record PushProperties(
        String credentialsPath,
        @DefaultValue("https://fcm.googleapis.com") String fcmBaseUrl,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("100") int previewMaxLength) {

    public boolean fcmEnabled() {
        return credentialsPath != null && !credentialsPath.isBlank();
    }
}
