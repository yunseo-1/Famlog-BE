package com.famlog.common.logging;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * JSON 구조화 로그(dev·prod)의 문자열 값 전체를 마스킹한다.
 * application.yml 의 logging.structured.json.customizer 로 등록한다.
 */
public class MaskingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogMasker::mask));
    }
}
