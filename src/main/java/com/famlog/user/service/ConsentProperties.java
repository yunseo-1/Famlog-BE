package com.famlog.user.service;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.famlog.user.domain.ConsentType;

/**
 * 현재 약관·개인정보처리방침·위치기반서비스 약관 버전과 AI 분석 안내 (사양서 8.3, 8.7). 앱이 보여준 버전이 이 값과 같아야
 * 동의로 기록한다. 문서를 고치면 버전을 올린다 (재동의 흐름은 S20).
 */
@ConfigurationProperties("famlog.consent")
public record ConsentProperties(String termsVersion, String privacyVersion, String locationVersion,
        AiNotice aiNotice) {

    /**
     * AI 분석 안내 (SET-01: 무엇이 어디로 전송되는지 + 개인정보 국외 이전 고지). AI_ANALYSIS·OVERSEAS_TRANSFER 는 이 버전으로
     * 함께 기록한다. 문구를 고치면 version 을 올린다 → 모두 다시 안내받는다
     *
     * @param sentItems    LLM 으로 보내는 것
     * @param notSentItems 보내지 않는 것
     */
    public record AiNotice(String version, String recipient, String country, List<String> sentItems,
            List<String> notSentItems, String purpose, String retention, String refusal) {
    }

    /** 그 동의의 현재 문서 버전 */
    public String currentVersion(ConsentType type) {
        return switch (type) {
            case TERMS -> termsVersion;
            case PRIVACY -> privacyVersion;
            case LOCATION -> locationVersion;
            case AI_ANALYSIS, OVERSEAS_TRANSFER -> aiNotice.version();
        };
    }
}
