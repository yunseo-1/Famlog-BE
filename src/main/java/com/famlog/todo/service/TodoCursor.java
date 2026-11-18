package com.famlog.todo.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.todo.domain.Todo;

/**
 * 할 일 목록 커서 (사양서 11.1 커서 페이징). 앱에는 뜻 없는 문자열로 보인다.
 * 안 한 일 구간이면 (기한, id), 완료한 일 구간이면 (완료 시각, id) 다음부터 이어 읽는다.
 *
 * @param open    안 한 일 구간인지
 * @param dueDate 안 한 일의 기한 (기한 없음은 {@link #NO_DUE})
 * @param doneAt  완료한 일의 완료 시각
 */
record TodoCursor(boolean open, LocalDate dueDate, Instant doneAt, long id) {

    /** 기한 없는 할 일을 맨 뒤로 보내는 값 */
    static final LocalDate NO_DUE = LocalDate.of(9999, 12, 31);
    static final TodoCursor OPEN_START = new TodoCursor(true, LocalDate.of(1, 1, 1), null, 0);
    static final TodoCursor DONE_START = new TodoCursor(false, null, Instant.parse("9999-12-31T00:00:00Z"),
            Long.MAX_VALUE);

    static TodoCursor after(Todo todo) {
        return todo.isDone()
                ? new TodoCursor(false, null, todo.getDoneAt(), todo.getId())
                : new TodoCursor(true, todo.getDueDate() == null ? NO_DUE : todo.getDueDate(), null, todo.getId());
    }

    String encode() {
        String raw = open ? "O." + dueDate.toEpochDay() + "." + id
                : "D." + ChronoUnit.MICROS.between(Instant.EPOCH, doneAt) + "." + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    static TodoCursor decode(String cursor) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII).split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException();
            }
            long key = Long.parseLong(parts[1]);
            long id = Long.parseLong(parts[2]);
            return switch (parts[0]) {
                case "O" -> new TodoCursor(true, LocalDate.ofEpochDay(key), null, id);
                case "D" -> new TodoCursor(false, null, Instant.EPOCH.plus(key, ChronoUnit.MICROS), id);
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "목록 위치(cursor)가 올바르지 않아요.");
        }
    }
}
