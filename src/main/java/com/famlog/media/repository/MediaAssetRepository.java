package com.famlog.media.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.famlog.media.domain.MediaAsset;

import jakarta.persistence.LockModeType;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    List<MediaAsset> findByMessageIdIn(Collection<Long> messageIds);

    /**
     * 메시지에 붙일 때와 워커 결과를 반영할 때 행을 잠근다.
     * 둘이 동시에 일어나도 나중 쪽이 앞의 변경(message_id 또는 READY)을 본다 → media.updated 누락 없음
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MediaAsset m where m.id in :ids order by m.id")
    List<MediaAsset> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MediaAsset m where m.id = :id")
    Optional<MediaAsset> findByIdForUpdate(@Param("id") Long id);
}
