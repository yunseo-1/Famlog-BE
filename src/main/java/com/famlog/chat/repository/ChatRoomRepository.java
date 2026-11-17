package com.famlog.chat.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.chat.domain.ChatRoom;

import jakarta.persistence.LockModeType;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByGroupId(Long groupId);

    List<ChatRoom> findByGroupIdIn(Collection<Long> groupIds);

    /** seq 발급용. 같은 방의 전송은 이 잠금으로 한 줄로 선다 (다른 방은 막지 않는다) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChatRoom r where r.groupId = :groupId")
    Optional<ChatRoom> findByGroupIdForUpdate(@Param("groupId") Long groupId);
}
