package com.famlog.user.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.famlog.user.domain.ConsentType;

/**
 * 현재 약관·개인정보처리방침·위치기반서비스 약관 버전 (사양서 8.3). 앱이 보여준 버전이 이 값과 같아야 동의로 기록한다.
 * 문서를 고치면 버전을 올린다 (재동의 흐름은 S20).
 */
@ConfigurationProperties("famlog.consent")
public record ConsentProperties(String termsVersion, String privacyVersion, String locationVersion) {

    /** 그 동의의 현재 문서 버전. 아직 API 로 받지 않는 동의(AI 분석·국외 이전, S16)는 null */
    public String currentVersion(ConsentType type) {
        return switch (type) {
            case TERMS -> termsVersion;
            case PRIVACY -> privacyVersion;
            case LOCATION -> locationVersion;
            case AI_ANALYSIS, OVERSEAS_TRANSFER -> null;
        };
    }
}
