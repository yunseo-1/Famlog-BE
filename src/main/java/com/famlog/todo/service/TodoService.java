package com.famlog.todo.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.common.source.ItemSources;
import com.famlog.common.source.SourceRef;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.domain.GroupRole;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.group.service.GroupAccessChecker;
import com.famlog.todo.domain.Todo;
import com.famlog.todo.domain.TodoKind;
import com.famlog.todo.dto.TodoDtos.CreateTodoRequest;
import com.famlog.todo.dto.TodoDtos.TodoListResponse;
import com.famlog.todo.dto.TodoDtos.TodoResponse;
import com.famlog.todo.dto.TodoDtos.UpdateTodoRequest;
import com.famlog.todo.event.TodoCompleted;
import com.famlog.todo.repository.TodoRepository;

import lombok.RequiredArgsConstructor;

/**
 * 할 일 (사양서 4.3 TODO-01, 2.2 권한, 9.3 인가).
 * <ul>
 * <li>/todos/{id}: 다른 그룹(또는 나간 그룹) 할 일이면 404</li>
 * <li>제목·담당자·기한 수정과 삭제는 등록한 사람이나 ADMIN 이상, 완료·완료 취소는 구성원 누구나 (장 봐 온 사람이 체크)</li>
 * <li>완료하면서 announce=true 면 채팅방에 한 줄 알림 ({@link TodoCompleted})</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TodoService {

    static final int DEFAULT_PAGE_SIZE = 50;
    private static final LocalDate MIN_DUE = LocalDate.of(2000, 1, 1);
    private static final LocalDate MAX_DUE = LocalDate.of(2100, 12, 31);

    private final TodoRepository todoRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupAccessChecker accessChecker;
    private final ItemSources itemSources;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public TodoResponse create(long groupId, long userId, CreateTodoRequest request) {
        accessChecker.requireMember(groupId, userId);
        TodoKind kind = taskOnly(request.kind());
        if (request.assigneeId() != null) {
            requireActiveMember(groupId, request.assigneeId());
        }
        Todo todo = new Todo(groupId, userId, kind, request.title().strip(), request.assigneeId(),
                validDue(request.dueDate()));
        return TodoResponse.from(todoRepository.saveAndFlush(todo), null);
    }

    @Transactional(readOnly = true)
    public TodoListResponse list(long groupId, long userId, TodoKind kind, Long assigneeId, Boolean done,
            String cursor, Integer size) {
        accessChecker.requireMember(groupId, userId);
        TodoKind wantedKind = taskOnly(kind);
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        TodoCursor after = cursor == null || cursor.isBlank() ? null : TodoCursor.decode(cursor);
        if (after != null && ((after.open() && Boolean.TRUE.equals(done))
                || (!after.open() && Boolean.FALSE.equals(done)))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "목록 위치(cursor)가 올바르지 않아요.");
        }

        int want = pageSize + 1;
        List<Todo> found = new ArrayList<>();
        if (!Boolean.TRUE.equals(done) && (after == null || after.open())) {
            TodoCursor from = after == null ? TodoCursor.OPEN_START : after;
            found.addAll(todoRepository.findOpen(groupId, wantedKind, assigneeId, TodoCursor.NO_DUE, from.dueDate(),
                    from.id(), Limit.of(want)));
        }
        if (!Boolean.FALSE.equals(done) && found.size() < want) {
            TodoCursor from = after == null || after.open() ? TodoCursor.DONE_START : after;
            found.addAll(todoRepository.findDone(groupId, wantedKind, assigneeId, from.doneAt(), from.id(),
                    Limit.of(want - found.size())));
        }
        boolean hasNext = found.size() > pageSize;
        List<Todo> page = hasNext ? found.subList(0, pageSize) : found;
        Map<Long, SourceRef> sources = itemSources.find(ItemSources.TODO, page.stream().map(Todo::getId).toList());
        return new TodoListResponse(page.stream().map(todo -> TodoResponse.from(todo, sources.get(todo.getId())))
                .toList(),
                hasNext ? TodoCursor.after(page.getLast()).encode() : null);
    }

    @Transactional(readOnly = true)
    public TodoResponse get(long todoId, long userId) {
        Todo todo = find(todoId);
        accessChecker.requireMemberOrNotFound(todo.getGroupId(), userId);
        return TodoResponse.from(todo, itemSources.find(ItemSources.TODO, todoId));
    }

    @Transactional
    public TodoResponse update(long todoId, long userId, UpdateTodoRequest request) {
        Todo todo = find(todoId);
        GroupRole role = accessChecker.requireMemberOrNotFound(todo.getGroupId(), userId);
        if (request.changesContent() && !todo.isEditableBy(userId, role)) {
            throw notEditable();
        }
        if (request.getVersion() != todo.getVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        if (request.getTitle() != null) {
            if (request.getTitle().isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "제목을 입력해 주세요.");
            }
            todo.rename(request.getTitle().strip());
        }
        if (request.getAssigneeId() != null) {
            Long assigneeId = request.getAssigneeId().orElse(null);
            if (assigneeId != null && !assigneeId.equals(todo.getAssigneeId())) {
                requireActiveMember(todo.getGroupId(), assigneeId);
            }
            todo.assign(assigneeId);
        }
        if (request.getDueDate() != null) {
            todo.dueOn(validDue(request.getDueDate().orElse(null)));
        }
        if (Boolean.TRUE.equals(request.getDone())) {
            if (todo.complete(userId, Instant.now(clock)) && Boolean.TRUE.equals(request.getAnnounce())) {
                events.publishEvent(new TodoCompleted(todo.getGroupId(), todo.getId(), userId, todo.getTitle()));
            }
        } else if (Boolean.FALSE.equals(request.getDone())) {
            todo.reopen();
        }
        return TodoResponse.from(todoRepository.saveAndFlush(todo), itemSources.find(ItemSources.TODO, todoId));
    }

    @Transactional
    public void delete(long todoId, long userId) {
        Todo todo = find(todoId);
        GroupRole role = accessChecker.requireMemberOrNotFound(todo.getGroupId(), userId);
        if (!todo.isEditableBy(userId, role)) {
            throw notEditable();
        }
        todoRepository.delete(todo);
        itemSources.removeAll(ItemSources.TODO, todoId);
    }

    private Todo find(long todoId) {
        return todoRepository.findById(todoId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private void requireActiveMember(long groupId, long userId) {
        memberRepository.findByGroupIdAndUserId(groupId, userId)
                .filter(GroupMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "담당자는 이 그룹의 가족이어야 해요."));
    }

    private static TodoKind taskOnly(TodoKind kind) {
        if (kind != null && kind != TodoKind.TASK) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "장보기 목록은 아직 지원하지 않아요.");
        }
        return TodoKind.TASK;
    }

    private static LocalDate validDue(LocalDate dueDate) {
        if (dueDate != null && (dueDate.isBefore(MIN_DUE) || dueDate.isAfter(MAX_DUE))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "기한 날짜를 다시 확인해 주세요.");
        }
        return dueDate;
    }

    private static BusinessException notEditable() {
        return new BusinessException(ErrorCode.GROUP_ROLE_REQUIRED, "내가 등록한 할 일만 고치거나 지울 수 있어요.");
    }
}
