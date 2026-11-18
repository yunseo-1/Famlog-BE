package com.famlog.notification.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.group.domain.GroupMember;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.group.service.GroupAccessChecker;
import com.famlog.notification.domain.NotificationSettings;
import com.famlog.notification.dto.NotificationDtos.GroupPreferencesRequest;
import com.famlog.notification.dto.NotificationDtos.GroupPreferencesResponse;
import com.famlog.notification.dto.NotificationDtos.NotificationSettingsRequest;
import com.famlog.notification.dto.NotificationDtos.NotificationSettingsResponse;
import com.famlog.notification.repository.NotificationSettingsRepository;

import lombok.RequiredArgsConstructor;

/** 알림 설정 (사양서 4.1 SET-02): 사용자 단위(미리보기 숨기기·방해 금지), 그룹 단위(알림 끄기). */
@Service
@RequiredArgsConstructor
public class NotificationSettingsService {

    private final NotificationSettingsRepository settingsRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupAccessChecker accessChecker;

    @Transactional(readOnly = true)
    public NotificationSettingsResponse get(long userId) {
        return NotificationSettingsResponse.from(find(userId));
    }

    @Transactional
    public NotificationSettingsResponse update(long userId, NotificationSettingsRequest request) {
        NotificationSettings settings = find(userId);
        settings.update(request.previewHidden(), request.quietHoursEnabled(), request.quietStartTime(),
                request.quietEndTime());
        if (settings.getQuietStart().equals(settings.getQuietEnd())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "방해 금지 시작과 끝 시각이 같아요.");
        }
        return NotificationSettingsResponse.from(settingsRepository.save(settings));
    }

    @Transactional(readOnly = true)
    public GroupPreferencesResponse groupPreferences(long groupId, long userId) {
        return GroupPreferencesResponse.from(activeMember(groupId, userId));
    }

    @Transactional
    public GroupPreferencesResponse updateGroupPreferences(long groupId, long userId,
            GroupPreferencesRequest request) {
        GroupMember member = activeMember(groupId, userId);
        member.muteNotifications(request.notifMuted());
        return GroupPreferencesResponse.from(member);
    }

    private NotificationSettings find(long userId) {
        return settingsRepository.findById(userId).orElseGet(() -> NotificationSettings.defaults(userId));
    }

    private GroupMember activeMember(long groupId, long userId) {
        accessChecker.requireMember(groupId, userId);
        return memberRepository.findByGroupIdAndUserId(groupId, userId)
                .filter(GroupMember::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_MEMBER));
    }
}
