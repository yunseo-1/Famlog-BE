package com.famlog.support;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.AbstractMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.jayway.jsonpath.JsonPath;

/**
 * STOMP 테스트 클라이언트. 앱처럼 /ws 에 연결하고 CONNECT 헤더로 토큰을 보낸다.
 * 받은 프레임 본문은 JSON 문자열 그대로 큐에 쌓는다.
 */
public final class StompTestClient implements AutoCloseable {

    public static final long WAIT_SECONDS = 5;

    private final StompSession session;
    private final Handler handler;
    private final BlockingQueue<String> userEvents;

    private StompTestClient(StompSession session, Handler handler) {
        this.session = session;
        this.handler = handler;
        this.userEvents = subscribeRaw("/user/queue/events");
    }

    /** 연결한다. 거부되면 예외 */
    public static StompTestClient connect(int port, String accessToken) throws Exception {
        Handler handler = new Handler();
        StompSession session = client().connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                connectHeaders(accessToken), handler).get(WAIT_SECONDS, TimeUnit.SECONDS);
        return new StompTestClient(session, handler);
    }

    /** 연결을 시도하고 거부 코드(ERROR 프레임의 message)를 돌려준다. 연결되면 null */
    public static String connectError(int port, String accessToken) throws Exception {
        Handler handler = new Handler();
        try {
            StompSession session = client().connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                    connectHeaders(accessToken), handler).get(WAIT_SECONDS, TimeUnit.SECONDS);
            session.disconnect();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            return handler.errors.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** 그룹 토픽을 구독하고, 서버가 구독을 처리한 뒤에 돌아온다 */
    public BlockingQueue<String> subscribeGroup(long groupId) throws InterruptedException {
        BlockingQueue<String> queue = subscribeRaw("/sub/groups/" + groupId);
        sync(groupId);
        return queue;
    }

    public BlockingQueue<String> subscribeRaw(String destination) {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((String) payload);
            }
        });
        return queue;
    }

    public void send(String destination, String json) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        session.send(headers, json);
    }

    public void sendMessage(long groupId, String clientMessageId, String body) {
        send("/pub/groups/" + groupId + "/messages",
                "{\"clientMessageId\":\"%s\",\"body\":\"%s\"}".formatted(clientMessageId, body));
    }

    /**
     * 서버가 이 연결의 앞선 프레임을 모두 처리할 때까지 기다린다.
     * 프레임은 연결별로 순서대로 처리되므로, 틀린 읽음 요청의 오류 응답이 오면 앞의 구독도 끝난 것이다.
     */
    public void sync(long groupId) throws InterruptedException {
        send("/pub/groups/" + groupId + "/read", "{\"lastReadSeq\":-1}");
        String event = nextUserEvent();
        if (event == null || !"error".equals(JsonPath.read(event, "$.type"))) {
            throw new IllegalStateException("동기화 응답을 받지 못했습니다: " + event);
        }
    }

    public String nextUserEvent() throws InterruptedException {
        return userEvents.poll(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    public BlockingQueue<String> userEvents() {
        return userEvents;
    }

    /** 서버가 보낸 ERROR 프레임의 code */
    public String awaitError() throws InterruptedException {
        return handler.errors.poll(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** 서버가 연결을 끊을 때까지 기다린다 */
    public boolean awaitClosed() throws InterruptedException {
        try {
            handler.closed.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException | TimeoutException e) {
            return !session.isConnected();
        }
    }

    public boolean isConnected() {
        return session.isConnected();
    }

    /** 서버가 이미 끊었을 수 있으므로 실패는 무시한다 */
    @Override
    public void close() {
        try {
            session.disconnect();
        } catch (RuntimeException ignored) {
            // 이미 끊긴 연결
        }
    }

    private static WebSocketStompClient client() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JsonStringConverter());
        return client;
    }

    private static StompHeaders connectHeaders(String accessToken) {
        StompHeaders headers = new StompHeaders();
        if (accessToken != null) {
            headers.add("Authorization", "Bearer " + accessToken);
        }
        return headers;
    }

    private static final class Handler extends StompSessionHandlerAdapter {

        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            String code = headers.getFirst("message");
            errors.add(code == null ? StompCommand.ERROR.name() : code);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            closed.complete(null);
        }
    }

    /** JSON 본문을 문자열 그대로 주고받는다 */
    private static final class JsonStringConverter extends AbstractMessageConverter {

        JsonStringConverter() {
            super(MimeTypeUtils.APPLICATION_JSON);
        }

        @Override
        protected boolean supports(Class<?> clazz) {
            return String.class.equals(clazz);
        }

        @Override
        protected Object convertFromInternal(Message<?> message, Class<?> targetClass, Object conversionHint) {
            Object payload = message.getPayload();
            return payload instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : payload.toString();
        }

        @Override
        protected Object convertToInternal(Object payload, MessageHeaders headers, Object conversionHint) {
            return payload.toString().getBytes(StandardCharsets.UTF_8);
        }
    }
}
