package com.famlog.chat.ws;

import static com.famlog.support.GroupApi.addMember;
import static com.famlog.support.GroupApi.createGroup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import com.famlog.auth.domain.Platform;
import com.famlog.auth.jwt.AccessTokenIssuer;
import com.famlog.auth.jwt.AuthProperties;
import com.famlog.auth.jwt.JwtKeys;
import com.famlog.auth.service.SessionRevocationStore;
import com.famlog.auth.service.SessionTokenService;
import com.famlog.auth.service.TokenPair;
import com.famlog.chat.relay.ChatRelay;
import com.famlog.support.ChatIntegrationTest;
import com.famlog.support.StompTestClient;
import com.jayway.jsonpath.JsonPath;

/**
 * STOMP 실시간 채팅 (사양서 11.4, 4.2 CHAT-02, 18.1 CHAT-02·AUTH-03, 9.9 SEC-T4).
 * 가족: 엄마(그룹장) → 아빠 입장(seq 1) → 아들 입장(seq 2). 다른 가족의 stranger.
 */
class ChatStompTest extends ChatIntegrationTest {

    @Autowired
    SessionTokenService sessionTokenService;

    @Autowired
    SessionRevocationStore revocationStore;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    AuthProperties authProperties;

    @Autowired
    JwtKeys jwtKeys;

    long mom;
    long dad;
    long son;
    long groupId;
    long stranger;
    long otherGroupId;

    @BeforeEach
    void setUpFamily() throws Exception {
        mom = createUser();
        dad = createUser();
        son = createUser();
        groupId = createGroup(mockMvc, mom, "우리 가족");
        addMember(mockMvc, groupId, mom, dad);
        addMember(mockMvc, groupId, mom, son);
        stranger = createUser();
        otherGroupId = createGroup(mockMvc, stranger, "다른 가족");
        List.of(mom, dad, son, stranger).forEach(this::resetSendRate);
    }

    // --- CHAT-02 실시간 전송 ---

    @Test
    void 한_기기가_보내면_그룹_구독자_모두에게_seq와_함께_간다() throws Exception {
        try (StompTestClient momClient = StompTestClient.connect(port, token(mom));
                StompTestClient dadClient = StompTestClient.connect(port, token(dad))) {
            BlockingQueue<String> momTopic = momClient.subscribeGroup(groupId);
            BlockingQueue<String> dadTopic = dadClient.subscribeGroup(groupId);
            String clientMessageId = UUID.randomUUID().toString();

            momClient.sendMessage(groupId, clientMessageId, "저녁 7시에 모여요");

            for (BlockingQueue<String> topic : List.of(momTopic, dadTopic)) {
                String event = next(topic);
                assertThat(read(event, "$.type")).isEqualTo("message.created");
                assertThat(read(event, "$.data.seq")).isEqualTo(3);
                assertThat(read(event, "$.data.senderId")).isEqualTo((int) mom);
                assertThat(read(event, "$.data.clientMessageId")).isEqualTo(clientMessageId);
                assertThat(read(event, "$.data.body")).isEqualTo("저녁 7시에 모여요");
                assertThat(read(event, "$.data.unreadCount")).isEqualTo(2);
                assertThat((String) read(event, "$.data.sentAt")).endsWith("+09:00");
            }
            // 보낸 사람의 읽음 위치도 그 메시지까지 올라간다
            String readEvent = next(dadTopic);
            assertThat(read(readEvent, "$.type")).isEqualTo("read.updated");
            assertThat(read(readEvent, "$.data.userId")).isEqualTo((int) mom);
            assertThat(read(readEvent, "$.data.lastReadSeq")).isEqualTo(3);
        }
    }

