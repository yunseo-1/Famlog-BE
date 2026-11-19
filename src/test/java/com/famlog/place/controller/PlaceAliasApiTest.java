package com.famlog.place.controller;

import static com.famlog.support.PlaceApi.doc;
import static com.famlog.support.PlaceApi.shareNew;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.famlog.support.Family;
import com.famlog.support.FakeKakaoLocalClient;
import com.famlog.support.GroupApi;
import com.famlog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 별칭 장소 (사양서 4.4 PLC-06, 2.2 권한, 9.3 인가). */
class PlaceAliasApiTest extends IntegrationTest {

    private static final String ALIASES = "/api/v1/groups/{g}/place-aliases";

    @Autowired
    FakeKakaoLocalClient kakao;

    private Family family;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
    }

    @Test
    void 공유한_장소나_주소_메모를_붙여_만들고_이름_순으로_본다() throws Exception {
        long placeId = shareNew(mockMvc, kakao, family.groupId(), family.mom(),
                doc("우리 아파트", "ETC", "서울 송파구 잠실동 1", 37.51, 127.1), "ETC");

        create(family.dad(), "{\"alias\":\"  할머니 댁 \",\"addressText\":\"부산 해운대구 어딘가\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alias").value("할머니 댁"))
                .andExpect(jsonPath("$.addressText").value("부산 해운대구 어딘가"))
                .andExpect(jsonPath("$.place").doesNotExist())
                .andExpect(jsonPath("$.createdBy").value(family.dad()));
        create(family.son(), "{\"alias\":\"우리 집\",\"placeId\":%d}".formatted(placeId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.place.id").value(placeId))
                .andExpect(jsonPath("$.place.name").value("우리 아파트"))
                .andExpect(jsonPath("$.addressText").doesNotExist());
        create(family.mom(), "{\"alias\":\"고모네\"}").andExpect(status().isCreated());

        mockMvc.perform(get(ALIASES, family.groupId()).with(asUser(family.son())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].alias", contains("고모네", "우리 집", "할머니 댁")))
                .andExpect(jsonPath("$.items[1].place.address").value("서울 송파구 잠실동 1"));
    }

    @Test
    void 같은_별칭은_409이고_다른_그룹_장소는_연결할_수_없고_값을_검증한다() throws Exception {
        create(family.mom(), "{\"alias\":\"할머니 댁\"}").andExpect(status().isCreated());
        create(family.dad(), "{\"alias\":\" 할머니 댁\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALIAS_DUPLICATED"));

        long stranger = createUser();
        long otherGroup = GroupApi.createGroup(mockMvc, stranger, "다른 가족");
        long otherPlace = shareNew(mockMvc, kakao, otherGroup, stranger,
                doc("남의 집", "ETC", "서울 송파구 잠실동 9", 37.51, 127.1), "ETC");
        create(family.mom(), "{\"alias\":\"남의 집\",\"placeId\":%d}".formatted(otherPlace))
                .andExpect(status().isBadRequest());
        // 다른 가족은 같은 별칭을 따로 쓸 수 있다
        mockMvc.perform(post(ALIASES, otherGroup).with(asUser(stranger)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"alias\":\"할머니 댁\"}")).andExpect(status().isCreated());

        for (String body : new String[] { "{\"alias\":\" \"}", "{}", "{\"alias\":\"" + "가".repeat(31) + "\"}",
                "{\"alias\":\"이모네\",\"addressText\":\"" + "가".repeat(201) + "\"}" }) {
            create(family.mom(), body).andExpect(status().isBadRequest());
        }
    }

    @Test
    void 만든_사람과_ADMIN만_지우고_다른_그룹_별칭은_404_구성원이_아니면_403() throws Exception {
        long sonAlias = id(create(family.son(), "{\"alias\":\"학원\"}"));
        long dadAlias = id(create(family.dad(), "{\"alias\":\"회사\"}"));

        remove(family.dad(), sonAlias).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_REQUIRED"));
        remove(family.son(), sonAlias).andExpect(status().isNoContent());
        remove(family.mom(), dadAlias).andExpect(status().isNoContent());

        long stranger = createUser();
        long otherGroup = GroupApi.createGroup(mockMvc, stranger, "다른 가족");
        long strangerAlias = id(mockMvc.perform(post(ALIASES, otherGroup).with(asUser(stranger))
                .contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"별장\"}")));
        remove(family.mom(), strangerAlias).andExpect(status().isNotFound());
        mockMvc.perform(get(ALIASES, family.groupId()).with(asUser(stranger))).andExpect(status().isForbidden());
        mockMvc.perform(post(ALIASES, family.groupId()).with(asUser(stranger))
                .contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"몰래\"}"))
                .andExpect(status().isForbidden());
    }

    private ResultActions create(long userId, String body) throws Exception {
        return mockMvc.perform(post(ALIASES, family.groupId()).with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions remove(long userId, long aliasId) throws Exception {
        return mockMvc.perform(delete(ALIASES + "/{a}", family.groupId(), aliasId).with(asUser(userId)));
    }

    private static long id(ResultActions result) throws Exception {
        String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
