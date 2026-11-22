package com.famlog.common.cleanup;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 운영 정리 배치 (사양서 8.2 보관·파기, 9장 감사 로그 1년). 기간이 지난 행·파일을 지운다.
 *
 * @param enabled              그룹 파기(04:30)·미디어 정리(매시)·보관 기간 정리(04:45) 배치. 테스트는 끄고 직접 부른다
 * @param unattachedMediaAfter 메시지에 붙지 않은 미디어를 지우기까지
 * @param sessionsAfter        만료·폐기된 로그인 세션·리프레시 토큰 (접속 기록 3개월)
 * @param invitesAfter         만료·폐기된 초대 코드
 * @param reminderSendsAfter   일정 리마인드 발송 기록 (일정 시각 기준)
 * @param locationLogsAfter    위치정보 이용·제공 사실 확인자료 (위치정보법 최소 6개월)
 * @param reportsAfter         처리한 신고
 * @param candidatesAfter      AI 후보·근거·피드백과 AI 요청 기록
 * @param auditLogsAfter       감사 로그
 * @param dataExportsAfter     끝난(실패·만료) 내보내기 요청
 */
@ConfigurationProperties("famlog.cleanup")
public record CleanupProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("24h") Duration unattachedMediaAfter,
        @DefaultValue("90d") Duration sessionsAfter,
        @DefaultValue("30d") Duration invitesAfter,
        @DefaultValue("30d") Duration reminderSendsAfter,
        @DefaultValue("180d") Duration locationLogsAfter,
        @DefaultValue("365d") Duration reportsAfter,
        @DefaultValue("365d") Duration candidatesAfter,
        @DefaultValue("365d") Duration auditLogsAfter,
        @DefaultValue("30d") Duration dataExportsAfter) {
}
