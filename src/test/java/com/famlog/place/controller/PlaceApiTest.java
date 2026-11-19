package com.famlog.place.controller;

import static com.famlog.support.PlaceApi.doc;
import static com.famlog.support.PlaceApi.newQuery;
import static com.famlog.support.PlaceApi.search;
import static com.famlog.support.PlaceApi.share;
import static com.famlog.support.PlaceApi.shareNew;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.famlog.notification.push.PushMessage;
import com.famlog.place.kakao.KakaoLocalClient.Document;
import com.famlog.support.Family;
import com.famlog.support.FakeKakaoLocalClient;
import com.famlog.support.GroupApi;
import com.famlog.support.PushIntegrationTest;
import com.famlog.support.ScheduleApi;

/** 장소 공유·수정·삭제·다녀옴/가고 싶어요 (사양서 4.4 PLC-01·05, 2.2 권한, 9.3 인가). */
class PlaceApiTest extends PushIntegrationTest {

    private static final String ONE = "/api/v1/places/{id}";

    @Autowired
    FakeKakaoLocalClient kakao;

    private Family family;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
    }

    @Test
    void 검색한_장소를_공유하면_서버가_가진_검색_결과로_저장하고_채팅방에_카드가_올라가고_가족에게_푸시가_간다() throws Exception {
        String dadPhone = deviceOf(family.dad());
        String momPhone = deviceOf(family.mom());
        Document cold = doc("잠실 냉면", "FD6", "서울 송파구 잠실동 40-1", 37.5133, 127.1001);
        String query = newQuery();
        kakao.register(query, true, cold);
        search(mockMvc, family.mom(), query).andExpect(status().isOk());

        share(mockMvc, family.groupId(), family.mom(), """
                {"kakaoPlaceId":"%s","category":"RESTAURANT","oneLineReview":"  물냉면 최고 ","rating":5}"""
                .formatted(cold.id()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("잠실 냉면"))
                .andExpect(jsonPath("$.category").value("RESTAURANT"))
                .andExpect(jsonPath("$.categoryDetail").value("잠실 냉면"))
                .andExpect(jsonPath("$.address").value("서울 송파구 잠실동 40-1"))
                .andExpect(jsonPath("$.region1").value("서울"))
                .andExpect(jsonPath("$.region2").value("송파구"))
                .andExpect(jsonPath("$.region3").value("잠실동"))
                .andExpect(jsonPath("$.lat").value(37.5133))
                .andExpect(jsonPath("$.lng").value(127.1001))
                .andExpect(jsonPath("$.oneLineReview").value("물냉면 최고"))
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.sharedBy").value(family.mom()))
                .andExpect(jsonPath("$.marks.visited", empty()));

        long placeId = jdbcTemplate.queryForObject("SELECT id FROM places WHERE kakao_place_id = ?", Long.class,
                cold.id());
        assertThat(lastMessage("type")).isEqualTo("PLACE");
        assertThat(lastMessage("sender_id")).isEqualTo(Long.toString(family.mom()));
        assertThat(lastMessage("body")).isEqualTo("잠실 냉면");
        assertThat(lastMessage("payload").replace(" ", "")).contains("\"placeId\":" + placeId,
                "\"category\":\"RESTAURANT\"", "\"region2\":\"송파구\"", "\"rating\":5");
        PushMessage push = pushSender.await(dadPhone, data -> "PLACE".equals(data.get("messageType")));
        assertThat(push.data()).containsEntry("type", "CHAT_MESSAGE")
                .containsEntry("body", "장소를 공유했어요: 잠실 냉면")
                .containsEntry("senderId", Long.toString(family.mom()));
        assertThat(pushSender.batchOf(push)).extracting(PushMessage::token).doesNotContain(momPhone);
    }

    @Test
    void shareToChat이_false면_채팅에_올리지_않는다() throws Exception {
        long before = roomLastSeq(family.groupId());
        shareNew(mockMvc, kakao, family.groupId(), family.dad(), doc("동네 약국", "PM9", "서울 강남구 역삼동 1",
                37.5, 127.03), "LIFE");

        assertThat(roomLastSeq(family.groupId())).isEqualTo(before);
    }

    @Test
    void 검색하지_않았거나_오래된_장소는_400이고_같은_장소를_다시_공유하면_409() throws Exception {
        share(mockMvc, family.groupId(), family.mom(), "{\"kakaoPlaceId\":\"never-searched\",\"category\":\"CAFE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Document cafe = doc("모퉁이 카페", "CE7", "서울 송파구 방이동 1", 37.51, 127.11);
        shareNew(mockMvc, kakao, family.groupId(), family.mom(), cafe, "CAFE");
        share(mockMvc, family.groupId(), family.dad(), "{\"kakaoPlaceId\":\"%s\",\"category\":\"CAFE\"}"
                .formatted(cafe.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLACE_ALREADY_SHARED"));

        // 다른 가족은 같은 장소를 따로 공유할 수 있다
        long stranger = createUser();
        long otherGroup = GroupApi.createGroup(mockMvc, stranger, "다른 가족");
        share(mockMvc, otherGroup, stranger, "{\"kakaoPlaceId\":\"%s\",\"category\":\"CAFE\",\"shareToChat\":false}"
                .formatted(cafe.id()))
                .andExpect(status().isCreated());
    }

    @Test
    void 카테고리_별점_한줄평을_검증한다() throws Exception {
        Document doc = doc("한강 공원", "AT4", "서울 영등포구 여의도동 1", 37.52, 126.93);
        String query = newQuery();
        kakao.register(query, true, doc);
        search(mockMvc, family.mom(), query).andExpect(status().isOk());

        for (String body : new String[] { "{\"kakaoPlaceId\":\"%s\"}",
                "{\"kakaoPlaceId\":\"%s\",\"category\":\"PARK\"}",
                "{\"kakaoPlaceId\":\"%s\",\"category\":\"TRAVEL\",\"rating\":6}",
                "{\"kakaoPlaceId\":\"%s\",\"category\":\"TRAVEL\",\"rating\":0}",
                "{\"kakaoPlaceId\":\"%s\",\"category\":\"TRAVEL\",\"oneLineReview\":\"" + "가".repeat(101) + "\"}" }) {
            share(mockMvc, family.groupId(), family.mom(), body.formatted(doc.id())).andExpect(status().isBadRequest());
        }
    }

    @Test
    void 구성원이_아니면_공유할_수_없고_다른_그룹_장소는_404() throws Exception {
        long placeId = shareNew(mockMvc, kakao, family.groupId(), family.mom(),
                doc("우리 동네 빵집", "FD6", "서울 송파구 잠실동 2", 37.51, 127.09), "RESTAURANT");
        long stranger = createUser();

        share(mockMvc, family.groupId(), stranger, "{\"kakaoPlaceId\":\"x\",\"category\":\"CAFE\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));
        mockMvc.perform(get(ONE, placeId).with(asUser(stranger))).andExpect(status().isNotFound());
        update(placeId, stranger, "{\"rating\":1}").andExpect(status().isNotFound());
        mockMvc.perform(delete(ONE, placeId).with(asUser(stranger))).andExpect(status().isNotFound());
        mockMvc.perform(put(ONE + "/marks/WANT", placeId).with(asUser(stranger))).andExpect(status().isNotFound());
        mockMvc.perform(get(ONE, placeId).with(asUser(family.son())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("우리 동네 빵집"));
    }

    @Test
    void 공유한_사람과_ADMIN만_고치고_null은_지우고_키를_빼면_그대로다() throws Exception {
        long placeId = shareNew(mockMvc, kakao, family.groupId(), family.dad(),
                doc("오래된 식당", "FD6", "서울 송파구 잠실동 3", 37.51, 127.09), "RESTAURANT");
        update(placeId, family.dad(), "{\"rating\":4,\"oneLineReview\":\"맛있다\"}").andExpect(status().isOk());

        update(placeId, family.son(), "{\"rating\":1}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_REQUIRED"));
        update(placeId, family.dad(), "{\"category\":\"CAFE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("CAFE"))
                .andExpect(jsonPath("$.rating").value(4))
                .andExpect(jsonPath("$.oneLineReview").value("맛있다"));
        update(placeId, family.mom(), "{\"rating\":null,\"oneLineReview\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").doesNotExist())
                .andExpect(jsonPath("$.oneLineReview").doesNotExist())
                .andExpect(jsonPath("$.category").value("CAFE"));
        update(placeId, family.dad(), "{\"rating\":6}").andExpect(status().isBadRequest());
        update(placeId, family.dad(), "{\"oneLineReview\":\"" + "가".repeat(101) + "\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void 지우면_일정과_별칭의_연결은_비고_채팅_카드는_남는다() throws Exception {
        String query = newQuery();
        Document doc = doc("가족 식당", "FD6", "서울 송파구 잠실동 4", 37.51, 127.09);
        kakao.register(query, true, doc);
        search(mockMvc, family.son(), query).andExpect(status().isOk());
        share(mockMvc, family.groupId(), family.son(), "{\"kakaoPlaceId\":\"%s\",\"category\":\"RESTAURANT\"}"
                .formatted(doc.id())).andExpect(status().isCreated());
        long placeId = jdbcTemplate.queryForObject("SELECT id FROM places WHERE kakao_place_id = ?", Long.class,
                doc.id());
        long cardSeq = roomLastSeq(family.groupId());
        long scheduleId = ScheduleApi.create(mockMvc, family.groupId(), family.son(), ScheduleApi.with(
                ScheduleApi.timed("외식", "2026-11-21T18:00", "2026-11-21T20:00", family.son()),
                "\"placeId\":" + placeId));
        mockMvc.perform(post("/api/v1/groups/{g}/place-aliases", family.groupId()).with(asUser(family.son()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"단골집\",\"placeId\":%d}".formatted(placeId)))
                .andExpect(status().isCreated());
        mockMvc.perform(put(ONE + "/marks/VISITED", placeId).with(asUser(family.dad()))).andExpect(status().isOk());

        mockMvc.perform(delete(ONE, placeId).with(asUser(family.dad())))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(ONE, placeId).with(asUser(family.son()))).andExpect(status().isNoContent());

        mockMvc.perform(get(ONE, placeId).with(asUser(family.son()))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/schedules/{id}", scheduleId).with(asUser(family.son())))
                .andExpect(jsonPath("$.place").doesNotExist());
        mockMvc.perform(get("/api/v1/groups/{g}/place-aliases", family.groupId()).with(asUser(family.son())))
                .andExpect(jsonPath("$.items[0].alias").value("단골집"))
                .andExpect(jsonPath("$.items[0].place").doesNotExist());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM place_marks WHERE place_id = ?", Long.class,
                placeId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT m.type FROM messages m JOIN chat_rooms r ON r.id = m.room_id "
                + "WHERE r.group_id = ? AND m.seq = ?", String.class, family.groupId(), cardSeq)).isEqualTo("PLACE");
    }

    @Test
    void ADMIN은_남이_공유한_장소를_지울_수_있다() throws Exception {
        long placeId = shareNew(mockMvc, kakao, family.groupId(), family.son(),
                doc("PC방", "ETC", "서울 송파구 잠실동 5", 37.51, 127.09), "ETC");

        mockMvc.perform(delete(ONE, placeId).with(asUser(family.mom()))).andExpect(status().isNoContent());
    }

    @Test
    void 다녀옴과_가고_싶어요는_구성원마다_켜고_끄고_여러_번_해도_같다() throws Exception {
        long placeId = shareNew(mockMvc, kakao, family.groupId(), family.mom(),
                doc("제주 바다", "AT4", "제주특별자치도 제주시 애월읍 1", 33.46, 126.31), "TRAVEL");

        mark(placeId, family.dad(), "VISITED", true);
        mark(placeId, family.dad(), "VISITED", true);
        mark(placeId, family.mom(), "VISITED", true);
        mark(placeId, family.son(), "WANT", true)
                .andExpect(jsonPath("$.visited", contains((int) family.mom(), (int) family.dad())))
                .andExpect(jsonPath("$.want", contains((int) family.son())));
        mark(placeId, family.dad(), "VISITED", false);
        mark(placeId, family.dad(), "VISITED", false)
                .andExpect(jsonPath("$.visited", contains((int) family.mom())));
        mockMvc.perform(put(ONE + "/marks/SOMEDAY", placeId).with(asUser(family.son())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get(ONE, placeId).with(asUser(family.son())))
                .andExpect(jsonPath("$.marks.visited", contains((int) family.mom())))
                .andExpect(jsonPath("$.marks.want", contains((int) family.son())));
    }

    private ResultActions update(long placeId, long userId, String body) throws Exception {
        return mockMvc.perform(patch(ONE, placeId).with(asUser(userId)).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions mark(long placeId, long userId, String kind, boolean on) throws Exception {
        return mockMvc.perform((on ? put(ONE + "/marks/{k}", placeId, kind) : delete(ONE + "/marks/{k}", placeId, kind))
                        .with(asUser(userId)))
                .andExpect(status().isOk());
    }

    private String lastMessage(String column) {
        return jdbcTemplate.queryForObject("SELECT m." + column + "::text FROM messages m JOIN chat_rooms r "
                + "ON r.id = m.room_id WHERE r.group_id = ? ORDER BY m.seq DESC LIMIT 1", String.class,
                family.groupId());
    }
}
