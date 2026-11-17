package com.famlog.chat.controller;

import static com.famlog.support.GroupApi.addMember;
import static com.famlog.support.GroupApi.createGroup;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.famlog.chat.dto.ChatDtos.MessageResponse;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.support.ChatIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * 채팅 REST API (사양서 11.3 CHAT-03·04·06, 18.1 GRP-02 입장 메시지, 9.9 SEC-T1·T9).
 * 가족: 엄마(그룹장) → 아빠 입장(seq 1) → 아들 입장(seq 2). 다른 가족: stranger.
 */
@ExtendWith(OutputCaptureExtension.class)
class ChatApiTest extends ChatIntegrationTest {

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
    }

    // --- GRP-02 입장 메시지 / 입장 이후 대화만 공개 ---

    @Test
    void 들어오면_입장_메시지가_보이고_들어오기_전_대화는_보이지_않는다() throws Exception {
        send(groupId, mom, "아들 오기 전 이야기");
        long daughter = createUser();
        addMember(mockMvc, groupId, mom, daughter);

        messages(daughter, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("SYSTEM"))
                .andExpect(jsonPath("$.items[0].senderId").doesNotExist())
                .andExpect(jsonPath("$.items[0].body").value("테스트님이 들어왔어요."))
                .andExpect(jsonPath("$.items[0].payload.kind").value("MEMBER_JOINED"))
                .andExpect(jsonPath("$.items[0].payload.userId").value((int) daughter))
                .andExpect(jsonPath("$.items[0].unreadCount").value(0))
                .andExpect(jsonPath("$.hasMore").value(false));
        messages(mom, "")
                .andExpect(jsonPath("$.items[*].type", contains("SYSTEM", "SYSTEM", "TEXT", "SYSTEM")));
    }

    @Test
    void 나가거나_내보내지면_퇴장_메시지가_남는다() throws Exception {
        mockMvc.perform(post("/api/v1/groups/{g}/leave", groupId).with(asUser(son)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/groups/{g}/members/{u}", groupId, dad).with(asUser(mom)))
                .andExpect(status().isNoContent());

        messages(mom, "?afterSeq=2")
                .andExpect(jsonPath("$.items[*].payload.kind", contains("MEMBER_LEFT", "MEMBER_KICKED")))
                .andExpect(jsonPath("$.items[*].body", contains("테스트님이 나갔어요.", "테스트님이 나갔어요.")));
    }

    // --- CHAT-03 내역 ---

    @Test
    void 최신부터_위로_스크롤하고_재접속_때는_afterSeq_다음부터_받는다() throws Exception {
        for (int i = 1; i <= 10; i++) {
            send(groupId, mom, "메시지 " + i); // seq 3 ~ 12
        }

        messages(dad, "?size=3")
                .andExpect(jsonPath("$.items[*].seq", contains(10, 11, 12)))
                .andExpect(jsonPath("$.hasMore").value(true));
        messages(dad, "?beforeSeq=10&size=3")
                .andExpect(jsonPath("$.items[*].seq", contains(7, 8, 9)))
                .andExpect(jsonPath("$.hasMore").value(true));
        messages(dad, "?beforeSeq=3&size=3")
                .andExpect(jsonPath("$.items[*].seq", contains(1, 2)))
                .andExpect(jsonPath("$.hasMore").value(false));
        messages(dad, "?afterSeq=8&size=2")
                .andExpect(jsonPath("$.items[*].seq", contains(9, 10)))
                .andExpect(jsonPath("$.hasMore").value(true));
        messages(dad, "?afterSeq=10&size=2")
                .andExpect(jsonPath("$.items[*].seq", contains(11, 12)))
                .andExpect(jsonPath("$.hasMore").value(false));
        // 아들은 seq 1(아빠 입장) 뒤에 들어왔다
        messages(son, "?afterSeq=0")
                .andExpect(jsonPath("$.items[0].seq").value(2));
    }

    @Test
    void 페이징_값이_잘못되면_400() throws Exception {
        messages(dad, "?afterSeq=1&beforeSeq=5").andExpect(status().isBadRequest());
        messages(dad, "?size=101").andExpect(status().isBadRequest());
        messages(dad, "?size=0").andExpect(status().isBadRequest());
        messages(dad, "?afterSeq=-1").andExpect(status().isBadRequest());
        messages(dad, "?afterSeq=abc").andExpect(status().isBadRequest());
    }

    // --- CHAT-04 읽음·안 읽은 수 ---

    @Test
    void 안_읽은_수는_보낸_사람과_나중에_들어온_사람을_빼고_센다() throws Exception {
        long beforeDaughter = send(groupId, mom, "딸 오기 전").seq();
        assertThat(unreadOf(mom, beforeDaughter)).isEqualTo(2); // 아빠, 아들

        long daughter = createUser();
        addMember(mockMvc, groupId, mom, daughter);
        long afterDaughter = send(groupId, mom, "딸 온 뒤").seq();

        assertThat(unreadOf(mom, beforeDaughter)).isEqualTo(2);
        assertThat(unreadOf(mom, afterDaughter)).isEqualTo(3);

        read(dad, afterDaughter).andExpect(status().isOk()).andExpect(jsonPath("$.lastReadSeq").value((int) afterDaughter));
        assertThat(unreadOf(mom, beforeDaughter)).isEqualTo(1);
        assertThat(unreadOf(mom, afterDaughter)).isEqualTo(2);
    }

    @Test
    void 읽음은_앞으로만_움직이고_마지막_seq를_넘지_않는다() throws Exception {
        long last = send(groupId, mom, "안녕").seq();

        read(son, 999).andExpect(jsonPath("$.lastReadSeq").value((int) last));
        read(son, 1).andExpect(jsonPath("$.lastReadSeq").value((int) last));
        read(son, -1).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/groups/{g}/read", groupId).with(asUser(son))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void 보내면_그때까지의_메시지는_읽은_것이_된다() throws Exception {
        send(groupId, mom, "아빠 밥 먹었어?");
        long reply = send(groupId, dad, "응").seq();

        Long dadRead = jdbcTemplate.queryForObject(
                "SELECT last_read_seq FROM group_members WHERE group_id = ? AND user_id = ?", Long.class, groupId, dad);
        assertThat(dadRead).isEqualTo(reply);
    }

    // --- 채팅 탭 (SCR-10) ---

    @Test
    void 내_그룹_목록에_마지막_메시지와_안_읽은_수가_나온다() throws Exception {
        send(groupId, mom, "첫째");
        send(groupId, mom, "둘째");
        myGroups(dad)
                .andExpect(jsonPath("$[0].lastMessage.body").value("둘째"))
                .andExpect(jsonPath("$[0].lastMessage.senderId").value((int) mom))
                .andExpect(jsonPath("$[0].lastMessage.sentAt").value(Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$[0].unreadCount").value(2));

        send(groupId, dad, "읽었어");
        myGroups(dad).andExpect(jsonPath("$[0].unreadCount").value(0));

        long last = send(groupId, mom, "셋째").seq();
        myGroups(dad).andExpect(jsonPath("$[0].unreadCount").value(1));
        read(dad, last);
        myGroups(dad).andExpect(jsonPath("$[0].unreadCount").value(0));
    }

    // --- CHAT-06 답장 ---

    @Test
    void 답장에는_원문_미리보기가_붙는다() throws Exception {
        MessageResponse original = send(groupId, mom, "저녁 뭐 먹을까");
        MessageResponse reply = send(groupId, dad, "치킨", original.id());

        assertThat(reply.replyTo().id()).isEqualTo(original.id());
        assertThat(reply.replyTo().body()).isEqualTo("저녁 뭐 먹을까");
        assertThat(reply.replyTo().senderId()).isEqualTo(mom);
    }

    @Test
    void 나중에_들어온_사람에게는_입장_전_원문의_본문을_보여주지_않는다() throws Exception {
        MessageResponse original = send(groupId, mom, "딸 오기 전 이야기");
        long daughter = createUser();
        addMember(mockMvc, groupId, mom, daughter);
        long replySeq = send(groupId, dad, "그 얘기 말이야", original.id()).seq();

        messages(daughter, "?afterSeq=" + (replySeq - 1))
                .andExpect(jsonPath("$.items[0].replyTo.id").value((int) original.id()))
                .andExpect(jsonPath("$.items[0].replyTo.body").doesNotExist());
        messages(mom, "?afterSeq=" + (replySeq - 1))
                .andExpect(jsonPath("$.items[0].replyTo.body").value("딸 오기 전 이야기"));
    }

    @Test
    void 다른_그룹이나_입장_전_메시지에는_답장할_수_없다() throws Exception {
        long otherMessage = send(otherGroupId, stranger, "다른 가족 이야기").id();
        long beforeDaughter = send(groupId, mom, "딸 오기 전").id();
        long daughter = createUser();
        addMember(mockMvc, groupId, mom, daughter);

        assertThatThrownBy(() -> send(groupId, dad, "답장", otherMessage))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> send(groupId, daughter, "답장", beforeDaughter))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // --- CHAT-06 삭제 ---

    @Test
    void 보낸_사람은_5분_안에_모두에게서_지울_수_있다() throws Exception {
        MessageResponse message = send(groupId, mom, "잘못 보냄");
        react(dad, message.id(), "HEART").andExpect(status().isOk());

        deleteMessage(mom, message.id()).andExpect(status().isNoContent());
        deleteMessage(mom, message.id()).andExpect(status().isNoContent());

        messages(dad, "?afterSeq=" + (message.seq() - 1))
                .andExpect(jsonPath("$.items[0].deleted").value(true))
                .andExpect(jsonPath("$.items[0].body").doesNotExist())
                .andExpect(jsonPath("$.items[0].reactions").isEmpty());
    }

    @Test
    void 남의_메시지나_5분이_지난_메시지나_시스템_메시지는_지울_수_없다() throws Exception {
        MessageResponse message = send(groupId, mom, "엄마 메시지");
        deleteMessage(dad, message.id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MESSAGE_NOT_DELETABLE"));

        jdbcTemplate.update("UPDATE messages SET created_at = now() - interval '6 minutes' WHERE id = ?", message.id());
        deleteMessage(mom, message.id()).andExpect(status().isForbidden());

        long systemMessageId = jdbcTemplate.queryForObject(
                "SELECT m.id FROM messages m JOIN chat_rooms r ON r.id = m.room_id WHERE r.group_id = ? AND m.seq = 1",
                Long.class, groupId);
        deleteMessage(dad, systemMessageId).andExpect(status().isForbidden());
    }

    // --- CHAT-06 공감 ---

    @Test
    void 공감은_한_사람당_하나이고_바꾸거나_취소할_수_있다() throws Exception {
        long messageId = send(groupId, mom, "합격했어!").id();

        react(dad, messageId, "HEART").andExpect(status().isOk())
                .andExpect(jsonPath("$.reactions[0].emoji").value("HEART"))
                .andExpect(jsonPath("$.reactions[0].userIds", contains((int) dad)));
        react(son, messageId, "HEART")
                .andExpect(jsonPath("$.reactions[0].userIds", contains((int) dad, (int) son)));
        react(dad, messageId, "LAUGH")
                .andExpect(jsonPath("$.reactions.length()").value(2))
                .andExpect(jsonPath("$.reactions[?(@.emoji == 'HEART')].userIds[*]", contains((int) son)))
                .andExpect(jsonPath("$.reactions[?(@.emoji == 'LAUGH')].userIds[*]", contains((int) dad)));
        mockMvc.perform(delete("/api/v1/messages/{id}/reactions/me", messageId).with(asUser(dad)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reactions.length()").value(1))
                .andExpect(jsonPath("$.reactions[0].emoji").value("HEART"));

        messages(mom, "?afterSeq=" + (roomLastSeq(groupId) - 1))
                .andExpect(jsonPath("$.items[0].reactions[0].userIds", contains((int) son)));
    }

    @Test
    void 없는_공감_종류나_시스템_메시지_공감은_400() throws Exception {
        long messageId = send(groupId, mom, "안녕").id();
        react(dad, messageId, "FIRE").andExpect(status().isBadRequest());

        long systemMessageId = jdbcTemplate.queryForObject(
                "SELECT m.id FROM messages m JOIN chat_rooms r ON r.id = m.room_id WHERE r.group_id = ? AND m.seq = 1",
                Long.class, groupId);
        react(dad, systemMessageId, "HEART").andExpect(status().isBadRequest());
    }

    // --- SEC-T1 다른 그룹 접근 ---

    @Test
    void 다른_그룹의_채팅방은_403() throws Exception {
        messages(stranger, "").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));
        read(stranger, 1).andExpect(status().isForbidden());
    }

    @Test
    void 다른_그룹이나_입장_전_메시지를_id로_건드리면_404로_숨긴다() throws Exception {
        long messageId = send(groupId, mom, "가족 이야기").id();

        deleteMessage(stranger, messageId).andExpect(status().isNotFound());
        react(stranger, messageId, "HEART").andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/messages/{id}/reactions/me", messageId).with(asUser(stranger)))
                .andExpect(status().isNotFound());
        deleteMessage(mom, 999_999_999L).andExpect(status().isNotFound());

        long daughter = createUser();
        addMember(mockMvc, groupId, mom, daughter);
        react(daughter, messageId, "HEART").andExpect(status().isNotFound());
    }

    // --- SEC-T9 로그 ---

    @Test
    void 메시지_본문은_로그에_남지_않는다(CapturedOutput output) throws Exception {
        String marker = "비밀-" + UUID.randomUUID();
        long messageId = send(groupId, mom, marker).id();
        messages(dad, "").andExpect(status().isOk());
        deleteMessage(mom, messageId).andExpect(status().isNoContent());

        assertThat(output.getAll()).doesNotContain(marker);
    }

    // --- 도우미 ---

    private ResultActions messages(long userId, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/groups/" + groupId + "/messages" + query).with(asUser(userId)));
    }

    private ResultActions read(long userId, long lastReadSeq) throws Exception {
        return mockMvc.perform(put("/api/v1/groups/{g}/read", groupId).with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"lastReadSeq\":" + lastReadSeq + "}"));
    }

    private ResultActions react(long userId, long messageId, String emoji) throws Exception {
        return mockMvc.perform(post("/api/v1/messages/{id}/reactions", messageId).with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"emoji\":\"" + emoji + "\"}"));
    }

    private ResultActions deleteMessage(long userId, long messageId) throws Exception {
        return mockMvc.perform(delete("/api/v1/messages/{id}", messageId).with(asUser(userId)));
    }

    private ResultActions myGroups(long userId) throws Exception {
        return mockMvc.perform(get("/api/v1/groups/me").with(asUser(userId)));
    }

    private int unreadOf(long viewer, long seq) throws Exception {
        String body = messages(viewer, "?afterSeq=" + (seq - 1) + "&size=1").andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.items[0].unreadCount");
    }
}
