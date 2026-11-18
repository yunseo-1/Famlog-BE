package com.famlog.todo.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.todo.domain.Todo;
import com.famlog.todo.domain.TodoKind;

public interface TodoRepository extends JpaRepository<Todo, Long> {

    /** 안 한 일: 기한이 이른 순(기한 없음은 맨 뒤 = noDue), 그다음 id 순. (afterDue, afterId) 다음부터 */
    @Query("""
            select t from Todo t
            where t.groupId = :groupId and t.kind = :kind and t.doneAt is null
              and (:assigneeId is null or t.assigneeId = :assigneeId)
              and (coalesce(t.dueDate, :noDue) > :afterDue
                   or (coalesce(t.dueDate, :noDue) = :afterDue and t.id > :afterId))
            order by coalesce(t.dueDate, :noDue) asc, t.id asc
            """)
    List<Todo> findOpen(@Param("groupId") long groupId, @Param("kind") TodoKind kind,
            @Param("assigneeId") Long assigneeId, @Param("noDue") LocalDate noDue,
            @Param("afterDue") LocalDate afterDue, @Param("afterId") long afterId, Limit limit);

    /** 완료한 일: 완료 시각이 늦은 순, 그다음 id 역순. (beforeAt, beforeId) 다음부터 */
    @Query("""
            select t from Todo t
            where t.groupId = :groupId and t.kind = :kind and t.doneAt is not null
              and (:assigneeId is null or t.assigneeId = :assigneeId)
              and (t.doneAt < :beforeAt or (t.doneAt = :beforeAt and t.id < :beforeId))
            order by t.doneAt desc, t.id desc
            """)
    List<Todo> findDone(@Param("groupId") long groupId, @Param("kind") TodoKind kind,
            @Param("assigneeId") Long assigneeId, @Param("beforeAt") Instant beforeAt,
            @Param("beforeId") long beforeId, Limit limit);
}
