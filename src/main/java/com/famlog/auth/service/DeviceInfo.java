package com.famlog.auth.service;

import com.famlog.auth.domain.Platform;

/** 로그인한 기기 (기기 관리 화면에 보인다). deviceId는 앱 설치마다 만든 무작위 값이다. */
public record DeviceInfo(String deviceId, String deviceName, Platform platform) {
}
