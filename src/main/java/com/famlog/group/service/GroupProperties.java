package com.famlog.group.service;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 가족 그룹 규칙 (사양서 2.1, 4.1 GRP-01~05).
 *
 * @param maxMembers          그룹당 최대 인원
 * @param maxGroupsPerUser    사용자당 최대 그룹 수
 * @param inviteTtl           초대 코드 유효 기간
 * @param inviteLinkBaseUrl   초대 링크 앞부분. 코드가 뒤에 붙는다 (App Links 도메인이 정해지면 바꾼다)
 * @param inviteMaxFailures   연속 실패 허용 횟수. 넘으면 잠금
 * @param inviteLockDuration  잠금 시간
 * @param deletionGrace       그룹 삭제 유예
 * @param calendarPalette     구성원 캘린더 색 (#RRGGBB 대문자). 디자인이 정해지면 바꾼다
 */
@ConfigurationProperties("famlog.group")
public record GroupProperties(
        @DefaultValue("20") int maxMembers,
        @DefaultValue("10") int maxGroupsPerUser,
        @DefaultValue("7d") Duration inviteTtl,
        @DefaultValue("https://famlog.app/invite/") String inviteLinkBaseUrl,
        @DefaultValue("5") int inviteMaxFailures,
        @DefaultValue("10m") Duration inviteLockDuration,
        @DefaultValue("30d") Duration deletionGrace,
        List<String> calendarPalette) {
}
