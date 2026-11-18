package com.famlog.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.famlog.notification.push.PushMessage;
import com.famlog.notification.push.PushSender;
import com.famlog.notification.repository.DeviceTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 사용자별 data 를 그 사용자의 모든 기기(쓸 수 있는 세션의 토큰)로 보내고, FCM이 거절한 토큰을 지운다. */
@Slf4j
@Component
@RequiredArgsConstructor
class PushNotifier {

    private final DeviceTokenRepository tokenRepository;
    private final DeviceTokenService deviceTokenService;
    private final PushSender sender;
    private final Clock clock;

    /** @param excludeSessionId 이 세션(기기)에는 보내지 않는다. 없으면 null */
    void notify(Map<Long, Map<String, String>> dataByUser, Long excludeSessionId) {
        if (dataByUser.isEmpty()) {
            return;
        }
        List<PushMessage> messages = tokenRepository.findUsable(dataByUser.keySet(), Instant.now(clock)).stream()
                .filter(token -> !token.getSessionId().equals(excludeSessionId))
                .map(token -> new PushMessage(token.getFcmToken(), dataByUser.get(token.getUserId())))
                .toList();
        if (messages.isEmpty()) {
            return;
        }
        Set<String> invalid = sender.send(messages);
        if (!invalid.isEmpty()) {
            deviceTokenService.deleteTokens(invalid);
            log.info("쓸 수 없는 푸시 토큰을 지웠습니다: count={}", invalid.size());
        }
    }
}
