package com.famlog.auth.service;

import com.famlog.auth.domain.Platform;

/**
 * 처음 쓰는 기기로 로그인했다 (사양서 4.1 AUTH-03 "새 기기 로그인 시 알림"). 같은 기기로 다시 로그인하면 발행하지 않는다.
 * NOTI(S11)가 커밋 뒤 그 사용자의 다른 기기로 알린다.
 */
public record NewDeviceLoggedIn(long userId, long sessionId, String deviceName, Platform platform) {
}
