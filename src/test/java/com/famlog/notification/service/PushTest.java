package com.famlog.notification.service;

import static com.famlog.support.AuthApi.read;
import static com.famlog.support.FakePushSender.chatMessage;
import static com.famlog.support.FakePushSender.ofType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.famlog.chat.domain.MessageType;
import com.famlog.chat.dto.ChatDtos.MessageResponse;
import com.famlog.notification.push.PushMessage;
import com.famlog.support.AuthApi;
import com.famlog.support.FakeSocialLoginVerifier;
import com.famlog.support.GroupApi;
import com.famlog.support.PushIntegrationTest;

/**
 * 푸시 받는 사람과 내용 (사양서 4.2 NOTI-01, 4.1 SET-02·AUTH-03). 실제 FCM 대신 FakePushSender 로 확인한다.
 * 가족: 엄마(그룹장)·아빠(기기 2대)·아들.
 */
class PushTest extends PushIntegrationTest {

    private long mom;
    private long dad;
    private long son;
    private long groupId;
    private String momToken;
    private String dadPhone;
    private String dadTablet;
    private String sonToken;

    @BeforeEach
    void family() throws Exception {
        mom = user("엄마닉");
        dad = user("아빠닉");
        son = user("아들닉");
        groupId = GroupApi.createGroup(mockMvc, mom, "우리 가족");
        GroupApi.addMember(mockMvc, groupId, mom, dad);
        GroupApi.addMember(mockMvc, groupId, mom, son);
        momToken = deviceOf(mom);
        dadPhone = deviceOf(dad);
        dadTablet = deviceOf(dad);
        sonToken = deviceOf(son);
    }

    @Test
    void 새_메시지는_보낸_사람을_빼고_구성원의_모든_기기로_data_메시지로_간다() throws Exception {
        MessageResponse sent = send(groupId, mom, "저녁 7시에 외식해요");

        PushMessage push = pushSender.await(dadPhone, chatMessage(sent.id()));

        assertThat(tokens(pushSender.batchOf(push))).containsExactlyInAnyOrder(dadPhone, dadTablet, sonToken);
        assertThat(push.data())
                .containsEntry("type", "CHAT_MESSAGE")
                .containsEntry("channel", "CHAT")
                .containsEntry("groupId", Long.toString(groupId))
                .containsEntry("groupName", "우리 가족")
                .containsEntry("messageId", Long.toString(sent.id()))
                .containsEntry("seq", Long.toString(sent.seq()))
                .containsEntry("messageType", "TEXT")
                .containsEntry("senderId", Long.toString(mom))
                .containsEntry("senderName", "엄마닉")
                .containsEntry("body", "저녁 7시에 외식해요")
                .containsEntry("silent", "false")
                .doesNotContainKey("mediaCount");
        assertThat(push.data().get("sentAt")).endsWith("+09:00");
    }

    @Test
    void 보낸_사람_이름은_호칭이_있으면_호칭이다() throws Exception {
        mockMvc.perform(patch("/api/v1/groups/{g}/members/me", groupId).with(asUser(mom))
                .contentType(MediaType.APPLICATION_JSON).content("{\"familyTitle\":\"우리 엄마\"}"))
                .andExpect(status().isOk());

        MessageResponse sent = send(groupId, mom, "안녕");

        assertThat(pushSender.await(sonToken, chatMessage(sent.id())).data()).containsEntry("senderName", "우리 엄마");
    }

    @Test
    void 그룹_알림을_끈_구성원에게는_보내지_않는다() throws Exception {
        mockMvc.perform(patch("/api/v1/groups/{g}/members/me/preferences", groupId).with(asUser(dad))
                .contentType(MediaType.APPLICATION_JSON).content("{\"notifMuted\":true}"))
                .andExpect(status().isOk());

        MessageResponse sent = send(groupId, mom, "안녕");

        PushMessage push = pushSender.await(sonToken, chatMessage(sent.id()));
        assertThat(tokens(pushSender.batchOf(push))).containsExactly(sonToken);
    }

    @Test
    void 나간_구성원에게는_보내지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/groups/{g}/leave", groupId).with(asUser(son))).andExpect(status().isNoContent());

        MessageResponse sent = send(groupId, mom, "안녕");

