package com.famlog.group.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.group.domain.GroupInvite;

public interface GroupInviteRepository extends JpaRepository<GroupInvite, Long> {

    Optional<GroupInvite> findByCode(String code);

    boolean existsByCode(String code);

    List<GroupInvite> findByGroupIdAndRevokedAtIsNull(Long groupId);
}
