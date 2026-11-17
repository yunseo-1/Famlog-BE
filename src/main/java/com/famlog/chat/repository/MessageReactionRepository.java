package com.famlog.chat.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.chat.domain.MessageReaction;

public interface MessageReactionRepository extends JpaRepository<MessageReaction, MessageReaction.Key> {

    List<MessageReaction> findByMessageIdInOrderByCreatedAtAsc(Collection<Long> messageIds);

    @Modifying(flushAutomatically = true)
    @Query("delete from MessageReaction r where r.messageId = :messageId")
    void deleteByMessageId(@Param("messageId") Long messageId);
}