        PushMessage push = pushSender.await(dadPhone, chatMessage(sent.id()));
        assertThat(tokens(pushSender.batchOf(push))).doesNotContain(sonToken);
    }

    @Test
    void 미리보기를_숨긴_사람에게는_본문_키_자체가_없다() throws Exception {
        patchSettings(son, "{\"previewHidden\":true}");

        MessageResponse sent = send(groupId, mom, "비밀 이야기");

        PushMessage toSon = pushSender.await(sonToken, chatMessage(sent.id()));
        assertThat(toSon.data()).doesNotContainKey("body").containsEntry("senderName", "엄마닉");
        assertThat(pushSender.await(dadPhone, chatMessage(sent.id())).data()).containsEntry("body", "비밀 이야기");
    }

    @Test
    void 방해_금지_시간이면_silent가_true다() throws Exception {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Seoul"));
        DateTimeFormatter hhmm = DateTimeFormatter.ofPattern("HH:mm");
        patchSettings(son, "{\"quietHoursEnabled\":true,\"quietStart\":\"%s\",\"quietEnd\":\"%s\"}"
                .formatted(now.minusHours(1).format(hhmm), now.plusHours(1).format(hhmm)));

        MessageResponse sent = send(groupId, mom, "자니?");

        assertThat(pushSender.await(sonToken, chatMessage(sent.id())).data()).containsEntry("silent", "true");
        assertThat(pushSender.await(dadPhone, chatMessage(sent.id())).data()).containsEntry("silent", "false");
    }

    @Test
    void 긴_본문은_100자에서_자른다() throws Exception {
        MessageResponse sent = send(groupId, mom, "가".repeat(150));

        assertThat(pushSender.await(sonToken, chatMessage(sent.id())).data().get("body"))
                .isEqualTo("가".repeat(100) + "…");
    }

    @Test
    void 사진_묶음은_개수만_보내고_본문이_없다() throws Exception {
        MessageResponse sent = sendMedia(groupId, mom, MessageType.IMAGE, List.of(media(), media(), media()));

        assertThat(pushSender.await(sonToken, chatMessage(sent.id())).data())
                .containsEntry("messageType", "IMAGE")
                .containsEntry("mediaCount", "3")
                .doesNotContainKey("body");
    }

    @Test
    void 입장_메시지는_기존_구성원에게_가고_들어온_본인에게는_가지_않는다() throws Exception {
        long daughter = user("딸닉");
        String daughterToken = deviceOf(daughter);

        GroupApi.addMember(mockMvc, groupId, mom, daughter);

        long joinMessageId = jdbcTemplate.queryForObject("""
                SELECT m.id FROM messages m JOIN chat_rooms r ON r.id = m.room_id
                WHERE r.group_id = ? ORDER BY m.seq DESC LIMIT 1""", Long.class, groupId);
        PushMessage push = pushSender.await(momToken, chatMessage(joinMessageId));
        assertThat(push.data())
                .containsEntry("messageType", "SYSTEM")
                .containsEntry("body", "딸닉님이 들어왔어요.")
                .doesNotContainKey("senderId");
        assertThat(tokens(pushSender.batchOf(push))).doesNotContain(daughterToken);
    }

    @Test
    void 들어오기_전_메시지는_보내지_않는다() throws Exception {
        // 메시지 seq 가 아들의 입장 시점(joinSeq) 이하인 경우를 흉내 낸다
        jdbcTemplate.update("UPDATE group_members SET join_seq = 1000000 WHERE group_id = ? AND user_id = ?", groupId,
                son);

        MessageResponse sent = send(groupId, mom, "안녕");

        PushMessage push = pushSender.await(dadPhone, chatMessage(sent.id()));
        assertThat(tokens(pushSender.batchOf(push))).doesNotContain(sonToken);
    }

    @Test
    void 만료된_세션의_토큰으로는_보내지_않는다() throws Exception {
        jdbcTemplate.update("""
                UPDATE user_sessions SET expires_at = now() - interval '1 minute'
                WHERE id = (SELECT session_id FROM device_tokens WHERE fcm_token = ?)""", dadTablet);

        MessageResponse sent = send(groupId, mom, "안녕");

        PushMessage push = pushSender.await(dadPhone, chatMessage(sent.id()));
        assertThat(tokens(pushSender.batchOf(push))).doesNotContain(dadTablet);
    }

    @Test
    void FCM이_거절한_토큰은_지운다() throws Exception {
        pushSender.markInvalid(dadTablet);

        MessageResponse sent = send(groupId, mom, "안녕");

        pushSender.await(dadTablet, chatMessage(sent.id()));
        awaitTokenDeleted(dadTablet);
        assertThat(tokenCount(dadPhone)).isOne();
    }

    @Test
    void 내보내지면_본인에게_시스템_알림이_가고_퇴장_메시지_푸시는_가지_않는다() throws Exception {
        mockMvc.perform(delete("/api/v1/groups/{g}/members/{u}", groupId, son).with(asUser(mom)))
                .andExpect(status().isNoContent());

        PushMessage removed = pushSender.await(sonToken, ofType("MEMBER_REMOVED"));
        assertThat(removed.data())
                .containsEntry("channel", "SYSTEM")
                .containsEntry("groupId", Long.toString(groupId))
                .containsEntry("groupName", "우리 가족")
                .containsEntry("silent", "false");
        assertThat(pushSender.batchOf(removed)).hasSize(1);
        PushMessage leftMessage = pushSender.await(dadPhone, data -> "아들닉님이 나갔어요.".equals(data.get("body")));
        assertThat(leftMessage.data()).containsEntry("messageType", "SYSTEM");
        assertThat(tokens(pushSender.batchOf(leftMessage))).doesNotContain(sonToken);
    }

    @Test
    void 새_기기로_로그인하면_다른_기기에만_알리고_같은_기기로_다시_로그인하면_알리지_않는다() throws Exception {
        String providerUserId = "kakao-" + randomId();
        String phone = AuthApi.signupAndGetBody(mockMvc, providerUserId, "phone");
        String phoneToken = newToken();
        mockMvc.perform(put("/api/v1/devices/token")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + read(phone, "$.tokens.accessToken"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"%s\"}".formatted(phoneToken)))
                .andExpect(status().isNoContent());

        String tablet = login(providerUserId, "tablet");
        PushMessage first = pushSender.await(phoneToken, ofType("NEW_DEVICE_LOGIN"));
        assertThat(first.data())
                .containsEntry("channel", "SYSTEM")
                .containsEntry("sessionId", Long.toString(sessionId(tablet)))
                .containsEntry("deviceName", "테스트폰")
                .containsEntry("platform", "ANDROID");
        assertThat(first.data().get("sentAt")).endsWith("+09:00");

        login(providerUserId, "tablet");
        String laptop = login(providerUserId, "tablet-2");
        String laptopSession = Long.toString(sessionId(laptop));
        pushSender.await(phoneToken, data -> laptopSession.equals(data.get("sessionId")));
        Thread.sleep(300);
        assertThat(pushSender.to(phoneToken, ofType("NEW_DEVICE_LOGIN"))).hasSize(2);
    }

    private long user(String nickname) {
        return jdbcTemplate.queryForObject("INSERT INTO users (nickname) VALUES (?) RETURNING id", Long.class,
                nickname);
    }

    private long media() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO media_assets (group_id, uploader_id, media_type, mime, size, storage_key, status,
                                          upload_expires_at)
                VALUES (?, ?, 'IMAGE', 'image/jpeg', 100, ?, 'READY', now() + interval '10 minutes') RETURNING id""",
                Long.class, groupId, mom, "media/" + UUID.randomUUID() + "/original");
    }

    private void patchSettings(long userId, String body) throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/notification-settings").with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    private String login(String providerUserId, String deviceId) throws Exception {
        return mockMvc.perform(AuthApi.login("kakao", FakeSocialLoginVerifier.token(providerUserId), deviceId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static long sessionId(String authBody) {
        String accessToken = read(authBody, "$.tokens.accessToken");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        return Long.parseLong(read(payload, "$.sid"));
    }

    private void awaitTokenDeleted(String token) throws InterruptedException {
        for (int i = 0; i < 100 && tokenCount(token) > 0; i++) {
            Thread.sleep(50);
        }
        assertThat(tokenCount(token)).isZero();
    }

    private static List<String> tokens(List<PushMessage> batch) {
        return batch.stream().map(PushMessage::token).toList();
    }
}
