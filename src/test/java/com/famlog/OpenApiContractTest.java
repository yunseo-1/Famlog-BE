package com.famlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.famlog.support.IntegrationTest;

/**
 * API 계약 파일 docs/api/openapi.yaml (사양서 11.1).
 * <ul>
 * <li>평소(./gradlew test, CI): 저장된 파일이 지금 코드로 만든 계약과 같은지 확인한다. API를 바꾸고 갱신을 잊으면 실패한다</li>
 * <li>./gradlew updateOpenApi: 파일을 다시 만든다 (famlog.openapi.write=true)</li>
 * </ul>
 * 문서 레포로 옮기기: sh scripts/export-openapi.sh &lt;문서 레포 경로&gt;
 */
class OpenApiContractTest extends IntegrationTest {

    static final Path CONTRACT = Path.of(System.getProperty("famlog.repoRoot", "."), "docs", "api", "openapi.yaml");

    @Test
    void 저장된_API_계약이_지금_코드와_같다() throws Exception {
        String generated = normalize(mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        if (Boolean.getBoolean("famlog.openapi.write")) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, generated, StandardCharsets.UTF_8);
            return;
        }

        assertThat(Files.exists(CONTRACT))
                .as("docs/api/openapi.yaml 이 없습니다. ./gradlew updateOpenApi 로 만드세요")
                .isTrue();
        assertThat(Files.readString(CONTRACT, StandardCharsets.UTF_8))
                .as("API가 바뀌었는데 docs/api/openapi.yaml 이 갱신되지 않았습니다. ./gradlew updateOpenApi 를 실행하고 커밋하세요")
                .isEqualTo(generated);
    }

    @Test
    void 계약에는_테스트용_API와_내부_엔드포인트가_없다() throws Exception {
        String generated = mockMvc.perform(get("/v3/api-docs.yaml")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(generated)
                .contains("/api/v1/auth/{provider}/login", "/api/v1/groups/join", "ErrorResponse", "bearerAuth")
                .doesNotContain("/test/", "/api/v1/_test/", "/actuator", "localhost")
                // 토큰에서 채우는 AuthUser 가 요청 파라미터로 새어 나오면 안 된다
                .doesNotContain("AuthUser", "name: user\n");
    }

    private static String normalize(String yaml) {
        String unix = yaml.replace("\r\n", "\n").stripTrailing();
        return unix + "\n";
    }
}
