package com.famlog.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.famlog.place.kakao.KakaoLocalClient.Document;
import com.jayway.jsonpath.JsonPath;

/** 장소 API 테스트 도우미: 가짜 카카오 장소 만들기, 검색 후 공유. */
public final class PlaceApi {

    private PlaceApi() {
    }

    /** 카카오 장소 하나. id 는 테스트끼리 겹치지 않게 새로 만든다. address 는 지번 주소 */
    public static Document doc(String name, String groupCode, String address, double lat, double lng) {
        return new Document(newKakaoId(), name, "음식점 > 한식 > " + name, groupCode, "02-123-4567", address,
                null, Double.toString(lng), Double.toString(lat), "http://place.map.kakao.com/1", null);
    }

    public static String newKakaoId() {
        return "k" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000_000L);
    }

    public static String newQuery() {
        return "검색" + UUID.randomUUID();
    }

    public static ResultActions search(MockMvc mockMvc, long userId, String query) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/places/search").param("query", query)
                .with(GroupApi.as(userId)));
    }

    /** 공유 요청. body 예: {"kakaoPlaceId":"k1","category":"RESTAURANT"} */
    public static ResultActions share(MockMvc mockMvc, long groupId, long userId, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/groups/{g}/places", groupId)
                .with(GroupApi.as(userId)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 가짜 카카오에 등록하고 검색한 뒤 공유한다 (채팅 카드 없이). 장소 id 를 돌려준다 */
    public static long shareNew(MockMvc mockMvc, FakeKakaoLocalClient kakao, long groupId, long userId,
            Document doc, String category) throws Exception {
        String query = newQuery();
        kakao.register(query, true, doc);
        search(mockMvc, userId, query).andExpect(status().isOk());
        String response = share(mockMvc, groupId, userId, """
                {"kakaoPlaceId":"%s","category":"%s","shareToChat":false}""".formatted(doc.id(), category))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
}
