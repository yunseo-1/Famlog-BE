package com.famlog.common.time;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * API 응답의 시각을 Asia/Seoul 오프셋 ISO-8601 문자열로 직렬화한다 (사양서 11.1: 2026-11-14T11:00:00+09:00).
 * Spring Boot가 JacksonModule 빈을 JsonMapper에 자동 등록한다. 요청 본문의 오프셋 포함 시각은 기본 역직렬화를 쓴다.
 */
@Component
public class FamlogTimeModule extends SimpleModule {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    public FamlogTimeModule() {
        super("FamlogTimeModule");
        addSerializer(Instant.class, new DisplayZoneSerializer<>(Instant.class));
        addSerializer(OffsetDateTime.class, new DisplayZoneSerializer<>(OffsetDateTime.class));
        addSerializer(ZonedDateTime.class, new DisplayZoneSerializer<>(ZonedDateTime.class));
    }

    private static final class DisplayZoneSerializer<T> extends StdSerializer<T> {

        DisplayZoneSerializer(Class<T> type) {
            super(type);
        }

        @Override
        public void serialize(T value, JsonGenerator generator, SerializationContext context) {
            Instant instant = switch (value) {
                case Instant i -> i;
                case OffsetDateTime o -> o.toInstant();
                case ZonedDateTime z -> z.toInstant();
                default -> throw new IllegalArgumentException("지원하지 않는 시각 타입: " + value.getClass());
            };
            generator.writeString(FORMATTER.format(instant.atZone(FamlogTime.DISPLAY_ZONE).toOffsetDateTime()));
        }
    }
}
