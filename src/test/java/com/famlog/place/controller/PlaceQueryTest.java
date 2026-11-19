package com.famlog.place.controller;

import static com.famlog.support.PlaceApi.doc;
import static com.famlog.support.PlaceApi.shareNew;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.famlog.support.Family;
import com.famlog.support.FakeKakaoLocalClient;
import com.famlog.support.GroupApi;
import com.famlog.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 장소 목록·지역별 개수·내 주변 (사양서 4.4 PLC-02·03·04, 8.6, 18.1 PLC-02·03). */
@ExtendWith(OutputCaptureExtension.class)
class PlaceQueryTest extends IntegrationTest {

    private static final String LIST = "/api/v1/groups/{g}/places";

    @Autowired
    FakeKakaoLocalClient kakao;

    private Family family;

    @BeforeEach
    void setUp() throws Exception {
        family = Family.create(mockMvc, this::createUser);
    }

    @Test
    void 인수기준_아빠가_송파구_2곳_강남구_1곳을_공유했을_때_서울_송파구_아빠를_고르면_2곳만_보인다() throws Exception {
        share(family.dad(), "송파 냉면", "FD6", "서울 송파구 잠실동 1", 37.51, 127.10, "RESTAURANT");
        share(family.dad(), "송파 카페", "CE7", "서울 송파구 방이동 2", 37.52, 127.11, "CAFE");
        share(family.dad(), "강남 국밥", "FD6", "서울 강남구 역삼동 3", 37.50, 127.03, "RESTAURANT");
        share(family.mom(), "엄마 송파 빵집", "FD6", "서울 송파구 잠실동 4", 37.51, 127.09, "RESTAURANT");

        mockMvc.perform(get(LIST, family.groupId()).param("region1", "서울").param("region2", "송파구")
                        .param("sharedBy", Long.toString(family.dad())).with(asUser(family.son())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].name", contains("송파 카페", "송파 냉면")))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void 지역_공유한_사람_카테고리는_함께_걸리고_여러_값을_고를_수_있다() throws Exception {
        share(family.dad(), "A 식당", "FD6", "서울 송파구 잠실동 1", 37.51, 127.10, "RESTAURANT");
        share(family.mom(), "B 카페", "CE7", "서울 송파구 방이동 2", 37.52, 127.11, "CAFE");
        share(family.son(), "C 공원", "AT4", "경기 수원시 영통구 이의동 3", 37.29, 127.04, "TRAVEL");
        share(family.son(), "D 약국", "PM9", "서울 강남구 역삼동 4", 37.50, 127.03, "LIFE");

        assertThat(names(get(LIST, family.groupId()).param("region1", "서울"))).containsExactly("D 약국", "B 카페",
                "A 식당");
        assertThat(names(get(LIST, family.groupId()).param("sharedBy", Long.toString(family.mom()),
                Long.toString(family.son())))).containsExactly("D 약국", "C 공원", "B 카페");
        assertThat(names(get(LIST, family.groupId()).param("category", "CAFE", "RESTAURANT")))
                .containsExactly("B 카페", "A 식당");
        assertThat(names(get(LIST, family.groupId()).param("region1", "경기").param("region2", "수원시 영통구")
                .param("category", "TRAVEL"))).containsExactly("C 공원");
        assertThat(names(get(LIST, family.groupId()).param("region1", "서울").param("category", "TRAVEL"))).isEmpty();

        mockMvc.perform(get(LIST, family.groupId()).param("region2", "송파구").with(asUser(family.mom())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(LIST, family.groupId()).param("category", "PARK").with(asUser(family.mom())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 최신_공유_순으로_커서로_이어_읽는다() throws Exception {
        List<String> shared = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            share(family.mom(), "장소" + i, "FD6", "서울 송파구 잠실동 " + i, 37.51, 127.10, "RESTAURANT");
            shared.addFirst("장소" + i);
        }

        List<String> read = new ArrayList<>();
        String cursor = null;
        do {
            var request = get(LIST, family.groupId()).param("size", "2").with(asUser(family.dad()));
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString();
            read.addAll(JsonPath.read(body, "$.items[*].name"));
            cursor = JsonPath.read(body, "$.nextCursor");
        } while (cursor != null);

        assertThat(read).isEqualTo(shared);
        mockMvc.perform(get(LIST, family.groupId()).param("cursor", "WC4xMjM").with(asUser(family.dad())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(LIST, family.groupId()).param("size", "101").with(asUser(family.dad())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 지도_영역_안의_장소만_보이고_영역은_네_값을_모두_보내야_한다() throws Exception {
        share(family.mom(), "안쪽", "FD6", "서울 송파구 잠실동 1", 37.51, 127.10, "RESTAURANT");
        share(family.mom(), "바깥", "FD6", "서울 강남구 역삼동 2", 37.50, 127.03, "RESTAURANT");

        assertThat(names(get(LIST, family.groupId()).param("swLat", "37.505").param("swLng", "127.05")
                .param("neLat", "37.52").param("neLng", "127.12"))).containsExactly("안쪽");
        mockMvc.perform(get(LIST, family.groupId()).param("swLat", "37.505").param("swLng", "127.05")
                        .with(asUser(family.mom())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(LIST, family.groupId()).param("swLat", "37.6").param("swLng", "127.05")
                        .param("neLat", "37.5").param("neLng", "127.12").with(asUser(family.mom())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 지역별_개수는_공유한_사람_카테고리_필터를_따르고_시군구가_없는_곳은_맨_뒤다() throws Exception {
        share(family.dad(), "a", "FD6", "서울 송파구 잠실동 1", 37.51, 127.10, "RESTAURANT");
        share(family.dad(), "b", "FD6", "서울 송파구 방이동 2", 37.52, 127.11, "RESTAURANT");
        share(family.dad(), "c", "CE7", "서울 강남구 역삼동 3", 37.50, 127.03, "CAFE");
        share(family.mom(), "d", "FD6", "세종특별자치시 한솔동 4", 36.48, 127.25, "RESTAURANT");
        share(family.mom(), "e", "FD6", "경기 수원시 영통구 이의동 5", 37.29, 127.04, "RESTAURANT");

        mockMvc.perform(get(LIST + "/regions", family.groupId()).with(asUser(family.son())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items[*].region1", contains("경기", "서울", "세종")))
                .andExpect(jsonPath("$.items[1].count").value(3))
                .andExpect(jsonPath("$.items[1].children[*].region2", contains("강남구", "송파구")))
                .andExpect(jsonPath("$.items[1].children[1].count").value(2))
                .andExpect(jsonPath("$.items[2].children[0].region2").doesNotExist())
                .andExpect(jsonPath("$.items[2].children[0].count").value(1));
        mockMvc.perform(get(LIST + "/regions", family.groupId()).param("sharedBy", Long.toString(family.dad()))
                        .param("category", "RESTAURANT").with(asUser(family.son())))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].region1", contains("서울")))
                .andExpect(jsonPath("$.items[0].children[*].region2", contains("송파구")));
    }

    @Test
    void 내_주변은_반경_안을_가까운_순으로_주고_다른_그룹_장소는_없고_위치_이용을_기록한다() throws Exception {
        // 기준점 37.5000, 127.0000 에서 북쪽으로 약 0.56km, 2.2km, 5.6km
        share(family.mom(), "가까운 곳", "FD6", "서울 서초구 서초동 1", 37.505, 127.0, "RESTAURANT");
        share(family.mom(), "먼 곳", "FD6", "서울 서초구 서초동 3", 37.55, 127.0, "RESTAURANT");
        share(family.mom(), "중간", "FD6", "서울 서초구 서초동 2", 37.52, 127.0, "RESTAURANT");
        agreeLocation(family.dad());
        long stranger = createUser();
        long otherGroup = GroupApi.createGroup(mockMvc, stranger, "다른 가족");
        shareNew(mockMvc, kakao, otherGroup, stranger, doc("남의 집 앞", "FD6", "서울 서초구 서초동 9", 37.5001,
                127.0), "RESTAURANT");

        mockMvc.perform(nearby("37.5", "127.0", null).with(asUser(family.dad())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].place.name", contains("가까운 곳", "중간")))
                .andExpect(jsonPath("$.items[0].distanceM", both(greaterThan(550)).and(lessThan(560))))
                .andExpect(jsonPath("$.items[1].distanceM", both(greaterThan(2210)).and(lessThan(2230))));
        mockMvc.perform(nearby("37.5", "127.0", "10").with(asUser(family.dad())))
                .andExpect(jsonPath("$.items[*].place.name", containsInAnyOrder("가까운 곳", "중간", "먼 곳")))
                .andExpect(jsonPath("$.items[2].place.name").value("먼 곳"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM location_access_logs WHERE user_id = ? "
                + "AND purpose = 'NEARBY'", Long.class, family.dad())).isEqualTo(2);
    }

    @Test
    void 내_주변_좌표와_반경을_검증한다() throws Exception {
        agreeLocation(family.dad());
        for (String[] params : new String[][] { { "95", "127", "3" }, { "37.5", "181", "3" }, { "37.5", "127", "0.5" },
                { "37.5", "127", "21" } }) {
            mockMvc.perform(nearby(params[0], params[1], params[2]).with(asUser(family.dad())))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get(LIST + "/nearby", family.groupId()).param("lat", "37.5").with(asUser(family.dad())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 좌표는_로그에_남지_않는다(CapturedOutput output) throws Exception {
        share(family.mom(), "로그 확인", "FD6", "서울 서초구 서초동 1", 37.505, 127.0, "RESTAURANT");
        agreeLocation(family.dad());

        mockMvc.perform(nearby("37.512345", "127.098765", null).with(asUser(family.dad())))
                .andExpect(status().isOk());
        mockMvc.perform(nearby("95.123456", "127.098765", null).with(asUser(family.dad())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/places/search").param("query", "냉면").param("x", "127.087654")
                .param("y", "37.523456").with(asUser(family.dad()))).andExpect(status().isOk());

        assertThat(output).doesNotContain("37.512345", "127.098765", "95.123456", "127.087654", "37.523456");
    }

    @Test
    void 위치기반서비스에_동의하지_않았거나_철회하면_내_주변은_403이고_기록하지_않는다() throws Exception {
        nearbyAs(family.son())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOCATION_CONSENT_REQUIRED"));
        agreeLocation(family.son());
        nearbyAs(family.son()).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/users/me/consents/LOCATION").with(asUser(family.son()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agreed\":false}"))
                .andExpect(status().isOk());
        nearbyAs(family.son())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOCATION_CONSENT_REQUIRED"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM location_access_logs WHERE user_id = ? "
                + "AND purpose = 'NEARBY'", Long.class, family.son())).isEqualTo(1);
    }

    @Test
    void 구성원이_아니면_목록_개수_내_주변_모두_403() throws Exception {
        long stranger = createUser();
        mockMvc.perform(get(LIST, family.groupId()).with(asUser(stranger))).andExpect(status().isForbidden());
        mockMvc.perform(get(LIST + "/regions", family.groupId()).with(asUser(stranger)))
                .andExpect(status().isForbidden());
        mockMvc.perform(nearby("37.5", "127.0", null).with(asUser(stranger))).andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM location_access_logs WHERE user_id = ?",
                Long.class, stranger)).isZero();
    }

    private ResultActions nearbyAs(long userId) throws Exception {
        return mockMvc.perform(nearby("37.5", "127.0", null).with(asUser(userId)));
    }

    /** 내 주변은 위치기반서비스 동의가 있어야 쓸 수 있다 */
    private void agreeLocation(long userId) throws Exception {
        mockMvc.perform(put("/api/v1/users/me/consents/LOCATION").with(asUser(userId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agreed\":true,\"docVersion\":\"1.0\"}"))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder nearby(String lat, String lng, String radiusKm) {
        var request = get(LIST + "/nearby", family.groupId()).param("lat", lat).param("lng", lng);
        return radiusKm == null ? request : request.param("radiusKm", radiusKm);
    }

    private void share(long userId, String name, String code, String address, double lat, double lng,
            String category) throws Exception {
        shareNew(mockMvc, kakao, family.groupId(), userId, doc(name, code, address, lat, lng), category);
    }

    private List<String> names(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request.with(asUser(family.mom()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].name");
    }
}
