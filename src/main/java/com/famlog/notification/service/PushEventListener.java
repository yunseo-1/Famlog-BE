package com.famlog.notification.service;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.famlog.auth.service.NewDeviceLoggedIn;
import com.famlog.chat.event.MessagePosted;
import com.famlog.group.event.GroupMemberEvents.MemberLeft;

import lombok.extern.slf4j.Slf4j;

/**
 * 푸시를 보내는 일 (사양서 4.2 NOTI-01, 4.1 AUTH-03). 커밋된 뒤에만, 발송 스레드(push-)에서 보낸다.
 * <ul>
 * <li>새 메시지(입장·퇴장 시스템 메시지 포함) → 그룹 구성원</li>
 * <li>내보내짐 → 내보내진 본인</li>
 * <li>새 기기 로그인 → 그 사용자의 다른 기기</li>
 * </ul>
 * 채팅방을 보고 있는지는 서버가 따지지 않는다. 앱이 화면에 있으면 앱이 표시하지 않는다 (그때는 STOMP로 이미 받았다).
 */
@Slf4j
@Component
class PushEventListener {

    private final PushComposer composer;
    private final PushNotifier notifier;
    private final ThreadPoolTaskExecutor pushExecutor;

    PushEventListener(PushComposer composer, PushNotifier notifier, ThreadPoolTaskExecutor pushExecutor) {
        this.composer = composer;
        this.notifier = notifier;
        this.pushExecutor = pushExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onMessagePosted(MessagePosted event) {
        submit(() -> composer.chatMessage(event), null);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onMemberLeft(MemberLeft event) {
        if (event.kicked()) {
            submit(() -> composer.memberRemoved(event), null);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onNewDevice(NewDeviceLoggedIn event) {
        submit(() -> composer.newDeviceLogin(event), event.sessionId());
    }

    private void submit(Supplier<Map<Long, Map<String, String>>> compose, Long excludeSessionId) {
        pushExecutor.execute(() -> {
            try {
                notifier.notify(compose.get(), excludeSessionId);
            } catch (RuntimeException e) {
                log.warn("푸시를 보내지 못했습니다: error={}", e.getClass().getSimpleName());
            }
        });
    }
}
