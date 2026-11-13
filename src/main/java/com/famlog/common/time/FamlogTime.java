package com.famlog.common.time;

import java.time.ZoneId;

/**
 * 시간 기준 (사양서 10장, 11.1, 12장): 저장·계산은 UTC, 표시·날짜 해석은 Asia/Seoul.
 */
public final class FamlogTime {

    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");

    private FamlogTime() {
    }
}
