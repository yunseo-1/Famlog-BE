package com.famlog.user.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 현재 약관·개인정보처리방침 버전 (사양서 8.3). 가입 시 앱이 보여준 버전이 이 값과 같아야 동의로 기록한다.
 * 문서를 고치면 버전을 올린다 (재동의 흐름은 S20).
 */
@ConfigurationProperties("famlog.consent")
public record ConsentProperties(String termsVersion, String privacyVersion) {
}
