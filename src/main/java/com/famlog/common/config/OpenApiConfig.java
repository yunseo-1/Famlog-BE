package com.famlog.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * API 계약 문서 정보 (사양서 11.1). 생성된 문서는 문서 레포의 docs/api/openapi.yaml 로 내보내 커밋한다.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI famlogOpenApi() {
        return new OpenAPI().info(new Info()
                .title("FamLog API")
                .description("FamLog(팸로그) 가족 앱 서버 API. 기준: 통합 사양서 v2.0 11장")
                .version("v1"));
    }
}
