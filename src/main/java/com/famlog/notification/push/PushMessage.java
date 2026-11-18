package com.famlog.notification.push;

import java.util.Map;

/**
 * 기기 하나로 보내는 data 메시지. 값은 모두 문자열 (FCM data 규칙).
 * 토큰·본문이 로그에 찍히지 않게 toString 에서 뺀다 (사양서 9.8).
 */
public record PushMessage(String token, Map<String, String> data) {

    @Override
    public String toString() {
        return "PushMessage[type=" + data.get(PushData.TYPE) + "]";
    }
}
