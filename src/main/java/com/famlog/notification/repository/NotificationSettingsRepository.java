package com.famlog.notification.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.notification.domain.NotificationSettings;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, Long> {

    List<NotificationSettings> findByUserIdIn(Collection<Long> userIds);
}
