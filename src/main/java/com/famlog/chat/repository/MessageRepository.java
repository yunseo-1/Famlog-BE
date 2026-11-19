package com.famlog.chat.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.chat.domain.Message;

public interface MessageRepository extends JpaRepository<Message, Long> {

    Optional<Message> findBySenderIdAndClientMessageId(Long senderId, UUID clientMessageId);

    List<Message> findByIdIn(Collection<Long> ids);

    /** seq가 afterSeq보다 큰 메시지를 오래된 순으로 (재접속 동기화) */
    List<Message> findByRoomIdAndSeqGreaterThanOrderBySeqAsc(Long roomId, long afterSeq, Limit limit);

    /** seq가 (fromSeq, beforeSeq) 사이인 메시지를 최신 순으로 (위로 스크롤) */
    List<Message> findByRoomIdAndSeqGreaterThanAndSeqLessThanOrderBySeqDesc(Long roomId, long fromSeq,
            long beforeSeq, Limit limit);

    /** 채팅 탭: 방의 마지막 메시지 (볼 수 있는 범위 안에서) */
    Optional<Message> findFirstByRoomIdAndSeqGreaterThanOrderBySeqDesc(Long roomId, long fromSeq);

    /** 채팅 탭: 안 읽은 메시지 수. 내가 보낸 것·시스템 메시지·삭제된 메시지는 세지 않는다 */
    @Query("""
            select count(m) from Message m
            where m.roomId = :roomId and m.seq > :afterSeq and m.senderId <> :userId
              and m.deletedAt is null
            """)
    long countUnread(@Param("roomId") Long roomId, @Param("afterSeq") long afterSeq, @Param("userId") Long userId);

    /** 후보 승인 시스템 메시지 (되돌리기에서 지운다) */
    @Query(value = """
            SELECT * FROM messages
            WHERE room_id = :roomId AND type = 'SYSTEM' AND deleted_at IS NULL
              AND payload ->> 'kind' = 'CANDIDATE_APPROVED' AND (payload ->> 'candidateId')::bigint = :candidateId
            """, nativeQuery = true)
    List<Message> findCandidateApprovals(@Param("roomId") long roomId, @Param("candidateId") long candidateId);
}