    @Test
    void 같은_clientMessageId로_세_번_보내도_한_건만_저장되고_재전송은_보낸_연결에만_확인된다() throws Exception {
        try (StompTestClient momClient = StompTestClient.connect(port, token(mom));
                StompTestClient dadClient = StompTestClient.connect(port, token(dad))) {
            BlockingQueue<String> dadTopic = dadClient.subscribeGroup(groupId);
            String clientMessageId = UUID.randomUUID().toString();

            for (int i = 0; i < 3; i++) {
                momClient.sendMessage(groupId, clientMessageId, "네트워크가 끊겼다 다시 보냄");
            }

            String first = momClient.nextUserEvent();
            String second = momClient.nextUserEvent();
            for (String resent : List.of(first, second)) {
                assertThat(read(resent, "$.type")).isEqualTo("message.created");
                assertThat(read(resent, "$.data.seq")).isEqualTo(3);
                assertThat(read(resent, "$.data.clientMessageId")).isEqualTo(clientMessageId);
            }
            List<String> types = drain(dadTopic);
            assertThat(types).containsExactly("message.created", "read.updated");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM messages WHERE sender_id = ?", Long.class, mom))
                .isEqualTo(1);
    }

    @Test
    void 동시에_보내도_seq가_빠짐없이_겹치지_않는다() throws Exception {
        List<Long> senders = new ArrayList<>(List.of(mom, dad, son));
        for (int i = 0; i < 2; i++) {
            long member = createUser();
            addMember(mockMvc, groupId, mom, member);
            senders.add(member);
        }
        long before = roomLastSeq(groupId);
        senders.forEach(this::resetSendRate);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (long sender : senders) {
                for (int i = 0; i < 4; i++) {
                    tasks.add(() -> chatService.send(groupId, sender, text("동시에", null))
                            .message().seq());
                }
            }
            List<Long> seqs = new ArrayList<>();
            for (Future<Long> future : pool.invokeAll(tasks)) {
                seqs.add(future.get());
            }
            assertThat(seqs).containsExactlyInAnyOrderElementsOf(
                    LongStream.rangeClosed(before + 1, before + 20).boxed().toList());
        } finally {
            pool.shutdownNow();
        }
        List<Long> stored = jdbcTemplate.queryForList("""
                SELECT m.seq FROM messages m JOIN chat_rooms r ON r.id = m.room_id
                WHERE r.group_id = ? ORDER BY m.seq""", Long.class, groupId);
        assertThat(stored).containsExactlyElementsOf(LongStream.rangeClosed(1, before + 20).boxed().toList());
        assertThat(roomLastSeq(groupId)).isEqualTo(before + 20);
    }

    @Test
    void 사용자당_초당_5개를_넘으면_RATE_LIMITED() throws Exception {
        try (StompTestClient momClient = StompTestClient.connect(port, token(mom))) {
            for (int i = 0; i < 10; i++) {
                momClient.sendMessage(groupId, UUID.randomUUID().toString(), "도배 " + i);
            }
            String event = momClient.nextUserEvent();
            assertThat(read(event, "$.type")).isEqualTo("error");
            assertThat(read(event, "$.data.code")).isEqualTo("RATE_LIMITED");
            assertThat((String) read(event, "$.data.clientMessageId")).isNotBlank();
        }
    }

