package com.famlog.notification.domain;

import com.famlog.auth.domain.Platform;
import com.famlog.common.domain.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 푸시 토큰 (사양서 10.2 device_tokens). 로그인 세션 하나에 토큰 하나.
 * 세션이 폐기되면(로그아웃·원격 로그아웃·계정 삭제) 함께 지운다 (사양서 8.2). 토큰 값은 로그에 남기지 않는다.
 */
@Getter
@Entity
@Table(name = "device_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceToken extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Platform platform;

    @Column(name = "fcm_token", nullable = false, updatable = false)
    private String fcmToken;

    @Column(name = "app_version")
    private String appVersion;

    public DeviceToken(Long userId, Long sessionId, Platform platform, String fcmToken, String appVersion) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.platform = platform;
        this.fcmToken = fcmToken;
        this.appVersion = appVersion;
    }

    public void updateAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }
}
