package com.famlog.support;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.famlog.notification.push.PushMessage;
import com.famlog.notification.push.PushSender;

/**
 * 실제 FCM 대신 보낸 내용을 기록한다. 푸시는 비동기라 토큰과 조건(메시지 id 등)으로 기다린다.
 * 테스트끼리 컨텍스트를 나눠 쓰므로 토큰은 테스트마다 새로 만든다.
 * 한 번의 발송(이벤트 하나)이 묶음 하나로 기록되므로, 받은 사람의 묶음에 다른 사람이 없으면 그 사람에게는 가지 않은 것이다.
 */
public class FakePushSender implements PushSender {

    public static final long WAIT_SECONDS = 5;

    private final List<List<PushMessage>> batches = new CopyOnWriteArrayList<>();
    private final Set<String> invalidTokens = ConcurrentHashMap.newKeySet();

    @Override
    public Set<String> send(List<PushMessage> messages) {
        batches.add(List.copyOf(messages));
        Set<String> invalid = new HashSet<>();
        messages.stream().map(PushMessage::token).filter(invalidTokens::contains).forEach(invalid::add);
        return invalid;
    }

    /** 이 토큰을 FCM이 UNREGISTERED 로 거절한 것처럼 한다 */
    public void markInvalid(String token) {
        invalidTokens.add(token);
    }

    /** 이 토큰으로 조건에 맞는 메시지가 올 때까지 기다린다 */
    public PushMessage await(String token, Predicate<Map<String, String>> match) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            Optional<PushMessage> found = to(token, match).stream().findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("푸시가 오지 않았습니다");
    }

    /** 그 메시지와 같은 발송 묶음 */
    public List<PushMessage> batchOf(PushMessage message) {
        return batches.stream().filter(batch -> batch.contains(message)).findFirst().orElseThrow();
    }

    public List<PushMessage> to(String token, Predicate<Map<String, String>> match) {
        return batches.stream().flatMap(List::stream)
                .filter(message -> message.token().equals(token) && match.test(message.data()))
                .toList();
    }

    /** 이 메시지 id의 새 메시지 푸시 */
    public static Predicate<Map<String, String>> chatMessage(long messageId) {
        return data -> "CHAT_MESSAGE".equals(data.get("type")) && Long.toString(messageId).equals(data.get("messageId"));
    }

    public static Predicate<Map<String, String>> ofType(String type) {
        return data -> type.equals(data.get("type"));
    }
}
