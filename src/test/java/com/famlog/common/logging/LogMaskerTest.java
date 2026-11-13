package com.famlog.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LogMaskerTest {

    private static final String JWT =
            "eyJhbGciOiJSUzI1NiIsImtpZCI6ImsxIn0.eyJzdWIiOiIxMjMiLCJleHAiOjE3OTk5OTk5OTl9.c2lnbmF0dXJlLXZhbHVl";

    @Test
    void Bearer_토큰을_가린다() {
        String masked = LogMasker.mask("Authorization 헤더: Bearer abc.def-ghi_123 로 요청");

        assertThat(masked).doesNotContain("abc.def-ghi_123").contains("Bearer [MASKED]");
    }

    @Test
    void 문장_속_JWT를_가린다() {
        assertThat(LogMasker.mask("발급된 토큰 " + JWT + " 저장")).doesNotContain(JWT).contains("[MASKED]");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "refreshToken=r4nd0m-refresh-value",
            "refresh_token: r4nd0m-refresh-value",
            "{\"refreshToken\":\"r4nd0m-refresh-value\"}",
            "accessToken=r4nd0m-refresh-value",
            "fcmToken=r4nd0m-refresh-value",
            "idToken=r4nd0m-refresh-value",
            "token=r4nd0m-refresh-value",
            "password=r4nd0m-refresh-value",
            "secret: r4nd0m-refresh-value",
    })
    void 토큰_비밀번호_비밀_필드_값을_가린다(String text) {
        assertThat(LogMasker.mask(text)).doesNotContain("r4nd0m-refresh-value").contains("[MASKED]");
    }

    @Test
    void 메시지_본문을_가린다_JSON과_toString_형식_모두() {
        String json = LogMasker.mask("{\"clientMessageId\":\"c1\",\"body\":\"이번 토요일 11시에 할머니 댁 가자\",\"type\":\"TEXT\"}");
        String record = LogMasker.mask("SendMessage[clientMessageId=c1, body=이번 토요일 11시에 할머니 댁 가자, type=TEXT]");

        assertThat(json).doesNotContain("할머니").contains("\"body\":\"[MASKED]\"").contains("\"type\":\"TEXT\"");
        assertThat(record).doesNotContain("할머니").contains("body=[MASKED]").contains("type=TEXT");
    }

    @Test
    void 따옴표가_이스케이프된_본문도_끝까지_가린다() {
        String masked = LogMasker.mask("{\"body\":\"엄마가 \\\"좋아\\\" 했어\",\"seq\":3}");

        assertThat(masked).doesNotContain("엄마").doesNotContain("좋아").contains("\"seq\":3");
    }

    @Test
    void 출생연도월을_가린다() {
        assertThat(LogMasker.mask("연령 확인 요청 birthYm=201205")).doesNotContain("201205");
        assertThat(LogMasker.mask("{\"birth_ym\":\"201205\"}")).doesNotContain("201205");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "내 주변 검색 lat=37.5133, lng=127.1001|37.5133|127.1001",
            "{\"latitude\":37.5133,\"longitude\":127.1001}|37.5133|127.1001",
    })
    void 좌표를_가린다(String text, String lat, String lng) {
        assertThat(LogMasker.mask(text)).doesNotContain(lat).doesNotContain(lng);
    }

    @Test
    void 민감하지_않은_문장은_그대로_둔다() {
        String text = "그룹 12 메시지 seq=345 저장 완료 (traceId=abc123, size=20)";

        assertThat(LogMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void 단어_일부로_들어간_키는_가리지_않는다() {
        String text = "nobody=1 tokens_count=3 latency=120ms";

        assertThat(LogMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void null과_빈_문자열은_그대로_반환한다() {
        assertThat(LogMasker.mask(null)).isNull();
        assertThat(LogMasker.mask("")).isEmpty();
    }
}
