package com.famlog.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;

/** dev·prod JSON 구조화 로그의 문자열 값이 마스킹되는지 확인한다. */
class MaskingJsonMembersCustomizerTest {

    @Test
    void JSON_로그의_모든_문자열_값을_마스킹한다() {
        JsonWriter<Map<String, String>> writer = JsonWriter.of(members -> {
            members.add("message", event -> event.get("message"));
            members.add("traceId", event -> event.get("traceId"));
            new MaskingJsonMembersCustomizer().customize(cast(members));
        });

        String json = writer.writeToString(Map.of(
                "message", "로그인 refreshToken=r4nd0m-refresh-value body=이번 토요일 할머니 댁",
                "traceId", "abc123"));

        assertThat(json)
                .doesNotContain("r4nd0m-refresh-value")
                .doesNotContain("할머니")
                .contains("\"traceId\":\"abc123\"");
    }

    @SuppressWarnings("unchecked")
    private static JsonWriter.Members<Object> cast(JsonWriter.Members<?> members) {
        return (JsonWriter.Members<Object>) members;
    }
}
