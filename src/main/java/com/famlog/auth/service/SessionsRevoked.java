package com.famlog.auth.service;

import java.util.List;

/** 세션이 폐기됐다 (로그아웃·원격 로그아웃·Refresh 재사용 감지). 채팅이 이 세션들의 WebSocket 연결을 끊는다. */
public record SessionsRevoked(long userId, List<Long> sessionIds) {
}
