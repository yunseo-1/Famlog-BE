package com.famlog.auth.service;

/**
 * 가입 정보 (SCR-01).
 *
 * @param termsVersion   사용자가 동의한 이용약관 버전
 * @param privacyVersion 사용자가 동의한 개인정보 수집·이용 버전
 */
public record SignupCommand(String nickname, String birthYm, String termsVersion, String privacyVersion) {

    @Override
    public String toString() {
        // 출생연도월은 로그에 남기지 않는다 (사양서 9.8)
        return "SignupCommand[termsVersion=" + termsVersion + ", privacyVersion=" + privacyVersion + "]";
    }
}
