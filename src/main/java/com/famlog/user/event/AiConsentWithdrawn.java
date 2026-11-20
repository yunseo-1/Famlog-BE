package com.famlog.user.event;

/** AI 분석 동의를 철회했다 (사양서 8.3 "철회 = 분석 제외"). 같은 트랜잭션에서 내 모든 그룹의 분석 제외를 켠다 */
public record AiConsentWithdrawn(long userId) {
}
