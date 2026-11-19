package com.famlog.group.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.group.domain.GroupSettings;

public interface GroupSettingsRepository extends JpaRepository<GroupSettings, Long> {
}
