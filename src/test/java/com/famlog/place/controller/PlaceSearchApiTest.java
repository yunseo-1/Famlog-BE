package com.famlog.place.controller;

import static com.famlog.support.PlaceApi.doc;
import static com.famlog.support.PlaceApi.newKakaoId;
import static com.famlog.support.PlaceApi.newQuery;
import static com.famlog.support.PlaceApi.search;
import static com.famlog.support.PlaceApi.shareNew;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.famlog.place.kakao.KakaoLocalClient.Document;
import com.famlog.support.Family;
import com.famlog.support.FakeKakaoLocalClient;
import com.famlog.support.GroupApi;
import com.famlog.support.IntegrationTest;

/** 카카오 로컬 검색 프록시 (사양서 11.3 PLC-01, 8.6 위치정보, 9.6 요청 제한). */
class PlaceSearchApiTest extends IntegrationTest {

    private static final String SEARCH = "/api/v1/places/search";

    @Autowired
    FakeKakaoLocalClient kakao;

    private Family family;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
    }

    @Test
    void 결과에_카테고리_제안_지역_주소를_넣고_이미_공유한_장소를_알려_준다() throws Exception {
        Document noodle = doc("잠실 냉면", "FD6", "서울 송파구 잠실동 40-1", 37.5133, 127.1001);
        Document cafe = new Document(newKakaoId(), "수원 카페", "카페", "CE7", "",
                "경기 수원시 영통구 이의동 1-1", "경기 수원시 영통구 광교로 1", "127.04", "37.29", "", null);
        Document gym = doc("동네 헬스장", "ZZ9", "세종특별자치시 한솔동 960", 36.48, 127.25);
        Document noCode = doc("뭔지 모를 곳", null, "서울 송파구 잠실동 1", 37.51, 127.1);
        String query = newQuery();
        kakao.register(query, false, noodle, cafe, gym, noCode);
        long sharedId = shareNew(mockMvc, kakao, family.groupId(), family.dad(), noodle, "RESTAURANT");

        mockMvc.perform(get(SEARCH).param("query", query).param("groupId", Long.toString(family.groupId()))
                        .with(asUser(family.mom())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].kakaoPlaceId").value(noodle.id()))
                .andExpect(jsonPath("$.items[0].suggestedCategory").value("RESTAURANT"))
                .andExpect(jsonPath("$.items[0].categoryDetail").value("잠실 냉면"))
                .andExpect(jsonPath("$.items[0].region2").value("송파구"))
                .andExpect(jsonPath("$.items[0].sharedPlaceId").value(sharedId))
                .andExpect(jsonPath("$.items[0].distanceM").doesNotExist())
                .andExpect(jsonPath("$.items[1].suggestedCategory").value("CAFE"))
                .andExpect(jsonPath("$.items[1].address").value("경기 수원시 영통구 광교로 1"))
                .andExpect(jsonPath("$.items[1].region2").value("수원시 영통구"))
                .andExpect(jsonPath("$.items[1].phone").doesNotExist())
                .andExpect(jsonPath("$.items[1].sharedPlaceId").doesNotExist())
                .andExpect(jsonPath("$.items[2].suggestedCategory").value("ETC"))
                .andExpect(jsonPath("$.items[2].region1").value("세종"))
                .andExpect(jsonPath("$.items[2].region2").doesNotExist())
                .andExpect(jsonPath("$.items[3].suggestedCategory").value("ETC"));

        // groupId 가 없으면 이미 공유했는지 알려 주지 않는다
        search(mockMvc, family.mom(), query).andExpect(jsonPath("$.items[0].sharedPlaceId").doesNotExist());
    }

    @Test
    void 위치_없는_검색은_목록을_캐시하고_위치가_있는_검색은_캐시하지_않고_위치_이용을_기록한다() throws Exception {
        String query = newQuery();
        Document doc = doc("동네 분식", "FD6", "서울 송파구 잠실동 1", 37.51, 127.1);
        kakao.register(query, true, doc);
        long user = createUser();

        search(mockMvc, user, query).andExpect(status().isOk());
        search(mockMvc, user, query).andExpect(status().isOk());
        assertThat(kakao.callCount(query)).isEqualTo(1);
        assertThat(accessLogs(user, "PLACE_SEARCH")).isZero();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get(SEARCH).param("query", query).param("x", "127.1").param("y", "37.5")
                            .with(asUser(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].kakaoPlaceId").value(doc.id()));
        }
        assertThat(kakao.callCount(query)).isEqualTo(3);
        assertThat(kakao.calledWithLocation(query)).isTrue();
        assertThat(accessLogs(user, "PLACE_SEARCH")).isEqualTo(2);
    }

    @Test
    void 검색어_좌표_쪽_번호를_검증한다() throws Exception {
        long user = createUser();
        String[][] invalid = { { "query", " " }, { "query", "가".repeat(101) }, { "x", "127.1" }, { "y", "37.5" },
                { "page", "0" }, { "page", "46" } };
        for (String[] param : invalid) {
            var request = get(SEARCH).with(asUser(user));
            if (!param[0].equals("query")) {
                request.param("query", "냉면");
            }
            mockMvc.perform(request.param(param[0], param[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(get(SEARCH).param("query", "냉면").param("x", "200").param("y", "37.5").with(asUser(user)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(SEARCH).with(asUser(user))).andExpect(status().isBadRequest());
    }

    @Test
    void 다른_그룹을_주면_403이고_로그인하지_않으면_401() throws Exception {
        long stranger = createUser();
        long otherGroup = GroupApi.createGroup(mockMvc, stranger, "다른 가족");

        mockMvc.perform(get(SEARCH).param("query", "냉면").param("groupId", Long.toString(otherGroup))
                        .with(asUser(family.mom())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_MEMBER"));
        mockMvc.perform(get(SEARCH).param("query", "냉면")).andExpect(status().isUnauthorized());
    }

    @Test
    void 사용자당_분당_30번까지_검색한다() throws Exception {
        long user = createUser();
        String query = newQuery();
        for (int i = 0; i < 30; i++) {
            search(mockMvc, user, query).andExpect(status().isOk());
        }

        search(mockMvc, user, query)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        search(mockMvc, createUser(), query).andExpect(status().isOk());
    }

    private long accessLogs(long userId, String purpose) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM location_access_logs WHERE user_id = ? "
                + "AND purpose = ?", Long.class, userId, purpose);
    }
}
