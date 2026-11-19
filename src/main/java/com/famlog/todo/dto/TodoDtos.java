package com.famlog.todo.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.famlog.common.source.SourceRef;
import com.famlog.todo.domain.Todo;
import com.famlog.todo.domain.TodoKind;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 할 일 API 요청·응답 (사양서 11.3 TODO-01). */
public final class TodoDtos {

    private TodoDtos() {
    }

    public record CreateTodoRequest(
            @Schema(example = "우유 사 오기") @NotBlank @Size(max = 100) String title,
            @Schema(description = "담당자 userId (이 그룹 가족). 없으면 담당자 없음") Long assigneeId,
            @Schema(description = "기한 (서울 날짜)", example = "2026-11-21") LocalDate dueDate,
            @Schema(description = "TASK 만 (SHOPPING 은 v1.1). 없으면 TASK") TodoKind kind) {
    }

    /**
     * 바꿀 값만 보낸다. assigneeId·dueDate 는 null 을 보내면 지운다 (키를 빼면 그대로).
     * 제목·담당자·기한은 등록한 사람이나 ADMIN 이상만, 완료(done)는 구성원 누구나 바꾼다.
     * <p>
     * 레코드가 아닌 이유: 레코드는 생성자로 만들어 빠진 키도 Optional.empty() 가 돼 "null 을 보냄"과 구분되지 않는다.
     * 필드로 받으면 보낸 키만 채워지고(null 이면 Optional.empty()), 빠진 키는 null 로 남는다.
     */
    @Getter
    @NoArgsConstructor
    public static final class UpdateTodoRequest {

        @Schema(description = "조회한 할 일의 version (다르면 409 VERSION_CONFLICT)")
        @NotNull
        private Long version;

        @Size(max = 100)
        private String title;

        @Schema(implementation = Long.class, nullable = true)
        private Optional<Long> assigneeId;

        @Schema(implementation = LocalDate.class, nullable = true)
        private Optional<LocalDate> dueDate;

        @Schema(description = "true 완료, false 완료 취소")
        private Boolean done;

        @Schema(description = "완료하면서 채팅방에 한 줄 알림 남기기")
        private Boolean announce;

        public boolean changesContent() {
            return title != null || assigneeId != null || dueDate != null;
        }
    }

    public record TodoResponse(long id, long groupId, TodoKind kind, String title, Long assigneeId,
            LocalDate dueDate, boolean done, Instant doneAt, Long doneBy, long createdBy, long version,
            Instant createdAt, Instant updatedAt, SourceRef source) {

        public static TodoResponse from(Todo todo, SourceRef source) {
            return new TodoResponse(todo.getId(), todo.getGroupId(), todo.getKind(), todo.getTitle(),
                    todo.getAssigneeId(), todo.getDueDate(), todo.isDone(), todo.getDoneAt(), todo.getDoneBy(),
                    todo.getCreatedBy(), todo.getVersion(), todo.getCreatedAt(), todo.getUpdatedAt(), source);
        }
    }

    /** 안 한 일(기한 이른 순, 기한 없음은 뒤) 다음에 완료한 일(최근 완료 순) */
    public record TodoListResponse(List<TodoResponse> items,
            @Schema(description = "다음 페이지 커서. 없으면 끝") String nextCursor) {
    }
}
