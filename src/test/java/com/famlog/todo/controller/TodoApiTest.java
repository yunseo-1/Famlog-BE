package com.famlog.todo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.famlog.notification.push.PushMessage;
import com.famlog.support.Family;
import com.famlog.support.GroupApi;
import com.famlog.support.PushIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 할 일 (사양서 4.3 TODO-01, 2.2 권한, 9.3 인가, 11.1 커서 페이징). */
class TodoApiTest extends PushIntegrationTest {

    private static final String GROUP_TODOS = "/api/v1/groups/{g}/todos";
    private static final String ONE = "/api/v1/todos/{id}";

    private Family family;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
    }

    @Test
    void 등록하면_안_한_TASK로_만들어지고_구성원_누구나_본다() throws Exception {
        createRequest(family.mom(), """
                {"title":"  우유 사 오기 ","assigneeId":%d,"dueDate":"2026-11-21"}""".formatted(family.dad()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("우유 사 오기"))
                .andExpect(jsonPath("$.kind").value("TASK"))
                .andExpect(jsonPath("$.assigneeId").value(family.dad()))
                .andExpect(jsonPath("$.dueDate").value("2026-11-21"))
                .andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.doneAt").doesNotExist())
                .andExpect(jsonPath("$.createdBy").value(family.mom()))
                .andExpect(jsonPath("$.version").value(0));

        long id = create(family.mom(), "{\"title\":\"분리수거\"}");
        mockMvc.perform(get(ONE, id).with(asUser(family.son())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").doesNotExist())
                .andExpect(jsonPath("$.dueDate").doesNotExist());
    }

    @Test
    void 목록은_안_한_일_기한_순_기한_없음은_뒤_그다음_최근_완료_순이고_커서로_이어_읽는다() throws Exception {
        long a = create(family.mom(), "{\"title\":\"A\",\"dueDate\":\"2026-11-25\"}");
        long b = create(family.mom(), "{\"title\":\"B\",\"dueDate\":\"2026-11-21\"}");
        long c = create(family.mom(), "{\"title\":\"C\"}");
        long d = create(family.mom(), "{\"title\":\"D\",\"dueDate\":\"2026-11-21\"}");
        long e = create(family.mom(), "{\"title\":\"E\",\"dueDate\":\"2026-11-20\"}");
        long f = create(family.mom(), "{\"title\":\"F\"}");
        complete(e, family.son());
        complete(f, family.son());

        assertThat(readAll(null, 2)).containsExactly("B", "D", "A", "C", "F", "E");
        assertThat(readAll(null, 4)).containsExactly("B", "D", "A", "C", "F", "E");
        assertThat(readAll(null, 100)).containsExactly("B", "D", "A", "C", "F", "E");
        assertThat(readAll("false", 3)).containsExactly("B", "D", "A", "C");
        assertThat(readAll("true", 1)).containsExactly("F", "E");
        assertThat(List.of(a, b, c, d)).doesNotHaveDuplicates();
    }

    @Test
    void 담당자로_거른다() throws Exception {
        create(family.mom(), "{\"title\":\"아빠 일\",\"assigneeId\":%d}".formatted(family.dad()));
        create(family.mom(), "{\"title\":\"아들 일\",\"assigneeId\":%d}".formatted(family.son()));
        create(family.mom(), "{\"title\":\"누구든\"}");

        list(get(GROUP_TODOS, family.groupId()).param("assigneeId", Long.toString(family.son())))
                .andExpect(jsonPath("$.items[*].title", contains("아들 일")))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void 완료는_구성원_누구나_하고_취소할_수_있다() throws Exception {
        long id = create(family.mom(), "{\"title\":\"우유 사 오기\"}");

        update(id, family.son(), "{\"version\":0,\"done\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.doneBy").value(family.son()))
                .andExpect(jsonPath("$.doneAt").exists())
                .andExpect(jsonPath("$.version").value(1));
        update(id, family.dad(), "{\"version\":1,\"done\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.doneBy").doesNotExist())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void 제목_담당자_기한은_등록한_사람이나_ADMIN_이상만_고친다() throws Exception {
        long id = create(family.dad(), "{\"title\":\"세차\"}");

        update(id, family.son(), "{\"version\":0,\"title\":\"세차 (내가 할게)\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_REQUIRED"));
        update(id, family.son(), "{\"version\":0,\"assigneeId\":%d}".formatted(family.son()))
                .andExpect(status().isForbidden());
        update(id, family.dad(), "{\"version\":0,\"title\":\"손세차\"}").andExpect(status().isOk());
        update(id, family.mom(), "{\"version\":1,\"dueDate\":\"2026-11-22\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("손세차"))
                .andExpect(jsonPath("$.dueDate").value("2026-11-22"));
    }

    @Test
    void 담당자와_기한은_null을_보내면_지우고_키를_빼면_그대로다() throws Exception {
        long id = create(family.mom(), """
                {"title":"병원 예약","assigneeId":%d,"dueDate":"2026-11-21"}""".formatted(family.dad()));

        update(id, family.mom(), "{\"version\":0,\"title\":\"치과 예약\"}")
                .andExpect(jsonPath("$.assigneeId").value(family.dad()))
                .andExpect(jsonPath("$.dueDate").value("2026-11-21"));
        update(id, family.mom(), "{\"version\":1,\"assigneeId\":null}")
                .andExpect(jsonPath("$.assigneeId").doesNotExist())
                .andExpect(jsonPath("$.dueDate").value("2026-11-21"));
        update(id, family.mom(), "{\"version\":2,\"dueDate\":null,\"assigneeId\":%d}".formatted(family.son()))
                .andExpect(jsonPath("$.assigneeId").value(family.son()))
                .andExpect(jsonPath("$.dueDate").doesNotExist());
    }

    @Test
    void 알리기로_하고_완료하면_채팅방에_한_줄이_남고_다른_가족에게_푸시가_간다() throws Exception {
        GroupApi.setFamilyTitle(mockMvc, family.groupId(), family.son(), "막내");
        String momToken = deviceOf(family.mom());
        String sonToken = deviceOf(family.son());
        long id = create(family.mom(), "{\"title\":\"우유 사 오기\"}");
        long before = roomLastSeq(family.groupId());

        update(id, family.son(), "{\"version\":0,\"done\":true,\"announce\":true}").andExpect(status().isOk());

        assertThat(roomLastSeq(family.groupId())).isEqualTo(before + 1);
        assertThat(lastMessage("type")).isEqualTo("SYSTEM");
        assertThat(lastMessage("body")).isEqualTo("막내님이 '우유 사 오기' 할 일을 완료했어요.");
        assertThat(lastMessage("payload").replace(" ", "")).contains("\"kind\":\"TODO_DONE\"", "\"todoId\":" + id,
                "\"userId\":" + family.son());
        PushMessage push = pushSender.await(momToken, data -> "CHAT_MESSAGE".equals(data.get("type"))
                && Long.toString(before + 1).equals(data.get("seq")));
        assertThat(push.data()).containsEntry("messageType", "SYSTEM")
                .containsEntry("body", "막내님이 '우유 사 오기' 할 일을 완료했어요.");
        assertThat(pushSender.batchOf(push)).extracting(PushMessage::token).doesNotContain(sonToken);

        // 이미 완료한 일을 다시 완료하거나, 알리지 않고 완료하면 남기지 않는다
        update(id, family.son(), "{\"version\":1,\"done\":true,\"announce\":true}").andExpect(status().isOk());
        long quiet = create(family.mom(), "{\"title\":\"조용히\"}");
        update(quiet, family.son(), "{\"version\":0,\"done\":true}").andExpect(status().isOk());
        assertThat(roomLastSeq(family.groupId())).isEqualTo(before + 1);

        // 호칭이 없으면 닉네임
        update(quiet, family.dad(), "{\"version\":1,\"done\":false}").andExpect(status().isOk());
        update(quiet, family.dad(), "{\"version\":2,\"done\":true,\"announce\":true}").andExpect(status().isOk());
        assertThat(lastMessage("body")).isEqualTo("테스트님이 '조용히' 할 일을 완료했어요.");
    }

    @Test
    void 예전_version으로_고치면_409이고_version이_없으면_400() throws Exception {
        long id = create(family.mom(), "{\"title\":\"세탁\"}");
        update(id, family.mom(), "{\"version\":0,\"title\":\"빨래\"}").andExpect(status().isOk());

        update(id, family.son(), "{\"version\":0,\"done\":true}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        update(id, family.son(), "{\"done\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 입력이_틀리면_400() throws Exception {
        long stranger = createUser();
        for (String body : new String[] { "{\"title\":\" \"}", "{}", "{\"title\":\"장보기\",\"kind\":\"SHOPPING\"}",
            "{\"title\":\"남의 집\",\"assigneeId\":%d}".formatted(stranger),
            "{\"title\":\"옛날\",\"dueDate\":\"1999-12-31\"}", "{\"title\":\"%s\"}".formatted("가".repeat(101)) }) {
            createRequest(family.mom(), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        long id = create(family.mom(), "{\"title\":\"정리\"}");
        update(id, family.mom(), "{\"version\":0,\"title\":\"\"}").andExpect(status().isBadRequest());
        update(id, family.mom(), "{\"version\":0,\"assigneeId\":%d}".formatted(stranger))
                .andExpect(status().isBadRequest());

        for (String[] param : new String[][] { { "cursor", "아무거나" }, { "cursor", "WC4xLjI" }, { "size", "101" },
            { "size", "0" }, { "kind", "SHOPPING" } }) {
            mockMvc.perform(get(GROUP_TODOS, family.groupId()).with(asUser(family.mom())).param(param[0], param[1]))
                    .andExpect(status().isBadRequest());
        }
        // 안 한 일 구간의 커서("O.1.2")로 완료한 일만 달라고 하면 400
        mockMvc.perform(get(GROUP_TODOS, family.groupId()).with(asUser(family.mom())).param("cursor", "Ty4xLjI")
                        .param("done", "true"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 구성원이_아니면_403이고_다른_그룹_할_일_id는_404다() throws Exception {
        long id = create(family.mom(), "{\"title\":\"우리 집 일\"}");
        long other = createUser();
        GroupApi.createGroup(mockMvc, other, "다른 가족");

        createRequest(other, "{\"title\":\"몰래\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));
        mockMvc.perform(get(GROUP_TODOS, family.groupId()).with(asUser(other))).andExpect(status().isForbidden());
        mockMvc.perform(get(ONE, id).with(asUser(other))).andExpect(status().isNotFound());
        update(id, other, "{\"version\":0,\"done\":true}").andExpect(status().isNotFound());
        mockMvc.perform(delete(ONE, id).with(asUser(other))).andExpect(status().isNotFound());
        mockMvc.perform(get(ONE, id).with(asUser(family.mom()))).andExpect(jsonPath("$.done").value(false));
    }

    @Test
    void 남이_등록한_할_일은_MEMBER가_지울_수_없고_등록한_사람은_지운다() throws Exception {
        long id = create(family.dad(), "{\"title\":\"분리수거\"}");

        mockMvc.perform(delete(ONE, id).with(asUser(family.son()))).andExpect(status().isForbidden());
        mockMvc.perform(delete(ONE, id).with(asUser(family.dad()))).andExpect(status().isNoContent());
        mockMvc.perform(get(ONE, id).with(asUser(family.dad()))).andExpect(status().isNotFound());
    }

    /** 커서로 끝까지 읽은 제목 */
    private List<String> readAll(String done, int size) throws Exception {
        List<String> titles = new ArrayList<>();
        String cursor = null;
        do {
            MockHttpServletRequestBuilder request = get(GROUP_TODOS, family.groupId()).param("size",
                    Integer.toString(size));
            if (done != null) {
                request.param("done", done);
            }
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = list(request).andReturn().getResponse().getContentAsString();
            titles.addAll(JsonPath.read(body, "$.items[*].title"));
            cursor = JsonPath.read(body, "$.nextCursor");
        } while (cursor != null && titles.size() < 100);
        return titles;
    }

    private ResultActions list(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(asUser(family.son()))).andExpect(status().isOk());
    }

    private ResultActions createRequest(long userId, String body) throws Exception {
        return mockMvc.perform(post(GROUP_TODOS, family.groupId()).with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long create(long userId, String body) throws Exception {
        String response = createRequest(userId, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private ResultActions update(long id, long userId, String body) throws Exception {
        return mockMvc.perform(patch(ONE, id).with(asUser(userId)).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void complete(long id, long userId) throws Exception {
        String body = mockMvc.perform(get(ONE, id).with(asUser(userId))).andReturn().getResponse().getContentAsString();
        int version = JsonPath.read(body, "$.version");
        update(id, userId, "{\"version\":%d,\"done\":true}".formatted(version)).andExpect(status().isOk());
    }

    private String lastMessage(String column) {
        return jdbcTemplate.queryForObject("SELECT m." + column + "::text FROM messages m JOIN chat_rooms r "
                + "ON r.id = m.room_id WHERE r.group_id = ? ORDER BY m.seq DESC LIMIT 1", String.class,
                family.groupId());
    }
}
