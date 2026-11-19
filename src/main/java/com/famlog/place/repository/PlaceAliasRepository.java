package com.famlog.place.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.place.domain.PlaceAlias;

public interface PlaceAliasRepository extends JpaRepository<PlaceAlias, Long> {

    List<PlaceAlias> findByGroupId(long groupId);

    boolean existsByGroupIdAndAlias(long groupId, String alias);
}
