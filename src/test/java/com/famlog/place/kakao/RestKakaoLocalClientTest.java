package com.famlog.place.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.place.kakao.KakaoLocalClient.SearchResult;

/** 카카오 로컬 키워드 검색 호출 (사양서 11.3 PLC-01): 키는 헤더로만, 실패는 상태 코드만 남긴다. */
@ExtendWith(OutputCaptureExtension.class)
class RestKakaoLocalClientTest {

    private static final String KEY = "test-rest-key-value";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void 키를_KakaoAK_헤더로_보내고_결과를_읽는다() {
        server.expect(requestTo(startsWith("https://dapi.kakao.com/v2/local/search/keyword.json")))
                .andExpect(header("Authorization", "KakaoAK " + KEY))
                .andExpect(queryParam("page", "2"))
                .andExpect(queryParam("size", "15"))
                .andExpect(queryParam("sort", "distance"))
                .andRespond(withSuccess("""
                        {"meta":{"is_end":false,"total_count":20},
                         "documents":[{"id":"26338954","place_name":"잠실 냉면","category_name":"음식점 > 한식 > 냉면",
                           "category_group_code":"FD6","phone":"02-1","address_name":"서울 송파구 잠실동 40-1",
                           "road_address_name":"서울 송파구 올림픽로 1","x":"127.1","y":"37.5",
                           "place_url":"http://place.map.kakao.com/26338954","distance":"120"}]}
                        """, MediaType.APPLICATION_JSON));

        SearchResult result = client(KEY).search("냉면", 127.1, 37.5, 2);

        server.verify();
        assertThat(result.end()).isFalse();
        assertThat(result.documents()).singleElement().satisfies(doc -> {
            assertThat(doc.id()).isEqualTo("26338954");
            assertThat(doc.placeName()).isEqualTo("잠실 냉면");
            assertThat(doc.categoryGroupCode()).isEqualTo("FD6");
            assertThat(doc.roadAddressName()).isEqualTo("서울 송파구 올림픽로 1");
            assertThat(doc.distance()).isEqualTo("120");
        });
    }

    @Test
    void 위치가_없으면_정확도_순이다() {
        server.expect(requestTo(startsWith("https://dapi.kakao.com/v2/local/search/keyword.json")))
                .andExpect(request -> assertThat(request.getURI().getQuery()).doesNotContain("x=", "sort="))
                .andRespond(withSuccess("{\"meta\":{\"is_end\":true},\"documents\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client(KEY).search("냉면", null, null, 1).end()).isTrue();
        server.verify();
    }

    @Test
    void 키가_없으면_부르지_않고_500() {
        assertThatThrownBy(() -> client(" ").search("냉면", null, null, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        server.verify();
    }

    @Test
    void 카카오가_거절하거나_실패하면_500이고_로그에_검색어_좌표_키가_없다(CapturedOutput output) {
        server.expect(requestTo(startsWith("https://dapi.kakao.com"))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(startsWith("https://dapi.kakao.com"))).andRespond(withServerError());

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client(KEY).search("비밀검색어", 127.123456, 37.654321, 1))
                    .isInstanceOf(BusinessException.class);
        }
        assertThat(output).contains("status=401", "status=500")
                .doesNotContain("비밀검색어", "127.123456", "37.654321", KEY);
    }

    private RestKakaoLocalClient client(String key) {
        return new RestKakaoLocalClient(builder.build(), key);
    }
}
