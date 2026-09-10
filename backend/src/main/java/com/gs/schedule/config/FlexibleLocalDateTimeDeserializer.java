package com.gs.schedule.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/**
 * 宽松的 LocalDateTime 反序列化：
 * 接受 "2026-09-10T23:40:00Z"、"2026-09-10T23:40:00"、"2026-09-11 07:40:00+08:00" 等，
 * 带偏移量的输入一律换算为 UTC，保证跨时区提交语义明确。
 */
public class FlexibleLocalDateTimeDeserializer extends LocalDateTimeDeserializer {

    private static final DateTimeFormatter SPACE_FORMATTER =
            new DateTimeFormatterBuilder().parseCaseInsensitive()
                    .append(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .toFormatter();

    public FlexibleLocalDateTimeDeserializer() {
        super(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Override
    public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String text = parser.getText().trim();

        if (text.endsWith("Z") || text.contains("+") || text.matches(".*-\\d{2}:?\\d{2}$")) {
            return LocalDateTime.ofInstant(Instant.parse(
                    text.endsWith("Z") ? text : normalizeOffset(text)), ZoneOffset.UTC);
        }
        if (text.contains(" ")) {
            return LocalDateTime.parse(text, SPACE_FORMATTER);
        }
        return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /** "2026-09-11 07:40:00+08:00" -> "2026-09-11T07:40:00+08:00"。 */
    private String normalizeOffset(String text) {
        return text.replaceFirst(" ", "T");
    }
}
