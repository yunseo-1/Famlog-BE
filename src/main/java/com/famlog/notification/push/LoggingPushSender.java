package com.famlog.notification.push;

import java.util.List;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

/** FCM 키가 없을 때 쓴다. 실제로 보내지 않고 종류와 개수만 남긴다 (토큰·본문은 남기지 않는다). */
@Slf4j
public class LoggingPushSender implements PushSender {

    @Override
    public Set<String> send(List<PushMessage> messages) {
        if (!messages.isEmpty()) {
            log.info("푸시 발송 생략(FCM 키 없음): type={}, count={}", messages.get(0).data().get(PushData.TYPE),
                    messages.size());
        }
        return Set.of();
    }
}
