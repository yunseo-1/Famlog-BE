package com.famlog.common.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.famlog.common.error.ErrorCode;
import com.famlog.common.error.ErrorResponse;
import com.famlog.common.security.AuthUser;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/**
 * API 계약 (사양서 11.1). docs/api/openapi.yaml 로 내보내 문서 레포에 커밋하고, 앱은 그 파일을 기준으로 구현한다.
 * 내보내기: ./gradlew updateOpenApi → sh scripts/export-openapi.sh <문서 레포 경로>
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static final String BEARER = "bearerAuth";
    static final String ERROR_RESPONSE = "ErrorResponse";

    static {
        // AuthUser 는 요청 값이 아니라 Access 토큰에서 채우는 파라미터다. 계약에 쿼리 파라미터로 나오지 않게 한다
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthUser.class);
    }

    /** 토큰 없이 부르는 API (SecurityConfig.PUBLIC_PATHS 중 문서에 나오는 것) */
    private static final List<String> PUBLIC_PREFIXES = List.of("/api/v1/auth/", "/api/v1/app/config");

    @Bean
    OpenAPI famlogOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FamLog API")
                        .description("""
                                FamLog(팸로그) 가족 앱 서버 API. 기준: 통합 사양서 v2.0 11장.
                                - 시각은 +09:00 ISO-8601, 날짜만은 YYYY-MM-DD
                                - 실패 응답은 모두 ErrorResponse {code, message, traceId}. 앱은 code로 분기한다
                                - 401: AUTH_TOKEN_EXPIRED 면 /auth/refresh 후 재시도, AUTH_INVALID 면 로그인 화면
                                """)
                        .version("v1"))
                // 환경마다 주소가 달라 고정하지 않는다 (local: http://localhost:8080, 에뮬레이터: http://10.0.2.2:8080)
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Access 토큰 (30분)")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /** 공통 에러 응답 스키마와 에러 코드 목록을 넣고, 공개 API는 인증 없음으로 표시한다 */
    @Bean
    OpenApiCustomizer famlogContractCustomizer() {
        return openApi -> {
            Map<String, Schema> schemas = ModelConverters.getInstance()
                    .readAllAsResolvedSchema(ErrorResponse.class).referencedSchemas;
            schemas.forEach((name, schema) -> openApi.getComponents().addSchemas(name, schema));
            Schema<?> errorResponse = openApi.getComponents().getSchemas().get(ERROR_RESPONSE);
            @SuppressWarnings("unchecked")
            Schema<String> code = (Schema<String>) errorResponse.getProperties().get("code");
            code.setEnum(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
            code.setDescription("에러 코드 (사양서 11.2)");

            ApiResponse defaultError = new ApiResponse()
                    .description("실패. code 로 원인을 구분한다")
                    .content(new Content().addMediaType("application/json",
                            new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR_RESPONSE))));

            openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                operation.getResponses().putIfAbsent("default", defaultError);
                if (PUBLIC_PREFIXES.stream().anyMatch(path::startsWith)) {
                    markPublic(operation);
                }
            }));
        };
    }

    private static void markPublic(Operation operation) {
        operation.setSecurity(List.of());
    }
}