    @Test
    void 잘못된_메시지는_보낸_연결에만_error_이벤트로_알린다() throws Exception {
        long otherGroupMessage = send(otherGroupId, stranger, "다른 가족").id();
        try (StompTestClient momClient = StompTestClient.connect(port, token(mom))) {
            String id = UUID.randomUUID().toString();
            List<String> bad = List.of(
                    "{\"clientMessageId\":\"%s\",\"body\":\"   \"}".formatted(id),
                    "{\"clientMessageId\":\"%s\",\"body\":\"%s\"}".formatted(id, "가".repeat(2001)),
                    "{\"clientMessageId\":\"not-a-uuid\",\"body\":\"안녕\"}",
                    "{\"clientMessageId\":\"%s\",\"body\":\"답장\",\"replyToId\":%d}".formatted(id, otherGroupMessage),
                    "이건 JSON이 아님");
            for (String payload : bad) {
                momClient.send("/pub/groups/" + groupId + "/messages", payload);
                String event = momClient.nextUserEvent();
                assertThat(read(event, "$.type")).as(payload).isEqualTo("error");
                assertThat(read(event, "$.data.code")).as(payload).isEqualTo("VALIDATION_FAILED");
            }
            assertThat(momClient.isConnected()).isTrue();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM messages WHERE sender_id = ?", Long.class, mom))
                .isZero();
    }

    // --- CHAT-04 읽음 ---

    @Test
    void STOMP로_읽음을_보내면_그룹에_read_updated가_간다() throws Exception {
        long seq = send(groupId, mom, "읽었어?").seq();
        try (StompTestClient momClient = StompTestClient.connect(port, token(mom));
                StompTestClient sonClient = StompTestClient.connect(port, token(son))) {
            BlockingQueue<String> momTopic = momClient.subscribeGroup(groupId);

            sonClient.send("/pub/groups/" + groupId + "/read", "{\"lastReadSeq\":" + seq + "}");

            String event = next(momTopic);
            assertThat(read(event, "$.type")).isEqualTo("read.updated");
            assertThat(read(event, "$.data.userId")).isEqualTo((int) son);
            assertThat(read(event, "$.data.fromSeq")).isEqualTo(2);
            assertThat(read(event, "$.data.lastReadSeq")).isEqualTo((int) seq);
        }
    }

    // --- 인증 (CONNECT) ---

    @Test
    void 토큰이_없거나_위조됐거나_만료됐거나_폐기된_세션이면_연결을_거부한다() throws Exception {
        assertThat(StompTestClient.connectError(port, null)).isEqualTo("AUTH_INVALID");
        assertThat(StompTestClient.connectError(port, "abc.def.ghi")).isEqualTo("AUTH_INVALID");

        AccessTokenIssuer pastIssuer = new AccessTokenIssuer(jwtEncoder, authProperties, jwtKeys,
                Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC));
        assertThat(StompTestClient.connectError(port, pastIssuer.issue(mom, randomId()).value()))
                .isEqualTo("AUTH_TOKEN_EXPIRED");

        long sessionId = randomId();
        revocationStore.markRevoked(sessionId);
        assertThat(StompTestClient.connectError(port, accessTokenIssuer.issue(mom, sessionId).value()))
                .isEqualTo("AUTH_INVALID");

        assertThat(StompTestClient.connectError(port, token(mom))).isNull();
    }

    // --- SEC-T4 다른 그룹 구독·전송 ---

    @Test
    void 다른_그룹_토픽을_구독하면_거부하고_연결을_끊는다() throws Exception {
        try (StompTestClient strangerClient = StompTestClient.connect(port, token(stranger))) {
            strangerClient.subscribeRaw("/sub/groups/" + groupId);

            assertThat(strangerClient.awaitError()).isEqualTo("GROUP_NOT_MEMBER");
            assertThat(strangerClient.awaitClosed()).isTrue();
        }
    }

