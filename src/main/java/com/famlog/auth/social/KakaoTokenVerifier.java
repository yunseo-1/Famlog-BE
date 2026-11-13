package com.famlog.auth.social;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.user.domain.SocialProvider;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.extern.slf4j.Slf4j;

/**
 * 카카오 액세스 토큰 검증: GET /v1/user/access_token_info 로 토큰이 유효한지, 우리 앱(app_id)에서 발급됐는지 확인하고
 * 회원 번호(id)를 얻는다. app_id를 확인하지 않으면 다른 앱용 토큰으로 우리 서비스에 로그인할 수 있다.
 */
@Slf4j
public class KakaoTokenVerifier implements SocialTokenVerifier {

    private final RestClient restClient;
    private final Long appId;

    public KakaoTokenVerifier(RestClient restClient, Long appId) {
        this.restClient = restClient;
        this.appId = appId;
    }

    @Override
    public SocialProvider provider() {
        return SocialProvider.KAKAO;
    }

    @Override
    public SocialIdentity verify(String providerToken) {
        if (appId == null) {
            log.warn("카카오 앱 ID(famlog.social.kakao.app-id)가 설정되지 않아 카카오 로그인을 처리할 수 없습니다");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        TokenInfo info;
        try {
            info = restClient.get()
                    .uri("/v1/user/access_token_info")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + providerToken)
                    .retrieve()
                    .body(TokenInfo.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new BusinessException(ErrorCode.AUTH_INVALID);
            }
            log.warn("카카오 토큰 확인 실패: status={}", e.getStatusCode().value());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        } catch (ResourceAccessException e) {
            log.warn("카카오 서버에 연결하지 못했습니다: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (info == null || info.id() == null || !appId.equals(info.appId())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID);
        }
        return new SocialIdentity(SocialProvider.KAKAO, Long.toString(info.id()));
    }

    record TokenInfo(Long id, @JsonProperty("app_id") Long appId) {
    }
}
