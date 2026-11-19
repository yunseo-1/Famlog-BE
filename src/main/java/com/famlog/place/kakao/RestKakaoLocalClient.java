package com.famlog.place.kakao;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.extern.slf4j.Slf4j;

/** 카카오 로컬 API 호출. 실패하면 상태 코드만 남긴다 (검색어·좌표·키는 남기지 않는다). */
@Slf4j
public class RestKakaoLocalClient implements KakaoLocalClient {

    private final RestClient restClient;
    private final String restApiKey;

    public RestKakaoLocalClient(RestClient restClient, String restApiKey) {
        this.restClient = restClient;
        this.restApiKey = restApiKey;
    }

    @Override
    public SearchResult search(String query, Double x, Double y, int page) {
        if (restApiKey == null || restApiKey.isBlank()) {
            log.warn("카카오 REST API 키(famlog.place.kakao-rest-api-key)가 설정되지 않아 장소를 검색할 수 없습니다");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        Response response;
        try {
            response = restClient.get()
                    .uri(builder -> {
                        builder.path("/v2/local/search/keyword.json")
                                .queryParam("query", query)
                                .queryParam("page", page)
                                .queryParam("size", PAGE_SIZE);
                        if (x != null && y != null) {
                            builder.queryParam("x", x).queryParam("y", y).queryParam("sort", "distance");
                        }
                        return builder.build();
                    })
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                    .retrieve()
                    .body(Response.class);
        } catch (RestClientResponseException e) {
            log.warn("카카오 장소 검색 실패: status={}", e.getStatusCode().value());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        } catch (ResourceAccessException e) {
            log.warn("카카오 서버에 연결하지 못했습니다: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (response == null || response.documents() == null) {
            return new SearchResult(List.of(), true);
        }
        return new SearchResult(response.documents(), response.meta() == null || response.meta().end());
    }

    record Response(Meta meta, List<Document> documents) {
    }

    record Meta(@JsonProperty("is_end") boolean end) {
    }
}
