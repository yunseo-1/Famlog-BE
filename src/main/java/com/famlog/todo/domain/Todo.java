package com.famlog.todo.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.famlog.common.domain.BaseTimeEntity;
import com.famlog.group.domain.GroupRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 할 일 (사양서 4.3 TODO-01, 10.2 todos). 장보기(SHOPPING)는 v1.1. */
@Getter
@Entity
@Table(name = "todos")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Todo extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TodoKind kind;

    @Column(nullable = false)
    private String title;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "done_by")
    private Long doneBy;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Version
    @Column(nullable = false)
    private long version;

    public Todo(long groupId, long createdBy, TodoKind kind, String title, Long assigneeId, LocalDate dueDate) {
        this.groupId = groupId;
        this.createdBy = createdBy;
        this.kind = kind;
        this.title = title;
        this.assigneeId = assigneeId;
        this.dueDate = dueDate;
    }

    /** 사양서 2.2: 남이 등록한 할 일은 ADMIN 이상만 고치거나 지운다 (완료 처리는 누구나) */
    public boolean isEditableBy(long userId, GroupRole role) {
        return createdBy == userId || role.atLeast(GroupRole.ADMIN);
    }

    public boolean isDone() {
        return doneAt != null;
    }

    public void rename(String title) {
        this.title = title;
    }

    public void assign(Long assigneeId) {
        this.assigneeId = assigneeId;
    }

    public void dueOn(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    /** @return 이번에 완료됐으면 true (이미 완료였으면 false) */
    public boolean complete(long userId, Instant now) {
        if (isDone()) {
            return false;
        }
        // DB 정밀도(마이크로초)에 맞춰 둔다: 목록 커서가 저장된 값과 같아야 한다
        this.doneAt = now.truncatedTo(ChronoUnit.MICROS);
        this.doneBy = userId;
        return true;
    }

    public void reopen() {
        this.doneAt = null;
        this.doneBy = null;
    }
}
