package com.famlog.group.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.famlog.group.domain.GroupMember;
import com.famlog.group.domain.GroupRole;
import com.famlog.group.domain.GroupSettings;
import com.famlog.group.dto.GroupDtos.GroupSettingsResponse;
import com.famlog.group.dto.GroupDtos.UpdateGroupSettingsRequest;
import com.famlog.group.event.AiAnalysisEnabled;
import com.famlog.group.repository.GroupMemberRepository;
import com.famlog.group.repository.GroupSettingsRepository;
import com.famlog.user.service.ConsentProperties;
import com.famlog.user.service.ConsentService;

import lombok.RequiredArgsConstructor;

/**
 * 그룹 설정 (사양서 4.1 SET-01, 11.3). 조회는 구성원, 변경은 ADMIN 이상.
 * <ul>
 * <li>꺼진 그룹을 켤 때는 관리자가 본 AI 분석 안내 버전(aiNoticeVersion)이 현재 버전이어야 하고(아니면 400),
 * 그 관리자의 AI_ANALYSIS·OVERSEAS_TRANSFER 확인을 남긴다</li>
 * <li>조회 응답의 aiNoticePending: 켜져 있는데 내가 현재 안내를 확인하지 않았고 이 그룹에서 분석 제외도 안 했으면 true
 * (확인하지 않아도 분석은 된다, 개인은 언제든 분석 제외)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class GroupSettingsService {

    private final GroupSettingsRepository settingsRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupAccessChecker accessChecker;
    private final ApplicationEventPublisher events;
    private final ConsentService consentService;
    private final ConsentProperties consentProperties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public GroupSettingsResponse get(long groupId, long userId) {
        accessChecker.requireMember(groupId, userId);
        return response(find(groupId), userId);
    }

    @Transactional
    public GroupSettingsResponse update(long groupId, long userId, UpdateGroupSettingsRequest request) {
        accessChecker.requireRole(groupId, userId, GroupRole.ADMIN);
        GroupSettings settings = find(groupId);
        if (request.aiAnalysisEnabled() && !settings.isAiAnalysisEnabled()) {
            consentService.acknowledgeAiNotice(userId, request.aiNoticeVersion(), "aiNoticeVersion");
        }
        if (settings.changeAiAnalysis(request.aiAnalysisEnabled(), userId, Instant.now(clock))) {
            events.publishEvent(new AiAnalysisEnabled(groupId));
        }
        return response(settingsRepository.save(settings), userId);
    }

    /** 이 그룹에서 내 메시지를 분석 제외했으면 보낼 것이 없으니 안내도 필요 없다 (pending=false) */
    private GroupSettingsResponse response(GroupSettings settings, long userId) {
        boolean optedOut = memberRepository.findByGroupIdAndUserId(settings.getGroupId(), userId)
                .map(GroupMember::isAiOptOut).orElse(false);
        return GroupSettingsResponse.from(settings, consentProperties.aiNotice().version(),
                optedOut || consentService.hasCurrentAiNotice(userId));
    }

    /** AI 트리거가 쓴다: 설정 행이 없으면 꺼짐 */
    @Transactional(readOnly = true)
    public GroupSettings find(long groupId) {
        return settingsRepository.findById(groupId).orElseGet(() -> GroupSettings.defaults(groupId));
    }
}