    @Test
    void 다른_그룹으로_보내면_거부하고_저장하지_않는다() throws Exception {
        try (StompTestClient momClient = StompTestClient.connect(port, token(mom));
                StompTestClient strangerClient = StompTestClient.connect(port, token(stranger))) {
            BlockingQueue<String> momTopic = momClient.subscribeGroup(groupId);

            strangerClient.sendMessage(groupId, UUID.randomUUID().toString(), "끼어들기");

            assertThat(strangerClient.awaitError()).isEqualTo("GROUP_NOT_MEMBER");
            assertThat(strangerClient.awaitClosed()).isTrue();
            assertThat(momTopic.poll(1, TimeUnit.SECONDS)).isNull();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM messages WHERE sender_id = ?", Long.class,
                stranger)).isZero();
    }

    @Test
    void 브로커_주소로_직접_보내거나_남의_개인_큐를_구독할_수_없다() throws Exception {
        try (StompTestClient dadClient = StompTestClient.connect(port, token(dad));
                StompTestClient momClient = StompTestClient.connect(port, token(mom))) {
            BlockingQueue<String> dadTopic = dadClient.subscribeGroup(groupId);

            momClient.send("/sub/groups/" + groupId, "{\"type\":\"message.created\",\"data\":{\"body\":\"가짜\"}}");

            assertThat(momClient.awaitError()).isEqualTo("NOT_FOUND");
            assertThat(dadTopic.poll(1, TimeUnit.SECONDS)).isNull();
        }
        try (StompTestClient strangerClient = StompTestClient.connect(port, token(stranger))) {
            strangerClient.subscribeRaw("/queue/events-user" + UUID.randomUUID());
            assertThat(strangerClient.awaitError()).isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void 내보내진_구성원의_연결은_끊기고_다시_구독할_수_없다() throws Exception {
        try (StompTestClient momClient = StompTestClient.connect(port, token(mom));
                StompTestClient dadClient = StompTestClient.connect(port, token(dad))) {
            BlockingQueue<String> momTopic = momClient.subscribeGroup(groupId);
            dadClient.subscribeGroup(groupId);

            mockMvc.perform(delete("/api/v1/groups/{g}/members/{u}", groupId, dad).with(asUser(mom)))
                    .andExpect(status().isNoContent());

            assertThat(dadClient.awaitClosed()).isTrue();
            String event = next(momTopic);
            assertThat(read(event, "$.data.type")).isEqualTo("SYSTEM");
            assertThat(read(event, "$.data.payload.kind")).isEqualTo("MEMBER_KICKED");
        }
        try (StompTestClient dadAgain = StompTestClient.connect(port, token(dad))) {
            dadAgain.subscribeRaw("/sub/groups/" + groupId);
            assertThat(dadAgain.awaitError()).isEqualTo("GROUP_NOT_MEMBER");
        }
    }

    @Test
    void 다른_기기에서_로그아웃시키면_그_세션의_연결이_끊긴다() throws Exception {
        TokenPair deviceA = sessionTokenService.startSession(dad, "device-a-" + randomId(), "아빠폰", Platform.ANDROID);
        try (StompTestClient dadClient = StompTestClient.connect(port, deviceA.accessToken());
                StompTestClient momClient = StompTestClient.connect(port, token(mom))) {
            dadClient.subscribeGroup(groupId);
            momClient.subscribeGroup(groupId);

            sessionTokenService.revokeOwnSession(dad, deviceA.sessionId());

            assertThat(dadClient.awaitClosed()).isTrue();
            assertThat(momClient.isConnected()).isTrue();
        }
        assertThat(StompTestClient.connectError(port, deviceA.accessToken())).isEqualTo("AUTH_INVALID");
    }

    // --- 서버 간 중계 (Redis Pub/Sub) ---

    @Test
    void 다른_서버가_Redis로_보낸_이벤트도_이_서버의_구독자에게_전달된다() throws Exception {
        try (StompTestClient dadClient = StompTestClient.connect(port, token(dad))) {
            BlockingQueue<String> dadTopic = dadClient.subscribeGroup(groupId);
            String envelope = """
                    {"kind":"TOPIC","destination":"/sub/groups/%d",
                     "payload":{"type":"message.created","data":{"seq":99,"body":"다른 서버에서"}}}
                    """.formatted(groupId);

            redis.convertAndSend(ChatRelay.CHANNEL, envelope);

            String event = next(dadTopic);
            assertThat(read(event, "$.data.body")).isEqualTo("다른 서버에서");
        }
    }

    @Test
    void Redis를_거쳐도_이벤트는_보낸_순서대로_도착한다() throws Exception {
        int count = 300;
        try (StompTestClient dadClient = StompTestClient.connect(port, token(dad))) {
            BlockingQueue<String> dadTopic = dadClient.subscribeGroup(groupId);

            // 여러 서버가 몰아서 보낸 것처럼 한 번에 밀어 넣는다
            redis.executePipelined((RedisCallback<Object>) connection -> {
                for (int i = 1; i <= count; i++) {
                    String envelope = """
                            {"kind":"TOPIC","destination":"/sub/groups/%d","payload":{"type":"test.order","data":{"n":%d}}}
                            """.formatted(groupId, i);
                    connection.publish(ChatRelay.CHANNEL.getBytes(StandardCharsets.UTF_8),
                            envelope.getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });

            List<Integer> received = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                received.add((Integer) read(next(dadTopic), "$.data.n"));
            }
            assertThat(received).isSorted().hasSize(count);
        }
    }

    // --- 도우미 ---

    private static String next(BlockingQueue<String> queue) throws InterruptedException {
        String event = queue.poll(StompTestClient.WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(event).as("이벤트를 받지 못했습니다").isNotNull();
        return event;
    }

    /** 1초 동안 더 오는 이벤트가 없을 때까지 받은 이벤트 종류 */
    private static List<String> drain(BlockingQueue<String> queue) throws InterruptedException {
        List<String> types = new ArrayList<>();
        String event;
        while ((event = queue.poll(1, TimeUnit.SECONDS)) != null) {
            types.add(JsonPath.read(event, "$.type"));
        }
        return types;
    }

    private static Object read(String json, String path) {
        return JsonPath.read(json, path);
    }
}
