package com.famlog.group.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.group.domain.GroupRole;
import com.famlog.group.domain.GroupSettings;
import com.famlog.group.dto.GroupDtos.GroupSettingsResponse;
import com.famlog.group.dto.GroupDtos.UpdateGroupSettingsRequest;
import com.famlog.group.event.AiAnalysisEnabled;
import com.famlog.group.repository.GroupSettingsRepository;

import lombok.RequiredArgsConstructor;

/**
 * 그룹 설정 (사양서 4.1 SET-01, 11.3). 조회는 구성원, 변경은 ADMIN 이상.
 * 처음 켤 때의 안내(전송 범위·국외 이전 고지)와 동의 기록은 S20에서 붙인다.
 */
@Service
@RequiredArgsConstructor
public class GroupSettingsService {

    private final GroupSettingsRepository settingsRepository;
    private final GroupAccessChecker accessChecker;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional(readOnly = true)
    public GroupSettingsResponse get(long groupId, long userId) {
        accessChecker.requireMember(groupId, userId);
        return GroupSettingsResponse.from(find(groupId));
    }

    @Transactional
    public GroupSettingsResponse update(long groupId, long userId, UpdateGroupSettingsRequest request) {
        accessChecker.requireRole(groupId, userId, GroupRole.ADMIN);
        GroupSettings settings = find(groupId);
        if (settings.changeAiAnalysis(request.aiAnalysisEnabled(), userId, Instant.now(clock))) {
            events.publishEvent(new AiAnalysisEnabled(groupId));
        }
        return GroupSettingsResponse.from(settingsRepository.save(settings));
    }

    /** AI 트리거가 쓴다: 설정 행이 없으면 꺼짐 */
    @Transactional(readOnly = true)
    public GroupSettings find(long groupId) {
        return settingsRepository.findById(groupId).orElseGet(() -> GroupSettings.defaults(groupId));
    }
}
