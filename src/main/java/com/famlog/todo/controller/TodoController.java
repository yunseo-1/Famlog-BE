package com.famlog.todo.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.famlog.common.security.AuthUser;
import com.famlog.todo.domain.TodoKind;
import com.famlog.todo.dto.TodoDtos.CreateTodoRequest;
import com.famlog.todo.dto.TodoDtos.TodoListResponse;
import com.famlog.todo.dto.TodoDtos.TodoResponse;
import com.famlog.todo.dto.TodoDtos.UpdateTodoRequest;
import com.famlog.todo.service.TodoService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * 할 일 (사양서 4.3 TODO-01, 11.3). /groups/{groupId}/** 는 GroupPathAccessFilter 가 구성원 여부를 먼저 확인하고,
 * /todos/{id} 는 서비스가 할 일의 그룹으로 확인한다 (다른 그룹이면 404).
 */
@RestController
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;

    @Operation(summary = "할 일 등록", description = "담당자는 이 그룹 가족. kind 는 TASK 만 (SHOPPING 은 v1.1)")
    @PostMapping("/api/v1/groups/{groupId}/todos")
    @ResponseStatus(HttpStatus.CREATED)
    TodoResponse createTodo(AuthUser user, @PathVariable long groupId,
            @Valid @RequestBody CreateTodoRequest request) {
        return todoService.create(groupId, user.userId(), request);
    }

    @Operation(summary = "할 일 목록", description = "안 한 일(기한 이른 순, 기한 없음은 뒤) 다음에 완료한 일(최근 완료 순). "
            + "done 으로 한쪽만 볼 수 있다. 커서 페이징")
    @GetMapping("/api/v1/groups/{groupId}/todos")
    TodoListResponse listTodos(AuthUser user, @PathVariable long groupId,
            @RequestParam(required = false) TodoKind kind,
            @RequestParam(required = false) Long assigneeId,
            @Parameter(description = "true 완료한 일만, false 안 한 일만, 없으면 둘 다")
            @RequestParam(required = false) Boolean done,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer size) {
        return todoService.list(groupId, user.userId(), kind, assigneeId, done, cursor, size);
    }

    @Operation(summary = "할 일 하나", description = "다른 그룹 할 일이면 404")
    @GetMapping("/api/v1/todos/{todoId}")
    TodoResponse getTodo(AuthUser user, @PathVariable long todoId) {
        return todoService.get(todoId, user.userId());
    }

    @Operation(summary = "할 일 수정·완료", description = "바꿀 값과 version 을 보낸다. 제목·담당자·기한은 등록한 사람이나 "
            + "ADMIN 이상(403), 완료(done)는 누구나. announce=true 로 완료하면 채팅방에 한 줄 알림")
    @PatchMapping("/api/v1/todos/{todoId}")
    TodoResponse updateTodo(AuthUser user, @PathVariable long todoId,
            @Valid @RequestBody UpdateTodoRequest request) {
        return todoService.update(todoId, user.userId(), request);
    }

    @Operation(summary = "할 일 삭제", description = "등록한 사람이나 ADMIN 이상")
    @DeleteMapping("/api/v1/todos/{todoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteTodo(AuthUser user, @PathVariable long todoId) {
        todoService.delete(todoId, user.userId());
    }
}
